package com.indriver.bot.ui.log

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.indriver.bot.R
import com.indriver.bot.databinding.ActivityLogBinding
import com.indriver.bot.utils.OrderLogger

class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private lateinit var logger: OrderLogger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.statusBarColor = ContextCompat.getColor(this, R.color.bg_primary)

        logger = OrderLogger(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnClearLog.setOnClickListener {
            logger.clear()
            loadLog()
            Toast.makeText(this, "Журнал очищен", Toast.LENGTH_SHORT).show()
        }

        loadLog()
    }

    private fun loadLog() {
        val entries = logger.getAll()
        binding.tvTodaySummary.text = logger.todaySummary()

        if (entries.isEmpty()) {
            binding.recyclerLog.visibility = View.GONE
            return
        }

        binding.recyclerLog.visibility = View.VISIBLE
        binding.recyclerLog.layoutManager = LinearLayoutManager(this)
        binding.recyclerLog.adapter = LogAdapter(entries)
    }

    override fun onResume() {
        super.onResume()
        loadLog()
    }

    inner class LogAdapter(private val items: List<OrderLogger.LogEntry>) :
        RecyclerView.Adapter<LogAdapter.VH>() {

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_log_entry, parent, false)
        )

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            fun bind(entry: OrderLogger.LogEntry) {
                val strip      = itemView.findViewById<View>(R.id.statusStrip)
                val tvType     = itemView.findViewById<TextView>(R.id.tvOrderType)
                val tvPrice    = itemView.findViewById<TextView>(R.id.tvPrice)
                val tvRoute    = itemView.findViewById<TextView>(R.id.tvRoute)
                val tvTime     = itemView.findViewById<TextView>(R.id.tvTime)
                val tvReason   = itemView.findViewById<TextView>(R.id.tvReason)

                val isAccepted = entry.status == "ПРИНЯТ"

                strip.setBackgroundColor(
                    if (isAccepted) 0xFF00C853.toInt() else 0xFFFF4C6A.toInt()
                )

                tvType.text  = entry.orderType
                tvType.setTextColor(if (isAccepted) 0xFFF0F2FF.toInt() else 0xFF7B82A0.toInt())

                tvPrice.text = "${entry.price} T"
                tvPrice.setTextColor(
                    if (isAccepted) 0xFFFFB300.toInt() else 0xFF4A5070.toInt()
                )

                val route = when {
                    entry.cityFrom.isNotEmpty() && entry.cityTo.isNotEmpty() ->
                        "${entry.cityFrom} -> ${entry.cityTo}"
                    entry.cityTo.isNotEmpty() -> "-> ${entry.cityTo}"
                    else -> ""
                }
                tvRoute.text = route
                tvRoute.visibility = if (route.isNotEmpty()) View.VISIBLE else View.GONE

                tvTime.text = entry.formattedTime()

                if (entry.reason.isNotEmpty()) {
                    tvReason.visibility = View.VISIBLE
                    tvReason.text = entry.reason
                } else {
                    tvReason.visibility = View.GONE
                }
            }
        }
    }
}
