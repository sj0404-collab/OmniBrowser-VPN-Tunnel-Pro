package com.winwasm64.vpn

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.winwasm64.extensions.ExtensionProxy

/**
 * «Что сейчас включено» — простыми словами: как идёт браузер, какие приложения идут через VPN, что остальные — напрямую.
 * Кнопки: журнал VPN и быстрое добавление популярных приложений (YouTube, Telegram, AyuGram…).
 */
class VpnStatusView(context: Context, private val onQuickAdd: (List<String>) -> Unit) : LinearLayout(context) {

    companion object {
        private val POPULAR = listOf(
            "com.google.android.youtube" to "YouTube",
            "org.telegram.messenger" to "Telegram",
            "org.telegram.messenger.web" to "Telegram",
            "com.radolyn.ayugram" to "AyuGram",
            "org.thunderdog.challegram" to "Telegram X",
            "com.discord" to "Discord",
            "com.instagram.android" to "Instagram",
            "com.twitter.android" to "X",
            "com.openai.chatgpt" to "ChatGPT"
        )
    }

    private val tvBrowser = TextView(context)
    private val tvApps = TextView(context)
    private val tvHint = TextView(context)
    private val btnAdd = TextView(context)
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1500L)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun styleText(tv: TextView, size: Float, color: Int, bold: Boolean = false) {
        tv.textSize = size
        tv.setTextColor(color)
        if (bold) tv.typeface = Typeface.DEFAULT_BOLD
        tv.setPadding(0, dp(3), 0, dp(3))
    }

    private fun pill(tv: TextView, text: String, onClick: () -> Unit): TextView {
        tv.text = text
        tv.textSize = 13f
        tv.setTextColor(0xFFECECEC.toInt())
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(12), dp(8), dp(12), dp(8))
        tv.background = GradientDrawable().apply {
            setColor(0x24FFFFFF)
            cornerRadius = dp(12).toFloat()
        }
        tv.isClickable = true
        tv.setOnClickListener { onClick() }
        return tv
    }

    init {
        orientation = VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(0xFF1C1D21.toInt())
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), 0x335B8DEF)
        }
        VpnLog.init(context)
        val title = TextView(context)
        styleText(title, 16f, 0xFFECECEC.toInt(), true)
        title.text = "Что сейчас включено"
        addView(title)
        styleText(tvBrowser, 13f, 0xFFECECEC.toInt())
        addView(tvBrowser)
        styleText(tvApps, 13f, 0xFFECECEC.toInt())
        addView(tvApps)
        styleText(tvHint, 11.5f, 0xFF9AA0A6.toInt())
        addView(tvHint)
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        row.addView(pill(TextView(context), "📜 Журнал VPN") {
            context.startActivity(Intent(context, VpnLogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        row.addView(pill(btnAdd, "") {
            val missing = missingPopular()
            if (missing.isNotEmpty()) {
                onQuickAdd(missing.map { it.first })
                VpnLog.i("Сервис", "Быстро добавлены в VPN: " + missing.joinToString(", ") { it.second })
                Toast.makeText(context, "Добавлено в VPN: " + missing.joinToString(", ") { it.second }, Toast.LENGTH_SHORT).show()
            }
            refresh()
        })
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        refresh()
    }

    private fun missingPopular(): List<Pair<String, String>> {
        val pm = context.packageManager
        return POPULAR.filter { (pkg, _) ->
            !BrowsecVpnService.routedPackages.contains(pkg) && try {
                pm.getApplicationInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun refresh() {
        tvBrowser.text = try {
            when {
                ExtensionProxy.isActive -> "🌐 Браузер: через прокси расширения «${ExtensionProxy.ownerName ?: "?"}». Если прокси не отвечает — сайт откроется через встроенный обход DPI (видно в журнале)."
                BrowserDirectRepeater.enabled -> "🌐 Браузер: VPN включён — обход DPI, профиль ${BrowserDirectRepeater.sampleBrowserTraffic().activeCountry}. IP не меняется."
                else -> "🌐 Браузер: напрямую (VPN браузера выключен)."
            }
        } catch (_: Exception) {
            "🌐 Браузер: —"
        }
        val routed = BrowsecVpnService.routedPackages.toList()
        tvApps.text = when {
            !BrowsecVpnService.isRunning -> "📱 Приложения: VPN выключен — все приложения идут напрямую."
            BrowsecVpnService.isPaused -> "📱 Приложения: VPN на паузе — все приложения идут напрямую."
            routed.isEmpty() -> "📱 Приложения: VPN включён, но список пуст — ни одно приложение не идёт через VPN. Добавьте приложения (кнопка ниже или «+ Добавить в VPN»)."
            else -> "📱 Приложения через VPN (${routed.size}): ${VpnLog.appList(routed)}. Остальные — напрямую" +
                (if (BrowsecVpnService.transparentActive) "." else " (режим прокси).")
        }
        tvApps.setTextColor(if (BrowsecVpnService.isRunning && !BrowsecVpnService.isPaused && routed.isEmpty()) 0xFFFDD663.toInt() else 0xFFECECEC.toInt())
        tvHint.text = "«Страны» в списках — это профили обхода DPI: IP не меняется. Не открывается сайт или приложение — откройте «Журнал VPN», там видно, куда и с какой ошибкой оно подключалось."
        val missing = missingPopular()
        btnAdd.visibility = if (missing.isEmpty()) GONE else VISIBLE
        btnAdd.text = "+ " + missing.map { it.second }.distinct().take(3).joinToString(", ")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(ticker)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }
}
