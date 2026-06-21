package com.indriver.bot.service

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.util.Log
import com.indriver.bot.utils.WhatsAppHelper

/**
 * Следит за журналом звонков (CallLog) и ловит номер ИМЕННО ТОГО исходящего
 * вызова, который инициировал бот. Номер берётся из реального звонка,
 * а не из текста карточки заказа в inDrive — там он часто скрыт или отсутствует.
 *
 * Два режима взвода:
 *  - arm(phoneNumber)  — номер уже известен заранее (бот сам звонил), просто
 *                        подтверждаем, что именно этот звонок попал в CallLog.
 *  - armBlind(onFound) — номер НЕ известен заранее (звонок инициирует сам
 *                        inDrive при нажатии на карточку попутчика/посылки,
 *                        а номера в тексте карточки нет). Берём номер из
 *                        первой же исходящей записи CallLog после взвода.
 *
 * Защита от чужих звонков: реагируем только на armed-номер (или на ближайший
 * исходящий после armBlind), остальные изменения CallLog игнорируются.
 */
class CallLogWhatsAppWatcher(private val context: Context) {

    companion object {
        private const val TAG = "TaksaBot"
        private const val ARM_TIMEOUT_MS = 30_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var armedNumber: String? = null
    private var blindCallback: ((String) -> Unit)? = null
    private var armTimeMs = 0L
    private var observer: ContentObserver? = null

    private val disarmRunnable = Runnable { disarm() }

    /** Взвести наблюдатель: ждём, что в CallLog появится исходящий звонок на этот номер */
    fun arm(phoneNumber: String) {
        val digits = WhatsAppHelper.normalize(phoneNumber)
        if (digits.length < 10) return

        armedNumber   = digits
        blindCallback = null
        armTimeMs     = System.currentTimeMillis()
        rearmTimeout()
        ensureObserver()
    }

    /**
     * Взвести наблюдатель "вслепую": номер неизвестен, ждём первую исходящую
     * запись в CallLog после этого момента и отдаём её номер в callback.
     * Используется, когда сам inDrive звонит при нажатии на карточку и номер
     * в карточке не показан.
     */
    fun armBlind(onFound: (String) -> Unit) {
        armedNumber   = null
        blindCallback = onFound
        armTimeMs     = System.currentTimeMillis()
        rearmTimeout()
        ensureObserver()
    }

    private fun rearmTimeout() {
        handler.removeCallbacks(disarmRunnable)
        handler.postDelayed(disarmRunnable, ARM_TIMEOUT_MS)
    }

    private fun ensureObserver() {
        if (observer != null) return
        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                checkLatestCall()
            }
        }
        observer = obs
        try {
            context.contentResolver.registerContentObserver(
                CallLog.Calls.CONTENT_URI, true, obs
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "CallLogWatcher: нет разрешения READ_CALL_LOG — пропускаем")
        }
    }

    private fun disarm() {
        armedNumber   = null
        blindCallback = null
    }

    fun release() {
        disarm()
        handler.removeCallbacks(disarmRunnable)
        observer?.let {
            try { context.contentResolver.unregisterContentObserver(it) } catch (e: Exception) { /* нет-ноп */ }
        }
        observer = null
    }

    private fun checkLatestCall() {
        val blind = blindCallback
        val expected = armedNumber
        if (expected == null && blind == null) return

        try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                null, null,
                "${CallLog.Calls.DATE} DESC LIMIT 1"
            ) ?: return

            cursor.use {
                if (it.moveToFirst()) {
                    val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: ""
                    val type = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                    val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                    val normalized = WhatsAppHelper.normalize(number)
                    val isOutgoing = type == CallLog.Calls.OUTGOING_TYPE

                    if (blind != null) {
                        // Вслепую: берём ЛЮБОЙ исходящий звонок, появившийся после взвода.
                        if (isOutgoing && date >= armTimeMs && normalized.length >= 10) {
                            Log.d(TAG, "CallLogWatcher: вслепую найден исходящий $normalized")
                            disarm()
                            blind(normalized)
                        }
                        return
                    }

                    if (expected != null) {
                        val matches = normalized == expected ||
                                normalized.takeLast(10) == expected.takeLast(10)
                        if (isOutgoing && matches) {
                            Log.d(TAG, "CallLogWatcher: подтверждён исходящий звонок на $expected — открываю WhatsApp")
                            disarm()
                            WhatsAppHelper.openChat(context, expected)
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "CallLogWatcher: нет разрешения READ_CALL_LOG")
        } catch (e: Exception) {
            Log.e(TAG, "CallLogWatcher: ${e.message}")
        }
    }
}
