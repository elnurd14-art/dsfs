package com.indriver.bot.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * Открывает чат WhatsApp с клиентом по номеру телефона.
 * Не отправляет сообщение автоматически — только открывает чат,
 * чтобы водитель сам решил, что написать.
 */
object WhatsAppHelper {

    private const val TAG = "TaksaBot"
    private const val PKG_WHATSAPP = "com.whatsapp"
    private const val PKG_WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    /** Приводит номер к формату без +, пробелов и тире (нужно для wa.me и intent-схемы) */
    fun normalize(phone: String): String {
        var digits = phone.replace(Regex("[^0-9]"), "")
        // Казахстанские номера: если начинается с 8 и длина 11 — заменяем на 7
        if (digits.length == 11 && digits.startsWith("8")) {
            digits = "7" + digits.substring(1)
        }
        return digits
    }

    fun isInstalled(context: Context): Boolean = isAppInstalled(context, PKG_WHATSAPP) ||
            isAppInstalled(context, PKG_WHATSAPP_BUSINESS)

    private fun isAppInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Открывает чат WhatsApp с указанным номером.
     * Сначала пробует прямую intent-схему конкретного приложения (быстрее, без диалога
     * выбора), затем стандартный wa.me как запасной вариант.
     */
    fun openChat(context: Context, phone: String): Boolean {
        val digits = normalize(phone)
        if (digits.length < 10) {
            Log.w(TAG, "WhatsApp: номер слишком короткий для открытия чата: $phone")
            return false
        }

        val preferredPkg = when {
            isAppInstalled(context, PKG_WHATSAPP) -> PKG_WHATSAPP
            isAppInstalled(context, PKG_WHATSAPP_BUSINESS) -> PKG_WHATSAPP_BUSINESS
            else -> null
        }

        if (preferredPkg != null) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://wa.me/$digits")
                    setPackage(preferredPkg)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "WhatsApp openChat (прямой intent): ${e.message}")
            }
        }

        // Запасной вариант — пусть система сама решит, чем открыть wa.me ссылку
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "WhatsApp openChat (wa.me fallback): ${e.message}")
            false
        }
    }
}
