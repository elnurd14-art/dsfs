package com.indriver.bot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.indriver.bot.utils.PreferenceManager
import java.util.Calendar

class InDriverAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "InDriverBot"
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
    private val handler = Handler(Looper.getMainLooper())
    private var lastCallTime = 0L
    private val MIN_CALL_INTERVAL = 15_000L
    private var lastScreenHash = 0
    private val processedOrders = mutableSetOf<Int>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = PreferenceManager(this)
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 50
            packageNames = arrayOf(PACKAGE_OLD, PACKAGE_NEW)
        }
        Log.d(TAG, "✅ Сервис подключён — inDrive водительское приложение")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isRunning) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != PACKAGE_OLD && pkg != PACKAGE_NEW) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> scanScreen()
        }
    }

    override fun onInterrupt() = Unit

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
        if (hash == lastScreenHash) return
        lastScreenHash = hash

        val isCarpoolFeed = isCarpoolFeedScreen(fullText)
        val isOrderFeed = !isCarpoolFeed && isOrderFeedScreen(fullText)
        val isSingleOrder = !isCarpoolFeed && !isOrderFeed && isSingleOrderScreen(fullText)

        when {
            isCarpoolFeed && prefs.getMode() == PreferenceManager.MODE_CARPOOL ->
                processCarpoolFeed(root, fullText)
            isCarpoolFeed ->
                processCarpoolFeed(root, fullText) // обрабатываем в любом режиме если попутчики
            isOrderFeed -> processOrderFeed(texts, fullText)
            isSingleOrder -> processSingleOrder(texts, fullText)
        }
    }

    // ================================================================
    //  ОПРЕДЕЛЕНИЕ ЭКРАНА
    // ================================================================

    // Экран "Лента заказов" — ₸/км означает экран водительских заказов
    private fun isOrderFeedScreen(text: String): Boolean {
        return text.contains("₸/км", ignoreCase = true) &&
               (text.contains("₸") || text.contains("тенге", ignoreCase = true))
    }

    // Экран попутчиков (вкладка «Попутки»)
    // ПРИОРИТЕТНЕЕ — проверяем первым
    private fun isCarpoolFeedScreen(text: String): Boolean {
        return (text.contains("С попутчиками", ignoreCase = true) ||
                text.contains("Отправить посылку", ignoreCase = true)) &&
               (text.contains("₸") || text.contains("тенге", ignoreCase = true)) &&
               !text.contains("₸/км", ignoreCase = true)  // если нет ₸/км — точно попутки
    }

    // Экран одного заказа (открытая карточка)
    private fun isSingleOrderScreen(text: String): Boolean {
        return listOf(
            "Принять", "Предложить цену", "Откликнуться",
            "Ищем водителей", "Ожидайте звонков", "Заказ создан",
            "Отменить заказ", "Откуда", "Куда"
        ).any { text.contains(it, ignoreCase = true) }
    }

    // ================================================================
    //  ОБРАБОТКА ПОПУТЧИКОВ — ИСПРАВЛЕННАЯ ВЕРСИЯ
    //  Ключевое исправление: разбиваем экран на отдельные карточки
    //  через дерево accessibility, а не через regex по fullText
    // ================================================================
    private fun processCarpoolFeed(root: AccessibilityNodeInfo, fullText: String) {
        // Собираем карточки из дерева view — каждая карточка это кликабельный контейнер
        // с ценой внутри. Это надёжнее чем парсить склеенный текст.
        val cards = findCarpoolCards(root)
        Log.d(TAG, "🚗 Найдено карточек попутчиков: ${cards.size}")

        for (cardNode in cards) {
            val cardTexts = mutableListOf<String>()
            collectAllText(cardNode, cardTexts)
            val cardText = cardTexts.joinToString(" | ")

            Log.d(TAG, "🚗 Карточка: ${cardText.take(100)}")

            val price = extractCarpoolPrice(cardText)
            if (price <= 0) continue

            // Города — берём ТОЛЬКО из текста этой карточки
            val cityFrom = PreferenceManager.KZ_CITIES.firstOrNull {
                cardText.contains(it, ignoreCase = true)
            } ?: "Астана"
            val cityTo = PreferenceManager.KZ_CITIES.firstOrNull {
                cardText.contains(it, ignoreCase = true) && !it.equals(cityFrom, ignoreCase = true)
            } ?: ""

            // Уникальный ключ: цена + время создания (если есть) + адрес
            val cardKey = "${price.toInt()}_${cardText.take(80)}".hashCode()
            if (processedOrders.contains(cardKey)) {
                Log.d(TAG, "🚗 Карточка уже обработана: ${price.toInt()}₸")
                continue
            }

            // Тип: попутчик или посылка
            val isParcel = cardText.contains("Отправить посылку", ignoreCase = true)
            val orderType = if (isParcel) "Посылка" else "Попутчики"

            // Фильтр цены
            val minPrice = when {
                isParcel -> prefs.getMinIntercityPrice()
                else -> prefs.getMinCarpoolPrice()
            }
            val priceMode = when {
                isParcel -> prefs.getIntercityPriceMode()
                else -> prefs.getCarpoolPriceMode()
            }

            if (priceMode == PreferenceManager.PRICE_OFF) {
                Log.d(TAG, "🚗 Фильтр цены выключен для $orderType")
                // Пропускаем если фильтр выключен (нет смысла обрабатывать)
                continue
            }

            if (priceMode == PreferenceManager.PRICE_MIN && price < minPrice) {
                Log.d(TAG, "🚗 $orderType ${price.toInt()}₸ < мин ${minPrice.toInt()}₸")
                continue
            }

            // Фильтр городов назначения
            if (prefs.isCityFilterEnabled() && cityTo.isNotEmpty()) {
                if (prefs.getAllowedCities().none { cityTo.contains(it, ignoreCase = true) }) {
                    Log.d(TAG, "🚗 $orderType — город '$cityTo' не в списке")
                    continue
                }
            }

            // ✅ Карточка подходит!
            processedOrders.add(cardKey)
            if (processedOrders.size > 100) processedOrders.clear()

            prefs.incrementAccepted()
            val summary = "🚗 $orderType\n💵 ${price.toInt()} ₸\n📍 $cityFrom → $cityTo\n→ Нажимаю карточку..."
            prefs.setLastOrderInfo(summary)

            val info = OrderInfo(
                price, 0.0, 0.0, 0.0, 0, "", "",
                cityFrom, cityTo, cityFrom, cityTo, "",
                true, orderType, cardText
            )
            onOrderDetected?.invoke(info)

            // Кликаем конкретный узел карточки
            handler.postDelayed({
                val clicked = performClickOnCard(cardNode)
                if (!clicked) {
                    // Fallback: ищем по цене
                    tapCardByPrice(price.toInt().toString())
                }
            }, prefs.getCallDelayMs())

            break // берём первую подходящую
        }
    }

    // ================================================================
    //  ПОИСК КАРТОЧЕК ПОПУТЧИКОВ В ДЕРЕВЕ VIEW
    //  Карточка = кликабельный контейнер с ценой (₸) внутри
    // ================================================================
    private fun findCarpoolCards(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        findCarpoolCardsRecursive(root, result, depth = 0)
        return result
    }

    private fun findCarpoolCardsRecursive(
        node: AccessibilityNodeInfo,
        result: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 15) return

        // Карточка: кликабельный контейнер, у которого есть дети с ценой ₸
        if (node.isClickable && node.childCount >= 2) {
            val texts = mutableListOf<String>()
            collectAllText(node, texts)
            val joined = texts.joinToString(" ")
            // Карточка попутчика содержит ₸ и (С попутчиками ИЛИ Отправить посылку)
            if ((joined.contains("С попутчиками", ignoreCase = true) ||
                 joined.contains("Отправить посылку", ignoreCase = true)) &&
                joined.contains("₸")) {
                result.add(node)
                return // не углубляемся в уже найденную карточку
            }
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findCarpoolCardsRecursive(it, result, depth + 1) }
        }
    }

    // Кликаем карточку — сначала сам узел, потом родители
    private fun performClickOnCard(cardNode: AccessibilityNodeInfo): Boolean {
        if (cardNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            prefs.incrementCalls()
            handler.post {
                Toast.makeText(applicationContext, "🚗 Открываю карточку — inDrive звонит!", Toast.LENGTH_SHORT).show()
            }
            return true
        }
        // Пробуем родителей
        var parent = cardNode.parent
        repeat(5) {
            if (parent?.isClickable == true) {
                if (parent!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    prefs.incrementCalls()
                    handler.post {
                        Toast.makeText(applicationContext, "🚗 Открываю карточку — inDrive звонит!", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
            }
            parent = parent?.parent
        }
        return false
    }

    // Цена в карточке попутчика: "15 000 ₸" или "4 000 ₸"
    private fun extractCarpoolPrice(text: String): Double {
        val pattern = Regex("""(\d[\d\s]{1,8})\s*[₸Т]""")
        for (match in pattern.findAll(text)) {
            val raw = match.groupValues[1].replace("\\s".toRegex(), "").toDoubleOrNull() ?: continue
            if (raw in 500.0..9_999_999.0) return raw
        }
        return 0.0
    }

    // Кликаем карточку с конкретной ценой (fallback)
    private fun tapCardByPrice(priceStr: String) {
        val root = rootInActiveWindow ?: return
        val priceWithSpace = priceStr.reversed().chunked(3).joinToString(" ").reversed()

        val priceNode = findNodeByTextAny(root, listOf(priceStr, priceWithSpace))
        if (priceNode != null) {
            var clickable: AccessibilityNodeInfo? = priceNode
            repeat(12) {
                if (clickable?.isClickable == true) return@repeat
                clickable = clickable?.parent
            }
            if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                prefs.incrementCalls()
                Log.d(TAG, "✅ Кликнули карточку с ценой $priceStr ₸ (fallback)")
                handler.post {
                    Toast.makeText(applicationContext, "🚗 Кликнули $priceStr ₸ — inDrive звонит!", Toast.LENGTH_SHORT).show()
                }
                return
            }
        }

        // Последний fallback: первая большая кликабельная карточка
        val firstCard = findFirstClickableCard(root)
        if (firstCard != null) {
            firstCard.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            prefs.incrementCalls()
            Log.d(TAG, "✅ Кликнули первую карточку (последний fallback)")
            handler.post {
                Toast.makeText(applicationContext, "🚗 Открываю карточку — inDrive звонит...", Toast.LENGTH_SHORT).show()
            }
            return
        }

        handler.post {
            Toast.makeText(applicationContext, "🚗 Подходящий попутчик найден — нажми сам!", Toast.LENGTH_LONG).show()
        }
    }

    private fun findNodeByTextAny(node: AccessibilityNodeInfo, texts: List<String>): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString() ?: ""
        val nodeDesc = node.contentDescription?.toString() ?: ""
        if (texts.any { nodeText.contains(it) || nodeDesc.contains(it) }) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findNodeByTextAny(it, texts) }
            if (found != null) return found
        }
        return null
    }

    private fun findFirstClickableCard(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable && node.childCount > 2) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findFirstClickableCard(it) }
            if (found != null) return found
        }
        return null
    }

    // ================================================================
    //  ОБРАБОТКА ЛЕНТЫ ЗАКАЗОВ (главный экран водителя — ₸/км)
    // ================================================================
    private fun processOrderFeed(texts: List<String>, fullText: String) {
        val cardPattern = Regex("""(\d+[.,]\d+)\s*[₸Т]/км[^\|]*?(\d[\d\s]+)\s*[₸Т]""")
        val matches = cardPattern.findAll(fullText)

        for (match in matches) {
            val cardText = extractCardBlock(fullText, match.range.first)
            val info = parseOrderCard(cardText)

            val cardKey = "${info.price}_${info.addressFrom}".hashCode()
            if (processedOrders.contains(cardKey)) continue

            val (passed, reason) = checkFilters(info)
            if (passed) {
                processedOrders.add(cardKey)
                if (processedOrders.size > 100) processedOrders.clear()

                prefs.incrementAccepted()
                prefs.setLastOrderInfo(buildSummary(info))
                onOrderDetected?.invoke(info)

                if (prefs.isAutoCallEnabled() && info.phone.isNotEmpty()) {
                    scheduleCall(info.phone)
                } else if (prefs.isAutoCallEnabled()) {
                    handler.post {
                        Toast.makeText(applicationContext,
                            "📦 Заказ ${info.price.toInt()}₸ подходит! Откройте карточку",
                            Toast.LENGTH_LONG).show()
                    }
                }
                break
            } else {
                Log.d(TAG, "❌ ${info.price.toInt()}₸ — $reason")
                prefs.incrementMissed()
            }
        }
    }

    private fun extractCardBlock(fullText: String, startPos: Int): String {
        val end = minOf(startPos + 500, fullText.length)
        return fullText.substring(startPos, end)
    }

    // ================================================================
    //  РАЗБОР КАРТОЧКИ ЗАКАЗА
    // ================================================================
    private fun parseOrderCard(cardText: String): OrderInfo {
        val pricePerKm = extractPricePerKm(cardText)
        val price = extractMainPrice(cardText)
        val distToClient = extractDistanceToClient(cardText)
        val rating = extractRating(cardText)
        val ratingCount = extractRatingCount(cardText)
        val clientName = extractClientName(cardText)
        val addressFrom = extractAddressFrom(cardText)
        val addressTo = extractAddressTo(cardText)
        val cityFrom = extractCityFromAddress(addressFrom)
        val cityTo = extractCityFromAddress(addressTo)
        val paymentType = extractPaymentType(cardText)
        val phone = extractPhone(cardText)
        val isIntercity = detectIntercity(cardText, cityFrom, cityTo)
        val orderType = if (isIntercity) "Межгород" else "Город"

        return OrderInfo(
            price, pricePerKm, distToClient, rating, ratingCount,
            phone, clientName, addressFrom, addressTo,
            cityFrom, cityTo, paymentType, isIntercity, orderType, cardText
        )
    }

    // ================================================================
    //  ОБРАБОТКА ОДИНОЧНОГО ЗАКАЗА (открытая карточка)
    // ================================================================
    private fun processSingleOrder(texts: List<String>, fullText: String) {
        val info = parseOrderCard(fullText)
        val cardKey = "${info.price}_${info.addressFrom}".hashCode()
        if (processedOrders.contains(cardKey)) return

        val (passed, reason) = checkFilters(info)
        if (passed) {
            processedOrders.add(cardKey)
            prefs.incrementAccepted()
            prefs.setLastOrderInfo(buildSummary(info))
            onOrderDetected?.invoke(info)
            if (prefs.isAutoCallEnabled() && info.phone.isNotEmpty()) scheduleCall(info.phone)
        } else {
            prefs.incrementMissed()
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
        val cleaned = text.replace(Regex("""\d+[.,]\d+\s*[₸Т]/км"""), "")
        val patterns = listOf(
            Regex("""(\d[\d\s]{2,6})\s*[₸Т](?!\s*/\s*км)"""),
            Regex("""[₸Т]\s*(\d[\d\s]{2,6})"""),
        )
        for (p in patterns) {
            val m = p.find(cleaned) ?: continue
            val n = m.groupValues[1].replace("\\s".toRegex(), "").toDoubleOrNull() ?: continue
            if (n in 300.0..9_999_999.0) return n
        }
        return 0.0
    }

    private fun extractDistanceToClient(text: String): Double {
        val mPattern = Regex("""~(\d+)\s*м\b""")
        val mMatch = mPattern.find(text)
        if (mMatch != null) return mMatch.groupValues[1].toDoubleOrNull()?.div(1000) ?: 0.0
        val kmPattern = Regex("""~(\d+[.,]\d+)\s*км""")
        val kmMatch = kmPattern.find(text)
        if (kmMatch != null) return kmMatch.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
        return 0.0
    }

    private fun extractRating(text: String): Double {
        val p = Regex("""([4-5][.,]\d{1,2})\s*\(""")
        return p.find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
    }

    private fun extractRatingCount(text: String): Int {
        val p = Regex("""\((\d+)\)""")
        return p.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun extractClientName(text: String): String {
        val p = Regex("""([А-ЯЁа-яёA-Za-z][а-яёa-z]{2,20})\s*[★⭐\*]?\s*[4-5][.,]\d""")
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
        return line.contains("₸") || line.contains("км") ||
               line.contains("Лента") || line.contains("Статистика") ||
               line.contains("Кошелёк") || line.contains("Спрос") ||
               line.contains("Создано", ignoreCase = true) ||
               line.contains("июн", ignoreCase = true)
    }

    private fun extractCityFromAddress(address: String): String {
        for (city in PreferenceManager.KZ_CITIES) {
            if (address.contains(city, ignoreCase = true)) return city
        }
        return ""
    }

    private fun extractPaymentType(text: String): String {
        return when {
            text.contains("Kaspi", ignoreCase = true) -> "Kaspi"
            text.contains("наличн", ignoreCase = true) -> "Наличные"
            text.contains("перевод", ignoreCase = true) -> "Перевод"
            text.contains("карт", ignoreCase = true) -> "Карта"
            else -> ""
        }
    }

    private fun extractPhone(text: String): String {
        val p = Regex("""(\+?[78][\s\-]?7\d{2}[\s\-]?\d{3}[\s\-]?\d{2}[\s\-]?\d{2})""")
        return p.find(text)?.groupValues?.get(1)?.replace("[\\s\\-]".toRegex(), "") ?: ""
    }

    private fun detectIntercity(text: String, cityFrom: String, cityTo: String): Boolean {
        if (text.contains("Межгород", ignoreCase = true)) return true
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
        if (mode == PreferenceManager.MODE_INTERCITY && !info.isIntercity)
            return false to "Не межгородской"
        if (mode == PreferenceManager.MODE_CARPOOL && info.orderType != "Попутчики" && info.orderType != "Посылка")
            return false to "Не попутчик/посылка"
        if (info.phone.isNotEmpty() && prefs.isBlacklisted(info.phone))
            return false to "Blacklist"
        if (info.price > 0) {
            val priceMode: String
            val minPrice: Double
            val fixedPrice: Double
            when {
                info.orderType == "Попутчики" || info.orderType == "Посылка" -> {
                    priceMode = if (info.orderType == "Посылка")
                        prefs.getIntercityPriceMode() else prefs.getCarpoolPriceMode()
                    minPrice = if (info.orderType == "Посылка")
                        prefs.getMinIntercityPrice() else prefs.getMinCarpoolPrice()
                    fixedPrice = if (info.orderType == "Посылка")
                        prefs.getFixedIntercityPrice() else prefs.getFixedCarpoolPrice()
                }
                info.isIntercity -> {
                    priceMode = prefs.getIntercityPriceMode()
                    minPrice = prefs.getMinIntercityPrice()
                    fixedPrice = prefs.getFixedIntercityPrice()
                }
                else -> {
                    priceMode = prefs.getCityPriceMode()
                    minPrice = prefs.getMinPrice()
                    fixedPrice = prefs.getFixedCityPrice()
                }
            }
            when (priceMode) {
                PreferenceManager.PRICE_OFF -> return false to "Фильтр выключен"
                PreferenceManager.PRICE_MIN ->
                    if (info.price < minPrice)
                        return false to "Цена ${info.price.toInt()}₸ < ${minPrice.toInt()}₸"
                PreferenceManager.PRICE_FIXED ->
                    if (fixedPrice > 0 && Math.abs(info.price - fixedPrice) > fixedPrice * 0.05)
                        return false to "Цена ${info.price.toInt()}₸ != ${fixedPrice.toInt()}₸"
            }
        }
        if (prefs.isCityFilterEnabled() && info.cityTo.isNotEmpty()) {
            if (prefs.getAllowedCities().none { info.cityTo.contains(it, ignoreCase = true) })
                return false to "Город '${info.cityTo}' не в списке"
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
        handler.postDelayed({ makeCall(phone) }, prefs.getCallDelayMs())
    }

    private fun makeCall(phone: String) {
        try {
            val clean = phone.replace("[^+0-9]".toRegex(), "")
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$clean")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            prefs.incrementCalls()
            handler.post {
                Toast.makeText(applicationContext, "📞 Звоним: $clean", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            handler.post {
                Toast.makeText(applicationContext, "⚠️ Дайте разрешение на звонки", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun buildSummary(info: OrderInfo): String = buildString {
        append(if (info.isIntercity) "🚚 МЕЖГОРОД\n" else "📦 Городская\n")
        if (info.price > 0) append("💵 ${info.price.toInt()} ₸")
        if (info.pricePerKm > 0) append("  (${info.pricePerKm} ₸/км)")
        append("\n")
        if (info.clientName.isNotEmpty()) append("👤 ${info.clientName}")
        if (info.rating > 0) append("  ⭐ ${info.rating}")
        if (info.ratingCount > 0) append(" (${info.ratingCount})")
        if (info.clientName.isNotEmpty() || info.rating > 0) append("\n")
        if (info.addressFrom.isNotEmpty()) append("📍 ${info.addressFrom.take(40)}\n")
        if (info.addressTo.isNotEmpty()) append("→ ${info.addressTo.take(40)}\n")
        if (info.paymentType.isNotEmpty()) append("💳 ${info.paymentType}\n")
        if (info.phone.isNotEmpty()) append("📞 ${info.phone}")
        else append("📞 Номер появится после принятия")
    }

    private fun collectAllText(node: AccessibilityNodeInfo, list: MutableList<String>) {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { list.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { list.add(it) }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectAllText(it, list) }
    }
}
