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
        val price: Double,          // итоговая цена в ₸
        val pricePerKm: Double,     // цена за км (₸/км)
        val distanceToClient: Double, // расстояние до клиента (м или км)
        val rating: Double,         // рейтинг клиента
        val ratingCount: Int,       // количество оценок
        val phone: String,          // номер телефона (может быть пустым)
        val clientName: String,     // имя клиента
        val addressFrom: String,    // адрес откуда
        val addressTo: String,      // адрес куда
        val cityFrom: String,       // город откуда
        val cityTo: String,         // город куда
        val paymentType: String,    // Kaspi / Наличные и т.д.
        val isIntercity: Boolean,
        val orderType: String,      // "Город" / "Межгород" / "Грузовые"
        val rawText: String
    )

    private lateinit var prefs: PreferenceManager
    private val handler = Handler(Looper.getMainLooper())
    private var lastCallTime = 0L
    private val MIN_CALL_INTERVAL = 15_000L
    private var lastScreenHash = 0
    // Хранение уже обработанных заказов чтобы не дублировать
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

        // === Определяем что за экран ===
        val isOrderFeed = isOrderFeedScreen(fullText)
        val isSingleOrder = isSingleOrderScreen(fullText)
        val isCarpoolFeed = isCarpoolFeedScreen(fullText)

        when {
            isCarpoolFeed && prefs.getMode() == PreferenceManager.MODE_CARPOOL ->
                processCarpoolFeed(texts, fullText)
            isOrderFeed -> processOrderFeed(texts, fullText)
            isSingleOrder -> processSingleOrder(texts, fullText)
        }
    }

    // ================================================================
    //  ОПРЕДЕЛЕНИЕ ЭКРАНА
    // ================================================================

    // Экран "Лента заказов" — список карточек как на фото 3
    private fun isOrderFeedScreen(text: String): Boolean {
        return (text.contains("Лента заказов", ignoreCase = true) ||
                text.contains("₸/км", ignoreCase = true)) &&
               (text.contains("₸") || text.contains("тенге", ignoreCase = true))
    }

    // Экран попутчиков (вкладка «Попутки» — фото 1)
    private fun isCarpoolFeedScreen(text: String): Boolean {
        return (text.contains("С попутчиками", ignoreCase = true) ||
                text.contains("Попутки", ignoreCase = true)) &&
               (text.contains("₸") || text.contains("тенге", ignoreCase = true))
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
    //  ОБРАБОТКА ПОПУТЧИКОВ (вкладка «Попутки» — пассажирское приложение)
    //  Логика: найти карточку → проверить цену → нажать "Откликнуться" →
    //          после открытия карточки номер станет виден → позвонить
    // ================================================================
    private fun processCarpoolFeed(texts: List<String>, fullText: String) {
        // Карточки попутчиков содержат цену в ₸ и «С попутчиками»
        val pricePattern = Regex("""(\d[\d\s]{2,8})\s*[₸Т]""")
        val matches = pricePattern.findAll(fullText)

        for (match in matches) {
            val rawPrice = match.groupValues[1].replace("\\s".toRegex(), "").toDoubleOrNull() ?: continue
            if (rawPrice < 500 || rawPrice > 9_999_999) continue

            val cardText = fullText.substring(
                maxOf(0, match.range.first - 50),
                minOf(fullText.length, match.range.last + 400)
            )

            // Извлекаем города из карточки попутчика
            val cityFrom = PreferenceManager.KZ_CITIES.firstOrNull {
                cardText.contains(it, ignoreCase = true)
            } ?: ""
            val cityTo = PreferenceManager.KZ_CITIES.lastOrNull {
                cardText.contains(it, ignoreCase = true) && it != cityFrom
            } ?: ""

            val cardKey = "${rawPrice}_$cityFrom".hashCode()
            if (processedOrders.contains(cardKey)) continue

            // Фильтр минимальной цены
            if (prefs.isMinCarpoolPriceEnabled() && rawPrice < prefs.getMinCarpoolPrice()) {
                Log.d(TAG, "🚗 Попутчик ${rawPrice.toInt()}₸ < мин ${prefs.getMinCarpoolPrice().toInt()}₸")
                continue
            }

            // Фильтр городов назначения
            if (prefs.isCityFilterEnabled() && cityTo.isNotEmpty()) {
                if (prefs.getAllowedCities().none { cityTo.contains(it, ignoreCase = true) }) {
                    Log.d(TAG, "🚗 Попутчик — город $cityTo не в списке")
                    continue
                }
            }

            // Подходит! Кликаем карточку — inDrive сам звонит
            processedOrders.add(cardKey)
            if (processedOrders.size > 50) processedOrders.clear()

            prefs.incrementAccepted()
            val summary = "🚗 ПОПУТЧИК\n💵 ${rawPrice.toInt()} ₸\n📍 $cityFrom → $cityTo\n→ Кликаю карточку..."
            prefs.setLastOrderInfo(summary)
            onOrderDetected?.invoke(
                OrderInfo(rawPrice, 0.0, 0.0, 0.0, 0, "", "", cityFrom, cityTo,
                    cityFrom, cityTo, "", true, "Попутчики", cardText)
            )

            // Кликаем карточку с нужной ценой — inDrive сам открывает и звонит
            val priceStr = rawPrice.toInt().toString()
            handler.postDelayed({ tapCardByPrice(priceStr) }, prefs.getCallDelayMs())
            break
        }
    }

    // Кликаем карточку с конкретной ценой — inDrive сам открывает и звонит клиенту
    private fun tapCardByPrice(priceStr: String) {
        val root = rootInActiveWindow ?: return

        // Ищем узел с нужной ценой (например "7 000" или "7000")
        val priceNode = findNodeWithPrice(root, priceStr)
        if (priceNode != null) {
            // Поднимаемся до кликабельного родителя — это и есть карточка
            var clickable: AccessibilityNodeInfo? = priceNode
            for (i in 0..10) {
                if (clickable?.isClickable == true) break
                clickable = clickable?.parent
            }
            val clicked = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            if (clicked) {
                prefs.incrementCalls()
                Log.d(TAG, "✅ Кликнули карточку с ценой $priceStr ₸")
                handler.post {
                    Toast.makeText(applicationContext,
                        "🚗 Кликнули $priceStr ₸ — inDrive звонит!",
                        Toast.LENGTH_SHORT).show()
                }
                return
            }
        }

        // Fallback: кликаем по tapCarpoolAcceptButton
        Log.d(TAG, "⚠️ Цена $priceStr не найдена, пробуем общий клик")
        tapCarpoolAcceptButton()
    }

    // Ищем узел содержащий цену (с пробелами или без: "7 000" / "7000")
    private fun findNodeWithPrice(node: AccessibilityNodeInfo, priceStr: String): AccessibilityNodeInfo? {
        val text = node.text?.toString() ?: ""
        // Цена может быть "7 000 ₸" или "7000₸" — проверяем разные форматы
        val priceWithSpace = priceStr.reversed().chunked(3).joinToString(" ").reversed()
        if (text.contains(priceStr) || text.contains(priceWithSpace)) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findNodeWithPrice(it, priceStr) }
            if (found != null) return found
        }
        return null
    }

        // Кликаем на карточку попутчика в ленте — inDrive сам звонит клиенту
    private fun tapCarpoolAcceptButton() {
        val root = rootInActiveWindow ?: return

        // Стратегия 1: найти карточку по тексту "С попутчиками" и кликнуть её родителя
        val carpoolNode = findNodeByText(root, "С попутчиками")
        if (carpoolNode != null) {
            // Поднимаемся вверх по дереву до кликабельного контейнера (сама карточка)
            var target: AccessibilityNodeInfo? = carpoolNode
            repeat(6) {
                val parent = target?.parent
                if (parent != null && parent.isClickable) target = parent
                else if (parent != null) target = parent
            }
            // Ищем кликабельного предка
            var clickable: AccessibilityNodeInfo? = carpoolNode
            repeat(8) {
                if (clickable?.isClickable == true) return@repeat
                clickable = clickable?.parent
            }
            val clicked = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            Log.d(TAG, if (clicked) "✅ Кликнули карточку попутчика" else "⚠️ Клик не прошёл")
            if (clicked) {
                prefs.incrementCalls()
                handler.post {
                    Toast.makeText(applicationContext,
                        "🚗 Открываю карточку — inDrive звонит...",
                        Toast.LENGTH_SHORT).show()
                }
                return
            }
        }

        // Стратегия 2: кликнуть первый кликабельный большой элемент на экране (карточка заказа)
        val firstCard = findFirstClickableCard(root)
        if (firstCard != null) {
            firstCard.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            prefs.incrementCalls()
            Log.d(TAG, "✅ Кликнули первую карточку на экране")
            handler.post {
                Toast.makeText(applicationContext,
                    "🚗 Открываю карточку — inDrive звонит...",
                    Toast.LENGTH_SHORT).show()
            }
            return
        }

        Log.d(TAG, "⚠️ Карточка попутчика не найдена для клика")
        handler.post {
            Toast.makeText(applicationContext,
                "🚗 Подходящий попутчик найден — нажми на карточку сам!",
                Toast.LENGTH_LONG).show()
        }
    }

    // Ищем первый большой кликабельный элемент (карточка заказа в ленте)
    private fun findFirstClickableCard(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable && node.childCount > 2) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findFirstClickableCard(it) }
            if (found != null) return found
        }
        return null
    }

    // Поиск узла по тексту рекурсивно
    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString() ?: ""
        val nodeDesc = node.contentDescription?.toString() ?: ""
        if ((nodeText.contains(text, ignoreCase = true) || nodeDesc.contains(text, ignoreCase = true))
            && node.isClickable) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findNodeByText(it, text) }
            if (found != null) return found
        }
        return null
    }

        // ================================================================
    //  ОБРАБОТКА ЛЕНТЫ ЗАКАЗОВ (фото 3 — главный экран водителя)
    // ================================================================
    private fun processOrderFeed(texts: List<String>, fullText: String) {
        // В ленте может быть несколько карточек — ищем первую подходящую

        // Разбиваем на блоки по разделителю между карточками
        // Каждая карточка начинается с "X,X ₸/км" — это уникальный маркер
        val cardPattern = Regex("""(\d+[.,]\d+)\s*[₸Т]/км[^\|]*?(\d[\d\s]+)\s*[₸Т]""")
        val matches = cardPattern.findAll(fullText)

        for (match in matches) {
            val cardText = extractCardBlock(fullText, match.range.first)
            val info = parseOrderCard(cardText)

            // Уникальный ключ карточки = цена + адрес от
            val cardKey = "${info.price}_${info.addressFrom}".hashCode()
            if (processedOrders.contains(cardKey)) continue

            val (passed, reason) = checkFilters(info)
            if (passed) {
                processedOrders.add(cardKey)
                if (processedOrders.size > 50) processedOrders.clear()

                prefs.incrementAccepted()
                prefs.setLastOrderInfo(buildSummary(info))
                onOrderDetected?.invoke(info)

                if (prefs.isAutoCallEnabled() && info.phone.isNotEmpty()) {
                    scheduleCall(info.phone)
                } else if (prefs.isAutoCallEnabled()) {
                    // Номер появится после принятия — пока уведомление
                    handler.post {
                        Toast.makeText(applicationContext,
                            "📦 Заказ ${info.price.toInt()}₸ подходит! Откройте карточку",
                            Toast.LENGTH_LONG).show()
                    }
                }
                break // Берём первый подходящий
            } else {
                Log.d(TAG, "❌ ${info.price.toInt()}₸ — $reason")
            }
        }
    }

    // Извлекаем блок текста одной карточки
    private fun extractCardBlock(fullText: String, startPos: Int): String {
        val end = minOf(startPos + 400, fullText.length)
        return fullText.substring(startPos, end)
    }

    // ================================================================
    //  РАЗБОР КАРТОЧКИ ЗАКАЗА (формат из фото 3)
    // ================================================================
    private fun parseOrderCard(cardText: String): OrderInfo {
        // Пример текста карточки:
        // "192,3 ₸/км ~745 м | 1 000 ₸ | Сатурн-3, жилой комплекс (Астана) | Ак Шанырак (Астана) | Kaspi (перевод)"

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
    //  ИЗВЛЕЧЕНИЕ ДАННЫХ — под реальный формат inDrive
    // ================================================================

    // "192,3 ₸/км" — цена за километр
    private fun extractPricePerKm(text: String): Double {
        val p = Regex("""(\d+[.,]\d+)\s*[₸Т]/км""")
        return p.find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
    }

    // "1 000 ₸" — главная цена (крупным шрифтом)
    // На экране inDrive цена идёт ПОСЛЕ "₸/км ~X м" блока
    private fun extractMainPrice(text: String): Double {
        // Убираем "₸/км" блок чтобы не путать с ценой за км
        val cleaned = text.replace(Regex("""\d+[.,]\d+\s*[₸Т]/км"""), "")
        val patterns = listOf(
            Regex("""(\d[\d\s]{2,6})\s*[₸Т](?!\s*/\s*км)"""),  // "1 000 ₸" не за км
            Regex("""[₸Т]\s*(\d[\d\s]{2,6})"""),
        )
        for (p in patterns) {
            val m = p.find(cleaned) ?: continue
            val n = m.groupValues[1].replace("\\s".toRegex(), "").toDoubleOrNull() ?: continue
            if (n in 300.0..9_999_999.0) return n
        }
        return 0.0
    }

    // "~745 м" или "~2,3 км" — расстояние до клиента
    private fun extractDistanceToClient(text: String): Double {
        // В метрах
        val mPattern = Regex("""~(\d+)\s*м\b""")
        val mMatch = mPattern.find(text)
        if (mMatch != null) return mMatch.groupValues[1].toDoubleOrNull()?.div(1000) ?: 0.0
        // В километрах
        val kmPattern = Regex("""~(\d+[.,]\d+)\s*км""")
        val kmMatch = kmPattern.find(text)
        if (kmMatch != null) return kmMatch.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
        return 0.0
    }

    // "⭐ 5.0 (88)" — рейтинг
    private fun extractRating(text: String): Double {
        val p = Regex("""([4-5][.,]\d{1,2})\s*\(""")
        return p.find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
    }

    // "(88)" — количество оценок
    private fun extractRatingCount(text: String): Int {
        val p = Regex("""\((\d+)\)""")
        return p.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    // Имя клиента — слово перед рейтингом (Назерке, Марал, Алия)
    private fun extractClientName(text: String): String {
        val p = Regex("""([А-ЯЁа-яёA-Za-z][а-яёa-z]{2,20})\s*[★⭐\*]?\s*[4-5][.,]\d""")
        return p.find(text)?.groupValues?.get(1)?.trim() ?: ""
    }

    // Адрес ОТКУДА — первый адресный блок
    private fun extractAddressFrom(text: String): String {
        // Формат: "Название жилого комплекса (улица, Город)"
        val lines = text.split("|", "\n").map { it.trim() }.filter { it.length > 5 }
        for (line in lines) {
            if (isAddressLine(line) && !isMetaLine(line)) return line
        }
        return ""
    }

    // Адрес КУДА — второй адресный блок
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
               PreferenceManager.KZ_CITIES.any { line.contains(it, ignoreCase = true) }
    }

    private fun isMetaLine(line: String): Boolean {
        return line.contains("₸") || line.contains("км") ||
               line.contains("Лента") || line.contains("Статистика") ||
               line.contains("Кошелёк") || line.contains("Спрос")
    }

    // Извлекаем город из адреса "(Астана)" или "Астана,"
    private fun extractCityFromAddress(address: String): String {
        for (city in PreferenceManager.KZ_CITIES) {
            if (address.contains(city, ignoreCase = true)) return city
        }
        return ""
    }

    // Тип оплаты: "Kaspi (перевод)", "Наличные"
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

    // Межгород: слово "Межгород" в меню ИЛИ два разных города
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
        // Рабочее время
        if (prefs.isWorkHoursEnabled()) {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (h < prefs.getWorkStart() || h >= prefs.getWorkEnd())
                return false to "Вне рабочего времени"
        }
        // Режим
        val mode = prefs.getMode()
        if (mode == PreferenceManager.MODE_INTERCITY && !info.isIntercity)
            return false to "Не межгородской"
        if (mode == PreferenceManager.MODE_CARPOOL && info.orderType != "Попутчики")
            return false to "Не попутчик"
        // Blacklist
        if (info.phone.isNotEmpty() && prefs.isBlacklisted(info.phone))
            return false to "Blacklist"
        // Фильтр цены — зависит от режима (off/min/fixed)
        if (info.price > 0) {
            val priceMode: String
            val minPrice: Double
            val fixedPrice: Double
            when {
                info.orderType == "Попутчики" -> {
                    priceMode = prefs.getCarpoolPriceMode()
                    minPrice = prefs.getMinCarpoolPrice()
                    fixedPrice = prefs.getFixedCarpoolPrice()
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
        // Города назначения
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
                Toast.makeText(applicationContext,
                    "⚠️ Дайте разрешение на звонки", Toast.LENGTH_LONG).show()
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
