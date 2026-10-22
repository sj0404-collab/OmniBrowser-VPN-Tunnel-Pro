package com.winwasm64.vpn

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Панель стран Browsec: текущая страна, живой трафик (Б/КБ/МБ), страны с пингом и скоростью ДО подключения.
 * Одна и та же панель — в плавающей кнопке, в быстром окне из шторки и в разделе VPN приложения.
 */
class BrowsecPanelView(
    context: Context,
    private val compact: Boolean,
    private val onClose: (() -> Unit)?,
    private val showBubbleToggle: Boolean
) : LinearLayout(context) {

    companion object {
        const val BG = 0xF21C1D21.toInt()
        const val ROW = 0x14FFFFFF
        const val ROW_ACTIVE = 0x335B8DEF
        const val BTN = 0x1FFFFFFF
        const val BTN_ON = 0xFF2E7D4F.toInt()
        const val TEXT = 0xFFECECEC.toInt()
        const val DIM = 0xFF9AA0A6.toInt()
        const val ACCENT = 0xFF8AB4F8.toInt()
        const val GOOD = 0xFF81C995.toInt()
        const val WARN = 0xFFFDD663.toInt()
    }

    private val tvActive = TextView(context)
    private val tvLive = TextView(context)
    private val tvStatus = TextView(context)
    private val btnPower = TextView(context)
    private val rows = LinearLayout(context)
    private val btnRu = TextView(context)
    private val btnBubble = TextView(context)
    private val tvFooter = TextView(context)
    private var builtFor: List<String> = emptyList()
    private val rowViews = HashMap<String, Triple<LinearLayout, TextView, TextView>>()
    private val listener: () -> Unit = { refresh() }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun round(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun label(tv: TextView, size: Float, color: Int, bold: Boolean = false): TextView {
        tv.textSize = size
        tv.setTextColor(color)
        if (bold) tv.typeface = Typeface.DEFAULT_BOLD
        return tv
    }

    private fun button(tv: TextView, text: String, onClick: () -> Unit): TextView {
        tv.text = text
        label(tv, if (compact) 12f else 13f, TEXT, true)
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(10), dp(7), dp(10), dp(7))
        tv.background = round(BTN, 10)
        tv.isClickable = true
        tv.setOnClickListener { onClick() }
        return tv
    }

    private fun toast(text: String) {
        try { Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
    }

    init {
        orientation = VERTICAL
        val pad = dp(if (compact) 12 else 14)
        setPadding(pad, pad, pad, pad)
        background = round(BG, 16)
        BrowsecNative.init(context)

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = label(TextView(context), if (compact) 15f else 17f, TEXT, true)
        title.text = "🌍 VPN Browsec"
        header.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (onClose != null) {
            val close = button(TextView(context), "✕") { onClose.invoke() }
            header.addView(close, LayoutParams(dp(40), dp(34)))
        }
        addView(header)

        label(tvActive, if (compact) 13f else 14f, TEXT)
        tvActive.setPadding(0, dp(6), 0, 0)
        addView(tvActive)
        label(tvLive, 12f, GOOD)
        tvLive.setPadding(0, dp(2), 0, dp(8))
        addView(tvLive)

        val actions = LinearLayout(context).apply { orientation = HORIZONTAL }
        button(btnPower, "⏻") {
            val on = !BrowsecNative.enabled
            BrowsecNative.applyEnabled(on)
            toast(if (on) "Browsec включён: браузер и приложения из списка" else "Browsec выключен")
        }
        actions.addView(btnPower, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.2f))
        val speed = button(TextView(context), "⚡ Скорость") {
            BrowsecNative.measureAll(true)
            toast("Проверяю скорость стран (≈300 КБ на страну)…")
        }
        actions.addView(speed, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        val upd = button(TextView(context), "⟳ Обновить") {
            BrowsecNative.refreshServers { ok -> toast(if (ok) "Список стран обновлён" else "API Browsec недоступен") }
            BrowsecNative.measureAll(false)
        }
        actions.addView(upd, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        addView(actions)

        label(tvStatus, 12f, WARN)
        tvStatus.setPadding(0, dp(6), 0, 0)
        addView(tvStatus)

        rows.orientation = VERTICAL
        rows.setPadding(0, dp(8), 0, dp(4))
        addView(rows)

        button(btnRu, "") { BrowsecNative.applyRuDirect(!BrowsecNative.ruDirect) }
        btnRu.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        btnRu.background = null
        btnRu.setPadding(dp(2), dp(6), dp(2), dp(6))
        addView(btnRu)

        if (showBubbleToggle) {
            button(btnBubble, "") { toggleBubble() }
            btnBubble.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            btnBubble.background = null
            btnBubble.setPadding(dp(2), dp(6), dp(2), dp(6))
            addView(btnBubble)
        }

        label(tvFooter, 11f, DIM)
        tvFooter.setPadding(dp(2), dp(4), dp(2), 0)
        addView(tvFooter)
        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        BrowsecNative.listeners.add(listener)
        refresh()
        if (BrowsecNative.needsPing()) BrowsecNative.measureAll(false)
    }

    override fun onDetachedFromWindow() {
        BrowsecNative.listeners.remove(listener)
        super.onDetachedFromWindow()
    }

    private fun toggleBubble() {
        val ctx = context
        if (!FloatingVpnBubble.canShow(ctx)) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                toast("Разрешите «Поверх других приложений», затем включите кнопку ещё раз")
            } catch (_: Exception) {
                toast("Откройте Настройки → Приложения → OmniBrowser → Поверх других приложений")
            }
            return
        }
        BrowsecNative.applyBubbleWanted(!BrowsecNative.bubbleWanted)
        FloatingVpnBubble.syncWithState(ctx)
        refresh()
    }

    private fun metricsText(code: String): String {
        val m = BrowsecNative.metrics[code] ?: return "не проверена"
        if (m.testing) return "проверка…"
        if (m.pingMs < 0) return m.error ?: "нет ответа"
        val sb = StringBuilder("${m.pingMs} мс")
        if (m.speedBps > 0) sb.append(" · ").append(BrowsecNative.formatRate(m.speedBps))
        return sb.toString()
    }

    private fun rebuildRows(free: List<BrowsecNative.Country>) {
        rows.removeAllViews()
        rowViews.clear()
        for (c in free) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(if (compact) 8 else 10), dp(10), dp(if (compact) 8 else 10))
                background = round(ROW, 10)
                isClickable = true
                setOnClickListener {
                    if (BrowsecNative.select(c.code)) toast("Страна: ${c.flag} ${c.nameRu}")
                }
            }
            val name = label(TextView(context), if (compact) 13f else 14f, TEXT, true)
            name.maxLines = 1
            name.ellipsize = TextUtils.TruncateAt.END
            val met = label(TextView(context), 12f, DIM)
            met.gravity = Gravity.END
            row.addView(name, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            row.addView(met, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            rows.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(5) })
            rowViews[c.code] = Triple(row, name, met)
        }
        builtFor = free.map { it.code }
    }

    fun refresh() {
        val n = BrowsecNative
        val active = n.activeCountry()
        btnPower.text = if (n.enabled) "⏻ Выключить" else "⏻ Включить"
        btnPower.background = round(if (n.enabled) BTN_ON else BTN, 10)
        tvActive.text = if (active != null) {
            (if (n.enabled) "Подключено: " else "Выбрано (выкл.): ") + "${active.flag} ${active.nameRu} · ${metricsText(active.code)}"
        } else "Загружаю страны…"
        tvLive.text = "Сейчас ⬇ ${n.formatRate(n.rateDown)}  ⬆ ${n.formatRate(n.rateUp)} · всего ⬇ ${n.formatBytes(n.bytesDown.get())}  ⬆ ${n.formatBytes(n.bytesUp.get())}"
        tvStatus.text = n.statusText
        tvStatus.visibility = if (n.statusText.isBlank()) GONE else VISIBLE
        val free = n.countries.filter { it.isFree }
        if (free.map { it.code } != builtFor) rebuildRows(free)
        for (c in free) {
            val (row, name, met) = rowViews[c.code] ?: continue
            val isActive = c.code == active?.code
            name.text = (if (isActive) "✓ " else "") + "${c.flag} ${c.nameRu}"
            name.setTextColor(if (isActive) ACCENT else TEXT)
            val live = if (isActive && n.enabled && (n.rateDown > 0 || n.rateUp > 0)) "\n⬇ ${n.formatRate(n.rateDown)}  ⬆ ${n.formatRate(n.rateUp)}" else ""
            met.text = metricsText(c.code) + live
            val m = n.metrics[c.code]
            met.setTextColor(
                when {
                    m == null || m.testing -> DIM
                    m.pingMs < 0 -> WARN
                    m.pingMs < 150 -> GOOD
                    else -> TEXT
                }
            )
            row.background = round(if (isActive) ROW_ACTIVE else ROW, 10)
        }
        btnRu.text = (if (n.ruDirect) "☑" else "☐") + "  Российские сайты — напрямую (банки, Госуслуги)"
        btnRu.setTextColor(TEXT)
        if (showBubbleToggle) {
            btnBubble.text = (if (n.bubbleWanted && FloatingVpnBubble.canShow(context)) "☑" else "☐") + "  Плавающая кнопка стран поверх приложений"
            btnBubble.setTextColor(TEXT)
        }
        val premium = n.countries.count { !it.isFree }
        tvFooter.text = "Через VPN идут браузер и приложения из белого списка, остальные — напрямую." +
            (if (premium > 0) " Ещё $premium стран — только Browsec Premium." else "")
    }
}
