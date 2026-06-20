package com.indriver.bot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.indriver.bot.utils.CallStateManager
import com.indriver.bot.utils.OrderLogger
import com.indriver.bot.utils.PreferenceManager
import java.util.Calendar

class InDriverAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "TaksaBot"
        const val PACKAGE_OLD = "sinet.startup.inDriver"
        const val PACKAGE_NEW = "com.indriver"

        @Volatile var instance: InDriverAccessibilityService? = null
        @Volatile var isRunning = false

        var onOrderDetected: ((OrderInfo) -> Unit)? = null
        var onOrderRejected: ((OrderInfo, String) -> Unit)? = null

        // Regex компилируются один раз при загрузке класса, а не на каждый вызов функции.
        // Это горячий путь — вызывается на каждый скан экрана/карточки перед кликом,
        // компиляция паттерна на каждый вызов добавляла лишнюю работу CPU.
        private val RX_WHITESPACE_TENGE   = Regex("[\\s₸Т]")
        private val RX_PRICE_PER_KM_STRIP = Regex("""\d+[.,]\d+\s*[₸Т]/км""")
        private val RX_PRICE_TENGE        = Regex("""(\d[\d\s]{2,7})\s*[₸Т](?!\s*/\s*км)""")
        private val RX_WHITESPACE         = Regex("\\s")
        private val RX_PRICE_PER_KM       = Regex("""(\d+[.,]\d+)\s*[₸Т]/км""")
        private val RX_DIST_METERS        = Regex("""~(\d+)\s*м\b""")
        private val RX_DIST_KM            = Regex("""~(\d+[.,]\d+)\s*км""")
        private val RX_RATING             = Regex("""([4-5][.,]\d{1,2})\s*\(""")
        private val RX_RATING_COUNT       = Regex("""\((\d+)\)""")
        private val RX_CLIENT_NAME        = Regex("""([А-ЯЁа-яёA-Za-z][а-яёa-z]{2,20})\s*[*]?\s*[4-5][.,]\d""")
        private val RX_DATE_LINE          = Regex("""\d+\s*(янв|фев|мар|апр|май|июн|июл|авг|сен|окт|ноя|дек)""")
        private val RX_PHONE              = Regex("""(\+?[78][\s\-]?7\d{2}[\s\-]?\d{3}[\s\-]?\d{2}[\s\-]?\d{2})""")
        private val RX_WHITESPACE_DASH    = Regex("[\\s\\-]")
        private val RX_NOT_PHONE_CHARS    = Regex("[^+0-9]")

        private val BAD_PRICE_CONTEXT_AFTER = listOf(
            "коробк", "место", "чел ", "адам", "орын",
            "кг", "литр", "штук", "пакет"
        )
        private val BAD_PRICE_CONTEXT_BEFORE = listOf("везу", "вес ", "объём", "размер")
    }

    data class OrderInfo(
        val price: Double,
        val pricePerKm: Double,
        val distanceToClient: Double,
        val rating: Double,
        val ratingCount: Int,
        val phone: String,
        val clientName: String,
        val addressFrom: String,
        val addressTo: String,
        val cityFrom: String,
        val cityTo: String,
        val paymentType: String,
        val isIntercity: Boolean,
        val orderType: String,
        val rawText: String
    )

    private lateinit var prefs: PreferenceManager
    private lateinit var logger: OrderLogger
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var whatsAppWatcher: CallLogWhatsAppWatcher
    private lateinit var callStateManager: CallStateManager

    private var lastCallTime    = 0L
    private val MIN_CALL_INTERVAL = 15_000L

    // Антифлуд: хэш экрана + таймер сброса
    private var lastScreenHash    = 0
    private var lastScreenHashTs  = 0L
    private val HASH_RESET_MS     = 30_000L  // сбрасываем хэш через 30 сек

    private val processedOrders = mutableSetOf<Int>()

    // Скан запланирован — если придёт новое событие раньше, чем сработает старое,
    // старое отменяется и сканирование выполняется только по самому свежему состоянию экрана.
    // Это убирает очередь из устаревших сканирований дерева, которая раньше тормозила реакцию
    // на новый заказ при частых TYPE_WINDOW_CONTENT_CHANGED от анимаций/индикаторов inDrive.
    private val scanRunnable = Runnable {
        scanScheduled = false
        scanScreen()
    }
    private var scanScheduled = false
    private val CONTENT_CHANGE_DEBOUNCE_MS = 60L

    // Watchdog: если нет событий от inDrive > 5 мин — уведомляем
    private var lastIndriveEventTs = 0L
    private val WATCHDOG_MS = 5 * 60_000L
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isRunning && lastIndriveEventTs > 0) {
                val gap = System.currentTimeMillis() - lastIndriveEventTs
                if (gap > WATCHDOG_MS) {
                    BotService.updateBotNotification(
                        applicationContext,
                        "Нет событий от inDrive ${gap / 60_000} мин — проверьте приложение"
                    )
                }
            }
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs  = PreferenceManager(this)
        logger = OrderLogger(this)
        whatsAppWatcher = CallLogWhatsAppWatcher(applicationContext)
        callStateManager = CallStateManager(applicationContext)
        callStateManager.start()

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 0
            packageNames = arrayOf(PACKAGE_OLD, PACKAGE_NEW, "com.indriver.driver")
        }
        handler.postDelayed(watchdogRunnable, 60_000L)
        Log.d(TAG, "Такса подключена")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isRunning) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != PACKAGE_OLD && pkg != PACKAGE_NEW && pkg != "com.indriver.driver") return

        lastIndriveEventTs = System.currentTimeMillis()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                // Критичные события — никакого дебаунса, сканируем прямо сейчас.
                handler.removeCallbacks(scanRunnable)
                scanScheduled = false
                scanScreen()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (!scanScheduled) {
                    scanScheduled = true
                    handler.postDelayed(scanRunnable, CONTENT_CHANGE_DEBOUNCE_MS)
                }
                // Если скан уже запланирован — новое событие ничего не делает,
                // запланированный скан всё равно увидит самое свежее дерево экрана.
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(watchdogRunnable)
        if (::whatsAppWatcher.isInitialized) whatsAppWatcher.release()
        if (::callStateManager.isInitialized) callStateManager.stop()
        if (::logger.isInitialized) logger.shutdown()
    }

    // ================================================================
    //  СКАНИРОВАНИЕ ЭКРАНА
    // ================================================================
    private fun scanScreen() {
        val root = rootInActiveWindow ?: return

        val texts = mutableListOf<String>()
        collectAllText(root, texts)
        if (texts.isEmpty()) return

        val fullText = texts.joinToString(" | ")
        val hash = fullText.hashCode()

        // Антифлуд: пропускаем одинаковый экран, но сбрасываем через HASH_RESET_MS
        val now = System.currentTimeMillis()
        if (hash == lastScreenHash) {
            if (now - lastScreenHashTs < HASH_RESET_MS) return
        }
        lastScreenHash   = hash
        lastScreenHashTs = now

        when {
            isCarpoolFeedScreen(fullText) -> processCarpoolFeed(root, fullText)
            isOrderFeedScreen(fullText)   -> processOrderFeed(texts, fullText)
            isSingleOrderScreen(fullText) -> processSingleOrder(texts, fullText)
        }
    }

    // ================================================================
    //  ОПРЕДЕЛЕНИЕ ЭКРАНА
    // ================================================================
    private fun isOrderFeedScreen(text: String) =
        text.contains("₸/км", ignoreCase = true)

    private fun isCarpoolFeedScreen(text: String): Boolean {
        val marker = text.contains("С попутчиками", ignoreCase = true) ||
                     text.contains("Отправить посылку", ignoreCase = true)
        val price  = text.contains("₸") || text.contains("тенге", ignoreCase = true)
        return marker && price && !text.contains("₸/км", ignoreCase = true)
    }

    private fun isSingleOrderScreen(text: String) = listOf(
        "Принять", "Предложить цену", "Откликнуться",
        "Ищем водителей", "Ожидайте звонков", "Отменить заказ", "Откуда", "Куда"
    ).any { text.contains(it, ignoreCase = true) }

    // ================================================================
    //  ОБРАБОТКА ПОПУТЧИКОВ / ПОСЫЛОК
    // ================================================================
    private fun processCarpoolFeed(root: AccessibilityNodeInfo, fullText: String) {
        val mode  = prefs.getMode()
        val cards = findCarpoolCards(root)
        Log.d(TAG, "Карточек попутчиков: ${cards.size}")

        for (cardNode in cards) {
            val cardTexts = mutableListOf<String>()
            collectAllText(cardNode, cardTexts)
            val cardText = cardTexts.joinToString(" | ")

            // ── Умный парсинг цены ──────────────────────────────────
            // Сначала ищем цену в специфичном ценовом узле карточки,
            // затем по контексту (число стоит отдельно, не в слове),
            // и только последним — общий regex.
            val price = extractPriceSmartFromNode(cardNode)
                     ?: extractPriceSmart(cardText)
                     ?: 0.0
            if (price <= 0) continue

            val isParcel  = cardText.contains("Отправить посылку", ignoreCase = true)
            val orderType = if (isParcel) "Посылка" else "Попутчики"

            when (mode) {
                PreferenceManager.MODE_INTERCITY ->
                    if (!isParcel) { logMissed(orderType, price, "", "", "Режим: только посылки"); continue }
                PreferenceManager.MODE_CARPOOL ->
                    if (isParcel) { logMissed(orderType, price, "", "", "Режим: только попутчики"); continue }
            }

            // Грубый дедуп-ключ без городов — чтобы не тратить время на поиск городов
            // в карточке, которую мы уже обработали на предыдущем скане.
            val cardKey = "${price.toInt()}_${cardText.take(80)}".hashCode()
            if (processedOrders.contains(cardKey)) continue

            // Города — только из этой карточки.
            // cityTo нужен ДО клика (фильтр городов внизу), cityFrom — только для лога/сводки,
            // но один проход по списку городов дешевле двух, поэтому считаем оба сразу.
            val foundCities = PreferenceManager.KZ_CITIES.filter { cardText.contains(it, ignoreCase = true) }
            val cityFrom = foundCities.firstOrNull() ?: "Астана"
            val cityTo = foundCities.firstOrNull { !it.equals(cityFrom, ignoreCase = true) } ?: ""

            // Фильтр цены
            val priceMode  = if (isParcel) prefs.getIntercityPriceMode() else prefs.getCarpoolPriceMode()
            val minPrice   = if (isParcel) prefs.getMinIntercityPrice()  else prefs.getMinCarpoolPrice()
            val fixedPrice = if (isParcel) prefs.getFixedIntercityPrice() else prefs.getFixedCarpoolPrice()

            val priceOk = when (priceMode) {
                PreferenceManager.PRICE_OFF   -> true
                PreferenceManager.PRICE_MIN   -> price >= minPrice
                PreferenceManager.PRICE_FIXED -> fixedPrice <= 0 ||
                        Math.abs(price - fixedPrice) <= fixedPrice * 0.05
                else -> true
            }
            if (!priceOk) {
                logMissed(orderType, price, cityFrom, cityTo, "Цена ${price.toInt()} T < ${minPrice.toInt()} T")
                prefs.incrementMissed()
                continue
            }

            // Фильтр городов
            if (prefs.isCityFilterEnabled() && cityTo.isNotEmpty()) {
                if (prefs.getAllowedCities().none { cityTo.contains(it, ignoreCase = true) }) {
                    logMissed(orderType, price, cityFrom, cityTo, "Город '$cityTo' не разрешён")
                    prefs.incrementMissed()
                    continue
                }
            }

            // ── Принят: СНАЧАЛА действие, ВСЁ ОСТАЛЬНОЕ — после ──────────
            // Критично для скорости: клик должен случиться раньше записи статистики,
            // лога и показа уведомлений (SharedPreferences.apply, файловый лог и
            // NotificationManager — это диск/IPC, десятки миллисекунд каждое).
            // В гонке за заказ эти миллисекунды решают, кто кликнет первым.
            processedOrders.add(cardKey)
            if (processedOrders.size > 200) processedOrders.clear()

            val clicked = performClickOnCard(cardNode)
            if (!clicked) {
                // Запасной путь — без секундной задержки, прямо сейчас.
                if (!tapCardByPrice(price.toInt().toString())) {
                    handler.post { showToast("Подходящий заказ ${price.toInt()} T — нажмите сами!") }
                }
            }

            prefs.incrementAccepted()
            prefs.addEarnings(price)

            val summary = buildCarpoolSummary(orderType, price, cityFrom, cityTo)
            prefs.setLastOrderInfo(summary)

            logger.add(OrderLogger.LogEntry(
                timestamp = System.currentTimeMillis(),
                orderType = orderType, price = price.toInt(),
                cityFrom = cityFrom, cityTo = cityTo,
                status = "ПРИНЯТ", reason = ""
            ))

            val info = OrderInfo(price, 0.0, 0.0, 0.0, 0, "",
                "", cityFrom, cityTo, cityFrom, cityTo, "", true, orderType, cardText)
            onOrderDetected?.invoke(info)

            // Уведомление в шторке + вибрация
            val notifText = "$orderType — ${price.toInt()} T" +
                if (cityFrom.isNotEmpty() || cityTo.isNotEmpty()) "\n$cityFrom -> $cityTo" else ""
            BotService.showOrderNotification(applicationContext, "Такса нашла заказ!", notifText)
            vibrate()
            BotService.updateBotNotification(applicationContext,
                "Последний: ${price.toInt()} T — $orderType")

            break
        }
    }

    // ================================================================
    //  УМНЫЙ ПАРСИНГ ЦЕНЫ — узел
    //  Ищем TextView, который содержит ТОЛЬКО число + ₸,
    //  без лишнего текста рядом. Это поле цены в карточке.
    // ================================================================
    private fun extractPriceSmartFromNode(cardNode: AccessibilityNodeInfo): Double? {
        val priceNodes = mutableListOf<AccessibilityNodeInfo>()
        collectPriceNodes(cardNode, priceNodes)

        for (node in priceNodes) {
            val text = node.text?.toString() ?: continue
            // Текст должен быть короткий и содержать ₸
            if (text.length > 30) continue
            val cleaned = text.replace(RX_WHITESPACE_TENGE, "")
            val value = cleaned.toDoubleOrNull() ?: continue
            if (value in 500.0..9_999_999.0) return value
        }
        return null
    }

    private fun collectPriceNodes(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        try {
            val text = node.text?.toString() ?: ""
            // Ценовой узел: содержит ₸ и цифры, короткий, не кликабельный (не кнопка)
            if (text.contains("₸") && text.any { it.isDigit() } &&
                !node.isClickable && node.childCount == 0) {
                result.add(node)
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                collectPriceNodes(child, result)
            }
        } catch (e: Exception) { /* игнорируем */ }
    }

    // ================================================================
    //  УМНЫЙ ПАРСИНГ ЦЕНЫ — текст
    //  Фильтрует числа которые являются количеством предметов,
    //  номерами домов, или другими нечисловыми значениями.
    //  Правила:
    //  1. Цена стоит прямо перед ₸ (или ₸ прямо после числа)
    //  2. Число не предшествует словам "коробк", "место", "чел", "адам", "орын"
    //  3. Минимум 3 цифры (минимальная цена 500 T — всегда 3+ цифры)
    // ================================================================
    private fun extractPriceSmart(text: String): Double? {
        // Убираем ₸/км чтобы не спутать с основной ценой
        val cleaned = text.replace(RX_PRICE_PER_KM_STRIP, "SKIP")

        // Разбиваем на сегменты — ищем "число ₸"
        for (match in RX_PRICE_TENGE.findAll(cleaned)) {
            val raw = match.groupValues[1].replace(RX_WHITESPACE, "")
            val value = raw.toDoubleOrNull() ?: continue
            if (value < 500.0 || value > 9_999_999.0) continue

            // Проверяем контекст после числа — не должно быть слов-счётчиков
            val afterMatch = cleaned.substring(match.range.last + 1)
                .take(40).lowercase()
            if (BAD_PRICE_CONTEXT_AFTER.any { afterMatch.contains(it) }) continue

            // Проверяем что перед числом нет нежелательного контекста
            val beforeStart = maxOf(0, match.range.first - 30)
            val beforeMatch = cleaned.substring(beforeStart, match.range.first).lowercase()
            if (BAD_PRICE_CONTEXT_BEFORE.any { beforeMatch.contains(it) }) continue

            return value
        }
        return null
    }

    // ================================================================
    //  ПОИСК КАРТОЧЕК ПОПУТЧИКОВ
    // ================================================================
    private fun findCarpoolCards(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        findCarpoolCardsRecursive(root, result, 0)
        return result
    }

    private fun findCarpoolCardsRecursive(node: AccessibilityNodeInfo,
                                          result: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > 15) return
        try {
            if (node.isClickable && node.childCount >= 2) {
                // Лёгкая проверка без аллокации промежуточного списка строк —
                // ищем нужные маркеры прямо по дереву. Раньше здесь вызывался
                // collectAllText на каждом clickable узле, что при вложенных
                // контейнерах превращалось в повторный обход того же поддерева
                // несколько раз (узел внутри узла внутри узла).
                if (containsMarkers(node)) {
                    result.add(node)
                    return
                }
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                findCarpoolCardsRecursive(child, result, depth + 1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "findCarpoolCards: ${e.message}")
        }
    }

    /** Проверяет наличие маркеров карточки попутчика/посылки прямо по дереву, без сборки списка строк */
    private fun containsMarkers(node: AccessibilityNodeInfo): Boolean {
        var hasCarpoolOrParcel = false
        var hasPrice = false

        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 12 || (hasCarpoolOrParcel && hasPrice)) return
            try {
                val t = n.text?.toString() ?: n.contentDescription?.toString() ?: ""
                if (t.isNotEmpty()) {
                    if (!hasCarpoolOrParcel &&
                        (t.contains("С попутчиками", ignoreCase = true) ||
                         t.contains("Отправить посылку", ignoreCase = true))) {
                        hasCarpoolOrParcel = true
                    }
                    if (!hasPrice && t.contains("₸")) hasPrice = true
                }
                for (i in 0 until n.childCount) {
                    if (hasCarpoolOrParcel && hasPrice) return
                    val child = try { n.getChild(i) } catch (e: Exception) { null } ?: continue
                    walk(child, depth + 1)
                }
            } catch (e: Exception) { /* узел переработан */ }
        }
        walk(node, 0)
        return hasCarpoolOrParcel && hasPrice
    }

    private fun performClickOnCard(cardNode: AccessibilityNodeInfo): Boolean {
        return try {
            if (cardNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                prefs.incrementCalls()
                handler.post { showToast("Открываю карточку — inDrive звонит!") }
                return true
            }
            var parent = try { cardNode.parent } catch (e: Exception) { null }
            var found = false
            repeat(5) {
                if (found) return@repeat
                val p = parent ?: return@repeat
                if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    prefs.incrementCalls()
                    handler.post { showToast("Открываю карточку — inDrive звонит!") }
                    found = true
                }
                parent = try { p.parent } catch (e: Exception) { null }
            }
            found
        } catch (e: Exception) {
            Log.e(TAG, "performClickOnCard: ${e.message}")
            false
        }
    }

    private fun tapCardByPrice(priceStr: String): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val priceWithSpace = priceStr.reversed().chunked(3).joinToString(" ").reversed()
            val priceNode = findNodeByTextAny(root, listOf(priceStr, priceWithSpace))
            if (priceNode != null) {
                var clickable: AccessibilityNodeInfo? = priceNode
                var steps = 0
                while (clickable != null && steps < 12) {
                    if (clickable.isClickable) break
                    clickable = try { clickable.parent } catch (e: Exception) { null }
                    steps++
                }
                if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    prefs.incrementCalls()
                    handler.post { showToast("Кликнул заказ $priceStr T") }
                    return true
                }
            }
            val firstCard = findFirstClickableCard(root)
            if (firstCard != null) {
                firstCard.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                prefs.incrementCalls()
                return true
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "tapCardByPrice: ${e.message}")
            false
        }
    }

    // ================================================================
    //  ОБРАБОТКА ЛЕНТЫ ЗАКАЗОВ
    // ================================================================
    private fun processOrderFeed(texts: List<String>, fullText: String) {
        val matches = RX_PRICE_PER_KM.findAll(fullText).toList()

        for (i in matches.indices) {
            val start = matches[i].range.first
            val end = if (i + 1 < matches.size)
                minOf(matches[i + 1].range.first, start + 600)
            else
                minOf(start + 600, fullText.length)
            val cardText = fullText.substring(start, end)

            val info = parseOrderCard(cardText)
            if (info.price <= 0) continue

            val cardKey = "${info.price}_${info.addressFrom}".hashCode()
            if (processedOrders.contains(cardKey)) continue

            val (passed, reason) = checkFilters(info)
            if (passed) {
                processedOrders.add(cardKey)
                if (processedOrders.size > 200) processedOrders.clear()

                // Звонок — это и есть "первыми получить заказ", запускаем его
                // раньше статистики/лога/уведомлений (диск + IPC = миллисекунды простоя).
                if (prefs.isAutoCallEnabled() && info.phone.isNotEmpty()) scheduleCall(info.phone)

                prefs.incrementAccepted()
                prefs.addEarnings(info.price)
                prefs.setLastOrderInfo(buildSummary(info))

                logger.add(OrderLogger.LogEntry(
                    System.currentTimeMillis(), info.orderType, info.price.toInt(),
                    info.cityFrom, info.cityTo, "ПРИНЯТ", ""
                ))

                onOrderDetected?.invoke(info)
                vibrate()

                val notifText = "${info.orderType} — ${info.price.toInt()} T" +
                    if (info.addressFrom.isNotEmpty()) "\n${info.addressFrom.take(40)}" else ""
                BotService.showOrderNotification(applicationContext, "Такса нашла заказ!", notifText)
                BotService.updateBotNotification(applicationContext,
                    "Последний: ${info.price.toInt()} T — ${info.orderType}")

                break
            } else {
                Log.d(TAG, "Пропущен ${info.price.toInt()} T — $reason")
                prefs.incrementMissed()
                logMissed(info.orderType, info.price, info.cityFrom, info.cityTo, reason)
            }
        }
    }

    // ================================================================
    //  РАЗБОР КАРТОЧКИ ЗАКАЗА
    // ================================================================
    private fun parseOrderCard(cardText: String): OrderInfo {
        val pricePerKm   = extractPricePerKm(cardText)
        val price        = extractMainPrice(cardText)
        val distToClient = extractDistanceToClient(cardText)
        val rating       = extractRating(cardText)
        val ratingCount  = extractRatingCount(cardText)
        val clientName   = extractClientName(cardText)
        val addressFrom  = extractAddressFrom(cardText)
        val addressTo    = extractAddressTo(cardText)
        val cityFrom     = extractCityFromAddress(addressFrom)
        val cityTo       = extractCityFromAddress(addressTo)
        val paymentType  = extractPaymentType(cardText)
        val phone        = extractPhone(cardText)
        val isParcel     = cardText.contains("Отправить посылку", ignoreCase = true) ||
                           cardText.contains("посылк", ignoreCase = true)
        val isIntercity  = isParcel || detectIntercity(cardText, cityFrom, cityTo)
        val orderType    = when {
            isParcel    -> "Посылка"
            isIntercity -> "Посылка"
            else        -> "Город"
        }
        return OrderInfo(
            price, pricePerKm, distToClient, rating, ratingCount,
            phone, clientName, addressFrom, addressTo,
            cityFrom, cityTo, paymentType, isIntercity, orderType, cardText
        )
    }

    // ================================================================
    //  ОДИНОЧНЫЙ ЗАКАЗ
    // ================================================================
    private fun processSingleOrder(texts: List<String>, fullText: String) {
        val info = parseOrderCard(fullText)
        if (info.price <= 0) return

        val cardKey = "${info.price}_${info.addressFrom}".hashCode()
        if (processedOrders.contains(cardKey)) return

        val (passed, reason) = checkFilters(info)
        if (passed) {
            processedOrders.add(cardKey)

            // Звонок сначала — статистика/лог/уведомления после.
            if (prefs.isAutoCallEnabled() && info.phone.isNotEmpty()) scheduleCall(info.phone)

            prefs.incrementAccepted()
            prefs.addEarnings(info.price)
            prefs.setLastOrderInfo(buildSummary(info))
            logger.add(OrderLogger.LogEntry(
                System.currentTimeMillis(), info.orderType, info.price.toInt(),
                info.cityFrom, info.cityTo, "ПРИНЯТ", ""
            ))
            onOrderDetected?.invoke(info)
            vibrate()
            BotService.showOrderNotification(applicationContext,
                "Такса нашла заказ!", "${info.price.toInt()} T — ${info.orderType}")
        } else {
            prefs.incrementMissed()
            logMissed(info.orderType, info.price, info.cityFrom, info.cityTo, reason)
            onOrderRejected?.invoke(info, reason)
        }
    }

    // ================================================================
    //  ИЗВЛЕЧЕНИЕ ДАННЫХ
    // ================================================================
    private fun extractPricePerKm(text: String): Double {
        return RX_PRICE_PER_KM.find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
    }

    private fun extractMainPrice(text: String): Double {
        val cleaned = text.replace(RX_PRICE_PER_KM_STRIP, "SKIP")
        return extractPriceSmart(cleaned) ?: 0.0
    }

    private fun extractDistanceToClient(text: String): Double {
        val mMatch = RX_DIST_METERS.find(text)
        if (mMatch != null) return (mMatch.groupValues[1].toDoubleOrNull() ?: 0.0) / 1000.0
        val kmMatch = RX_DIST_KM.find(text)
        if (kmMatch != null) return kmMatch.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
        return 0.0
    }

    private fun extractRating(text: String): Double {
        return RX_RATING.find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
    }

    private fun extractRatingCount(text: String): Int {
        return RX_RATING_COUNT.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun extractClientName(text: String): String {
        return RX_CLIENT_NAME.find(text)?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun extractAddressFrom(text: String): String {
        val lines = text.split("|", "\n").map { it.trim() }.filter { it.length > 5 }
        for (line in lines) {
            if (isAddressLine(line) && !isMetaLine(line)) return line
        }
        return ""
    }

    private fun extractAddressTo(text: String): String {
        val lines = text.split("|", "\n").map { it.trim() }.filter { it.length > 5 }
        var foundFirst = false
        for (line in lines) {
            if (isAddressLine(line) && !isMetaLine(line)) {
                if (foundFirst) return line
                foundFirst = true
            }
        }
        return ""
    }

    private fun isAddressLine(line: String): Boolean {
        return line.contains("улица", ignoreCase = true) ||
               line.contains("проспект", ignoreCase = true) ||
               line.contains("жилой комплекс", ignoreCase = true) ||
               line.contains("мкр", ignoreCase = true) ||
               line.contains("микрорайон", ignoreCase = true) ||
               PreferenceManager.KZ_CITIES.any { line.contains(it, ignoreCase = true) }
    }

    private fun isMetaLine(line: String): Boolean {
        return line.contains("₸") || line.contains(" км") ||
               line.contains("Лента") || line.contains("Статистика") ||
               line.contains("Кошелёк") || line.contains("Спрос") ||
               line.contains("Создано ") ||
               RX_DATE_LINE.containsMatchIn(line.lowercase())
    }

    private fun extractCityFromAddress(address: String): String {
        for (city in PreferenceManager.KZ_CITIES) {
            if (address.contains(city, ignoreCase = true)) return city
        }
        return ""
    }

    private fun extractPaymentType(text: String): String {
        return when {
            text.contains("Kaspi", ignoreCase = true)   -> "Kaspi"
            text.contains("наличн", ignoreCase = true)  -> "Наличные"
            text.contains("перевод", ignoreCase = true) -> "Перевод"
            text.contains("карт", ignoreCase = true)    -> "Карта"
            else -> ""
        }
    }

    private fun extractPhone(text: String): String {
        return RX_PHONE.find(text)?.groupValues?.get(1)?.replace(RX_WHITESPACE_DASH, "") ?: ""
    }

    private fun detectIntercity(text: String, cityFrom: String, cityTo: String): Boolean {
        if (text.contains("межгород", ignoreCase = true)) return true
        if (text.contains("қалааралық", ignoreCase = true)) return true
        if (cityFrom.isNotEmpty() && cityTo.isNotEmpty() &&
            !cityFrom.equals(cityTo, ignoreCase = true)) return true
        return false
    }

    // ================================================================
    //  ФИЛЬТРЫ
    // ================================================================
    private fun checkFilters(info: OrderInfo): Pair<Boolean, String> {
        if (prefs.isWorkHoursEnabled()) {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (h < prefs.getWorkStart() || h >= prefs.getWorkEnd())
                return false to "Вне рабочего времени"
        }
        val mode = prefs.getMode()
        when (mode) {
            PreferenceManager.MODE_INTERCITY ->
                if (info.orderType != "Посылка" && !info.isIntercity)
                    return false to "Режим: только посылки"
            PreferenceManager.MODE_CARPOOL ->
                if (info.orderType != "Попутчики")
                    return false to "Режим: только попутчики"
        }
        if (info.phone.isNotEmpty() && prefs.isBlacklisted(info.phone))
            return false to "Заблокирован"

        if (info.price > 0) {
            val (priceMode, minPrice, fixedPrice) = when (info.orderType) {
                "Попутчики" -> Triple(
                    prefs.getCarpoolPriceMode(), prefs.getMinCarpoolPrice(), prefs.getFixedCarpoolPrice())
                "Посылка" -> Triple(
                    prefs.getIntercityPriceMode(), prefs.getMinIntercityPrice(), prefs.getFixedIntercityPrice())
                else -> Triple(
                    prefs.getCityPriceMode(), prefs.getMinPrice(), prefs.getFixedCityPrice())
            }
            when (priceMode) {
                PreferenceManager.PRICE_MIN ->
                    if (info.price < minPrice)
                        return false to "Цена ${info.price.toInt()} T < ${minPrice.toInt()} T"
                PreferenceManager.PRICE_FIXED ->
                    if (fixedPrice > 0 && Math.abs(info.price - fixedPrice) > fixedPrice * 0.05)
                        return false to "Цена ${info.price.toInt()} T != ${fixedPrice.toInt()} T"
            }
        }
        if (prefs.isCityFilterEnabled() && info.cityTo.isNotEmpty()) {
            if (prefs.getAllowedCities().none { info.cityTo.contains(it, ignoreCase = true) })
                return false to "Город '${info.cityTo}' не разрешён"
        }
        return true to ""
    }

    // ================================================================
    //  ВСПОМОГАТЕЛЬНЫЕ
    // ================================================================
    private fun vibrate() {
        try {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Vibrator::class.java)
            if (vibrator?.hasVibrator() == true) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(
                        longArrayOf(0, 200, 100, 200), -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 200, 100, 200), -1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "vibrate: ${e.message}")
        }
    }

    private fun logMissed(orderType: String, price: Double, from: String, to: String, reason: String) {
        logger.add(OrderLogger.LogEntry(
            System.currentTimeMillis(), orderType, price.toInt(),
            from, to, "ПРОПУЩЕН", reason
        ))
    }

    private fun scheduleCall(phone: String) {
        val now = System.currentTimeMillis()
        if (now - lastCallTime < MIN_CALL_INTERVAL) return
        lastCallTime = now
        handler.postDelayed({ makeCall(phone) }, prefs.getCallDelayMs())
    }

    private fun makeCall(phone: String) {
        try {
            val clean = phone.replace(RX_NOT_PHONE_CHARS, "")
            if (clean.length < 10) return
            // Взводим CallStateManager ДО старта звонка — как только звонок завершится
            // (OFFHOOK → IDLE), он откроет WhatsApp с этим номером (и шаблоном текста, если задан).
            if (::callStateManager.isInitialized) {
                callStateManager.setPendingPhone(clean)
            }
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$clean")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            prefs.incrementCalls()
            handler.post { showToast("Звоним: $clean") }
        } catch (e: SecurityException) {
            handler.post { showToast("Нет разрешения на звонки") }
        } catch (e: Exception) {
            Log.e(TAG, "makeCall: ${e.message}")
        }
    }

    private fun buildSummary(info: OrderInfo): String = buildString {
        append("${info.orderType.uppercase()}\n")
        if (info.price > 0) {
            append("${info.price.toInt()} T")
            if (info.pricePerKm > 0) append("  (${info.pricePerKm} T/km)")
            append("\n")
        }
        if (info.clientName.isNotEmpty()) append("${info.clientName}")
        if (info.rating > 0) append("  ${info.rating}")
        if (info.ratingCount > 0) append(" (${info.ratingCount})")
        if (info.clientName.isNotEmpty() || info.rating > 0) append("\n")
        if (info.addressFrom.isNotEmpty()) append("От: ${info.addressFrom.take(40)}\n")
        if (info.addressTo.isNotEmpty()) append("До: ${info.addressTo.take(40)}\n")
        if (info.paymentType.isNotEmpty()) append("${info.paymentType}\n")
        if (info.phone.isNotEmpty()) append(info.phone) else append("Номер после принятия")
    }

    private fun buildCarpoolSummary(type: String, price: Double, from: String, to: String) =
        "$type\n${price.toInt()} T\n$from -> $to"

    private fun showToast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    private fun collectAllText(node: AccessibilityNodeInfo, list: MutableList<String>) {
        try {
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { list.add(it) }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { list.add(it) }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                collectAllText(child, list)
            }
        } catch (e: Exception) { /* узел переработан */ }
    }

    private fun findNodeByTextAny(node: AccessibilityNodeInfo, texts: List<String>): AccessibilityNodeInfo? {
        try {
            val t = node.text?.toString() ?: ""
            val d = node.contentDescription?.toString() ?: ""
            if (texts.any { t.contains(it) || d.contains(it) }) return node
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                val found = findNodeByTextAny(child, texts)
                if (found != null) return found
            }
        } catch (e: Exception) { /* игнорируем */ }
        return null
    }

    private fun findFirstClickableCard(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try {
            if (node.isClickable && node.childCount > 2) return node
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                val found = findFirstClickableCard(child)
                if (found != null) return found
            }
        } catch (e: Exception) { /* игнорируем */ }
        return null
    }
}
