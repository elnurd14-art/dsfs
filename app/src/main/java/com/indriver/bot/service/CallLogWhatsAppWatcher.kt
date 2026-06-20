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
 * Как работает:
 * 1. Перед звонком вызывается arm() — наблюдатель "взведён" и ждёт ровно один
 *    исходящий звонок.
 * 2. Когда CallLog обновляется новой исходящей записью, наблюдатель сверяет
 *    её номер с тем, на который бот только что позвонил (makeCall),
 *    и если всё совпало — открывает WhatsApp с этим номером.
 * 3. Защита от чужих звонков: реагируем только на armed-номер, остальные
 *    изменения CallLog игнорируются.
 */
class CallLogWhatsAppWatcher(private val context: Context) {

    companion object {
        private const val TAG = "TaksaBot"
        private const val ARM_TIMEOUT_MS = 30_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var armedNumber: String? = null
    private var observer: ContentObserver? = null

    private val disarmRunnable = Runnable { disarm() }

    /** Взвести наблюдатель: ждём, что в CallLog появится исходящий звонок на этот номер */
    fun arm(phoneNumber: String) {
        val digits = WhatsAppHelper.normalize(phoneNumber)
        if (digits.length < 10) return

        armedNumber = digits
        handler.removeCallbacks(disarmRunnable)
        handler.postDelayed(disarmRunnable, ARM_TIMEOUT_MS)

        if (observer == null) {
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
    }

    private fun disarm() {
        armedNumber = null
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
        val expected = armedNumber ?: return
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
                    val normalized = WhatsAppHelper.normalize(number)

                    val isOutgoing = type == CallLog.Calls.OUTGOING_TYPE
                    val matches = normalized == expected ||
                            normalized.takeLast(10) == expected.takeLast(10)

                    if (isOutgoing && matches) {
                        Log.d(TAG, "CallLogWatcher: подтверждён исходящий звонок на $expected — открываю WhatsApp")
                        disarm()
                        WhatsAppHelper.openChat(context, expected)
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
