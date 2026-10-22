package com.winwasm64.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * Плавающая кнопка поверх приложений: флаг страны и скорость; касание — панель стран Browsec (смена страны без браузера).
 * Нужно разрешение «Поверх других приложений».
 */
object FloatingVpnBubble {
    private const val PREFS = "omni_browsec_bubble"
    private var wm: WindowManager? = null
    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var panelHost: FrameLayout? = null
    private var panelClosedAt = 0L
    private val listener: () -> Unit = { updateBubble() }

    fun canShow(ctx: Context): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    /** Показать или убрать кнопку по настройке пользователя. */
    fun syncWithState(ctx: Context) {
        val app = ctx.applicationContext
        BrowsecNative.init(app)
        if (BrowsecNative.bubbleWanted && canShow(app)) show(app, false) else hide()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show(ctx: Context, expanded: Boolean): Boolean {
        val app = ctx.applicationContext
        if (!canShow(app)) return false
        BrowsecNative.init(app)
        try {
            if (bubble == null) {
                val w = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm = w
                val tv = TextView(app).apply {
                    gravity = Gravity.CENTER
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 11f
                    setLineSpacing(0f, 0.9f)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xE61C1D21.toInt())
                        setStroke(dp(app, 2), 0xFF8AB4F8.toInt())
                    }
                }
                val size = dp(app, 58)
                val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val lp = WindowManager.LayoutParams(
                    size, size, overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = p.getInt("x", dp(app, 6))
                    y = p.getInt("y", dp(app, 180))
                }
                tv.setOnTouchListener(object : View.OnTouchListener {
                    var sx = 0
                    var sy = 0
                    var tx = 0f
                    var ty = 0f
                    var moved = false
                    override fun onTouch(v: View, e: MotionEvent): Boolean {
                        val l = bubbleLp ?: return false
                        when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                sx = l.x; sy = l.y; tx = e.rawX; ty = e.rawY; moved = false
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = e.rawX - tx
                                val dy = e.rawY - ty
                                if (abs(dx) > dp(app, 8) || abs(dy) > dp(app, 8)) moved = true
                                if (moved) {
                                    l.x = sx + dx.toInt()
                                    l.y = sy + dy.toInt()
                                    try { wm?.updateViewLayout(v, l) } catch (_: Exception) {}
                                }
                            }
                            MotionEvent.ACTION_UP -> {
                                if (moved) p.edit().putInt("x", l.x).putInt("y", l.y).apply()
                                else if (SystemClock.elapsedRealtime() - panelClosedAt > 350L) togglePanel(app)
                            }
                        }
                        return true
                    }
                })
                w.addView(tv, lp)
                bubble = tv
                bubbleLp = lp
                BrowsecNative.listeners.add(listener)
                updateBubble()
            }
            if (expanded) showPanel(app)
            return true
        } catch (_: Exception) {
            hide()
            return false
        }
    }

    fun hide() {
        hidePanel()
        bubble?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        bubble = null
        bubbleLp = null
        BrowsecNative.listeners.remove(listener)
    }

    private fun togglePanel(app: Context) {
        if (panelHost != null) hidePanel() else showPanel(app)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showPanel(app: Context) {
        if (panelHost != null) return
        val w = wm ?: (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { wm = it }
        val panel = BrowsecPanelView(app, true, { hidePanel() }, false)
        val scroll = ScrollView(app).apply {
            isVerticalScrollBarEnabled = false
            addView(panel)
        }
        val host = FrameLayout(app).apply {
            addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    hidePanel()
                    true
                } else false
            }
        }
        val dm = app.resources.displayMetrics
        val width = minOf(dp(app, 340), dm.widthPixels - dp(app, 16))
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val height = minOf(panel.measuredHeight + dp(app, 4), (dm.heightPixels * 0.78f).toInt())
        val lp = WindowManager.LayoutParams(
            width, height, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        try {
            w.addView(host, lp)
            panelHost = host
        } catch (_: Exception) {
        }
    }

    private fun hidePanel() {
        panelHost?.let {
            try { wm?.removeView(it) } catch (_: Exception) {}
            panelClosedAt = SystemClock.elapsedRealtime()
        }
        panelHost = null
    }

    private fun updateBubble() {
        val tv = bubble ?: return
        val n = BrowsecNative
        val c = n.activeCountry()
        tv.text = if (n.enabled && c != null) "${c.flag}\n⬇${n.formatRateShort(n.rateDown)}" else "🌍\nвыкл"
        (tv.background as? GradientDrawable)?.setStroke(dp(tv.context, 2), if (n.enabled) 0xFF81C995.toInt() else 0xFF9AA0A6.toInt())
    }
}
