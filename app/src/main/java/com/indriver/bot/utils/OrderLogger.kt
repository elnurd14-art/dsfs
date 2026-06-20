package com.indriver.bot.utils

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Журнал последних 50 заказов с персистентностью через SharedPreferences (JSON).
 *
 * Вся работа с диском и JSON (чтение, парсинг, сериализация) выполняется в фоновом
 * потоке через однопоточный Executor — он гарантирует, что записи обрабатываются
 * строго по порядку (важно, чтобы "новые сверху" не перепутались), но при этом
 * НИКОГДА не блокирует главный поток. Это особенно важно для AccessibilityService:
 * add() вызывается сразу после клика по заказу, и раньше это было синхронным
 * JSON parse + до 50 SimpleDateFormat вызовов + JSON serialize — то есть реальная
 * пауза в главном Looper-потоке, на время которой бот не мог обработать новые
 * accessibility-события (включая следующий заказ).
 */
class OrderLogger(context: Context) {

    data class LogEntry(
        val timestamp: Long,
        val orderType: String,   // "Город" / "Посылка" / "Попутчики"
        val price: Int,
        val cityFrom: String,
        val cityTo: String,
        val status: String,      // "ПРИНЯТ" / "ПРОПУЩЕН"
        val reason: String       // причина пропуска или ""
    ) {
        fun formattedTime(): String = timeFormat().format(Date(timestamp))

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

        companion object {
            // SimpleDateFormat не потокобезопасен — у каждого потока свой экземпляр.
            private val tlFormat = object : ThreadLocal<SimpleDateFormat>() {
                override fun initialValue() = SimpleDateFormat("dd.MM HH:mm", Locale("ru"))
            }
            fun timeFormat(): SimpleDateFormat = tlFormat.get()!!
        }
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("taksa_order_log", Context.MODE_PRIVATE)

    // Однопоточный executor — гарантирует порядок записей (FIFO), но работа идёт
    // не в главном потоке. Лёгче, чем корутины/WorkManager, для такого маленького файла.
    private val ioExecutor = Executors.newSingleThreadExecutor()

    /**
     * Добавляет запись в лог. Возвращается немедленно — вся работа с диском
     * происходит в фоновом потоке. Если важен порядок относительно других add(),
     * Executor гарантирует, что задачи выполнятся в порядке постановки.
     */
    fun add(entry: LogEntry) {
        ioExecutor.execute {
            try {
                val list = getAllBlocking().toMutableList()
                list.add(0, entry)             // новые сверху
                if (list.size > 50) list.subList(50, list.size).clear()
                saveBlocking(list)
            } catch (e: Exception) {
                android.util.Log.e("TaksaBot", "OrderLogger.add: ${e.message}")
            }
        }
    }

    /**
     * Читает весь лог. Вызывается из UI (LogActivity/MainActivity), где блокирующее
     * чтение SharedPreferences допустимо — это не горячий путь обработки заказа.
     */
    fun getAll(): List<LogEntry> = getAllBlocking()

    private fun getAllBlocking(): List<LogEntry> {
        val raw = prefs.getString("log", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it).toEntry() }
        } catch (e: Exception) { emptyList() }
    }

    /**
     * Очищает лог синхронно. Это безопасно — очистка не требует парсинга/сериализации
     * списка (просто запись пустого массива), и вызывается только из UI по нажатию
     * кнопки, никогда из горячего пути обработки заказа. Синхронность здесь нужна,
     * чтобы UI, читающий лог сразу после очистки (getAll()), не увидел старые данные
     * из-за гонки с фоновым add().
     */
    fun clear() {
        ioExecutor.submit {
            prefs.edit().putString("log", "[]").apply()
        }.get()
    }

    fun todaySummary(): String {
        val entries = getAll()
        val today = SimpleDateFormat("dd.MM", Locale("ru")).format(Date())
        val todayEntries = entries.filter { it.formattedTime().startsWith(today) }
        val accepted = todayEntries.count { it.status == "ПРИНЯТ" }
        val missed   = todayEntries.count { it.status == "ПРОПУЩЕН" }
        val earned   = todayEntries.filter { it.status == "ПРИНЯТ" }.sumOf { it.price }
        return "Сегодня: принято $accepted, пропущено $missed, доход ~$earned T"
    }

    /**
     * Останавливает фоновый поток логгера. Вызывать при остановке сервиса/активности,
     * чтобы не оставлять висящий поток при повторном создании OrderLogger.
     * Уже поставленные в очередь задачи (например, последний add()) успеют завершиться —
     * shutdown() (в отличие от shutdownNow()) не прерывает текущую работу.
     */
    fun shutdown() {
        ioExecutor.shutdown()
    }

    private fun saveBlocking(list: List<LogEntry>) {
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
