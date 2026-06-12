package com.indriver.bot.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

class CallStateManager(private val ctx: Context) {

    companion object {
        const val TAG = "TaksaCall"
    }

    private val telephony = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val handler   = Handler(Looper.getMainLooper())
    private val prefs     = PreferenceManager(ctx)

    private var pendingPhone: String? = null
    private var wasOffHook  = false

    // ── Android 12+ (API 31) — новый API ────────────────────────────
    private val telephonyCallback: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleState(state)
            }
        }
    } else null

    // ── Android < 12 — устаревший API ───────────────────────────────
    @Suppress("DEPRECATION")
    private val legacyListener = object : PhoneStateListener() {
        @Deprecated("Deprecated in API 31")
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            handleState(state)
        }
    }

    private fun handleState(state: Int) {
        Log.d(TAG, "CallState: $state")
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> wasOffHook = true
            TelephonyManager.CALL_STATE_IDLE -> {
                if (wasOffHook) {
                    wasOffHook = false
                    val phone = pendingPhone ?: return
                    pendingPhone = null
                    if (prefs.isWaEnabled()) {
                        handler.postDelayed({ sendWhatsApp(phone) }, 1500L)
                    }
                }
            }
        }
    }

    fun start() {
        val hasPermission = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Log.w(TAG, "READ_PHONE_STATE не выдано — WA после звонка не будет работать")
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let {
                    telephony?.registerTelephonyCallback(ctx.mainExecutor,
                        it as TelephonyCallback)
                }
            } else {
                @Suppress("DEPRECATION")
                telephony?.listen(legacyListener, PhoneStateListener.LISTEN_CALL_STATE)
            }
            Log.d(TAG, "CallStateManager запущен")
        } catch (e: Exception) {
            Log.e(TAG, "start: ${e.message}")
        }
    }

    fun stop() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let {
                    telephony?.unregisterTelephonyCallback(it as TelephonyCallback)
                }
            } else {
                @Suppress("DEPRECATION")
                telephony?.listen(legacyListener, PhoneStateListener.LISTEN_NONE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "stop: ${e.message}")
        }
        pendingPhone = null
        wasOffHook   = false
    }

    fun setPendingPhone(phone: String) {
        if (prefs.isWaEnabled()) {
            pendingPhone = normalizePhone(phone)
            Log.d(TAG, "Pending WA: $pendingPhone")
        }
    }

    private fun sendWhatsApp(phone: String) {
        try {
            val clean   = normalizePhone(phone)
            val encoded = Uri.encode(prefs.getWaTemplate())
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://api.whatsapp.com/send?phone=$clean&text=$encoded")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
            Log.d(TAG, "WA отправлен: $clean")
        } catch (e: Exception) {
            Log.e(TAG, "sendWhatsApp: ${e.message}")
        }
    }

    private fun normalizePhone(phone: String): String {
        var clean = phone.replace(Regex("[^+0-9]"), "")
        if (clean.startsWith("8") && clean.length == 11)
            clean = "7${clean.substring(1)}"
        if (!clean.startsWith("+"))
            clean = "+$clean"
        return clean
    }
}
