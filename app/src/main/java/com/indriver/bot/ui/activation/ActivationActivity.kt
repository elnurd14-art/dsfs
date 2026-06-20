package com.indriver.bot.ui.activation

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.indriver.bot.R
import com.indriver.bot.ui.main.MainActivity
import com.indriver.bot.utils.ActivationManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Экран активации — показывается, пока ActivationManager.isActivated() не вернёт true.
 * Блокирует доступ к остальному приложению (онбордингу, MainActivity, боту) без кода.
 */
class ActivationActivity : AppCompatActivity() {

    private lateinit var inputLayout: TextInputLayout
    private lateinit var etCode: TextInputEditText
    private lateinit var btnActivate: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Доп. защита: если код уже активирован на этом устройстве — сразу пропускаем экран.
        if (ActivationManager.isActivated(this)) {
            goToApp()
            return
        }

        setContentView(R.layout.activity_activation)

        inputLayout  = findViewById(R.id.inputLayoutCode)
        etCode       = findViewById(R.id.etCode)
        btnActivate  = findViewById(R.id.btnActivate)
        progressBar  = findViewById(R.id.progressBar)
        tvError      = findViewById(R.id.tvError)

        btnActivate.setOnClickListener { tryActivate() }
        etCode.setOnEditorActionListener { _, _, _ -> tryActivate(); true }
    }

    private fun tryActivate() {
        val code = etCode.text?.toString()?.trim().orEmpty()
        if (code.isBlank()) {
            showError("Введите код активации")
            return
        }

        setLoading(true)
        ActivationManager.activate(this, code) { result ->
            runOnUiThread {
                setLoading(false)
                when (result) {
                    ActivationManager.Result.Success,
                    ActivationManager.Result.RestoredDevice,
                    ActivationManager.Result.AlreadyActivated -> goToApp()

                    ActivationManager.Result.UsedOtherDevice ->
                        showError("Этот код уже активирован на другом устройстве")

                    ActivationManager.Result.Invalid ->
                        showError("Код не найден. Проверьте правильность ввода")

                    ActivationManager.Result.NetworkError ->
                        showError("Нет соединения с сервером. Проверьте интернет и попробуйте снова")
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnActivate.isEnabled  = !loading
        etCode.isEnabled       = !loading
        if (loading) tvError.visibility = View.GONE
    }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.visibility = View.VISIBLE
    }

    private fun goToApp() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
