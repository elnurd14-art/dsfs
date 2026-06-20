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
import com.indriver.bot.ui.activation.ActivationActivity
import com.indriver.bot.ui.log.LogActivity
import com.indriver.bot.ui.onboarding.OnboardingActivity
import com.indriver.bot.utils.ActivationManager
import com.indriver.bot.utils.OrderLogger
import com.indriver.bot.utils.PermissionHelper
import com.indriver.bot.utils.PreferenceManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferenceManager
    private lateinit var logger: OrderLogger

    companion object {
        private const val REQ_OVERLAY      = 1001
        private const val REQ_ACCESSIBILITY = 1002
        private const val REQ_CALL         = 1003

        // Цвета тёмной темы
        private const val COLOR_ACTIVE_BG   = 0xFF00C853.toInt()  // зелёный
        private const val COLOR_INACTIVE_BG = 0xFF0F1117.toInt()  // почти чёрный
        private const val COLOR_ACTIVE_TEXT = 0xFF0F1117.toInt()  // тёмный на зелёном
        private const val COLOR_INACTIVE_TEXT = 0xFF7B82A0.toInt() // серый
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs  = PreferenceManager(this)
        logger = OrderLogger(this)

        // Защита от обхода экрана активации (прямой запуск MainActivity, рекенты и т.д.)
        if (!ActivationManager.isActivated(this)) {
            startActivity(Intent(this, ActivationActivity::class.java))
            finish()
            return
        }

        // Показываем онбординг при первом запуске
        val appPrefs = getSharedPreferences("taksa_prefs", MODE_PRIVATE)
        if (!appPrefs.getBoolean("onboarding_done", false)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setupListeners()
        loadUI()
        checkPermissions()

        // Колбэк когда найден подходящий заказ
        InDriverAccessibilityService.onOrderDetected = { info ->
            runOnUiThread {
                val sb = StringBuilder()
                when (info.orderType) {
                    "Попутчики" -> sb.append("ПОПУТЧИК\n")
                    "Посылка"   -> sb.append("ПОСЫЛКА\n")
                    else        -> sb.append("ГОРОД\n")
                }
                if (info.price > 0) sb.append("${info.price.toInt()} T\n")
                if (info.cityFrom.isNotEmpty() && info.cityTo.isNotEmpty())
                    sb.append("${info.cityFrom} -> ${info.cityTo}\n")
                else if (info.cityTo.isNotEmpty())
                    sb.append("-> ${info.cityTo}\n")
                if (info.phone.isNotEmpty()) sb.append(info.phone)
                binding.tvLastOrder.text = sb.toString().trimEnd()
                binding.tvLastOrder.setTextColor(
                    ContextCompat.getColor(this, R.color.green_500)
                )
                loadStats()
            }
        }

        InDriverAccessibilityService.onOrderRejected = { _, _ ->
            runOnUiThread { loadStats() }
        }
    }

    // ================================================================
    //  LISTENERS
    // ================================================================
    private fun setupListeners() {
        // Кнопка включения бота
        binding.switchAutoAccept.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !checkPermissions()) {
                binding.switchAutoAccept.isChecked = false
                return@setOnCheckedChangeListener
            }
            if (isChecked) {
                BotService.start(this)
                InDriverAccessibilityService.isRunning = true
                binding.tvStatusLabel.text = "БОТ РАБОТАЕТ"
                binding.tvStatusDesc.text = "Слежу за заказами — откройте inDrive"
                binding.cardBotToggle.background =
                    ContextCompat.getDrawable(this, R.drawable.bg_status_on)
                Toast.makeText(this, "Такса запущена — откройте inDrive", Toast.LENGTH_LONG).show()
            } else {
                BotService.stop(this)
                InDriverAccessibilityService.isRunning = false
                binding.tvStatusLabel.text = "БОТ ОСТАНОВЛЕН"
                binding.tvStatusDesc.text = "Нажмите переключатель для запуска"
                binding.cardBotToggle.background =
                    ContextCompat.getDrawable(this, R.drawable.bg_status_off)
            }
        }

        // Режим работы
        binding.btnModeAll.setOnClickListener {
            prefs.setMode(PreferenceManager.MODE_ALL); updateModeUI()
        }
        binding.btnModeIntercity.setOnClickListener {
            prefs.setMode(PreferenceManager.MODE_INTERCITY); updateModeUI()
        }
        binding.btnModeCarpool.setOnClickListener {
            prefs.setMode(PreferenceManager.MODE_CARPOOL); updateModeUI()
        }

        // Фильтр городов
        binding.switchCityFilter.setOnCheckedChangeListener { _, isChecked ->
            prefs.setCityFilterEnabled(isChecked)
            binding.tvSelectedCities.alpha = if (isChecked) 1.0f else 0.5f
            binding.btnEditCities.isEnabled = isChecked
        }
        binding.btnEditCities.setOnClickListener { showCityPickerDialog() }

        // Фильтры цен
        binding.btnEditFilters.setOnClickListener { showFiltersDialog() }

        // Авто-звонок
        binding.switchAutoCall.isChecked = prefs.isAutoCallEnabled()
        binding.switchAutoCall.setOnCheckedChangeListener { _, isChecked ->
            prefs.setAutoCallEnabled(isChecked)
            if (isChecked) requestCallPermission()
        }

        // Авто-открытие WhatsApp после звонка (номер берётся из самого звонка)
        binding.switchAutoWhatsApp.isChecked = prefs.isAutoWhatsAppEnabled()
        binding.switchAutoWhatsApp.setOnCheckedChangeListener { _, isChecked ->
            prefs.setAutoWhatsAppEnabled(isChecked)
            if (isChecked) requestCallPermission() // нужен READ_CALL_LOG
        }

        // Задержка звонка
        binding.btnCallDelayEdit.setOnClickListener { showCallDelayDialog() }

        // Статистика — открывает журнал
        binding.btnStats.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        // Долгое нажатие — детальная статистика
        binding.btnStats.setOnLongClickListener {
            showStatsDialog(); true
        }
        binding.btnSettings.setOnClickListener { showPermissionsDialog() }
        binding.btnGrantPermissions.setOnClickListener { requestAllPermissions() }
    }

    // ================================================================
    //  ДИАЛОГ ФИЛЬТРОВ ЦЕН
    // ================================================================
    private fun showFiltersDialog() {
        val db = DialogFiltersBinding.inflate(LayoutInflater.from(this))

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

        // Вспомогательные функции выбора вкладок
        fun selectCityTab(mode: String) {
            styleTabDark(db.btnCityOff,   mode == PreferenceManager.PRICE_OFF)
            styleTabDark(db.btnCityMin,   mode == PreferenceManager.PRICE_MIN)
            styleTabDark(db.btnCityFixed, mode == PreferenceManager.PRICE_FIXED)
            db.layoutCityMin.visibility   = if (mode == PreferenceManager.PRICE_MIN)   View.VISIBLE else View.GONE
            db.layoutCityFixed.visibility = if (mode == PreferenceManager.PRICE_FIXED) View.VISIBLE else View.GONE
        }
        fun selectIntercityTab(mode: String) {
            styleTabDark(db.btnIntercityOff,   mode == PreferenceManager.PRICE_OFF)
            styleTabDark(db.btnIntercityMin,   mode == PreferenceManager.PRICE_MIN)
            styleTabDark(db.btnIntercityFixed, mode == PreferenceManager.PRICE_FIXED)
            db.layoutIntercityMin.visibility   = if (mode == PreferenceManager.PRICE_MIN)   View.VISIBLE else View.GONE
            db.layoutIntercityFixed.visibility = if (mode == PreferenceManager.PRICE_FIXED) View.VISIBLE else View.GONE
        }
        fun selectCarpoolTab(mode: String) {
            styleTabDark(db.btnCarpoolOff,   mode == PreferenceManager.PRICE_OFF)
            styleTabDark(db.btnCarpoolMin,   mode == PreferenceManager.PRICE_MIN)
            styleTabDark(db.btnCarpoolFixed, mode == PreferenceManager.PRICE_FIXED)
            db.layoutCarpoolMin.visibility   = if (mode == PreferenceManager.PRICE_MIN)   View.VISIBLE else View.GONE
            db.layoutCarpoolFixed.visibility = if (mode == PreferenceManager.PRICE_FIXED) View.VISIBLE else View.GONE
        }

        // Инициализация из сохранённых настроек
        selectCityTab(prefs.getCityPriceMode())
        selectIntercityTab(prefs.getIntercityPriceMode())
        selectCarpoolTab(prefs.getCarpoolPriceMode())

        var cityMode     = prefs.getCityPriceMode()
        var intercityMode = prefs.getIntercityPriceMode()
        var carpoolMode  = prefs.getCarpoolPriceMode()

        db.btnCityOff.setOnClickListener     { cityMode = PreferenceManager.PRICE_OFF;   selectCityTab(cityMode) }
        db.btnCityMin.setOnClickListener     { cityMode = PreferenceManager.PRICE_MIN;   selectCityTab(cityMode) }
        db.btnCityFixed.setOnClickListener   { cityMode = PreferenceManager.PRICE_FIXED; selectCityTab(cityMode) }

        db.btnIntercityOff.setOnClickListener   { intercityMode = PreferenceManager.PRICE_OFF;   selectIntercityTab(intercityMode) }
        db.btnIntercityMin.setOnClickListener   { intercityMode = PreferenceManager.PRICE_MIN;   selectIntercityTab(intercityMode) }
        db.btnIntercityFixed.setOnClickListener { intercityMode = PreferenceManager.PRICE_FIXED; selectIntercityTab(intercityMode) }

        db.btnCarpoolOff.setOnClickListener   { carpoolMode = PreferenceManager.PRICE_OFF;   selectCarpoolTab(carpoolMode) }
        db.btnCarpoolMin.setOnClickListener   { carpoolMode = PreferenceManager.PRICE_MIN;   selectCarpoolTab(carpoolMode) }
        db.btnCarpoolFixed.setOnClickListener { carpoolMode = PreferenceManager.PRICE_FIXED; selectCarpoolTab(carpoolMode) }

        MaterialAlertDialogBuilder(this, R.style.DarkDialogTheme)
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
                Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // Стилизация вкладок под тёмную тему — зелёный активный, серый неактивный
    private fun styleTabDark(view: TextView, selected: Boolean) {
        if (selected) {
            view.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_selected)
            view.setTextColor(0xFF0F1117.toInt())
        } else {
            view.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_unselected)
            view.setTextColor(0xFF7B82A0.toInt())
        }
        view.textSize = 12f
    }

    // ================================================================
    //  ДИАЛОГ ВЫБОРА ГОРОДОВ
    // ================================================================
    private fun showCityPickerDialog() {
        val cities = PreferenceManager.KZ_CITIES.toTypedArray()
        val allowed = prefs.getAllowedCities()
        val checked = BooleanArray(cities.size) { allowed.contains(cities[it]) }

        MaterialAlertDialogBuilder(this, R.style.DarkDialogTheme)
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
            .setNeutralButton("Все города") { _, _ ->
                prefs.setAllowedCities(PreferenceManager.KZ_CITIES.toSet())
                updateCitiesUI()
                Toast.makeText(this, "Выбраны все города", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ================================================================
    //  ДИАЛОГ ЗАДЕРЖКИ ЗВОНКА
    // ================================================================
    private fun showCallDelayDialog() {
        val opts   = arrayOf("Мгновенно", "1 секунда", "2 секунды", "3 секунды", "5 секунд", "10 секунд")
        val delays = longArrayOf(0L, 1000L, 2000L, 3000L, 5000L, 10_000L)
        val cur = prefs.getCallDelayMs()
        var sel = delays.indexOfFirst { it == cur }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(this, R.style.DarkDialogTheme)
            .setTitle("Задержка перед звонком")
            .setSingleChoiceItems(opts, sel) { _, w -> sel = w }
            .setPositiveButton("Сохранить") { _, _ ->
                prefs.setCallDelayMs(delays[sel])
                binding.tvCallDelay.text = opts[sel]
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ================================================================
    //  ДИАЛОГ СТАТИСТИКИ
    // ================================================================
    private fun showStatsDialog() {
        val msg =
            "Принято:      ${prefs.getAcceptedCount()}\n" +
            "Пропущено:  ${prefs.getMissedCount()}\n" +
            "Звонков:       ${prefs.getCallCount()}\n" +
            "Заработок:   ${prefs.getTotalEarnings().toInt()} T\n" +
            "Конверсия:  ${"%.1f".format(prefs.getWinRate())}%"

        MaterialAlertDialogBuilder(this, R.style.DarkDialogTheme)
            .setTitle("Статистика сессии")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .setNeutralButton("Сбросить") { _, _ ->
                prefs.resetStats()
                loadStats()
                binding.tvLastOrder.text = "Заказов пока нет. Запустите Такса и откройте inDrive."
                binding.tvLastOrder.setTextColor(
                    ContextCompat.getColor(this, R.color.text_secondary)
                )
                Toast.makeText(this, "Статистика сброшена", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ================================================================
    //  ДИАЛОГ РАЗРЕШЕНИЙ
    // ================================================================
    private fun showPermissionsDialog() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccess  = PermissionHelper.isAccessibilityEnabled(this)
        val hasCall    = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
                         PackageManager.PERMISSION_GRANTED
        val hasCallLog = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) ==
                         PackageManager.PERMISSION_GRANTED
        val hasPhoneState = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
                         PackageManager.PERMISSION_GRANTED

        val check = { b: Boolean -> if (b) "[V]" else "[ ]" }
        MaterialAlertDialogBuilder(this, R.style.DarkDialogTheme)
            .setTitle("Разрешения")
            .setMessage(
                "${check(hasAccess)}  Специальные возможности\n" +
                "${check(hasOverlay)}  Наложение поверх приложений\n" +
                "${check(hasCall)}  Совершение звонков\n" +
                "${check(hasCallLog)}  Журнал звонков (для WhatsApp)\n" +
                "${check(hasPhoneState)}  Состояние звонка (для WhatsApp)\n\n" +
                "Для работы необходимы все разрешения."
            )
            .setPositiveButton("Выдать") { _, _ -> requestAllPermissions() }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    // ================================================================
    //  ОБНОВЛЕНИЕ UI
    // ================================================================
    private fun loadUI() {
        loadStats()
        updateModeUI()
        updateCitiesUI()
        updatePricesUI()

        val delay = prefs.getCallDelayMs()
        binding.tvCallDelay.text = when (delay) {
            0L      -> "Мгновенно"
            1000L   -> "1 секунда"
            2000L   -> "2 секунды"
            3000L   -> "3 секунды"
            5000L   -> "5 секунд"
            10_000L -> "10 секунд"
            else    -> "${delay / 1000} сек"
        }

        val cityOn = prefs.isCityFilterEnabled()
        binding.switchCityFilter.isChecked = cityOn
        binding.tvSelectedCities.alpha = if (cityOn) 1.0f else 0.5f
        binding.btnEditCities.isEnabled = cityOn
        binding.switchAutoCall.isChecked = prefs.isAutoCallEnabled()
        binding.switchAutoWhatsApp.isChecked = prefs.isAutoWhatsAppEnabled()

        val last = prefs.getLastOrderInfo()
        if (last.isNotEmpty()) binding.tvLastOrder.text = last
    }

    private fun loadStats() {
        binding.tvAccepted.text = prefs.getAcceptedCount().toString()
        binding.tvRejected.text = prefs.getMissedCount().toString()
        binding.tvCalls.text    = prefs.getCallCount().toString()
        val e = prefs.getTotalEarnings().toInt()
        binding.tvEarnings.text = when {
            e >= 1_000_000 -> "${e / 1_000_000}M T"
            e >= 1_000     -> "${e / 1000}K T"
            else           -> "${e} T"
        }
    }

    // Диалог с детальной статистикой (открывается долгим нажатием на карточку статистики)
    private fun updateModeUI() {
        val mode = prefs.getMode()

        // Режим: Город
        val allSel      = mode == PreferenceManager.MODE_ALL
        val intercitySel = mode == PreferenceManager.MODE_INTERCITY
        val carpoolSel  = mode == PreferenceManager.MODE_CARPOOL

        setModeTab(binding.btnModeAll,       allSel,       "Город")
        setModeTab(binding.btnModeIntercity, intercitySel, "Посылки")
        setModeTab(binding.btnModeCarpool,   carpoolSel,   "Попутки")

        val desc = when (mode) {
            PreferenceManager.MODE_INTERCITY -> "Бот принимает только посылки межгород (вкладка Попутки)"
            PreferenceManager.MODE_CARPOOL   -> "Бот кликает карточку попутчика в ленте"
            else                             -> ""
        }
        binding.tvModeDesc.text = desc
        binding.tvModeDesc.visibility = if (desc.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun setModeTab(view: TextView, selected: Boolean, label: String) {
        view.text = label
        if (selected) {
            view.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_selected)
            view.setTextColor(0xFF0F1117.toInt())
        } else {
            view.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_unselected)
            view.setTextColor(0xFF7B82A0.toInt())
        }
    }

    private fun updateCitiesUI() {
        val cities = prefs.getAllowedCities().sorted()
        binding.tvSelectedCities.text =
            if (cities.isEmpty()) "Не выбрано" else cities.joinToString(", ")
    }

    private fun updatePricesUI() {
        // Городская доставка
        val cityMode = prefs.getCityPriceMode()
        binding.tvMinPrice.text = when (cityMode) {
            PreferenceManager.PRICE_OFF   -> "—"
            PreferenceManager.PRICE_FIXED -> "${prefs.getFixedCityPrice().toInt()} T"
            else                          -> "${prefs.getMinPrice().toInt()} T"
        }
        binding.tvCityPriceMode.text = when (cityMode) {
            PreferenceManager.PRICE_OFF   -> " · выкл"
            PreferenceManager.PRICE_FIXED -> " · фикс."
            else                          -> " · от мин."
        }
        setFilterBadge(binding.tvMinPriceStatus, cityMode != PreferenceManager.PRICE_OFF)

        // Посылки межгород
        val intMode = prefs.getIntercityPriceMode()
        binding.tvMinIntercityPrice.text = when (intMode) {
            PreferenceManager.PRICE_OFF   -> "—"
            PreferenceManager.PRICE_FIXED -> "${prefs.getFixedIntercityPrice().toInt()} T"
            else                          -> "${prefs.getMinIntercityPrice().toInt()} T"
        }
        binding.tvIntercityPriceMode.text = when (intMode) {
            PreferenceManager.PRICE_OFF   -> " · выкл"
            PreferenceManager.PRICE_FIXED -> " · фикс."
            else                          -> " · от мин."
        }
        setFilterBadge(binding.tvMinIntercityStatus, intMode != PreferenceManager.PRICE_OFF)

        // Попутчики
        val carpoolMode = prefs.getCarpoolPriceMode()
        binding.tvMinCarpoolPrice.text = when (carpoolMode) {
            PreferenceManager.PRICE_OFF   -> "—"
            PreferenceManager.PRICE_FIXED -> "${prefs.getFixedCarpoolPrice().toInt()} T"
            else                          -> "${prefs.getMinCarpoolPrice().toInt()} T"
        }
        binding.tvCarpoolPriceMode.text = when (carpoolMode) {
            PreferenceManager.PRICE_OFF   -> " · выкл"
            PreferenceManager.PRICE_FIXED -> " · фикс."
            else                          -> " · от мин."
        }
        setFilterBadge(binding.tvMinCarpoolStatus, carpoolMode != PreferenceManager.PRICE_OFF)
    }

    private fun setFilterBadge(view: TextView, enabled: Boolean) {
        view.text = if (enabled) "ВКЛ" else "ВЫКЛ"
        view.background = ContextCompat.getDrawable(
            this, if (enabled) R.drawable.badge_green else R.drawable.badge_gray
        )
    }

    // ================================================================
    //  РАЗРЕШЕНИЯ
    // ================================================================
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
            Toast.makeText(this, "Найдите 'Такса' в Спец. возможностях и включите", Toast.LENGTH_LONG).show()
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), REQ_ACCESSIBILITY)
            return
        }
        requestCallPermission()
    }

    private fun requestCallPermission() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CALL_PHONE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_CALL_LOG)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_CALL)
        }
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(req, perms, results)
        if (req == REQ_CALL) {
            val callIdx = perms.indexOf(Manifest.permission.CALL_PHONE)
            if (callIdx >= 0 && results.getOrNull(callIdx) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Без этого разрешения авто-звонок не работает", Toast.LENGTH_LONG).show()
                binding.switchAutoCall.isChecked = false
                prefs.setAutoCallEnabled(false)
            }
            val logIdx = perms.indexOf(Manifest.permission.READ_CALL_LOG)
            if (logIdx >= 0 && results.getOrNull(logIdx) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Без журнала звонков авто-открытие WhatsApp не работает", Toast.LENGTH_LONG).show()
                prefs.setAutoWhatsAppEnabled(false)
            }
            val phoneStateIdx = perms.indexOf(Manifest.permission.READ_PHONE_STATE)
            if (phoneStateIdx >= 0 && results.getOrNull(phoneStateIdx) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Без состояния звонка авто-открытие WhatsApp не работает", Toast.LENGTH_LONG).show()
                prefs.setAutoWhatsAppEnabled(false)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        when (rc) {
            REQ_OVERLAY      -> if (Settings.canDrawOverlays(this)) requestAllPermissions()
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
