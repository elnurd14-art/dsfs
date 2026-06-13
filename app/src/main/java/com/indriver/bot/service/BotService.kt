package com.indriver.bot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.indriver.bot.R
import com.indriver.bot.ui.main.MainActivity

class BotService : Service() {

    companion object {
        const val CHANNEL_ID      = "taksa_main"
        const val CHANNEL_ORDER   = "taksa_order"
        const val NOTIF_BOT_ID    = 1001
        const val NOTIF_ORDER_ID  = 1002

        fun start(context: Context) {
            val intent = Intent(context, BotService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BotService::class.java))
        }

        /** Показывает уведомление о найденном заказе — вызывается из AccessibilityService */
        fun showOrderNotification(context: Context, title: String, text: String) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val pi = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notif = NotificationCompat.Builder(context, CHANNEL_ORDER)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setVibrate(longArrayOf(0, 200, 100, 200))
                .build()
            nm.notify(NOTIF_ORDER_ID, notif)
        }

        /** Обновляет основное фоновое уведомление бота с новым текстом */
        fun updateBotNotification(context: Context, text: String) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val pi = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Такса — работает")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build()
            nm.notify(NOTIF_BOT_ID, notif)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(NOTIF_BOT_ID, buildBotNotification())
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: система перезапустит сервис с null intent при нехватке памяти
        // Это корректно для foreground service без данных в intent
        return START_STICKY
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            // Основной канал — тихий, для постоянного уведомления
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Такса — статус", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Бот работает в фоне" })

            // Канал заказов — с вибрацией и звуком
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ORDER, "Такса — заказы", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о найденных заказах"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
            })
        }
    }

    private fun buildBotNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Такса — работает")
            .setContentText("Слежу за заказами в inDrive")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()
    }
}
