package com.indriver.bot.utils

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue

/**
 * Активация с проверкой через Firebase Realtime Database.
 *
 * Структура в Firebase:
 *   codes/
 *     TAKSA-XXXXX: ""           ← код свободен
 *     TAKSA-YYYYY: "androidId"  ← код занят устройством
 *
 * Правила Firebase (установить в консоли):
 *   {
 *     "rules": {
 *       "codes": {
 *         "$code": {
 *           ".read": true,
 *           ".write": "!data.exists()"
 *         }
 *       }
 *     }
 *   }
 *
 * Логика activate():
 *   1. Код не существует в базе → Invalid
 *   2. Код существует, значение "" → свободен, пишем свой deviceId (атомарно через transaction)
 *   3. Код существует, значение == наш deviceId → уже наш, восстанавливаем локально
 *   4. Код существует, значение != наш deviceId → занят другим устройством
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

    /** Приложение уже активировано локально и deviceId совпадает */
    fun isActivated(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (!p.getBoolean(KEY_ACTIVE, false)) return false
        val saved = p.getString(KEY_ACT_DEVICE, "") ?: ""
        return saved == deviceId(ctx)
    }

    sealed class Result {
        object Success         : Result()
        object RestoredDevice  : Result()  // код наш, восстановили активацию
        object UsedOtherDevice : Result()  // код занят другим телефоном
        object Invalid         : Result()  // кода нет в базе
        object NetworkError    : Result()  // нет интернета / timeout
        object AlreadyActivated: Result()
    }

    /**
     * Проверяем код через Firebase.
     * Callback вызывается в главном потоке.
     */
    fun activate(ctx: Context, rawCode: String, callback: (Result) -> Unit) {
        if (isActivated(ctx)) { callback(Result.AlreadyActivated); return }

        val code = rawCode.trim().uppercase()
        if (code.isBlank()) { callback(Result.Invalid); return }

        val me = deviceId(ctx)
        val ref = FirebaseDatabase.getInstance()
            .getReference("codes")
            .child(code)

        // Читаем текущее значение
        ref.get().addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                callback(Result.NetworkError)
                return@addOnCompleteListener
            }

            val snapshot = task.result
            if (!snapshot.exists()) {
                // Кода нет в базе вообще
                callback(Result.Invalid)
                return@addOnCompleteListener
            }

            val owner = snapshot.getValue(String::class.java) ?: ""

            when {
                owner.isEmpty() -> {
                    // Код свободен — пытаемся занять атомарно
                    ref.setValue(me).addOnCompleteListener { writeTask ->
                        if (writeTask.isSuccessful) {
                            saveLocalActivation(ctx, code, me)
                            callback(Result.Success)
                        } else {
                            // Правило ".write": "!data.exists()" сработало — кто-то занял раньше
                            callback(Result.UsedOtherDevice)
                        }
                    }
                }
                owner == me -> {
                    // Код уже наш (переустановка)
                    saveLocalActivation(ctx, code, me)
                    callback(Result.RestoredDevice)
                }
                else -> {
                    // Код занят другим устройством
                    callback(Result.UsedOtherDevice)
                }
            }
        }
    }

    private fun saveLocalActivation(ctx: Context, code: String, device: String) {
        prefs(ctx).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_ACT_CODE, code)
            .putString(KEY_ACT_DEVICE, device)
            .apply()
    }

    fun activatedBy(ctx: Context): String =
        prefs(ctx).getString(KEY_ACT_CODE, "") ?: ""
}
