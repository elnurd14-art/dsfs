package com.indriver.bot.utils

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал последних 50 заказов с персистентностью через SharedPreferences (JSON).
 */
class OrderLogger(context: Context) {

    data class LogEntry(
        val timestamp: Long,
        val orderType: String,   // "Весь салон" / "Посылка" / "Попутчики"
        val price: Int,
        val cityFrom: String,
        val cityTo: String,
        val status: String,      // "ПРИНЯТ" / "ПРОПУЩЕН"
        val reason: String       // причина пропуска или ""
    ) {
        fun formattedTime(): String =
            SimpleDateFormat("dd.MM HH:mm", Locale("ru")).format(Date(timestamp))

        fun shortSummary(): String {
            val route = when {
                cityFrom.isNotEmpty() && cityTo.isNotEmpty() -> "$cityFrom -> $cityTo"
                cityTo.isNotEmpty() -> "-> $cityTo"
                else -> ""
            }
            return buildString {
                append(formattedTime())
                append("  ")
                append(if (status == "ПРИНЯТ") "[+]" else "[-]")
                append("  ")
                append("$price T")
                if (route.isNotEmpty()) { append("  "); append(route) }
                if (reason.isNotEmpty()) { append("\n     "); append(reason) }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("taksa_order_log", Context.MODE_PRIVATE)

    private val fmt = SimpleDateFormat("dd.MM HH:mm", Locale("ru"))

    fun add(entry: LogEntry) {
        val list = getAll().toMutableList()
        list.add(0, entry)             // новые сверху
        if (list.size > 50) list.subList(50, list.size).clear()
        save(list)
    }

    fun getAll(): List<LogEntry> {
        val raw = prefs.getString("log", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it).toEntry() }
        } catch (e: Exception) { emptyList() }
    }

    fun clear() = prefs.edit().putString("log", "[]").apply()

    fun todaySummary(): String {
        val entries = getAll()
        val today = SimpleDateFormat("dd.MM", Locale("ru")).format(Date())
        val todayEntries = entries.filter { it.formattedTime().startsWith(today) }
        val accepted = todayEntries.count { it.status == "ПРИНЯТ" }
        val missed   = todayEntries.count { it.status == "ПРОПУЩЕН" }
        val earned   = todayEntries.filter { it.status == "ПРИНЯТ" }.sumOf { it.price }
        return "Сегодня: принято $accepted, пропущено $missed, доход ~$earned T"
    }

    private fun save(list: List<LogEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("log", arr.toString()).apply()
    }

    private fun LogEntry.toJson() = JSONObject().apply {
        put("ts", timestamp)
        put("type", orderType)
        put("price", price)
        put("from", cityFrom)
        put("to", cityTo)
        put("status", status)
        put("reason", reason)
    }

    private fun JSONObject.toEntry() = LogEntry(
        timestamp = getLong("ts"),
        orderType = getString("type"),
        price     = getInt("price"),
        cityFrom  = optString("from", ""),
        cityTo    = optString("to", ""),
        status    = getString("status"),
        reason    = optString("reason", "")
    )
}
