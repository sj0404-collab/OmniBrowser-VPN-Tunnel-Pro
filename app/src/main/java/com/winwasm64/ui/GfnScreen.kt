package com.winwasm64.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * v4.95.0: вкладка GeForce NOW — «как есть», и наш интерфейс НЕ лежит поверх страницы.
 *
 * Что было не так в 4.94.0 (по вашему скриншоту: страница видна, но ни тап, ни скролл, а кнопка
 * «Принять и продолжить» серая):
 *  1. Кнопки Esc/Tab/Enter висели ПОВЕРХ страницы — мы убрали их из области страницы: теперь они
 *     в отдельной полосе ПОД ней и физически не могут перехватывать касания по игре.
 *  2. WebView создавался от applicationContext — такому виду Android хуже отдаёт фокус и ввод.
 *     Теперь WebView берёт контекст экрана и живёт в обычном FrameLayout, как вкладки браузера.
 *  3. UA подменялся на десктопный Chrome, а `navigator.userAgentData` (Client Hints) оставался
 *     андроидным — для их клиента это «неизвестный браузер», и он гасит свои кнопки (серая).
 *     Теперь UA и Client Hints согласованы: Windows/x86/Chrome — как настоящий десктоп.
 *
 * Никаких наших скриптов в страницу не вставляется: всё рисует и обрабатывает сам клиент NVIDIA.
 */
@Composable
fun GfnScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var desktopUa by remember { mutableStateOf(GfnHolder.desktopUa) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var fullscreenView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var statusText by remember { mutableStateOf<String?>("Загрузка клиента GeForce NOW…") }

    val host = remember { GfnHolder.container(ctx) }
    val webView = remember { GfnHolder.webView(ctx) }

    // Полный экран запрашивает сам клиент игры — отдаём ему вид как есть.
    GfnHolder.onShowCustomView = { view, cb ->
        fullscreenView = view
        fullscreenCallback = cb
    }
    GfnHolder.onHideCustomViewCallback = { fullscreenView = null }

    GfnHolder.onStatus = { text -> statusText = text }

    DisposableEffect(Unit) {
        onDispose {
            try {
                fullscreenView = null
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.onPause()
            } catch (_: Throwable) {
            }
        }
    }

    // Первый запуск и повторные — с согласованным UA. Клиент живёт в GfnHolder,
    // поэтому переключение вкладок не сбрасывает вход и не рвёт сессию.
    LaunchedEffect(desktopUa, reloadTick) {
        try {
            GfnHolder.applyUa(webView, desktopUa)
            webView.onResume()
            webView.loadUrl(GfnHolder.URL)
        } catch (_: Throwable) {
        }
    }

    Column(modifier = modifier.fillMaxSize().background(Color.Black)) {

        // ── служебная полоса: НАД страницей, не поверх ──
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF101215)).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KeyChip("⟳") { reloadTick++ }
            KeyChip(if (desktopUa) "Десктоп" else "Android") { desktopUa = !desktopUa }
            KeyChip("В Chrome") {
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GfnHolder.URL)))
                } catch (_: Throwable) {
                }
            }
            Text(
                statusText ?: "",
                color = Color(0xFF8A8F98),
                fontSize = 10.sp,
                maxLines = 1,
                modifier = Modifier.padding(start = 2.dp)
            )
        }

        if (fullscreenView == null) {
            // ── страница клиента ──
            AndroidView(
                factory = {
                    (host.parent as? ViewGroup)?.removeView(host)
                    host
                },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        } else {
            // ── их собственный полный экран ──
            val fsBox = remember(fullscreenView) {
                FrameLayout(ctx).apply { setBackgroundColor(android.graphics.Color.BLACK) }
            }
            LaunchedEffect(fullscreenView) {
                val v = fullscreenView
                try {
                    fsBox.removeAllViews()
                    if (v != null) {
                        (v.parent as? ViewGroup)?.removeView(v)
                        fsBox.addView(
                            v,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                        v.isFocusable = true
                        v.isFocusableInTouchMode = true
                        v.requestFocus()
                    }
                } catch (_: Throwable) {
                }
            }
            AndroidView(factory = { fsBox }, modifier = Modifier.fillMaxWidth().weight(1f))
        }

        // ── нужные клавиши: отдельная полоса ПОД страницей ──
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF101215)).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyChip("Esc") { pressKey(webView, KeyEvent.KEYCODE_ESCAPE) }
            KeyChip("Tab") { pressKey(webView, KeyEvent.KEYCODE_TAB) }
            KeyChip("Enter") { pressKey(webView, KeyEvent.KEYCODE_ENTER) }
            KeyChip("⌨") { showKeyboard(ctx, webView) }
            if (fullscreenView != null) {
                KeyChip("Выйти") {
                    try {
                        fullscreenCallback?.onCustomViewHidden()
                    } catch (_: Throwable) {
                    }
                    fullscreenCallback = null
                    fullscreenView = null
                }
            }
        }
    }
}

