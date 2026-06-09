package com.indriver.bot.ui.onboarding

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.indriver.bot.R
import com.indriver.bot.databinding.ActivityOnboardingBinding
import com.indriver.bot.ui.main.MainActivity

class OnboardingActivity : AppCompatActivity() {

    data class Page(
        val icon: String,
        val iconBgColor: Int,
        val title: String,
        val desc: String,
        val actionLabel: String = "",
        val actionId: Int = 0
    )

    private lateinit var binding: ActivityOnboardingBinding
    private val pages = listOf(
        Page(
            icon = "T", iconBgColor = 0xFF0D2E1A.toInt(),
            title = "Добро пожаловать в Такса",
            desc  = "Такса автоматически отслеживает новые заказы в inDrive и нажимает карточки подходящих — быстрее любого водителя."
        ),
        Page(
            icon = "A", iconBgColor = 0xFF0D1A2E.toInt(),
            title = "Специальные возможности",
            desc  = "Приложению нужен доступ к Специальным возможностям Android, чтобы видеть экран inDrive.\n\nНажмите кнопку, найдите «Такса» в списке и включите переключатель.",
            actionLabel = "Открыть настройки", actionId = 1
        ),
        Page(
            icon = "O", iconBgColor = 0xFF1A1A0D.toInt(),
            title = "Наложение поверх приложений",
            desc  = "Чтобы показывать уведомления поверх inDrive, нужно разрешение «Поверх других приложений».\n\nНажмите кнопку и включите переключатель для Такса.",
            actionLabel = "Открыть разрешения", actionId = 2
        ),
        Page(
            icon = "V", iconBgColor = 0xFF0D2E1A.toInt(),
            title = "Всё готово!",
            desc  = "Запустите inDrive в режиме водителя, откройте вкладку Заказы или Попутки.\n\nВернитесь в Такса, нажмите переключатель — бот начнёт работу."
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.statusBarColor = ContextCompat.getColor(this, R.color.bg_primary)

        setupPager()
        setupDots()
        updateDots(0)

        binding.btnNext.setOnClickListener {
            val cur = binding.viewPager.currentItem
            if (cur < pages.size - 1) binding.viewPager.currentItem = cur + 1
            else finishOnboarding()
        }
        binding.btnSkip.setOnClickListener { finishOnboarding() }
    }

    private fun setupPager() {
        binding.viewPager.adapter = PagesAdapter()
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
                binding.btnNext.text =
                    if (position == pages.size - 1) "Начать работу" else "Далее"
            }
        })
    }

    private fun setupDots() {
        binding.dotsLayout.removeAllViews()
        pages.forEach { _ ->
            val dot = View(this).apply {
                val lp = ViewGroup.MarginLayoutParams(10, 10).apply {
                    marginEnd = 8; marginStart = 8
                }
                layoutParams = lp
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF2A2F42.toInt())
                }
            }
            binding.dotsLayout.addView(dot)
        }
    }

    private fun updateDots(selected: Int) {
        for (i in 0 until binding.dotsLayout.childCount) {
            val dot = binding.dotsLayout.getChildAt(i) as? View ?: continue
            val bg  = dot.background as? GradientDrawable ?: continue
            val lp  = dot.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            if (i == selected) {
                bg.setColor(0xFF00C853.toInt()); lp.width = 24; lp.height = 10
            } else {
                bg.setColor(0xFF2A2F42.toInt()); lp.width = 10; lp.height = 10
            }
            dot.layoutParams = lp
        }
    }

    private fun finishOnboarding() {
        getSharedPreferences("taksa_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_done", true).apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    inner class PagesAdapter : RecyclerView.Adapter<PageVH>() {
        override fun getItemCount() = pages.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PageVH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_onboarding, parent, false)
        )
        override fun onBindViewHolder(holder: PageVH, position: Int) = holder.bind(pages[position])
    }

    inner class PageVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(page: Page) {
            val circle    = itemView.findViewById<ViewGroup>(R.id.iconCircle)
            val tvIcon    = itemView.findViewById<TextView>(R.id.tvIcon)
            val tvTitle   = itemView.findViewById<TextView>(R.id.tvTitle)
            val tvDesc    = itemView.findViewById<TextView>(R.id.tvDesc)
            val btnAction = itemView.findViewById<MaterialButton>(R.id.btnAction)

            tvIcon.text  = page.icon
            tvTitle.text = page.title
            tvDesc.text  = page.desc

            circle.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(page.iconBgColor)
            }

            if (page.actionLabel.isNotEmpty()) {
                btnAction.visibility = View.VISIBLE
                btnAction.text = page.actionLabel
                btnAction.setOnClickListener {
                    when (page.actionId) {
                        1 -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        2 -> startActivity(Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        ))
                    }
                }
            } else {
                btnAction.visibility = View.GONE
            }
        }
    }
}
