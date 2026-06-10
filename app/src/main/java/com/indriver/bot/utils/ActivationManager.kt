package com.indriver.bot.utils

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction

/**
 * Активация через Firebase Realtime Database.
 *
 * Структура в Firebase:
 *   codes/
 *     TAKSA-XXXXX: ""           ← свободен
 *     TAKSA-YYYYY: "androidId"  ← занят устройством
 *
 * Правила Firebase:
 *   {
 *     "rules": {
 *       "codes": {
 *         "$code": {
 *           ".read": true,
 *           ".write": "!data.exists() || data.val() == ''"
 *         }
 *       }
 *     }
 *   }
 */
object ActivationManager {

    private const val PREFS_NAME     = "taksa_activation"
    private const val KEY_ACTIVE     = "is_activated"
    private const val KEY_ACT_CODE   = "activated_by"
    private const val KEY_ACT_DEVICE = "activated_device_id"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun deviceId(ctx: Context): String =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    fun isActivated(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (!p.getBoolean(KEY_ACTIVE, false)) return false
        return (p.getString(KEY_ACT_DEVICE, "") ?: "") == deviceId(ctx)
    }

    sealed class Result {
        object Success          : Result()
        object RestoredDevice   : Result()  // переустановка на том же телефоне
        object UsedOtherDevice  : Result()  // код занят другим телефоном
        object Invalid          : Result()  // кода нет в базе
        object NetworkError     : Result()  // нет интернета
        object AlreadyActivated : Result()
    }

    fun activate(ctx: Context, rawCode: String, callback: (Result) -> Unit) {
        if (isActivated(ctx)) { callback(Result.AlreadyActivated); return }

        val code = rawCode.trim().uppercase()
        if (code.isBlank()) { callback(Result.Invalid); return }

        val me  = deviceId(ctx)
        val ref = FirebaseDatabase.getInstance()
            .getReference("codes")
            .child(code)

        // Сначала читаем — существует ли код вообще
        ref.get().addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                callback(Result.NetworkError)
                return@addOnCompleteListener
            }

            val snapshot = task.result
            if (!snapshot.exists()) {
                callback(Result.Invalid)
                return@addOnCompleteListener
            }

            val owner = snapshot.getValue(String::class.java) ?: ""

            when {
                owner == me -> {
                    // Наш код (переустановка) — восстанавливаем локально
                    saveLocal(ctx, code, me)
                    callback(Result.RestoredDevice)
                }
                owner.isNotEmpty() -> {
                    // Занят другим устройством
                    callback(Result.UsedOtherDevice)
                }
                else -> {
                    // Свободен ("") — занимаем через транзакцию
                    ref.runTransaction(object : Transaction.Handler {
                        override fun doTransaction(data: MutableData): Transaction.Result {
                            val current = data.getValue(String::class.java) ?: ""
                            return if (current.isEmpty()) {
                                data.value = me          // атомарно записываем наш ID
                                Transaction.success(data)
                            } else {
                                Transaction.abort()      // кто-то успел раньше
                            }
                        }

                        override fun onComplete(
                            error: DatabaseError?,
                            committed: Boolean,
                            snapshot: DataSnapshot?
                        ) {
                            when {
                                error != null  -> callback(Result.NetworkError)
                                committed      -> { saveLocal(ctx, code, me); callback(Result.Success) }
                                else           -> callback(Result.UsedOtherDevice)
                            }
                        }
                    })
                }
            }
        }
    }

    private fun saveLocal(ctx: Context, code: String, device: String) {
        prefs(ctx).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_ACT_CODE, code)
            .putString(KEY_ACT_DEVICE, device)
            .apply()
    }

    fun activatedBy(ctx: Context): String =
        prefs(ctx).getString(KEY_ACT_CODE, "") ?: ""
}