/**
 * Клиент GeForce NOW живёт, пока живёт приложение: переключение вкладок не сбрасывает вход.
 */
private object GfnHolder {

    const val URL = "https://play.geforcenow.com/"

    /** UA настоящего десктопного Chrome — так клиент NVIDIA открывает свой веб-клиент. */
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"

    var desktopUa: Boolean = true

    var onShowCustomView: ((View?, WebChromeClient.CustomViewCallback?) -> Unit)? = null
    var onHideCustomViewCallback: (() -> Unit)? = null
    var onStatus: ((String?) -> Unit)? = null

    private var host: FrameLayout? = null
    private var web: WebView? = null

    fun container(ctx: Context): FrameLayout {
        host?.let { h ->
            webView(ctx)
            return h
        }
        val h = FrameLayout(ctx)
        host = h
        h.addView(
            webView(ctx),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        return h
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun webView(ctx: Context): WebView {
        web?.let { return it }
        val wv = WebView(ctx).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            with(settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                setSupportMultipleWindows(false)
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(false)
                textZoom = 100
            }
            try {
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            } catch (_: Throwable) {
            }
            // касание по странице должно сразу отдавать фокус самому клиенту
            setOnTouchListener { v, event ->
                if (!v.hasFocus()) v.requestFocus()
                false
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    onStatus?.invoke(null)
                    view?.requestFocus()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        onStatus?.invoke("Сеть: ${error?.description ?: "ошибка"} — нажмите ⟳")
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    onShowCustomView?.invoke(view, callback)
                }

                override fun onHideCustomView() {
                    onHideCustomViewCallback?.invoke()
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    // микрофон для голосового чата в игре
                    try {
                        request?.grant(request.resources)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        web = wv
        return wv
    }

    /**
     * v4.95.0: UA и Client Hints — согласованно.
     * Раньше строка была десктопная, а `navigator.userAgentData` андроидный: клиент NVIDIA считал
     * браузер неизвестным и держал свои кнопки неактивными (серая «Принять и продолжить»).
     */
    fun applyUa(wv: WebView, desktop: Boolean) {
        try {
            val ua = if (desktop) DESKTOP_UA else BrowserUa.mobile(wv.context)
            if (wv.settings.userAgentString != ua) wv.settings.userAgentString = ua
            BrowserUa.applyClientHints(wv, desktop)
            desktopUa = desktop
        } catch (_: Throwable) {
        }
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    Surface(
        color = Color(0xFF202124),
        shape = RoundedCornerShape(50),
        modifier = Modifier.height(32.dp).clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
    }
}

/** Одно нажатие клавиши в страницу (без синтетических DOM-событий). */
private fun pressKey(wv: WebView?, keyCode: Int) {
    if (wv == null) return
    try {
        if (!wv.hasFocus()) wv.requestFocus()
        val now = SystemClock.uptimeMillis()
        wv.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        wv.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    } catch (_: Throwable) {
    }
}

private fun showKeyboard(ctx: Context, wv: WebView?) {
    try {
        wv?.let {
            if (!it.hasFocus()) it.requestFocus()
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(it, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    } catch (_: Throwable) {
    }
}
