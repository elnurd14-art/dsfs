package com.indriver.bot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.indriver.bot.utils.OrderLogger
import com.indriver.bot.utils.PreferenceManager
import java.util.Calendar

class InDriverAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG         = "TaksaBot"
        const val PACKAGE_OLD = "sinet.startup.inDriver"
        const val PACKAGE_NEW = "com.indriver"

        @Volatile var instance:  InDriverAccessibilityService? = null
        @Volatile var isRunning: Boolean = false

        var onOrderDetected: ((OrderInfo) -> Unit)? = null
        var onOrderRejected: ((OrderInfo, String) -> Unit)? = null
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

    private lateinit var prefs:  PreferenceManager
    private lateinit var logger: OrderLogger

    private val mainHandler = Handler(Looper.getMainLooper())

    // Фоновый поток — весь тяжёлый парсинг здесь, главный поток не блокируем
    private val bgThread  = HandlerThread("TaksaScanner", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
    private val bgHandler = Handler(bgThread.looper)

    private val MIN_CALL_INTERVAL = 15_000L
    private var lastCallTime      = 0L

    // Антифлуд по хэшу экрана
    private var lastScreenHash   = 0
    private var lastScreenHashTs = 0L
    private val HASH_TTL_MS      = 5_000L   // ← было 30с — сократили до 5с: новый заказ появится быстрее

    @Volatile private var scanInProgress = false

    // Кольцевой буфер обработанных заказов (не Set — избегаем утечки памяти)
    private val processedOrders  = ArrayDeque<Int>(300)
    private val MAX_PROCESSED    = 300

    // Предкомпилированные regex — создаём один раз, не при каждом вызове
    private val rePricePerKm     = Regex("""(\d+[.,]\d+)\s*[₸Т]/км""")
    private val rePriceInText    = Regex("""(\d[\d\s]{2,7})\s*[₸Т](?!\s*/\s*км)""")
    private val reDistanceM      = Regex("""~(\d+)\s*м\b""")
    private val reDistanceKm     = Regex("""~(\d+[.,]\d+)\s*км""")
    private val reRating         = Regex("""([4-5][.,]\d{1,2})\s*\(""")
    private val reRatingCount    = Regex("""\((\d+)\)""")
    private val reClientName     = Regex("""([А-ЯЁа-яёA-Za-z][а-яёa-z]{2,20})\s*[*]?\s*[4-5][.,]\d""")
    private val rePhone          = Regex("""(\+?[78][\s\-]?7\d{2}[\s\-]?\d{3}[\s\-]?\d{2}[\s\-]?\d{2})""")
    private val reDate           = Regex("""\d+\s*(янв|фев|мар|апр|май|июн|июл|авг|сен|окт|ноя|дек)""")

    // Watchdog
    private var lastIndriveEventTs = 0L
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isRunning && lastIndriveEventTs > 0) {
                val gap = System.currentTimeMillis() - lastIndriveEventTs
                if (gap > 5 * 60_000L)
                    BotService.updateBotNotification(applicationContext,
                        "Нет событий от inDrive ${gap / 60_000} мин — откройте приложение")
            }
            mainHandler.postDelayed(this, 60_000L)
        }
    }

    // ================================================================
    //  ЖИЗНЕННЫЙ ЦИКЛ
    // ================================================================
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs    = PreferenceManager(this)
        logger   = OrderLogger(this)

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes       = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType     = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags            = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                               AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 0   // без задержки
            packageNames     = arrayOf(PACKAGE_OLD, PACKAGE_NEW, "com.indriver.driver")
        }
        mainHandler.postDelayed(watchdogRunnable, 60_000L)
        Log.d(TAG, "Такса подключена")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isRunning) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != PACKAGE_OLD && pkg != PACKAGE_NEW && pkg != "com.indriver.driver") return
        lastIndriveEventTs = System.currentTimeMillis()
        // Реагируем на ВСЕ изменения контента — не только STATE_CHANGED
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,           // ← новое: скролл ленты
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> scheduleBackgroundScan()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(watchdogRunnable)
        bgThread.quitSafely()
        instance = null
    }

    // ================================================================
    //  ПЛАНИРОВЩИК
    // ================================================================
    private fun scheduleBackgroundScan() {
        if (scanInProgress) return
        bgHandler.post {
            if (scanInProgress) return@post
            scanInProgress = true
            try { scanScreen() }
            catch (e: Exception) { Log.e(TAG, "scanScreen crash: ${e.message}") }
            finally { scanInProgress = false }
        }
    }

    // ================================================================
    //  СКАНИРОВАНИЕ
    // ================================================================
    private fun scanScreen() {
        val root = rootInActiveWindow ?: return

        val texts = ArrayList<String>(64)
        collectAllText(root, texts)
        if (texts.isEmpty()) return

        val fullText = texts.joinToString(" | ")
        val hash     = fullText.hashCode()
        val now      = System.currentTimeMillis()

        if (hash == lastScreenHash && now - lastScreenHashTs < HASH_TTL_MS) return
        lastScreenHash   = hash
        lastScreenHashTs = now

        when {
            // Попутки/посылки/весь-салон — экран с лентой карточек
            isCarpoolFeedScreen(fullText) -> processCarpoolFeed(root, fullText)
            // Лента обычных заказов (цена/км)
            isOrderFeedScreen(fullText)   -> processOrderFeed(texts, fullText)
            // Отдельная карточка заказа
            isSingleOrderScreen(fullText) -> processSingleOrder(texts, fullText)
        }
    }

    // ================================================================
    //  ОПРЕДЕЛЕНИЕ ЭКРАНА
    // ================================================================
    private fun isOrderFeedScreen(text: String) =
        text.contains("₸/км", ignoreCase = true)

    private fun isCarpoolFeedScreen(text: String): Boolean {
        val hasMarker = text.contains("С попутчиками", ignoreCase = true) ||
                        text.contains("Весь салон",     ignoreCase = true) ||
                        text.contains("Отправить посылку", ignoreCase = true)
        val hasPrice  = text.contains("₸") || text.contains("тенге", ignoreCase = true)
        return hasMarker && hasPrice && !text.contains("₸/км", ignoreCase = true)
    }

    private fun isSingleOrderScreen(text: String) = listOf(
        "Принять", "Предложить цену", "Откликнуться",
        "Ищем водителей", "Ожидайте звонков", "Отменить заказ"
    ).any { text.contains(it, ignoreCase = true) }

    // ================================================================
    //  ОБРАБОТКА ЛЕНТЫ ПОПУТЧИКОВ / ПОСЫЛОК / ВЕСЬ-САЛОН
    // ================================================================
    private fun processCarpoolFeed(root: AccessibilityNodeInfo, fullText: String) {
        val mode  = prefs.getMode()
        val cards = findCarpoolCards(root)
        if (cards.isEmpty()) return

        for (cardNode in cards) {
            val cardTexts = ArrayList<String>(16)
            collectAllText(cardNode, cardTexts)
            val cardText = cardTexts.joinToString(" | ")

            // Тип заказа
            val isParcel    = cardText.contains("Отправить посылку", ignoreCase = true)
            val isFullSalon = cardText.contains("Весь салон", ignoreCase = true)
            val orderType   = when {
                isParcel    -> "Посылка"
                isFullSalon -> "Весь салон"
                else        -> "Попутчики"
            }

            // Фильтр по режиму
            when (mode) {
                PreferenceManager.MODE_ALL ->
                    // "Весь салон" — только карточки с меткой "Весь салон", не попутчики
                    if (!isFullSalon && !isParcel) { logMissed(orderType, 0.0, "", "", "Режим: только Весь салон"); continue }
                PreferenceManager.MODE_INTERCITY ->
                    if (!isParcel) { logMissed(orderType, 0.0, "", "", "Режим: только посылки"); continue }
                PreferenceManager.MODE_CARPOOL ->
                    if (isParcel || isFullSalon) { logMissed(orderType, 0.0, "", "", "Режим: только попутчики"); continue }
            }

            // Цена
            val price = extractPriceSmartFromNode(cardNode)
                     ?: extractPriceSmart(cardText)
                     ?: 0.0
            if (price <= 0) continue

            // Города — извлекаем из адресных строк, не из сырого текста
            val addressFrom = extractAddressFrom(cardText)
            val addressTo   = extractAddressTo(cardText)
            val cityFrom    = extractCityFromAddress(addressFrom)
                .ifEmpty {
                    // Fallback: первый город в тексте карточки
                    PreferenceManager.KZ_CITIES.firstOrNull { cardText.contains(it, ignoreCase = true) } ?: "Астана"
                }
            val cityTo      = extractCityFromAddress(addressTo)
                .ifEmpty {
                    // Fallback: второй отличный от cityFrom город
                    PreferenceManager.KZ_CITIES.firstOrNull {
                        cardText.contains(it, ignoreCase = true) && !it.equals(cityFrom, ignoreCase = true)
                    } ?: ""
                }

            // Антидубль
            val cardKey = "${price.toInt()}_${cardText.take(80)}".hashCode()
            if (isProcessed(cardKey)) continue

            // Ценовой фильтр
            val priceMode  = if (isParcel) prefs.getIntercityPriceMode() else prefs.getCarpoolPriceMode()
            val minPrice   = if (isParcel) prefs.getMinIntercityPrice()  else prefs.getMinCarpoolPrice()
            val fixedPrice = if (isParcel) prefs.getFixedIntercityPrice() else prefs.getFixedCarpoolPrice()

            val priceOk = when (priceMode) {
                PreferenceManager.PRICE_OFF   -> true
                PreferenceManager.PRICE_MIN   -> price >= minPrice
                PreferenceManager.PRICE_FIXED -> fixedPrice <= 0 ||
                        kotlin.math.abs(price - fixedPrice) <= fixedPrice * 0.05
                else -> true
            }
            if (!priceOk) {
                logMissed(orderType, price, cityFrom, cityTo, "Цена ${price.toInt()} T < ${minPrice.toInt()} T")
                prefs.incrementMissed()
                continue
            }

            // Фильтр города назначения
            if (prefs.isCityFilterEnabled()) {
                if (cityTo.isEmpty()) {
                    // Город не распознан — пропускаем только если разрешён "Другой/Неизвестный"
                    // По умолчанию блокируем нераспознанные направления
                    logMissed(orderType, price, cityFrom, cityTo, "Город назначения не распознан")
                    prefs.incrementMissed()
                    continue
                }
                if (prefs.getAllowedCities().none { cityTo.contains(it, ignoreCase = true) }) {
                    logMissed(orderType, price, cityFrom, cityTo, "Город '$cityTo' не разрешён")
                    prefs.incrementMissed()
                    continue
                }
            }

            // ── ПОДХОДЯЩИЙ ЗАКАЗ НАЙДЕН ──────────────────────────────
            markProcessed(cardKey)
            prefs.incrementAccepted()
            prefs.addEarnings(price)
            prefs.setLastOrderInfo(buildCarpoolSummary(orderType, price, cityFrom, cityTo))

            logger.add(OrderLogger.LogEntry(
                timestamp = System.currentTimeMillis(),
                orderType = orderType, price = price.toInt(),
                cityFrom  = cityFrom, cityTo = cityTo,
                status    = "ПРИНЯТ", reason = ""
            ))

            val info = OrderInfo(price, 0.0, 0.0, 0.0, 0, "",
                "", cityFrom, cityTo, cityFrom, cityTo, "", true, orderType, cardText)
            onOrderDetected?.invoke(info)

            val notifText = "$orderType — ${price.toInt()} T" +
                if (cityFrom.isNotEmpty() || cityTo.isNotEmpty()) "\n$cityFrom -> $cityTo" else ""
            BotService.showOrderNotification(applicationContext, "Такса нашла заказ!", notifText)
            BotService.updateBotNotification(applicationContext, "Последний: ${price.toInt()} T — $orderType")
            mainHandler.post { vibrate() }

            // ── КЛИК — максимально быстро ────────────────────────────
            val capturedNode = cardNode
            val delayMs      = prefs.getCallDelayMs()
            val clickAction  = Runnable {
                val clicked = performClickOnCard(capturedNode)
                if (!clicked) {
                    // Fallback: ищем по цене через 600мс
                    mainHandler.postDelayed({
                        if (!tapCardByPrice(price.toInt().toString()))
                            showToast("Заказ ${price.toInt()} T — нажмите вручную!")
                    }, 600L)
                }
            }
            if (delayMs <= 0L) clickAction.run()          // мгновенно в bgThread
            else mainHandler.postDelayed(clickAction, delayMs)

            break  // обрабатываем только первую подходящую карточку
        }
    }

    // ================================================================
    //  ПОИСК КАРТОЧЕК В ДЕРЕВЕ UI
    // ================================================================
    private fun findCarpoolCards(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>(8)
        findCarpoolCardsRecursive(root, result, 0)
        return result
    }

    private fun findCarpoolCardsRecursive(
        node: AccessibilityNodeInfo,
        result: ArrayList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 12) return   // ← было 15, сократили — дерево inDrive не глубже 10-12
        try {
            if (node.isClickable && node.childCount >= 2) {
                val texts = ArrayList<String>(8)
                collectAllText(node, texts)
                val joined = texts.joinToString(" ")
                val hasType  = joined.contains("С попутчиками", ignoreCase = true) ||
                               joined.contains("Весь салон",     ignoreCase = true) ||
                               joined.contains("Отправить посылку", ignoreCase = true)
                val hasPrice = joined.contains("₸")
                if (hasType && hasPrice) {
                    result.add(node)
                    return  // не ищем детей — карточка найдена
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

    // ================================================================
    //  КЛИК ПО КАРТОЧКЕ
    // ================================================================
    private fun performClickOnCard(cardNode: AccessibilityNodeInfo): Boolean {
        return try {
            if (cardNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                prefs.incrementCalls()
                mainHandler.post { showToast("Открываю карточку — inDrive звонит!") }
                return true
            }
            // Ищем кликабельного родителя (до 5 уровней)
            var parent = try { cardNode.parent } catch (e: Exception) { null }
            repeat(5) {
                val p = parent ?: return@repeat
                if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    prefs.incrementCalls()
                    mainHandler.post { showToast("Открываю карточку!") }
                    return true
                }
                parent = try { p.parent } catch (e: Exception) { null }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "performClickOnCard: ${e.message}")
            false
        }
    }

    private fun tapCardByPrice(priceStr: String): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val priceWithSpace = priceStr.reversed().chunked(3).joinToString(" ").reversed()
            val priceNode = findNodeByTextAny(root, listOf(priceStr, priceWithSpace, "$priceStr ₸"))
            if (priceNode != null) {
                var node: AccessibilityNodeInfo? = priceNode
                var steps = 0
                while (node != null && steps < 10) {
                    if (node.isClickable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        prefs.incrementCalls()
                        return true
                    }
                    node = try { node.parent } catch (e: Exception) { null }
                    steps++
                }
            }
            // Последний шанс — первая кликабельная карточка
            findFirstClickableCard(root)?.let {
                it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
    //  ПАРСИНГ ЦЕНЫ ИЗ УЗЛА UI
    // ================================================================
    private fun extractPriceSmartFromNode(cardNode: AccessibilityNodeInfo): Double? {
        val candidates = ArrayList<AccessibilityNodeInfo>(8)
        collectPriceNodes(cardNode, candidates)
        for (node in candidates) {
            val text    = node.text?.toString() ?: continue
            if (text.length > 30) continue
            val cleaned = text.replace(Regex("[\\s₸Т,]"), "").replace(" ", "")
            val value   = cleaned.toDoubleOrNull() ?: continue
            if (value in 500.0..9_999_999.0) return value
        }
        return null
    }

    private fun collectPriceNodes(node: AccessibilityNodeInfo, result: ArrayList<AccessibilityNodeInfo>) {
        try {
            val text = node.text?.toString() ?: ""
            if (text.contains("₸") && text.any { it.isDigit() } &&
                !node.isClickable && node.childCount == 0) result.add(node)
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                collectPriceNodes(child, result)
            }
        } catch (_: Exception) {}
    }

    // ================================================================
    //  ПАРСИНГ ЦЕНЫ ИЗ ТЕКСТА
    // ================================================================
    private fun extractPriceSmart(text: String): Double? {
        // Убираем цену/км чтобы не спутать
        val cleaned = text.replace(rePricePerKm, "SKIP")
        for (match in rePriceInText.findAll(cleaned)) {
            val raw   = match.groupValues[1].replace(Regex("\\s"), "")
            val value = raw.toDoubleOrNull() ?: continue
            if (value < 500.0 || value > 9_999_999.0) continue
            val after  = cleaned.substring(minOf(match.range.last + 1, cleaned.length)).take(40).lowercase()
            val before = cleaned.substring(maxOf(0, match.range.first - 30), match.range.first).lowercase()
            val badAfter  = listOf("коробк", "место", "чел ", "адам", "орын", "кг", "литр", "штук", "пакет")
            val badBefore = listOf("везу", "вес ", "объём", "размер")
            if (badAfter.any { after.contains(it) }) continue
            if (badBefore.any { before.contains(it) }) continue
            return value
        }
        return null
    }

    // ================================================================
    //  ЛЕНТА ОБЫЧНЫХ ЗАКАЗОВ (₸/км)
    // ================================================================
    private fun processOrderFeed(texts: List<String>, fullText: String) {
        val matches = rePricePerKm.findAll(fullText).toList()
        if (matches.isEmpty()) return

        for (i in matches.indices) {
            val start    = matches[i].range.first
            val end      = if (i + 1 < matches.size)
                minOf(matches[i + 1].range.first, start + 600)
                else minOf(start + 600, fullText.length)
            val cardText = fullText.substring(start, end)

            val info = parseOrderCard(cardText)
            if (info.price <= 0) continue

            val cardKey = "${info.price}_${info.addressFrom}".hashCode()
            if (isProcessed(cardKey)) continue

            val (passed, reason) = checkFilters(info)
            if (passed) {
                markProcessed(cardKey)
                prefs.incrementAccepted()
                prefs.addEarnings(info.price)
                prefs.setLastOrderInfo(buildSummary(info))
                logger.add(OrderLogger.LogEntry(
                    System.currentTimeMillis(), info.orderType, info.price.toInt(),
                    info.cityFrom, info.cityTo, "ПРИНЯТ", ""
                ))
                onOrderDetected?.invoke(info)
                mainHandler.post { vibrate() }
                val notifText = "${info.orderType} — ${info.price.toInt()} T" +
                    if (info.addressFrom.isNotEmpty()) "\n${info.addressFrom.take(40)}" else ""
                BotService.showOrderNotification(applicationContext, "Такса нашла заказ!", notifText)
                BotService.updateBotNotification(applicationContext,
                    "Последний: ${info.price.toInt()} T — ${info.orderType}")
                if (prefs.isAutoCallEnabled() && info.phone.isNotEmpty()) scheduleCall(info.phone)
                break
            } else {
                prefs.incrementMissed()
                logMissed(info.orderType, info.price, info.cityFrom, info.cityTo, reason)
            }
        }
    }

    // ================================================================
    //  ОДИНОЧНАЯ КАРТОЧКА ЗАКАЗА
    // ================================================================
    private fun processSingleOrder(texts: List<String>, fullText: String) {
        val info = parseOrderCard(fullText)
        if (info.price <= 0) return

        val cardKey = "${info.price}_${info.addressFrom}".hashCode()
        if (isProcessed(cardKey)) return

        val (passed, reason) = checkFilters(info)
        if (passed) {
            markProcessed(cardKey)
            prefs.incrementAccepted()
            prefs.addEarnings(info.price)
            prefs.setLastOrderInfo(buildSummary(info))
            logger.add(OrderLogger.LogEntry(
                System.currentTimeMillis(), info.orderType, info.price.toInt(),
                info.cityFrom, info.cityTo, "ПРИНЯТ", ""
            ))
            onOrderDetected?.invoke(info)
            mainHandler.post { vibrate() }
            BotService.showOrderNotification(applicationContext,
                "Такса нашла заказ!", "${info.price.toInt()} T — ${info.orderType}")
            if (prefs.isAutoCallEnabled() && info.phone.isNotEmpty()) scheduleCall(info.phone)
        } else {
            prefs.incrementMissed()
            logMissed(info.orderType, info.price, info.cityFrom, info.cityTo, reason)
            onOrderRejected?.invoke(info, reason)
        }
    }

    // ================================================================
    //  РАЗБОР КАРТОЧКИ
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
        val isFullSalon  = cardText.contains("Весь салон", ignoreCase = true)
        val isIntercity  = isParcel || detectIntercity(cardText, cityFrom, cityTo)
        val orderType    = when {
            isParcel    -> "Посылка"
            isFullSalon -> "Весь салон"
            isIntercity -> "Посылка"
            else        -> "Попутчики"
        }
        return OrderInfo(price, pricePerKm, distToClient, rating, ratingCount,
            phone, clientName, addressFrom, addressTo,
            cityFrom, cityTo, paymentType, isIntercity, orderType, cardText)
    }

    // ================================================================
    //  ИЗВЛЕЧЕНИЕ ПОЛЕЙ
    // ================================================================
    private fun extractPricePerKm(text: String): Double =
        rePricePerKm.find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0

    private fun extractMainPrice(text: String): Double {
        val cleaned = text.replace(rePricePerKm, "SKIP")
        return extractPriceSmart(cleaned) ?: 0.0
    }

    private fun extractDistanceToClient(text: String): Double {
        reDistanceM.find(text)?.let {
            return (it.groupValues[1].toDoubleOrNull() ?: 0.0) / 1000.0
        }
        reDistanceKm.find(text)?.let {
            return it.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
        }
        return 0.0
    }

    private fun extractRating(text: String): Double =
        reRating.find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0

    private fun extractRatingCount(text: String): Int =
        reRatingCount.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun extractClientName(text: String): String =
        reClientName.find(text)?.groupValues?.get(1)?.trim() ?: ""

    private fun extractAddressFrom(text: String): String {
        val lines = text.split("|", "\n").map { it.trim() }.filter { it.length > 5 }
        return lines.firstOrNull { isAddressLine(it) && !isMetaLine(it) } ?: ""
    }

    private fun extractAddressTo(text: String): String {
        val lines = text.split("|", "\n").map { it.trim() }.filter { it.length > 5 }
        var found = false
        for (line in lines) {
            if (isAddressLine(line) && !isMetaLine(line)) {
                if (found) return line
                found = true
            }
        }
        return ""
    }

    private fun isAddressLine(line: String): Boolean =
        line.contains("улица",         ignoreCase = true) ||
        line.contains("проспект",      ignoreCase = true) ||
        line.contains("жилой комплекс",ignoreCase = true) ||
        line.contains("жк ",           ignoreCase = true) ||
        line.contains("мкр",           ignoreCase = true) ||
        line.contains("микрорайон",    ignoreCase = true) ||
        line.contains("автовокзал",    ignoreCase = true) ||
        line.contains("аэропорт",      ignoreCase = true) ||
        line.contains("вокзал",        ignoreCase = true) ||
        PreferenceManager.KZ_CITIES.any { line.contains(it, ignoreCase = true) }

    private fun isMetaLine(line: String): Boolean =
        line.contains("₸") || line.contains(" км") ||
        line.contains("Лента") || line.contains("Статистика") ||
        line.contains("Кошелёк") || line.contains("Спрос") ||
        line.contains("Создано ") || reDate.containsMatchIn(line.lowercase())

    private fun extractCityFromAddress(address: String): String {
        // Прямое совпадение с городом
        PreferenceManager.KZ_CITIES.firstOrNull {
            address.contains(it, ignoreCase = true)
        }?.let { return it }
        // Известные ориентиры → город
        val landmarks = mapOf(
            "нурсултан назарбаев"   to "Астана",
            "астана халык аренасы"  to "Астана",
            "байтерек"              to "Астана",
            "хан шатыр"             to "Астана",
            "алматы арена"          to "Алматы",
            "медеу"                 to "Алматы",
            "шымбулак"              to "Алматы",
            "коктобе"               to "Алматы",
            "железнодорожный вокзал г. кара" to "Караганда",
            "баянаульский"          to "Павлодар",
            "автовокзал тараз"      to "Тараз"
        )
        val lower = address.lowercase()
        for ((key, city) in landmarks) {
            if (lower.contains(key)) return city
        }
        return ""
    }

    private fun extractPaymentType(text: String): String = when {
        text.contains("Kaspi",   ignoreCase = true) -> "Kaspi"
        text.contains("наличн",  ignoreCase = true) -> "Наличные"
        text.contains("перевод", ignoreCase = true) -> "Перевод"
        text.contains("карт",    ignoreCase = true) -> "Карта"
        else -> ""
    }

    private fun extractPhone(text: String): String =
        rePhone.find(text)?.groupValues?.get(1)?.replace(Regex("[\\s\\-]"), "") ?: ""

    private fun detectIntercity(text: String, cityFrom: String, cityTo: String): Boolean =
        text.contains("межгород",     ignoreCase = true) ||
        text.contains("қалааралық",   ignoreCase = true) ||
        (cityFrom.isNotEmpty() && cityTo.isNotEmpty() && !cityFrom.equals(cityTo, ignoreCase = true))

    // ================================================================
    //  ФИЛЬТРЫ
    // ================================================================
    private fun checkFilters(info: OrderInfo): Pair<Boolean, String> {
        // Рабочие часы
        if (prefs.isWorkHoursEnabled()) {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (h < prefs.getWorkStart() || h >= prefs.getWorkEnd())
                return false to "Вне рабочего времени ($h ч)"
        }

        // Режим
        when (prefs.getMode()) {
            PreferenceManager.MODE_INTERCITY ->
                if (info.orderType != "Посылка" && !info.isIntercity)
                    return false to "Режим: только посылки"
            PreferenceManager.MODE_CARPOOL ->
                if (info.orderType != "Попутчики")
                    return false to "Режим: только попутчики"
            // MODE_ALL — принимаем всё
        }

        // Чёрный список
        if (info.phone.isNotEmpty() && prefs.isBlacklisted(info.phone))
            return false to "Заблокирован: ${info.phone}"

        // Ценовой фильтр
        if (info.price > 0) {
            val (priceMode, minPrice, fixedPrice) = when (info.orderType) {
                "Попутчики",
                "Весь салон" -> Triple(
                    prefs.getCarpoolPriceMode(), prefs.getMinCarpoolPrice(), prefs.getFixedCarpoolPrice())
                "Посылка"    -> Triple(
                    prefs.getIntercityPriceMode(), prefs.getMinIntercityPrice(), prefs.getFixedIntercityPrice())
                else         -> Triple(
                    prefs.getCityPriceMode(), prefs.getMinPrice(), prefs.getFixedCityPrice())
            }
            when (priceMode) {
                PreferenceManager.PRICE_MIN ->
                    if (info.price < minPrice)
                        return false to "Цена ${info.price.toInt()} T < ${minPrice.toInt()} T"
                PreferenceManager.PRICE_FIXED ->
                    if (fixedPrice > 0 && kotlin.math.abs(info.price - fixedPrice) > fixedPrice * 0.05)
                        return false to "Цена ${info.price.toInt()} T ≠ ${fixedPrice.toInt()} T"
            }
        }

        // Фильтр города
        if (prefs.isCityFilterEnabled()) {
            if (info.cityTo.isEmpty())
                return false to "Город назначения не распознан"
            if (prefs.getAllowedCities().none { info.cityTo.contains(it, ignoreCase = true) })
                return false to "Город '${info.cityTo}' не разрешён"
        }

        return true to ""
    }

    // ================================================================
    //  ЗВОНОК
    // ================================================================
    private fun scheduleCall(phone: String) {
        val now = System.currentTimeMillis()
        if (now - lastCallTime < MIN_CALL_INTERVAL) return
        lastCallTime = now
        val delay = prefs.getCallDelayMs()
        if (delay <= 0L) makeCall(phone)
        else mainHandler.postDelayed({ makeCall(phone) }, delay)
    }

    private fun makeCall(phone: String) {
        try {
            val clean = phone.replace(Regex("[^+0-9]"), "")
            if (clean.length < 10) return
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$clean")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            prefs.incrementCalls()
            mainHandler.post { showToast("Звоним: $clean") }
        } catch (e: SecurityException) {
            mainHandler.post { showToast("Нет разрешения на звонки") }
        } catch (e: Exception) {
            Log.e(TAG, "makeCall: ${e.message}")
        }
    }

    // ================================================================
    //  АНТИДУБЛЬ — кольцевой буфер
    // ================================================================
    private fun isProcessed(key: Int): Boolean = processedOrders.contains(key)

    private fun markProcessed(key: Int) {
        if (processedOrders.size >= MAX_PROCESSED) processedOrders.removeFirst()
        processedOrders.addLast(key)
    }

    // ================================================================
    //  УТИЛИТЫ
    // ================================================================
    private fun vibrate() {
        try {
            val v = getSystemService(Vibrator::class.java) ?: return
            if (!v.hasVibrator()) return
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 80, 180), -1))
            else @Suppress("DEPRECATION") v.vibrate(longArrayOf(0, 180, 80, 180), -1)
        } catch (e: Exception) { Log.e(TAG, "vibrate: ${e.message}") }
    }

    private fun logMissed(orderType: String, price: Double, from: String, to: String, reason: String) {
        logger.add(OrderLogger.LogEntry(System.currentTimeMillis(),
            orderType, price.toInt(), from, to, "ПРОПУЩЕН", reason))
    }

    private fun buildSummary(info: OrderInfo) = buildString {
        append("${info.orderType.uppercase()}\n")
        if (info.price > 0) {
            append("${info.price.toInt()} T")
            if (info.pricePerKm > 0) append("  (${info.pricePerKm} T/km)")
            append("\n")
        }
        if (info.clientName.isNotEmpty()) append(info.clientName)
        if (info.rating > 0) append("  ${info.rating}")
        if (info.ratingCount > 0) append(" (${info.ratingCount})")
        if (info.clientName.isNotEmpty() || info.rating > 0) append("\n")
        if (info.addressFrom.isNotEmpty()) append("От: ${info.addressFrom.take(40)}\n")
        if (info.addressTo.isNotEmpty())   append("До: ${info.addressTo.take(40)}\n")
        if (info.paymentType.isNotEmpty()) append("${info.paymentType}\n")
        append(if (info.phone.isNotEmpty()) info.phone else "Номер после принятия")
    }

    private fun buildCarpoolSummary(type: String, price: Double, from: String, to: String) =
        "$type\n${price.toInt()} T\n$from -> $to"

    private fun showToast(msg: String) =
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()

    private fun collectAllText(node: AccessibilityNodeInfo, list: ArrayList<String>) {
        try {
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { list.add(it) }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { list.add(it) }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                collectAllText(child, list)
            }
        } catch (_: Exception) {}
    }

    private fun findNodeByTextAny(node: AccessibilityNodeInfo, texts: List<String>): AccessibilityNodeInfo? {
        try {
            val t = node.text?.toString() ?: ""
            val d = node.contentDescription?.toString() ?: ""
            if (texts.any { t.contains(it) || d.contains(it) }) return node
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                findNodeByTextAny(child, texts)?.let { return it }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun findFirstClickableCard(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try {
            if (node.isClickable && node.childCount > 2) return node
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                findFirstClickableCard(child)?.let { return it }
            }
        } catch (_: Exception) {}
        return null
    }
}
