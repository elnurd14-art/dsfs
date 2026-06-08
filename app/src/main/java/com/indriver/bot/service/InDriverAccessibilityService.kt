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
        val orderType: String,      // "Город" / "Посылка" / "Попутчики"
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
        Log.d(TAG, "Сервис подключён — Такса Bot")
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

        // Приоритет: сначала попутки (они не содержат ₸/км)
        when {
            isCarpoolFeedScreen(fullText) -> processCarpoolFeed(root, fullText)
            isOrderFeedScreen(fullText)   -> processOrderFeed(texts, fullText)
            isSingleOrderScreen(fullText) -> processSingleOrder(texts, fullText)
        }
    }

    // ================================================================
    //  ОПРЕДЕЛЕНИЕ ЭКРАНА
    // ================================================================

    // Лента заказов водителя — содержит ₸/км (цена за километр)
    private fun isOrderFeedScreen(text: String): Boolean {
        return text.contains("₸/км", ignoreCase = true)
    }

    // Экран попутчиков / посылок (вкладка Попутки) — НЕТ ₸/км
    private fun isCarpoolFeedScreen(text: String): Boolean {
        val hasCarpoolMarker = text.contains("С попутчиками", ignoreCase = true) ||
                               text.contains("Отправить посылку", ignoreCase = true)
        val hasPrice = text.contains("₸") || text.contains("тенге", ignoreCase = true)
        val noKmPrice = !text.contains("₸/км", ignoreCase = true)
        return hasCarpoolMarker && hasPrice && noKmPrice
    }

    // Открытая карточка одного заказа
    private fun isSingleOrderScreen(text: String): Boolean {
        return listOf(
            "Принять", "Предложить цену", "Откликнуться",
            "Ищем водителей", "Ожидайте звонков",
            "Отменить заказ", "Откуда", "Куда"
        ).any { text.contains(it, ignoreCase = true) }
    }

    // ================================================================
    //  ОБРАБОТКА ПОПУТЧИКОВ / ПОСЫЛОК
    //  Ключевая логика: обходим дерево View, каждую карточку
    //  обрабатываем отдельно — не смешиваем текст нескольких карточек
    // ================================================================
    private fun processCarpoolFeed(root: AccessibilityNodeInfo, fullText: String) {
        val mode = prefs.getMode()

        val cards = findCarpoolCards(root)
        Log.d(TAG, "Найдено карточек попутчиков/посылок: ${cards.size}")

        for (cardNode in cards) {
            val cardTexts = mutableListOf<String>()
            collectAllText(cardNode, cardTexts)
            val cardText = cardTexts.joinToString(" | ")

            val price = extractCarpoolPrice(cardText)
            if (price <= 0) continue

            val isParcel = cardText.contains("Отправить посылку", ignoreCase = true)
            val orderType = if (isParcel) "Посылка" else "Попутчики"

            // Режимный фильтр: пропускаем если режим не совпадает
            when (mode) {
                PreferenceManager.MODE_INTERCITY ->
                    if (!isParcel) { Log.d(TAG, "Режим Посылки — пропускаем попутчика"); continue }
                PreferenceManager.MODE_CARPOOL ->
                    if (isParcel) { Log.d(TAG, "Режим Попутчики — пропускаем посылку"); continue }
                // MODE_ALL — принимаем всё
            }

            // Города — ТОЛЬКО из этой карточки
            val cityFrom = PreferenceManager.KZ_CITIES.firstOrNull {
                cardText.contains(it, ignoreCase = true)
            } ?: "Астана"
            val cityTo = PreferenceManager.KZ_CITIES.firstOrNull {
                cardText.contains(it, ignoreCase = true) && !it.equals(cityFrom, ignoreCase = true)
            } ?: ""

            // Уникальный ключ карточки
            val cardKey = "${price.toInt()}_${cardText.take(80)}".hashCode()
            if (processedOrders.contains(cardKey)) continue

            // Фильтр цены
            val priceMode = if (isParcel) prefs.getIntercityPriceMode() else prefs.getCarpoolPriceMode()
            val minPrice  = if (isParcel) prefs.getMinIntercityPrice()  else prefs.getMinCarpoolPrice()
            val fixedPrice = if (isParcel) prefs.getFixedIntercityPrice() else prefs.getFixedCarpoolPrice()

            val priceFilterResult = when (priceMode) {
                PreferenceManager.PRICE_OFF  -> true   // FIX: выкл = принимаем всё
                PreferenceManager.PRICE_MIN  -> price >= minPrice
                PreferenceManager.PRICE_FIXED -> fixedPrice <= 0 ||
                        Math.abs(price - fixedPrice) <= fixedPrice * 0.05
                else -> true
            }
            if (!priceFilterResult) {
                Log.d(TAG, "$orderType ${price.toInt()}T отклонён по цене (min=${minPrice.toInt()})")
                prefs.incrementMissed()
                continue
            }

            // Фильтр городов назначения
            if (prefs.isCityFilterEnabled() && cityTo.isNotEmpty()) {
                if (prefs.getAllowedCities().none { cityTo.contains(it, ignoreCase = true) }) {
                    Log.d(TAG, "$orderType — город '$cityTo' не в списке")
                    prefs.incrementMissed()
                    continue
                }
            }

            // ===  ПОДХОДИТ  ===
            processedOrders.add(cardKey)
            if (processedOrders.size > 200) processedOrders.clear()

            prefs.incrementAccepted()
            val summary = buildCarpoolSummary(orderType, price, cityFrom, cityTo)
            prefs.setLastOrderInfo(summary)

            val info = OrderInfo(price, 0.0, 0.0, 0.0, 0, "",
                "", cityFrom, cityTo, cityFrom, cityTo, "", true, orderType, cardText)
            onOrderDetected?.invoke(info)

            // Кликаем карточку после задержки
            val capturedNode = cardNode
            handler.postDelayed({
                val clicked = performClickOnCard(capturedNode)
                if (!clicked) tapCardByPrice(price.toInt().toString())
            }, prefs.getCallDelayMs())

            break // берём первую подходящую карточку
        }
    }

    // ================================================================
    //  ПОИСК КАРТОЧЕК ПОПУТЧИКОВ В ДЕРЕВЕ VIEW
    // ================================================================
    private fun findCarpoolCards(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        findCarpoolCardsRecursive(root, result, 0)
        return result
    }

    private fun findCarpoolCardsRecursive(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>, depth: Int) {
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
                    return // не углубляемся внутрь карточки
                }
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                findCarpoolCardsRecursive(child, result, depth + 1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "findCarpoolCardsRecursive error: ${e.message}")
        }
    }

    private fun performClickOnCard(cardNode: AccessibilityNodeInfo): Boolean {
        return try {
            if (cardNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                prefs.incrementCalls()
                handler.post { showToast("Открываю карточку — inDrive звонит!") }
                return true
            }
            // Пробуем родителей (до 5 уровней)
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
            Log.e(TAG, "performClickOnCard error: ${e.message}")
            false
        }
    }

    private fun extractCarpoolPrice(text: String): Double {
        // Ищем числа перед ₸, пропускаем маленькие (номера домов) и огромные
        val pattern = Regex("""(\d[\d\s]{1,7})\s*[₸Т]""")
        for (match in pattern.findAll(text)) {
            val raw = match.groupValues[1].replace(Regex("\\s"), "").toDoubleOrNull() ?: continue
            if (raw in 500.0..9_999_999.0) return raw
        }
        return 0.0
    }

    private fun tapCardByPrice(priceStr: String) {
        try {
            val root = rootInActiveWindow ?: return
            // Форматируем цену с пробелом для группировки тысяч: "15000" -> "15 000"
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
                    Log.d(TAG, "Кликнули карточку по цене $priceStr (fallback1)")
                    handler.post { showToast("Нашёл заказ $priceStr T — звоним!") }
                    return
                }
            }

            // Последний fallback — первая большая кликабельная карточка
            val firstCard = findFirstClickableCard(root)
            if (firstCard != null) {
                firstCard.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                prefs.incrementCalls()
                Log.d(TAG, "Кликнули первую карточку (fallback2)")
                handler.post { showToast("Открываю карточку — inDrive звонит...") }
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "tapCardByPrice error: ${e.message}")
        }

        handler.post { showToast("Подходящий заказ найден — нажмите сами!") }
    }

    private fun findNodeByTextAny(node: AccessibilityNodeInfo, texts: List<String>): AccessibilityNodeInfo? {
        try {
            val nodeText = node.text?.toString() ?: ""
            val nodeDesc = node.contentDescription?.toString() ?: ""
            if (texts.any { nodeText.contains(it) || nodeDesc.contains(it) }) return node
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

    // ================================================================
    //  ОБРАБОТКА ЛЕНТЫ ЗАКАЗОВ (главный экран водителя — ₸/км)
    // ================================================================
    private fun processOrderFeed(texts: List<String>, fullText: String) {
        // Каждая карточка начинается с блока "X,X ₸/км"
        val cardPattern = Regex("""(\d+[.,]\d+)\s*[₸Т]/км""")
        val matches = cardPattern.findAll(fullText).toList()

        for (i in matches.indices) {
            val start = matches[i].range.first
            // Конец блока = начало следующей карточки или +600 символов
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
                prefs.setLastOrderInfo(buildSummary(info))
                onOrderDetected?.invoke(info)

                if (prefs.isAutoCallEnabled() && info.phone.isNotEmpty()) {
                    scheduleCall(info.phone)
                } else if (prefs.isAutoCallEnabled()) {
                    handler.post {
                        showToast("Заказ ${info.price.toInt()}T подходит! Откройте карточку")
                    }
                }
                break
            } else {
                Log.d(TAG, "Отклонён ${info.price.toInt()}T — $reason")
                prefs.incrementMissed()
            }
        }
    }

    // ================================================================
    //  РАЗБОР КАРТОЧКИ ЗАКАЗА
    // ================================================================
    private fun parseOrderCard(cardText: String): OrderInfo {
        val pricePerKm     = extractPricePerKm(cardText)
        val price          = extractMainPrice(cardText)
        val distToClient   = extractDistanceToClient(cardText)
        val rating         = extractRating(cardText)
        val ratingCount    = extractRatingCount(cardText)
        val clientName     = extractClientName(cardText)
        val addressFrom    = extractAddressFrom(cardText)
        val addressTo      = extractAddressTo(cardText)
        val cityFrom       = extractCityFromAddress(addressFrom)
        val cityTo         = extractCityFromAddress(addressTo)
        val paymentType    = extractPaymentType(cardText)
        val phone          = extractPhone(cardText)
        val isIntercity    = detectIntercity(cardText, cityFrom, cityTo)
        // Посылка определяется по маркеру в тексте карточки
        val isParcel       = cardText.contains("Отправить посылку", ignoreCase = true) ||
                             cardText.contains("посылк", ignoreCase = true)
        val orderType      = when {
            isParcel    -> "Посылка"
            isIntercity -> "Посылка"   // межгород без маркера тоже посылка
            else        -> "Город"
        }

        return OrderInfo(
            price, pricePerKm, distToClient, rating, ratingCount,
            phone, clientName, addressFrom, addressTo,
            cityFrom, cityTo, paymentType, isIntercity || isParcel, orderType, cardText
        )
    }

    // ================================================================
    //  ОБРАБОТКА ОДИНОЧНОГО ЗАКАЗА (открытая карточка)
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
            prefs.setLastOrderInfo(buildSummary(info))
            onOrderDetected?.invoke(info)
            if (prefs.isAutoCallEnabled() && info.phone.isNotEmpty()) scheduleCall(info.phone)
        } else {
            prefs.incrementMissed()
            onOrderRejected?.invoke(info, reason)
            Log.d(TAG, "Одиночный заказ отклонён: $reason")
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
        // Убираем "₸/км" блок чтобы не спутать с основной ценой
        val cleaned = text.replace(Regex("""\d+[.,]\d+\s*[₸Т]/км"""), "SKIP")
        val patterns = listOf(
            Regex("""(\d[\d\s]{2,7})\s*[₸Т](?!\s*/\s*км)"""),
            Regex("""[₸Т]\s*(\d[\d\s]{2,7})""")
        )
        for (p in patterns) {
            val m = p.find(cleaned) ?: continue
            val n = m.groupValues[1].replace(Regex("\\s"), "").toDoubleOrNull() ?: continue
            if (n in 300.0..9_999_999.0) return n
        }
        return 0.0
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
        return line.contains("₸") ||
               line.contains(" км") ||
               line.contains("Лента") ||
               line.contains("Статистика") ||
               line.contains("Кошелёк") ||
               line.contains("Спрос") ||
               line.contains("Создано ") ||   // "Создано 7 июня"
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
            text.contains("Kaspi", ignoreCase = true)     -> "Kaspi"
            text.contains("наличн", ignoreCase = true)    -> "Наличные"
            text.contains("перевод", ignoreCase = true)   -> "Перевод"
            text.contains("карт", ignoreCase = true)      -> "Карта"
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
    //  PRICE_OFF = фильтр выключен = принимать всё (не отклонять!)
    // ================================================================
    private fun checkFilters(info: OrderInfo): Pair<Boolean, String> {
        // Рабочее время
        if (prefs.isWorkHoursEnabled()) {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (h < prefs.getWorkStart() || h >= prefs.getWorkEnd())
                return false to "Вне рабочего времени"
        }

        // Режим работы
        val mode = prefs.getMode()
        when (mode) {
            PreferenceManager.MODE_INTERCITY ->
                if (info.orderType != "Посылка" && !info.isIntercity)
                    return false to "Режим: только посылки"
            PreferenceManager.MODE_CARPOOL ->
                if (info.orderType != "Попутчики")
                    return false to "Режим: только попутчики"
        }

        // Блэклист
        if (info.phone.isNotEmpty() && prefs.isBlacklisted(info.phone))
            return false to "Заблокирован"

        // Фильтр цены
        if (info.price > 0) {
            val (priceMode, minPrice, fixedPrice) = when (info.orderType) {
                "Попутчики" -> Triple(
                    prefs.getCarpoolPriceMode(),
                    prefs.getMinCarpoolPrice(),
                    prefs.getFixedCarpoolPrice()
                )
                "Посылка" -> Triple(
                    prefs.getIntercityPriceMode(),
                    prefs.getMinIntercityPrice(),
                    prefs.getFixedIntercityPrice()
                )
                else -> Triple(
                    prefs.getCityPriceMode(),
                    prefs.getMinPrice(),
                    prefs.getFixedCityPrice()
                )
            }
            when (priceMode) {
                // PRICE_OFF = фильтр выключен — пропускаем без проверки цены
                PreferenceManager.PRICE_MIN ->
                    if (info.price < minPrice)
                        return false to "Цена ${info.price.toInt()}T < ${minPrice.toInt()}T"
                PreferenceManager.PRICE_FIXED ->
                    if (fixedPrice > 0 && Math.abs(info.price - fixedPrice) > fixedPrice * 0.05)
                        return false to "Цена ${info.price.toInt()}T != ${fixedPrice.toInt()}T"
            }
        }

        // Фильтр городов
        if (prefs.isCityFilterEnabled() && info.cityTo.isNotEmpty()) {
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
        if (now - lastCallTime < MIN_CALL_INTERVAL) {
            Log.d(TAG, "Звонок заблокирован — слишком частый")
            return
        }
        lastCallTime = now
        handler.postDelayed({ makeCall(phone) }, prefs.getCallDelayMs())
    }

    private fun makeCall(phone: String) {
        try {
            val clean = phone.replace(Regex("[^+0-9]"), "")
            if (clean.length < 10) {
                Log.e(TAG, "Некорректный номер: $clean")
                return
            }
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$clean")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            prefs.incrementCalls()
            handler.post { showToast("Звоним: $clean") }
        } catch (e: SecurityException) {
            handler.post { showToast("Нет разрешения на звонки — проверьте настройки") }
        } catch (e: Exception) {
            Log.e(TAG, "makeCall error: ${e.message}")
        }
    }

    // ================================================================
    //  СВОДКА ЗАКАЗА
    // ================================================================
    private fun buildSummary(info: OrderInfo): String = buildString {
        val typeLabel = when (info.orderType) {
            "Посылка"    -> "ПОСЫЛКА"
            "Попутчики"  -> "ПОПУТЧИКИ"
            else         -> "ГОРОД"
        }
        append("$typeLabel\n")
        if (info.price > 0) {
            append("${info.price.toInt()} T")
            if (info.pricePerKm > 0) append("  (${info.pricePerKm} T/км)")
            append("\n")
        }
        if (info.clientName.isNotEmpty()) append("${info.clientName}")
        if (info.rating > 0) append("  ${info.rating}")
        if (info.ratingCount > 0) append(" (${info.ratingCount})")
        if (info.clientName.isNotEmpty() || info.rating > 0) append("\n")
        if (info.addressFrom.isNotEmpty()) append("От: ${info.addressFrom.take(40)}\n")
        if (info.addressTo.isNotEmpty()) append("До: ${info.addressTo.take(40)}\n")
        if (info.paymentType.isNotEmpty()) append("${info.paymentType}\n")
        if (info.phone.isNotEmpty()) append(info.phone)
        else append("Номер после принятия")
    }

    private fun buildCarpoolSummary(orderType: String, price: Double, from: String, to: String): String {
        val sb = StringBuilder()
        sb.append("$orderType\n")
        sb.append("${price.toInt()} T\n")
        if (from.isNotEmpty() || to.isNotEmpty()) sb.append("$from -> $to\n")
        sb.append("Нажимаю карточку...")
        return sb.toString()
    }

    // ================================================================
    //  ВСПОМОГАТЕЛЬНЫЕ
    // ================================================================
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
        } catch (e: Exception) {
            // игнорируем — узел мог быть переработан системой
        }
    }
}
