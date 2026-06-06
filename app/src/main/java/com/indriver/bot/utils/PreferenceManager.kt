package com.indriver.bot.utils

import android.content.Context
import android.content.SharedPreferences

class PreferenceManager(context: Context) {

    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences("indriver_bot_prefs", Context.MODE_PRIVATE)

    // Bot Settings
    fun isAutoBidEnabled(): Boolean = sharedPreferences.getBoolean("auto_bid_enabled", true)
    fun setAutoBidEnabled(enabled: Boolean) = sharedPreferences.edit().putBoolean("auto_bid_enabled", enabled).apply()

    fun areNotificationsEnabled(): Boolean = sharedPreferences.getBoolean("notifications_enabled", true)
    fun setNotificationsEnabled(enabled: Boolean) = sharedPreferences.edit().putBoolean("notifications_enabled", enabled).apply()

    fun getMinPrice(): Double = sharedPreferences.getString("min_price", "0.0")?.toDoubleOrNull() ?: 0.0
    fun setMinPrice(price: Double) = sharedPreferences.edit().putString("min_price", price.toString()).apply()

    fun getMaxDistance(): Double = sharedPreferences.getString("max_distance", "10.0")?.toDoubleOrNull() ?: 10.0
    fun setMaxDistance(distance: Double) = sharedPreferences.edit().putString("max_distance", distance.toString()).apply()

    // Stats
    fun getAcceptedCount(): Int = sharedPreferences.getInt("accepted_count", 0)
    fun incrementAccepted() = sharedPreferences.edit().putInt("accepted_count", getAcceptedCount() + 1).apply()

    fun getMissedCount(): Int = sharedPreferences.getInt("missed_count", 0)
    fun incrementMissed() = sharedPreferences.edit().putInt("missed_count", getMissedCount() + 1).apply()

    fun getTodayAccepted(): Int = sharedPreferences.getInt("today_accepted", 0)
    fun incrementTodayAccepted() = sharedPreferences.edit().putInt("today_accepted", getTodayAccepted() + 1).apply()

    fun getTodayEarnings(): Double = sharedPreferences.getString("today_earnings", "0.0")?.toDoubleOrNull() ?: 0.0
    fun addTodayEarnings(amount: Double) = sharedPreferences.edit().putString("today_earnings", (getTodayEarnings() + amount).toString()).apply()

    fun getTotalEarnings(): Double = sharedPreferences.getString("total_earnings", "0.0")?.toDoubleOrNull() ?: 0.0
    fun addEarnings(amount: Double) = sharedPreferences.edit().putString("total_earnings", (getTotalEarnings() + amount).toString()).apply()

    fun getBestDay(): String = sharedPreferences.getString("best_day", "N/A") ?: "N/A"
    fun setBestDay(day: String) = sharedPreferences.edit().putString("best_day", day).apply()

    fun getWinRate(): Double {
        val accepted = getAcceptedCount()
        val missed = getMissedCount()
        val total = accepted + missed
        return if (total > 0) (accepted.toDouble() / total) * 100 else 0.0
    }

    fun resetStats() {
        sharedPreferences.edit().apply {
            putInt("accepted_count", 0)
            putInt("missed_count", 0)
            putString("total_earnings", "0.0")
            putInt("today_accepted", 0)
            putString("today_earnings", "0.0")
            putString("best_day", "N/A")
            apply()
        }
    }
}
