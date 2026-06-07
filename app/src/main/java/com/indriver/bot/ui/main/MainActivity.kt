package com.indriver.bot.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
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

        InDriverAccessibilityService.onOrderDetected = { info ->
            runOnUiThread {
                val sb = StringBuilder()
                if (info.orderType == "Попутчики") sb.append("ПОПУТЧИК\n")
                else if (info.isIntercity) sb.append("МЕЖГОРОД\n")
                else sb.append("ГОРОД\n")
                if (info.price > 0) sb.append("${info.price.toInt()} ₸\n")
                if (info.cityFrom.isNotEmpty() && info.cityTo.isNotEmpty())
                    sb.append("${info.cityFrom} → ${info.cityTo}\n")
                else if (info.cityTo.isNotEmpty()) sb.append("→ ${info.cityTo}\n")
                if (info.phone.isNotEmpty()) sb.append("${info.phone}")
                binding.tvLastOrder.text = sb.toString().trimEnd()
                binding.tvLastOrder.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                loadStats()
            }
        }

        InDriverAccessibilityService.onOrderRejected = { _, _ ->
            runOnUiThread { loadStats() }
        }
    }

    private fun setupListeners() {
        // Bot toggle
        binding.switchAutoAccept.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !checkPermissions()) {
                binding.switchAutoAccept.isChecked = false
                return@setOnCheckedChangeListener
            }
            if (isChecked) {
                BotService.start(this)
                InDriverAccessibilityService.isRunning = true
                binding.tvStatusLabel.text = "БОТ РАБОТАЕТ"
                binding.tvStatusDesc.text = "Жду заказы — откройте inDrive"
                Toast.makeText(this, "Бот запущен! Откройте inDrive", Toast.LENGTH_LONG).show()
            } else {
                BotService.stop(this)
                InDriverAccessibilityService.isRunning = false
                binding.tvStatusLabel.text = "БОТ ОСТАНОВЛЕН"
                binding.tvStatusDesc.text = "Нажмите для запуска"
            }
        }

        // Mode tabs
        binding.btnModeAll.setOnClickListener { prefs.setMode(PreferenceManager.MODE_ALL); updateModeUI() }
        binding.btnModeIntercity.setOnClickListener { prefs.setMode(PreferenceManager.MODE_INTERCITY); updateModeUI() }
        binding.btnModeCarpool.setOnClickListener { prefs.setMode(PreferenceManager.MODE_CARPOOL); updateModeUI() }

        // City filter
        binding.switchCityFilter.setOnCheckedChangeListener { _, isChecked ->
            prefs.setCityFilterEnabled(isChecked)
            binding.tvSelectedCities.alpha = if (isChecked) 1.0f else 0.5f
            binding.btnEditCities.isEnabled = isChecked
        }
        binding.btnEditCities.setOnClickListener { showCityPickerDialog() }

        // Filters
        binding.btnEditFilters.setOnClickListener { showFiltersDialog() }

        // Auto call
        binding.switchAutoCall.isChecked = prefs.isAutoCallEnabled()
        binding.switchAutoCall.setOnCheckedChangeListener { _, isChecked ->
            prefs.setAutoCallEnabled(isChecked)
            if (isChecked) requestCallPermission()
        }

        // Call delay
        binding.btnCallDelayEdit.setOnClickListener { showCallDelayDialog() }

        // Stats & permissions
        binding.btnStats.setOnClickListener { showStatsDialog() }
        binding.btnSettings.setOnClickListener { showPermissionsDialog() }
        binding.btnGrantPermissions.setOnClickListener { requestAllPermissions() }
    }

    // ==================== FILTERS DIALOG ====================
    private fun showFiltersDialog() {
        val db = DialogFiltersBinding.inflate(LayoutInflater.from(this))

        // Init values
        db.etMinPrice.setText(prefs.getMinPrice().toInt().toString())
        db.etFixedCityPrice.setText(
            prefs.getFixedCityPrice().let { if (it > 0) it.toInt().toString() else "" }
        )
        db.etMinIntercityPrice.setText(prefs.getMinIntercityPrice().toInt().toString())
        db.etFixedIntercityPrice.setText(
            prefs.getFixedIntercityPrice().let { if (it > 0) it.toInt().toString() else "" }
        )
        db.etMinCarpoolPrice.setText(prefs.getMinCarpoolPrice().toInt().toString())
        db.etFixedCarpoolPrice.setText(
            prefs.getFixedCarpoolPrice().let { if (it > 0) it.toInt().toString() else "" }
        )
        db.etCallDelay.setText((prefs.getCallDelayMs() / 1000).toString())

        // Tab state helpers
        fun selectCityTab(mode: String) {
            val off = mode == PreferenceManager.PRICE_OFF
            val fixed = mode == PreferenceManager.PRICE_FIXED
            styleTab(db.btnCityOff, off)
            styleTab(db.btnCityMin, mode == PreferenceManager.PRICE_MIN)
            styleTab(db.btnCityFixed, fixed)
            db.layoutCityMin.visibility = if (mode == PreferenceManager.PRICE_MIN) View.VISIBLE else View.GONE
            db.layoutCityFixed.visibility = if (fixed) View.VISIBLE else View.GONE
        }
        fun selectIntercityTab(mode: String) {
            val fixed = mode == PreferenceManager.PRICE_FIXED
            styleTab(db.btnIntercityOff, mode == PreferenceManager.PRICE_OFF)
            styleTab(db.btnIntercityMin, mode == PreferenceManager.PRICE_MIN)
            styleTab(db.btnIntercityFixed, fixed)
            db.layoutIntercityMin.visibility = if (mode == PreferenceManager.PRICE_MIN) View.VISIBLE else View.GONE
            db.layoutIntercityFixed.visibility = if (fixed) View.VISIBLE else View.GONE
        }
        fun selectCarpoolTab(mode: String) {
            val fixed = mode == PreferenceManager.PRICE_FIXED
            styleTab(db.btnCarpoolOff, mode == PreferenceManager.PRICE_OFF)
            styleTab(db.btnCarpoolMin, mode == PreferenceManager.PRICE_MIN)
            styleTab(db.btnCarpoolFixed, fixed)
            db.layoutCarpoolMin.visibility = if (mode == PreferenceManager.PRICE_MIN) View.VISIBLE else View.GONE
            db.layoutCarpoolFixed.visibility = if (fixed) View.VISIBLE else View.GONE
        }

        // Init tabs from saved state
        selectCityTab(prefs.getCityPriceMode())
        selectIntercityTab(prefs.getIntercityPriceMode())
        selectCarpoolTab(prefs.getCarpoolPriceMode())

        // Tab click listeners
        var cityMode = prefs.getCityPriceMode()
        var intercityMode = prefs.getIntercityPriceMode()
        var carpoolMode = prefs.getCarpoolPriceMode()

        db.btnCityOff.setOnClickListener { cityMode = PreferenceManager.PRICE_OFF; selectCityTab(cityMode) }
        db.btnCityMin.setOnClickListener { cityMode = PreferenceManager.PRICE_MIN; selectCityTab(cityMode) }
        db.btnCityFixed.setOnClickListener { cityMode = PreferenceManager.PRICE_FIXED; selectCityTab(cityMode) }

        db.btnIntercityOff.setOnClickListener { intercityMode = PreferenceManager.PRICE_OFF; selectIntercityTab(intercityMode) }
        db.btnIntercityMin.setOnClickListener { intercityMode = PreferenceManager.PRICE_MIN; selectIntercityTab(intercityMode) }
        db.btnIntercityFixed.setOnClickListener { intercityMode = PreferenceManager.PRICE_FIXED; selectIntercityTab(intercityMode) }

        db.btnCarpoolOff.setOnClickListener { carpoolMode = PreferenceManager.PRICE_OFF; selectCarpoolTab(carpoolMode) }
        db.btnCarpoolMin.setOnClickListener { carpoolMode = PreferenceManager.PRICE_MIN; selectCarpoolTab(carpoolMode) }
        db.btnCarpoolFixed.setOnClickListener { carpoolMode = PreferenceManager.PRICE_FIXED; selectCarpoolTab(carpoolMode) }

        MaterialAlertDialogBuilder(this)
            .setTitle("Фильтры цены")
            .setView(db.root)
            .setPositiveButton("Сохранить") { _, _ ->
                prefs.setCityPriceMode(cityMode)
                prefs.setMinPrice(db.etMinPrice.text.toString().toDoubleOrNull() ?: 2000.0)
                prefs.setFixedCityPrice(db.etFixedCityPrice.text.toString().toDoubleOrNull() ?: 0.0)

                prefs.setIntercityPriceMode(intercityMode)
                prefs.setMinIntercityPrice(db.etMinIntercityPrice.text.toString().toDoubleOrNull() ?: 5000.0)
                prefs.setFixedIntercityPrice(db.etFixedIntercityPrice.text.toString().toDoubleOrNull() ?: 0.0)

                prefs.setCarpoolPriceMode(carpoolMode)
                prefs.setMinCarpoolPrice(db.etMinCarpoolPrice.text.toString().toDoubleOrNull() ?: 5000.0)
                prefs.setFixedCarpoolPrice(db.etFixedCarpoolPrice.text.toString().toDoubleOrNull() ?: 0.0)

                val delaySec = db.etCallDelay.text.toString().toLongOrNull()?.coerceIn(0, 30) ?: 0
                prefs.setCallDelayMs(delaySec * 1000)

                loadUI()
                Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun styleTab(view: TextView, selected: Boolean) {
        if (selected) {
            view.setBackgroundColor(0xFFFF6B00.toInt())
            view.setTextColor(0xFFFFFFFF.toInt())
            view.textSize = 12f
        } else {
            view.setBackgroundColor(0xFFF3F4F6.toInt())
            view.setTextColor(0xFF8A8A8A.toInt())
            view.textSize = 12f
        }
    }

    // ==================== CITY PICKER ====================
    private fun showCityPickerDialog() {
        val cities = PreferenceManager.KZ_CITIES.toTypedArray()
        val allowed = prefs.getAllowedCities()
        val checked = BooleanArray(cities.size) { allowed.contains(cities[it]) }

        MaterialAlertDialogBuilder(this)
            .setTitle("Города назначения")
            .setMultiChoiceItems(cities, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Сохранить") { _, _ ->
                val selected = cities.filterIndexed { i, _ -> checked[i] }.toSet()
                if (selected.isEmpty()) {
                    Toast.makeText(this, "Выберите хотя бы один город", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                prefs.setAllowedCities(selected)
                updateCitiesUI()
                Toast.makeText(this, "Сохранено: ${selected.size} городов", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .setNeutralButton("Все") { _, _ ->
                prefs.setAllowedCities(PreferenceManager.KZ_CITIES.toSet())
                updateCitiesUI()
            }
            .show()
    }

    // ==================== CALL DELAY ====================
    private fun showCallDelayDialog() {
        val opts = arrayOf("Мгновенно", "1 секунда", "2 секунды", "3 секунды", "5 секунд")
        val delays = longArrayOf(0L, 1000L, 2000L, 3000L, 5000L)
        val cur = prefs.getCallDelayMs()
        var sel = delays.indexOfFirst { it == cur }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle("Задержка перед звонком")
            .setSingleChoiceItems(opts, sel) { _, w -> sel = w }
            .setPositiveButton("Сохранить") { _, _ ->
                prefs.setCallDelayMs(delays[sel])
                binding.tvCallDelay.text = opts[sel]
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ==================== STATS ====================
    private fun showStatsDialog() {
        val msg = "Принято: ${prefs.getAcceptedCount()}\n" +
                "Пропущено: ${prefs.getMissedCount()}\n" +
                "Звонков: ${prefs.getCallCount()}\n" +
                "Заработок: ${prefs.getTotalEarnings().toInt()} ₸\n" +
                "Конверсия: ${"%.1f".format(prefs.getWinRate())}%"
        MaterialAlertDialogBuilder(this)
            .setTitle("Статистика")
            .setMessage(msg)
            .setPositiveButton("ОК", null)
            .setNeutralButton("Сбросить") { _, _ ->
                prefs.resetStats()
                loadStats()
                binding.tvLastOrder.text = "Заказов пока нет. Запустите бот и откройте inDrive."
                Toast.makeText(this, "Статистика сброшена", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ==================== PERMISSIONS ====================
    private fun showPermissionsDialog() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccess = PermissionHelper.isAccessibilityEnabled(this)
        val hasCall = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
                PackageManager.PERMISSION_GRANTED
        MaterialAlertDialogBuilder(this)
            .setTitle("Разрешения")
            .setMessage(
                "${if (hasAccess) "✓" else "✗"} Специальные возможности\n" +
                "${if (hasOverlay) "✓" else "✗"} Наложение поверх приложений\n" +
                "${if (hasCall) "✓" else "✗"} Звонки"
            )
            .setPositiveButton("Выдать") { _, _ -> requestAllPermissions() }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    // ==================== UI ====================
    private fun loadUI() {
        loadStats()
        updateModeUI()
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
        binding.tvSelectedCities.alpha = if (cityOn) 1.0f else 0.5f
        binding.btnEditCities.isEnabled = cityOn
        binding.switchAutoCall.isChecked = prefs.isAutoCallEnabled()

        val last = prefs.getLastOrderInfo()
        if (last.isNotEmpty()) binding.tvLastOrder.text = last
    }

    private fun loadStats() {
        binding.tvAccepted.text = prefs.getAcceptedCount().toString()
        binding.tvRejected.text = prefs.getMissedCount().toString()
        binding.tvCalls.text = prefs.getCallCount().toString()
        val e = prefs.getTotalEarnings().toInt()
        binding.tvEarnings.text = if (e >= 1000) "${e/1000}K₸" else "${e}₸"
    }

    private fun updateModeUI() {
        val mode = prefs.getMode()
        val active = 0xFFFF6B00.toInt()
        val inactive = 0xFFF3F4F6.toInt()
        val activeText = 0xFFFFFFFF.toInt()
        val inactiveText = 0xFF1A1A1A.toInt()

        listOf(
            binding.btnModeAll to (mode == PreferenceManager.MODE_ALL),
            binding.btnModeIntercity to (mode == PreferenceManager.MODE_INTERCITY),
            binding.btnModeCarpool to (mode == PreferenceManager.MODE_CARPOOL)
        ).forEach { (btn, sel) ->
            btn.setBackgroundColor(if (sel) active else inactive)
            btn.setTextColor(if (sel) activeText else inactiveText)
        }

        val desc = when (mode) {
            PreferenceManager.MODE_INTERCITY -> "Только межгород посылки"
            PreferenceManager.MODE_CARPOOL -> "Попутчики: кликает карточку в ленте"
            else -> ""
        }
        binding.tvModeDesc.text = desc
        binding.tvModeDesc.visibility = if (desc.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateCitiesUI() {
        val cities = prefs.getAllowedCities().sorted()
        binding.tvSelectedCities.text = if (cities.isEmpty()) "Не выбрано" else cities.joinToString(", ")
    }

    private fun updatePricesUI() {
        // City
        val cityMode = prefs.getCityPriceMode()
        binding.tvMinPrice.text = when (cityMode) {
            PreferenceManager.PRICE_OFF -> "—"
            PreferenceManager.PRICE_FIXED -> "${prefs.getFixedCityPrice().toInt()} ₸"
            else -> "${prefs.getMinPrice().toInt()} ₸"
        }
        binding.tvCityPriceMode.text = when (cityMode) {
            PreferenceManager.PRICE_OFF -> "фильтр выкл"
            PreferenceManager.PRICE_FIXED -> "фикс. цена"
            else -> "от мин."
        }
        setFilterBadge(binding.tvMinPriceStatus, cityMode != PreferenceManager.PRICE_OFF)

        // Intercity
        val intMode = prefs.getIntercityPriceMode()
        binding.tvMinIntercityPrice.text = when (intMode) {
            PreferenceManager.PRICE_OFF -> "—"
            PreferenceManager.PRICE_FIXED -> "${prefs.getFixedIntercityPrice().toInt()} ₸"
            else -> "${prefs.getMinIntercityPrice().toInt()} ₸"
        }
        binding.tvIntercityPriceMode.text = when (intMode) {
            PreferenceManager.PRICE_OFF -> "фильтр выкл"
            PreferenceManager.PRICE_FIXED -> "фикс. цена"
            else -> "от мин."
        }
        setFilterBadge(binding.tvMinIntercityStatus, intMode != PreferenceManager.PRICE_OFF)

        // Carpool
        val carpoolMode = prefs.getCarpoolPriceMode()
        binding.tvMinCarpoolPrice.text = when (carpoolMode) {
            PreferenceManager.PRICE_OFF -> "—"
            PreferenceManager.PRICE_FIXED -> "${prefs.getFixedCarpoolPrice().toInt()} ₸"
            else -> "${prefs.getMinCarpoolPrice().toInt()} ₸"
        }
        binding.tvCarpoolPriceMode.text = when (carpoolMode) {
            PreferenceManager.PRICE_OFF -> "фильтр выкл"
            PreferenceManager.PRICE_FIXED -> "фикс. цена"
            else -> "от мин."
        }
        setFilterBadge(binding.tvMinCarpoolStatus, carpoolMode != PreferenceManager.PRICE_OFF)
    }

    private fun setFilterBadge(view: TextView, enabled: Boolean) {
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
            Toast.makeText(this, "Без этого разрешения авто-звонок не работает", Toast.LENGTH_LONG).show()
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
