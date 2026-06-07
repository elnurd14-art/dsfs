package com.indriver.bot.utils

import android.content.Context
import android.content.SharedPreferences

class PreferenceManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("indrive_bot_prefs", Context.MODE_PRIVATE)

    // ===== РЕЖИМ =====
    fun getMode(): String = prefs.getString("mode", MODE_ALL) ?: MODE_ALL
    fun setMode(v: String) = prefs.edit().putString("mode", v).apply()

    // ===== ФИЛЬТРЫ ЦЕНЫ =====
    fun isMinPriceEnabled(): Boolean = prefs.getBoolean("filter_min_price_on", true)
    fun setMinPriceEnabled(v: Boolean) = prefs.edit().putBoolean("filter_min_price_on", v).apply()
    fun getMinPrice(): Double = prefs.getFloat("filter_min_price", 2000f).toDouble()
    fun setMinPrice(v: Double) = prefs.edit().putFloat("filter_min_price", v.toFloat()).apply()

    fun isMinIntercityPriceEnabled(): Boolean = prefs.getBoolean("filter_min_intercity_price_on", true)
    fun setMinIntercityPriceEnabled(v: Boolean) = prefs.edit().putBoolean("filter_min_intercity_price_on", v).apply()
    fun getMinIntercityPrice(): Double = prefs.getFloat("filter_min_intercity_price", 5000f).toDouble()
    fun setMinIntercityPrice(v: Double) = prefs.edit().putFloat("filter_min_intercity_price", v.toFloat()).apply()

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
    fun getCallDelayMs(): Long = prefs.getLong("call_delay_ms", 1000L)
    fun setCallDelayMs(v: Long) = prefs.edit().putLong("call_delay_ms", v).apply()

    // ===== РАБОЧЕЕ ВРЕМЯ =====
    fun isWorkHoursEnabled(): Boolean = prefs.getBoolean("work_hours_on", false)
    fun setWorkHoursEnabled(v: Boolean) = prefs.edit().putBoolean("work_hours_on", v).apply()
    fun getWorkStart(): Int = prefs.getInt("work_start", 8)   // час
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

    // ===== BLACKLIST НОМЕРОВ =====
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

    // ===== ПОПУТЧИКИ =====
    fun isMinCarpoolPriceEnabled(): Boolean = prefs.getBoolean("filter_min_carpool_price_on", true)
    fun setMinCarpoolPriceEnabled(v: Boolean) = prefs.edit().putBoolean("filter_min_carpool_price_on", v).apply()
    fun getMinCarpoolPrice(): Double = prefs.getFloat("filter_min_carpool_price", 5000f).toDouble()
    fun setMinCarpoolPrice(v: Double) = prefs.edit().putFloat("filter_min_carpool_price", v.toFloat()).apply()

    companion object {
        const val MODE_ALL = "all"           // городская + межгород посылки
        const val MODE_INTERCITY = "intercity" // только межгород посылки
        const val MODE_CARPOOL = "carpool"   // только межгород попутчики

        val KZ_CITIES = listOf(
            "Алматы", "Астана", "Шымкент", "Актобе", "Тараз",
            "Павлодар", "Усть-Каменогорск", "Семей", "Атырау",
            "Костанай", "Кызылорда", "Уральск", "Петропавловск",
            "Актау", "Темиртау", "Туркестан", "Кокшетау",
            "Талдыкорган", "Экибастуз", "Рудный", "Жезказган",
            "Житикара",
            "Балхаш", "Сатпаев", "Кентау", "Жанаозен"
        ).sorted()

        val DEFAULT_CITIES = setOf(
            "Алматы", "Астана", "Шымкент", "Актобе", "Тараз"
        )
    }
}
