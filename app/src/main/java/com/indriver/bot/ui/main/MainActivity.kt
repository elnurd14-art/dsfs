package com.indriver.bot.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indriver.bot.R
import com.indriver.bot.databinding.ActivityMainBinding
import com.indriver.bot.databinding.DialogFiltersBinding
import com.indriver.bot.service.BotService
import com.indriver.bot.service.InDriverAccessibilityService
import com.indriver.bot.utils.PermissionHelper
import com.indriver.bot.utils.PreferenceManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferenceManager

    companion object {
        private const val REQ_OVERLAY = 1001
        private const val REQ_ACCESSIBILITY = 1002
        private const val REQ_CALL = 1003
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PreferenceManager(this)

        setupListeners()
        loadUI()
        checkPermissions()

        // Callback: заказ принят
        InDriverAccessibilityService.onOrderDetected = { info ->
            runOnUiThread {
                binding.tvLastOrder.text = buildString {
                    if (info.isIntercity) append("🚚 МЕЖГОРОД\n") else append("📦 Городская доставка\n")
                    if (info.price > 0) append("💵 ${info.price.toInt()} ₸\n")
                    if (info.cityTo.isNotEmpty()) append("📍 Назначение: ${info.cityTo}\n")
                    if (info.distanceToClient > 0) append("🛣️ ${"%.0f".format(info.distanceToClient)} км\n")
                    if (info.phone.isNotEmpty()) append("📞 ${info.phone}")
                    if (prefs.isAutoCallEnabled() && info.phone.isNotEmpty())
                        append("\n→ Звоним автоматически...")
                }
                binding.tvLastOrder.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                loadStats()
            }
        }

        // Callback: заказ отклонён
        InDriverAccessibilityService.onOrderRejected = { info, reason ->
            runOnUiThread { loadStats() }
        }
    }

    // ==================== LISTENERS ====================
    private fun setupListeners() {

        // Главный переключатель
        binding.switchAutoAccept.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !checkPermissions()) {
                binding.switchAutoAccept.isChecked = false
                return@setOnCheckedChangeListener
            }
            if (isChecked) {
                BotService.start(this)
                InDriverAccessibilityService.isRunning = true
                binding.tvStatusLabel.text = "🟢 БОТ РАБОТАЕТ"
                binding.tvStatusDesc.text = "Жду заказы — откройте inDrive"
                Toast.makeText(this, "🟢 Бот запущен! Откройте inDrive", Toast.LENGTH_LONG).show()
            } else {
                BotService.stop(this)
                InDriverAccessibilityService.isRunning = false
                binding.tvStatusLabel.text = "🔴 БОТ ОСТАНОВЛЕН"
                binding.tvStatusDesc.text = "Нажмите для запуска"
                Toast.makeText(this, "🔴 Бот остановлен", Toast.LENGTH_SHORT).show()
            }
        }

        // Режим: всё
        binding.btnModeAll.setOnClickListener {
            prefs.setMode(PreferenceManager.MODE_ALL)
            updateModeButtons()
        }

        // Режим: только межгород
        binding.btnModeIntercity.setOnClickListener {
            prefs.setMode(PreferenceManager.MODE_INTERCITY)
            updateModeButtons()
        }

        // Фильтр городов вкл/выкл
        binding.switchCityFilter.setOnCheckedChangeListener { _, isChecked ->
            prefs.setCityFilterEnabled(isChecked)
            binding.tvSelectedCities.alpha = if (isChecked) 1.0f else 0.4f
            binding.btnEditCities.isEnabled = isChecked
        }

        // Выбор городов
        binding.btnEditCities.setOnClickListener { showCityPickerDialog() }

        // Фильтры цен
        binding.btnEditFilters.setOnClickListener { showFiltersDialog() }

        // Авто-звонок
        binding.switchAutoCall.isChecked = prefs.isAutoCallEnabled()
        binding.switchAutoCall.setOnCheckedChangeListener { _, isChecked ->
            prefs.setAutoCallEnabled(isChecked)
            if (isChecked) requestCallPermission()
        }

        // Задержка звонка
        binding.btnCallDelayEdit.setOnClickListener { showCallDelayDialog() }

        // Статистика
        binding.btnStats.setOnClickListener { showStatsDialog() }

        // Разрешения
        binding.btnSettings.setOnClickListener { showPermissionsDialog() }
        binding.btnGrantPermissions.setOnClickListener { requestAllPermissions() }
    }

    // ==================== ВЫБОР ГОРОДОВ ====================
    private fun showCityPickerDialog() {
        val cities = PreferenceManager.KZ_CITIES.toTypedArray()
        val allowed = prefs.getAllowedCities()
        val checked = BooleanArray(cities.size) { allowed.contains(cities[it]) }

        MaterialAlertDialogBuilder(this)
            .setTitle("🏙️ Выберите города назначения")
            .setMultiChoiceItems(cities, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("💾 Сохранить") { _, _ ->
                val selected = cities.filterIndexed { i, _ -> checked[i] }.toSet()
                if (selected.isEmpty()) {
                    Toast.makeText(this, "Выберите хотя бы один город", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                prefs.setAllowedCities(selected)
                updateCitiesUI()
                Toast.makeText(this, "✅ Сохранено: ${selected.size} городов", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .setNeutralButton("Все города") { _, _ ->
                prefs.setAllowedCities(PreferenceManager.KZ_CITIES.toSet())
                updateCitiesUI()
                Toast.makeText(this, "Выбраны все города", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ==================== ФИЛЬТРЫ ЦЕН ====================
    private fun showFiltersDialog() {
        val db = DialogFiltersBinding.inflate(LayoutInflater.from(this))

        db.switchMinPrice.isChecked = prefs.isMinPriceEnabled()
        db.etMinPrice.setText(prefs.getMinPrice().toInt().toString())
        db.switchMinIntercityPrice.isChecked = prefs.isMinIntercityPriceEnabled()
        db.etMinIntercityPrice.setText(prefs.getMinIntercityPrice().toInt().toString())
        db.etCallDelay.setText((prefs.getCallDelayMs() / 1000).toString())

        MaterialAlertDialogBuilder(this)
            .setTitle("💵 Минимальные цены")
            .setView(db.root)
            .setPositiveButton("💾 Сохранить") { _, _ ->
                prefs.setMinPriceEnabled(db.switchMinPrice.isChecked)
                prefs.setMinPrice(db.etMinPrice.text.toString().toDoubleOrNull() ?: 2000.0)
                prefs.setMinIntercityPriceEnabled(db.switchMinIntercityPrice.isChecked)
                prefs.setMinIntercityPrice(db.etMinIntercityPrice.text.toString().toDoubleOrNull() ?: 5000.0)
                val delaySec = db.etCallDelay.text.toString().toLongOrNull()?.coerceIn(0, 30) ?: 1
                prefs.setCallDelayMs(delaySec * 1000)
                loadUI()
                Toast.makeText(this, "✅ Сохранено!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ==================== ЗАДЕРЖКА ЗВОНКА ====================
    private fun showCallDelayDialog() {
        val opts = arrayOf("Мгновенно", "1 секунда", "2 секунды", "3 секунды", "5 секунд")
        val delays = longArrayOf(0L, 1000L, 2000L, 3000L, 5000L)
        val cur = prefs.getCallDelayMs()
        var sel = delays.indexOfFirst { it == cur }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle("⏱️ Задержка перед звонком")
            .setSingleChoiceItems(opts, sel) { _, w -> sel = w }
            .setPositiveButton("Сохранить") { _, _ ->
                prefs.setCallDelayMs(delays[sel])
                binding.tvCallDelay.text = opts[sel]
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ==================== СТАТИСТИКА ====================
    private fun showStatsDialog() {
        val msg = "✅ Принято: ${prefs.getAcceptedCount()}\n" +
                "❌ Пропущено: ${prefs.getMissedCount()}\n" +
                "📞 Звонков: ${prefs.getCallCount()}\n" +
                "💰 Заработок: ${prefs.getTotalEarnings().toInt()} ₸\n" +
                "📈 Процент: ${"%.1f".format(prefs.getWinRate())}%"
        MaterialAlertDialogBuilder(this)
            .setTitle("📊 Статистика")
            .setMessage(msg)
            .setPositiveButton("ОК", null)
            .setNeutralButton("🗑 Сбросить") { _, _ ->
                prefs.resetStats()
                loadStats()
                binding.tvLastOrder.text = "Заказов пока нет. Запустите бот и откройте inDrive."
                Toast.makeText(this, "Статистика сброшена", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ==================== РАЗРЕШЕНИЯ ====================
    private fun showPermissionsDialog() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccess = PermissionHelper.isAccessibilityEnabled(this)
        val hasCall = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
                PackageManager.PERMISSION_GRANTED
        MaterialAlertDialogBuilder(this)
            .setTitle("⚙️ Разрешения")
            .setMessage(
                "${if (hasAccess) "✅" else "❌"} Специальные возможности\n" +
                "${if (hasOverlay) "✅" else "❌"} Наложение поверх приложений\n" +
                "${if (hasCall) "✅" else "❌"} Разрешение на звонки"
            )
            .setPositiveButton("Выдать") { _, _ -> requestAllPermissions() }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    // ==================== UI UPDATE ====================
    private fun loadUI() {
        loadStats()
        updateModeButtons()
        updateCitiesUI()
        updatePricesUI()

        val delay = prefs.getCallDelayMs()
        binding.tvCallDelay.text = when (delay) {
            0L -> "Мгновенно"
            1000L -> "1 секунда"
            2000L -> "2 секунды"
            3000L -> "3 секунды"
            5000L -> "5 секунд"
            else -> "${delay / 1000} сек"
        }

        val cityOn = prefs.isCityFilterEnabled()
        binding.switchCityFilter.isChecked = cityOn
        binding.tvSelectedCities.alpha = if (cityOn) 1.0f else 0.4f
        binding.btnEditCities.isEnabled = cityOn
        binding.switchAutoCall.isChecked = prefs.isAutoCallEnabled()
    }

    private fun loadStats() {
        binding.tvAccepted.text = prefs.getAcceptedCount().toString()
        binding.tvRejected.text = prefs.getMissedCount().toString()
        binding.tvCalls.text = prefs.getCallCount().toString()
        binding.tvEarnings.text = "${prefs.getTotalEarnings().toInt()}₸"
        val last = prefs.getLastOrderInfo()
        if (last.isNotEmpty()) binding.tvLastOrder.text = last
    }

    private fun updateModeButtons() {
        val isIntercity = prefs.getMode() == PreferenceManager.MODE_INTERCITY
        binding.btnModeAll.backgroundTintList =
            ContextCompat.getColorStateList(this, if (!isIntercity) R.color.primary else R.color.gray_500)
        binding.btnModeIntercity.backgroundTintList =
            ContextCompat.getColorStateList(this, if (isIntercity) R.color.primary else R.color.gray_500)
        binding.tvModeDesc.text = if (isIntercity)
            "🚚 Только межгород: бот игнорирует городские заказы. Звонит только на дальние рейсы."
        else
            "ℹ️ Принимаются все посылки: городская и межгород. Межгород — отдельная мин. цена."
    }

    private fun updateCitiesUI() {
        val cities = prefs.getAllowedCities().sorted()
        binding.tvSelectedCities.text = if (cities.isEmpty()) "Не выбрано" else cities.joinToString(", ")
    }

    private fun updatePricesUI() {
        binding.tvMinPrice.text = "${prefs.getMinPrice().toInt()} ₸"
        setFilterBadge(binding.tvMinPriceStatus, prefs.isMinPriceEnabled())
        binding.tvMinIntercityPrice.text = "${prefs.getMinIntercityPrice().toInt()} ₸"
        setFilterBadge(binding.tvMinIntercityStatus, prefs.isMinIntercityPriceEnabled())
    }

    private fun setFilterBadge(view: android.widget.TextView, enabled: Boolean) {
        view.text = if (enabled) "ВКЛ" else "ВЫКЛ"
        view.background = ContextCompat.getDrawable(
            this, if (enabled) R.drawable.badge_green else R.drawable.badge_gray
        )
    }

    // ==================== PERMISSIONS ====================
    private fun checkPermissions(): Boolean {
        val ok = Settings.canDrawOverlays(this) && PermissionHelper.isAccessibilityEnabled(this)
        binding.cardPermission.visibility = if (ok) View.GONE else View.VISIBLE
        return ok
    }

    private fun requestAllPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Разрешите наложение поверх приложений", Toast.LENGTH_LONG).show()
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
            return
        }
        if (!PermissionHelper.isAccessibilityEnabled(this)) {
            Toast.makeText(this, "Найдите 'inDrive Авто-Бот' и включите", Toast.LENGTH_LONG).show()
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), REQ_ACCESSIBILITY)
            return
        }
        requestCallPermission()
    }

    private fun requestCallPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), REQ_CALL)
        }
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(req, perms, results)
        if (req == REQ_CALL && results.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "⚠️ Без этого разрешения авто-звонок не работает", Toast.LENGTH_LONG).show()
            binding.switchAutoCall.isChecked = false
            prefs.setAutoCallEnabled(false)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        when (rc) {
            REQ_OVERLAY -> if (Settings.canDrawOverlays(this)) requestAllPermissions()
            REQ_ACCESSIBILITY -> {
                if (PermissionHelper.isAccessibilityEnabled(this)) {
                    checkPermissions()
                    requestCallPermission()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        loadUI()
    }
}
