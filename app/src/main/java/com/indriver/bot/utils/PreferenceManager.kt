package com.indriver.bot.utils

import android.content.Context
import android.content.SharedPreferences

class PreferenceManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("indrive_bot_prefs", Context.MODE_PRIVATE)

    // ===== РЕЖИМ =====
    fun getMode(): String = prefs.getString("mode", MODE_ALL) ?: MODE_ALL
    fun setMode(v: String) = prefs.edit().putString("mode", v).apply()

    // ===== ФИЛЬТР ГОРОДСКАЯ ДОСТАВКА =====
    // mode: "off" | "min" | "fixed"
    fun getCityPriceMode(): String = prefs.getString("city_price_mode", PRICE_MIN) ?: PRICE_MIN
    fun setCityPriceMode(v: String) = prefs.edit().putString("city_price_mode", v).apply()
    fun getMinPrice(): Double = prefs.getFloat("filter_min_price", 2000f).toDouble()
    fun setMinPrice(v: Double) = prefs.edit().putFloat("filter_min_price", v.toFloat()).apply()
    fun getFixedCityPrice(): Double = prefs.getFloat("filter_fixed_city_price", 0f).toDouble()
    fun setFixedCityPrice(v: Double) = prefs.edit().putFloat("filter_fixed_city_price", v.toFloat()).apply()
    // legacy compat
    fun isMinPriceEnabled(): Boolean = getCityPriceMode() != PRICE_OFF
    fun setMinPriceEnabled(v: Boolean) { if (!v) setCityPriceMode(PRICE_OFF) }

    // ===== ФИЛЬТР МЕЖГОРОД ПОСЫЛКИ =====
    fun getIntercityPriceMode(): String = prefs.getString("intercity_price_mode", PRICE_MIN) ?: PRICE_MIN
    fun setIntercityPriceMode(v: String) = prefs.edit().putString("intercity_price_mode", v).apply()
    fun getMinIntercityPrice(): Double = prefs.getFloat("filter_min_intercity_price", 5000f).toDouble()
    fun setMinIntercityPrice(v: Double) = prefs.edit().putFloat("filter_min_intercity_price", v.toFloat()).apply()
    fun getFixedIntercityPrice(): Double = prefs.getFloat("filter_fixed_intercity_price", 0f).toDouble()
    fun setFixedIntercityPrice(v: Double) = prefs.edit().putFloat("filter_fixed_intercity_price", v.toFloat()).apply()
    // legacy compat
    fun isMinIntercityPriceEnabled(): Boolean = getIntercityPriceMode() != PRICE_OFF
    fun setMinIntercityPriceEnabled(v: Boolean) { if (!v) setIntercityPriceMode(PRICE_OFF) }

    // ===== ФИЛЬТР ПОПУТЧИКИ =====
    fun getCarpoolPriceMode(): String = prefs.getString("carpool_price_mode", PRICE_MIN) ?: PRICE_MIN
    fun setCarpoolPriceMode(v: String) = prefs.edit().putString("carpool_price_mode", v).apply()
    fun getMinCarpoolPrice(): Double = prefs.getFloat("filter_min_carpool_price", 5000f).toDouble()
    fun setMinCarpoolPrice(v: Double) = prefs.edit().putFloat("filter_min_carpool_price", v.toFloat()).apply()
    fun getFixedCarpoolPrice(): Double = prefs.getFloat("filter_fixed_carpool_price", 0f).toDouble()
    fun setFixedCarpoolPrice(v: Double) = prefs.edit().putFloat("filter_fixed_carpool_price", v.toFloat()).apply()
    // legacy compat
    fun isMinCarpoolPriceEnabled(): Boolean = getCarpoolPriceMode() != PRICE_OFF
    fun setMinCarpoolPriceEnabled(v: Boolean) { if (!v) setCarpoolPriceMode(PRICE_OFF) }

    // ===== ГОРОДА НАЗНАЧЕНИЯ =====
    fun getAllowedCities(): Set<String> =
        prefs.getStringSet("allowed_cities", DEFAULT_CITIES.toSet()) ?: DEFAULT_CITIES.toSet()
    fun setAllowedCities(cities: Set<String>) =
        prefs.edit().putStringSet("allowed_cities", cities).apply()
    fun isCityFilterEnabled(): Boolean = prefs.getBoolean("city_filter_on", false)
    fun setCityFilterEnabled(v: Boolean) = prefs.edit().putBoolean("city_filter_on", v).apply()

    // ===== АВТО-ЗВОНОК =====
    fun isAutoCallEnabled(): Boolean = prefs.getBoolean("auto_call_enabled", true)
    fun setAutoCallEnabled(v: Boolean) = prefs.edit().putBoolean("auto_call_enabled", v).apply()
    fun getCallDelayMs(): Long = prefs.getLong("call_delay_ms", 0L)
    fun setCallDelayMs(v: Long) = prefs.edit().putLong("call_delay_ms", v).apply()

    // ===== АВТО-WHATSAPP ПОСЛЕ ЗВОНКА =====
    // Номер берётся из самого исходящего звонка (CallLog), а не из карточки заказа в inDrive —
    // там номер часто скрыт. Сообщение НЕ отправляется автоматически, только открывается чат.
    fun isAutoWhatsAppEnabled(): Boolean = prefs.getBoolean("auto_whatsapp_enabled", true)
    fun setAutoWhatsAppEnabled(v: Boolean) = prefs.edit().putBoolean("auto_whatsapp_enabled", v).apply()

    // ===== РАБОЧЕЕ ВРЕМЯ =====
    fun isWorkHoursEnabled(): Boolean = prefs.getBoolean("work_hours_on", false)
    fun setWorkHoursEnabled(v: Boolean) = prefs.edit().putBoolean("work_hours_on", v).apply()
    fun getWorkStart(): Int = prefs.getInt("work_start", 8)
    fun setWorkStart(v: Int) = prefs.edit().putInt("work_start", v).apply()
    fun getWorkEnd(): Int = prefs.getInt("work_end", 22)
    fun setWorkEnd(v: Int) = prefs.edit().putInt("work_end", v).apply()

    // ===== СТАТИСТИКА =====
    fun getAcceptedCount(): Int = prefs.getInt("stat_accepted", 0)
    fun incrementAccepted() = prefs.edit().putInt("stat_accepted", getAcceptedCount() + 1).apply()
    fun getMissedCount(): Int = prefs.getInt("stat_missed", 0)
    fun incrementMissed() = prefs.edit().putInt("stat_missed", getMissedCount() + 1).apply()
    fun getCallCount(): Int = prefs.getInt("stat_calls", 0)
    fun incrementCalls() = prefs.edit().putInt("stat_calls", getCallCount() + 1).apply()
    fun getTotalEarnings(): Double = prefs.getFloat("stat_earnings", 0f).toDouble()
    fun addEarnings(amount: Double) =
        prefs.edit().putFloat("stat_earnings", (getTotalEarnings() + amount).toFloat()).apply()
    fun getLastOrderInfo(): String = prefs.getString("last_order_info", "") ?: ""
    fun setLastOrderInfo(info: String) = prefs.edit().putString("last_order_info", info).apply()
    fun getWinRate(): Double {
        val a = getAcceptedCount(); val m = getMissedCount()
        return if (a + m > 0) (a.toDouble() / (a + m)) * 100 else 0.0
    }
    fun resetStats() = prefs.edit()
        .putInt("stat_accepted", 0).putInt("stat_missed", 0)
        .putInt("stat_calls", 0).putFloat("stat_earnings", 0f)
        .putString("last_order_info", "").apply()

    // ===== BLACKLIST =====
    fun getBlacklist(): Set<String> =
        prefs.getStringSet("blacklist", emptySet()) ?: emptySet()
    fun addToBlacklist(phone: String) {
        val set = getBlacklist().toMutableSet()
        set.add(phone.replace("[^0-9]".toRegex(), ""))
        prefs.edit().putStringSet("blacklist", set).apply()
    }
    fun removeFromBlacklist(phone: String) {
        val set = getBlacklist().toMutableSet()
        set.remove(phone.replace("[^0-9]".toRegex(), ""))
        prefs.edit().putStringSet("blacklist", set).apply()
    }
    fun isBlacklisted(phone: String): Boolean =
        getBlacklist().contains(phone.replace("[^0-9]".toRegex(), ""))

    companion object {
        const val MODE_ALL = "all"
        const val MODE_INTERCITY = "intercity"
        const val MODE_CARPOOL = "carpool"

        const val PRICE_OFF = "off"
        const val PRICE_MIN = "min"
        const val PRICE_FIXED = "fixed"

        val KZ_CITIES = listOf(
            "Алматы", "Астана", "Шымкент", "Актобе", "Тараз",
            "Павлодар", "Усть-Каменогорск", "Семей", "Атырау",
            "Костанай", "Кызылорда", "Уральск", "Петропавловск",
            "Актау", "Темиртау", "Туркестан", "Кокшетау",
            "Талдыкорган", "Экибастуз", "Рудный", "Жезказган",
            "Житикара", "Балхаш", "Сатпаев", "Кентау", "Жанаозен",
            // Пригороды и районы Астаны
            "Астраханка", "Коянды", "Акмол", "Косшы", "Қарабұлақ",
            "Қараөткел", "Боровое", "Щучинск", "Степногорск",
            // Пригороды Алматы
            "Қаскелен", "Талгар", "Есик", "Қапшағай",
            // Другие города
            "Аксу", "Лисаковск", "Арысь", "Туркестан", "Арал",
            "Зайсан", "Риддер", "Шу", "Отеген батыр"
        ).sortedBy { it }

        val DEFAULT_CITIES = setOf("Алматы", "Астана", "Шымкент", "Актобе", "Тараз")
    }
}
