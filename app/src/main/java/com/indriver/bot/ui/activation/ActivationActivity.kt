package com.indriver.bot.ui.activation

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.indriver.bot.R
import com.indriver.bot.ui.main.MainActivity
import com.indriver.bot.utils.ActivationManager

class ActivationActivity : AppCompatActivity() {

    private lateinit var tilCode: TextInputLayout
    private lateinit var etCode: TextInputEditText
    private lateinit var btnActivate: MaterialButton
    private lateinit var tvError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ActivationManager.isActivated(this)) { goToMain(); return }

        window.statusBarColor = ContextCompat.getColor(this, R.color.bg_primary)
        setContentView(buildLayout())
        setupListeners()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.bg_primary))
            setPadding(dp(24), dp(0), dp(24), dp(40))
        }

        // Logo
        root.addView(TextView(this).apply {
            text = "Т"
            textSize = 42f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(80), dp(80)).apply {
                topMargin = dp(88); gravity = Gravity.CENTER_HORIZONTAL
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(0xFF00C853.toInt())
            }
        })

        // Title
        root.addView(TextView(this).apply {
            text = "Активация"
            textSize = 26f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(28) }
        })

        // Subtitle
        root.addView(TextView(this).apply {
            text = "Введите код для доступа к Такса"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        })

        // Input
        tilCode = TextInputLayout(this, null,
            com.google.android.material.R.style.Widget_MaterialComponents_TextInputLayout_OutlinedBox
        ).apply {
            hint = "Код активации"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(44) }
        }
        etCode = TextInputEditText(tilCode.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            textSize = 16f
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(20))
        }
        tilCode.addView(etCode)
        root.addView(tilCode)

        // Error
        tvError = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFFF5252.toInt())
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        root.addView(tvError)

        // Button
        btnActivate = MaterialButton(this).apply {
            text = "Активировать"
            textSize = 16f
            isAllCaps = false
            cornerRadius = dp(14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(24) }
        }
        root.addView(btnActivate)

        return root
    }

    private fun setupListeners() {
        etCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { tryActivate(); true } else false
        }
        etCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { tvError.visibility = View.GONE }
        })
        btnActivate.setOnClickListener { tryActivate() }
    }

    private fun tryActivate() {
        hideKeyboard()
        val code = etCode.text?.toString()?.trim() ?: ""
        if (code.isEmpty()) { showError("Введите код активации"); return }

        btnActivate.isEnabled = false
        etCode.isEnabled = false
        btnActivate.text = "Проверка..."

        ActivationManager.activate(this, code) { result ->
            when (result) {
                is ActivationManager.Result.Success,
                is ActivationManager.Result.RestoredDevice -> {
                    btnActivate.text = "Готово!"
                    btnActivate.setBackgroundColor(0xFF00C853.toInt())
                    tvError.visibility = View.GONE
                    btnActivate.postDelayed({ goToMain() }, 700L)
                }
                is ActivationManager.Result.UsedOtherDevice -> {
                    showError("Код уже активирован на другом устройстве")
                    resetInputs(); shakeView(tilCode)
                }
                is ActivationManager.Result.Invalid -> {
                    showError("Неверный код активации")
                    resetInputs(); shakeView(tilCode)
                }
                is ActivationManager.Result.NetworkError -> {
                    showError("Нет подключения к интернету. Проверьте сеть.")
                    resetInputs()
                }
                is ActivationManager.Result.AlreadyActivated -> goToMain()
            }
        }
    }

    private fun showError(msg: String) {
        tvError.text = msg; tvError.visibility = View.VISIBLE
    }

    private fun resetInputs() {
        btnActivate.isEnabled = true
        etCode.isEnabled = true
        btnActivate.text = "Активировать"
    }

    private fun shakeView(v: View) {
        ObjectAnimator.ofFloat(v, "translationX", 0f, -16f, 16f, -10f, 10f, -6f, 6f, 0f)
            .setDuration(400).start()
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(etCode.windowToken, 0)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
