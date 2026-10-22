package com.winwasm64.extensions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Окно расширения как отдельная страница (как в Lemur/Kiwi): popup или страница настроек на весь экран,
 * со своей панелью «назад / перезагрузить / настройки / закрыть». Работает поверх браузера, вкладки не теряются.
 */
class ExtensionPageActivity : Activity() {
    companion object {
        private const val EXTRA_EXT = "ext"
        private const val EXTRA_OPTIONS = "options"
        private val open = ConcurrentHashMap<String, WeakReference<ExtensionPageActivity>>()

        fun start(context: Context, extId: String, options: Boolean) {
            val i = Intent(context, ExtensionPageActivity::class.java)
                .putExtra(EXTRA_EXT, extId)
                .putExtra(EXTRA_OPTIONS, options)
            if (context !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }

        /** Расширение закрыло своё окно (window.close / ссылка в новую вкладку). */
        fun closeFor(extId: String) {
            open["popup:$extId"]?.get()?.let { a -> a.runOnUiThread { a.finish() } }
        }
    }

    private var host: ExtPageHost? = null
    private var key = ""

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun barButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(0xFFECECEC.toInt())
        gravity = Gravity.CENTER
        isClickable = true
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extId = intent.getStringExtra(EXTRA_EXT)
        if (extId.isNullOrBlank()) {
            finish()
            return
        }
        val options = intent.getBooleanExtra(EXTRA_OPTIONS, false)
        val ext = ExtensionStore.get(extId)
        key = (if (options) "options:" else "popup:") + extId
        open[key]?.get()?.let { if (it !== this) it.finish() }
        open[key] = WeakReference(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1C1D21.toInt())
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(0xFF26272B.toInt())
        }
        bar.addView(barButton("←") { goBack() }, LinearLayout.LayoutParams(dp(48), dp(44)))
        val title = TextView(this).apply {
            text = (ext?.manifest?.name ?: extId) + if (options) " — настройки" else ""
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFECECEC.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(barButton("⟳") { host?.webView?.reload() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        if (!options && ext?.manifest?.optionsPath != null) {
            bar.addView(barButton("⚙") { start(this, extId, true) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        bar.addView(barButton("✕") { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val h = if (options) ExtensionRuntime.createOptionsHost(this, extId) else ExtensionRuntime.createPopupHost(this, extId)
        host = h
        if (h == null) {
            val empty = TextView(this).apply {
                text = if (options) "У расширения нет страницы настроек" else "У расширения нет всплывающего окна (или WebView устарел)"
                setTextColor(0xFF9AA0A6.toInt())
                gravity = Gravity.CENTER
                textSize = 15f
            }
            root.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            (h.webView.parent as? ViewGroup)?.removeView(h.webView)
            h.webView.setBackgroundColor(Color.WHITE)
            root.addView(h.webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
    }

    private fun goBack() {
        val wv = host?.webView
        if (wv != null && wv.canGoBack()) wv.goBack() else finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        goBack()
    }

    override fun onDestroy() {
        if (open[key]?.get() === this) open.remove(key)
        host?.let {
            (it.webView.parent as? ViewGroup)?.removeView(it.webView)
            ExtensionRuntime.releaseHost(it)
        }
        host = null
        super.onDestroy()
    }
}
