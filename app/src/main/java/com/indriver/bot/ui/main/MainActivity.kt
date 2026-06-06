package com.indriver.bot.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indriver.bot.R
import com.indriver.bot.databinding.ActivityMainBinding
import com.indriver.bot.service.BotService
import com.indriver.bot.utils.PermissionHelper
import com.indriver.bot.utils.PreferenceManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferenceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferenceManager(this)

        setupUI()
        checkPermissions()
        loadStats()
    }

    private fun setupUI() {
        binding.switchAutoAccept.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !checkAllPermissions()) {
                binding.switchAutoAccept.isChecked = false
                return@setOnCheckedChangeListener
            }
            toggleBot(isChecked)
        }

        binding.btnSettings.setOnClickListener { showSettingsDialog() }
        binding.btnStats.setOnClickListener { showStatsDialog() }
        binding.btnGrantPermissions.setOnClickListener { requestAllPermissions() }
    }

    private fun toggleBot(enable: Boolean) {
        if (enable) {
            BotService.start(this)
            updateStatusRunning()
            Toast.makeText(this, "🟢 Бот запущен!", Toast.LENGTH_SHORT).show()
        } else {
            BotService.stop(this)
            updateStatusStopped()
            Toast.makeText(this, "🔴 Бот остановлен", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatusRunning() {
        binding.tvStatus.text = "🟢 РАБОТАЕТ"
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.green_500))
    }

    private fun updateStatusStopped() {
        binding.tvStatus.text = "🔴 ОСТАНОВЛЕН"
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.red_500))
    }

    private fun updateStatusPermissionNeeded() {
        binding.tvStatus.text = "⚠️ НУЖНЫ РАЗРЕШЕНИЯ"
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.orange_500))
    }

    private fun checkPermissions(): Boolean {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccessibility = PermissionHelper.isAccessibilityEnabled(this)

        return if (!hasOverlay || !hasAccessibility) {
            updateStatusPermissionNeeded()
            binding.cardPermission.visibility = View.VISIBLE
            false
        } else {
            binding.cardPermission.visibility = View.GONE
            true
        }
    }

    private fun checkAllPermissions(): Boolean {
        if (!checkPermissions()) {
            showPermissionExplanation()
            return false
        }
        return true
    }

    private fun requestAllPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
            return
        }

        if (!PermissionHelper.isAccessibilityEnabled(this)) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_ACCESSIBILITY_PERMISSION)
        }
    }

    private fun showPermissionExplanation() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Необходимы разрешения")
            .setMessage(
                "Приложению нужны:\n\n" +
                "📱 Наложение поверх других приложений\n" +
                "♿ Специальные возможности\n\n" +
                "Пожалуйста, выдайте эти разрешения для работы бота."
            )
            .setPositiveButton("Выдать") { _, _ -> requestAllPermissions() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun loadStats() {
        binding.tvAccepted.text = prefs.getAcceptedCount().toString()
        binding.tvRejected.text = prefs.getMissedCount().toString()
        binding.tvEarnings.text = "${String.format("%.0f", prefs.getTotalEarnings())} ₸"
    }

    private fun showSettingsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("⚙️ Настройки")
            .setMessage(
                "Минимальная цена: ${prefs.getMinPrice().toInt()} ₸\n" +
                "Макс. расстояние: ${prefs.getMaxDistance().toInt()} км\n\n" +
                "Расширенные настройки — скоро!"
            )
            .setPositiveButton("ОК", null)
            .show()
    }

    private fun showStatsDialog() {
        val stats =
            "📊 Статистика\n\n" +
            "✅ Принято: ${prefs.getAcceptedCount()}\n" +
            "❌ Пропущено: ${prefs.getMissedCount()}\n" +
            "💰 Заработок: ${String.format("%.0f", prefs.getTotalEarnings())} ₸\n" +
            "📈 Процент: ${String.format("%.1f", prefs.getWinRate())}%"

        MaterialAlertDialogBuilder(this)
            .setTitle("📈 Статистика")
            .setMessage(stats)
            .setPositiveButton("ОК", null)
            .setNeutralButton("Сбросить") { _, _ ->
                prefs.resetStats()
                loadStats()
                Toast.makeText(this, "Статистика сброшена", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY_PERMISSION -> {
                if (Settings.canDrawOverlays(this)) requestAllPermissions()
            }
            REQUEST_ACCESSIBILITY_PERMISSION -> {
                if (PermissionHelper.isAccessibilityEnabled(this)) {
                    checkPermissions()
                    Toast.makeText(this, "✅ Все разрешения выданы!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        loadStats()
    }

    companion object {
        private const val REQUEST_OVERLAY_PERMISSION = 1001
        private const val REQUEST_ACCESSIBILITY_PERMISSION = 1002
    }
}
