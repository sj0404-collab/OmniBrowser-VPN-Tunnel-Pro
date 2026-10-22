package com.winwasm64.vpn

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Экран «Журнал VPN»: фильтры, копировать / поделиться / сохранить / очистить. */
class VpnLogActivity : Activity() {
    private lateinit var tvLog: TextView
    private lateinit var scroll: ScrollView
    private val chips = ArrayList<Pair<TextView, String?>>()
    private var filter: String? = null
    private var shownVersion = -1
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (VpnLog.version != shownVersion) render()
            handler.postDelayed(this, 1000L)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun pill(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(0xFFECECEC.toInt())
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(7), dp(12), dp(7))
        background = GradientDrawable().apply {
            setColor(0x1FFFFFFF)
            cornerRadius = dp(14).toFloat()
        }
        isClickable = true
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VpnLog.init(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF141519.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "📜 Журнал VPN"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFECECEC.toInt())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(pill("✕") { finish() })
        root.addView(header)

        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, f) in listOf<Pair<String, String?>>("Все" to null, "Ошибки" to "err", "Приложения" to "Прил", "Браузер" to "Браузер", "Туннель" to "Туннель", "Сервис" to "Сервис")) {
            val chip = pill(label) {
                filter = f
                shownVersion = -1
                render()
            }
            chips.add(chip to f)
            filters.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(filters)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(pill("Копировать") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("OmniBrowser VPN", VpnLog.text(filter)))
            Toast.makeText(this, "Журнал скопирован", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
        actions.addView(pill("Поделиться") {
            val t = VpnLog.text(filter)
            val body = if (t.length > 90_000) t.substring(t.length - 90_000) else t
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body), "Журнал VPN"))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
        actions.addView(pill("Сохранить") {
            val path = VpnLog.saveToDownloads(this)
            Toast.makeText(this, if (path != null) "Сохранено: $path" else "Не удалось сохранить", Toast.LENGTH_LONG).show()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
        actions.addView(pill("Очистить") {
            VpnLog.clear()
            render()
        })
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(actions)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        tvLog = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(0xFFD7DAE0.toInt())
            setTextIsSelectable(true)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        scroll = ScrollView(this).apply { addView(tvLog) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        setContentView(root)
        render()
    }

    private fun render() {
        shownVersion = VpnLog.version
        val list = VpnLog.snapshot(filter)
        tvLog.text = if (list.isEmpty()) "Пока пусто. Включите VPN и откройте сайт или приложение — сюда попадёт каждое подключение и каждая ошибка." else list.joinToString("\n")
        for ((chip, f) in chips) {
            (chip.background as? GradientDrawable)?.setColor(if (f == filter) 0x665B8DEF else 0x1FFFFFFF)
        }
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }
}
