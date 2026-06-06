package com.indriver.bot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class InDriverAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "InDriverBot"
        // Оба package name inDrive (старый и новый)
        const val PACKAGE_INDRIVER_OLD = "sinet.startup.inDriver"
        const val PACKAGE_INDRIVER_NEW = "com.indriver"

        @Volatile
        private var instance: InDriverAccessibilityService? = null

        fun getInstance(): InDriverAccessibilityService? = instance
    }

    private var totalOrdersDetected = 0
    private var ordersAccepted = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE or
                   AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                   AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
            packageNames = arrayOf(PACKAGE_INDRIVER_OLD, PACKAGE_INDRIVER_NEW)
        }
        serviceInfo = info

        Log.d(TAG, "Сервис специальных возможностей подключён")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        if (packageName != PACKAGE_INDRIVER_OLD && packageName != PACKAGE_INDRIVER_NEW) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> handleNotification(event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> handleWindowChange(event)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Сервис прерван")
    }

    private fun handleNotification(event: AccessibilityEvent) {
        val text = event.text?.joinToString(" ") ?: ""
        Log.d(TAG, "Уведомление: $text")

        // Ключевые слова на русском, казахском, арабском и английском
        val orderKeywords = listOf("заказ", "поездка", "заявка", "жаңа", "тапсырыс", "طلب", "order", "новый")
        if (orderKeywords.any { text.contains(it, ignoreCase = true) }) {
            totalOrdersDetected++
            Log.d(TAG, "Новый заказ обнаружен! Всего: $totalOrdersDetected")
            tryAcceptOrder()
        }
    }

    private fun handleWindowChange(event: AccessibilityEvent) {
        val className = event.className?.toString() ?: ""
        if (className.contains("Order", ignoreCase = true) ||
            className.contains("Trip", ignoreCase = true)) {
            tryAcceptOrder()
        }
    }

    private fun tryAcceptOrder() {
        val root = rootInActiveWindow ?: return

        // Тексты кнопок: русский, казахский, арабский, английский, испанский
        val acceptTexts = listOf(
            "Принять", "ПРИНЯТЬ",
            "Қабылдау", "ҚАБЫЛДАУ",
            "قبول",
            "Accept", "ACCEPT",
            "ACEPTAR"
        )

        for (text in acceptTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable) {
                    val accepted = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (accepted) {
                        ordersAccepted++
                        showToast("✅ Заказ принят!")
                        Log.d(TAG, "Заказ принят! Всего принято: $ordersAccepted")
                        return
                    }
                }
                // Попробовать нажать на родительский элемент
                val parent = node.parent
                if (parent != null && parent.isClickable) {
                    val accepted = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (accepted) {
                        ordersAccepted++
                        showToast("✅ Заказ принят!")
                        Log.d(TAG, "Заказ принят через родителя! Всего: $ordersAccepted")
                        return
                    }
                }
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    fun getStats(): Pair<Int, Int> = Pair(totalOrdersDetected, ordersAccepted)

    fun resetStats() {
        totalOrdersDetected = 0
        ordersAccepted = 0
    }
}
