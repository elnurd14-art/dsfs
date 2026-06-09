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

    private var lastCallTime    = 0L
    private val MIN_CALL_INTERVAL = 15_000L

    // Антифлуд: хэш экрана + таймер сброса
    private var lastScreenHash    = 0
    private var lastScreenHashTs  = 0L
    private val HASH_RESET_MS     = 30_000L  // сбрасываем хэш через 30 сек

    private val processedOrders = mutableSetOf<Int>()

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

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 50
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
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> scanScreen()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(watchdogRunnable)
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

            // Города — только из этой карточки
            val cityFrom = PreferenceManager.KZ_CITIES.firstOrNull {
                cardText.contains(it, ignoreCase = true)
            } ?: "Астана"
            val cityTo = PreferenceManager.KZ_CITIES.firstOrNull {
                cardText.contains(it, ignoreCase = true) && !it.equals(cityFrom, ignoreCase = true)
            } ?: ""

            val cardKey = "${price.toInt()}_${cardText.take(80)}".hashCode()
            if (processedOrders.contains(cardKey)) continue

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

            // ── Принят ──────────────────────────────────────────────
            processedOrders.add(cardKey)
            if (processedOrders.size > 200) processedOrders.clear()

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

            val capturedNode = cardNode
            handler.postDelayed({
                val clicked = performClickOnCard(capturedNode)
                if (!clicked) {
                    // Повторная попытка через 1 секунду
                    handler.postDelayed({
                        if (!tapCardByPrice(price.toInt().toString())) {
                            handler.post {
                                showToast("Подходящий заказ ${price.toInt()} T — нажмите сами!")
                            }
                        }
                    }, 1000L)
                }
            }, prefs.getCallDelayMs())

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
            val cleaned = text.replace(Regex("[\\s₸Т]"), "")
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
        val cleaned = text.replace(Regex("""\d+[.,]\d+\s*[₸Т]/км"""), "SKIP")

        // Разбиваем на сегменты — ищем "число ₸"
        val pattern = Regex("""(\d[\d\s]{2,7})\s*[₸Т](?!\s*/\s*км)""")
        for (match in pattern.findAll(cleaned)) {
            val raw = match.groupValues[1].replace(Regex("\\s"), "")
            val value = raw.toDoubleOrNull() ?: continue
            if (value < 500.0 || value > 9_999_999.0) continue

            // Проверяем контекст после числа — не должно быть слов-счётчиков
            val afterMatch = cleaned.substring(match.range.last + 1)
                .take(40).lowercase()
            val badContext = listOf(
                "коробк", "место", "чел ", "адам", "орын",
                "кг", "литр", "штук", "пакет"
            )
            if (badContext.any { afterMatch.contains(it) }) continue

            // Проверяем что перед числом нет нежелательного контекста
            val beforeStart = maxOf(0, match.range.first - 30)
            val beforeMatch = cleaned.substring(beforeStart, match.range.first).lowercase()
            val badBefore = listOf("везу", "вес ", "объём", "размер")
            if (badBefore.any { beforeMatch.contains(it) }) continue

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
                val texts = mutableListOf<String>()
                collectAllText(node, texts)
                val joined = texts.joinToString(" ")
                if ((joined.contains("С попутчиками", ignoreCase = true) ||
                     joined.contains("Отправить посылку", ignoreCase = true)) &&
                    joined.contains("₸")) {
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
        val matches = Regex("""(\d+[.,]\d+)\s*[₸Т]/км""").findAll(fullText).toList()

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

                if (prefs.isAutoCallEnabled() && info.phone.isNotEmpty()) scheduleCall(info.phone)
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
            if (prefs.isAutoCallEnabled() && info.phone.isNotEmpty()) scheduleCall(info.phone)
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
        val p = Regex("""(\d+[.,]\d+)\s*[₸Т]/км""")
        return p.find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
    }

    private fun extractMainPrice(text: String): Double {
        val cleaned = text.replace(Regex("""\d+[.,]\d+\s*[₸Т]/км"""), "SKIP")
        return extractPriceSmart(cleaned) ?: 0.0
    }

    private fun extractDistanceToClient(text: String): Double {
        val mMatch = Regex("""~(\d+)\s*м\b""").find(text)
        if (mMatch != null) return (mMatch.groupValues[1].toDoubleOrNull() ?: 0.0) / 1000.0
        val kmMatch = Regex("""~(\d+[.,]\d+)\s*км""").find(text)
        if (kmMatch != null) return kmMatch.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
        return 0.0
    }

    private fun extractRating(text: String): Double {
        val p = Regex("""([4-5][.,]\d{1,2})\s*\(""")
        return p.find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
    }

    private fun extractRatingCount(text: String): Int {
        return Regex("""\((\d+)\)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun extractClientName(text: String): String {
        val p = Regex("""([А-ЯЁа-яёA-Za-z][а-яёa-z]{2,20})\s*[*]?\s*[4-5][.,]\d""")
        return p.find(text)?.groupValues?.get(1)?.trim() ?: ""
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
               Regex("""\d+\s*(янв|фев|мар|апр|май|июн|июл|авг|сен|окт|ноя|дек)""")
                   .containsMatchIn(line.lowercase())
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
        val p = Regex("""(\+?[78][\s\-]?7\d{2}[\s\-]?\d{3}[\s\-]?\d{2}[\s\-]?\d{2})""")
        return p.find(text)?.groupValues?.get(1)?.replace(Regex("[\\s\\-]"), "") ?: ""
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
            val clean = phone.replace(Regex("[^+0-9]"), "")
            if (clean.length < 10) return
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
