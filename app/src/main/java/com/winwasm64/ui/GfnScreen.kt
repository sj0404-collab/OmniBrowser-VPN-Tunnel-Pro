package com.winwasm64.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * v4.96.0: вкладка GeForce NOW.
 *
 * Что показали ваши скриншоты и что исправлено:
 *  1. В режиме «Десктоп» NVIDIA прямо отвечает «Браузер или устройство не поддерживают трансляцию
 *     игр». В режиме Android игра запускается (очередь → подключение → игра). Поэтому Android-UA
 *     теперь режим по умолчанию, а «Десктоп» остался переключателем на всякий случай.
 *  2. Игра не разворачивалась: их полноэкранный вид мы клали в обычную область вкладки, ещё и в
 *     портретной ориентации — отсюда чёрные полосы и «не во весь экран». Теперь при их полном экране
 *     приложение уходит в ландшафт + immersive (без системных панелей), а наша нижняя панель
 *     навигации прячется.
 *  3. Пропадал их оверлей (клавиатура, кнопки): раньше мы ВЫКЛЮЧАЛИ WebView из композиции, когда
 *     показывали полноэкранный вид, — веб-клиент переставал быть частью иерархии и его DOM-оверлей
 *     умирал. Теперь WebView всегда в дереве, а их полноэкранный вид рисуется поверх него.
 *
 * Наших скриптов в страницу не вставляется: всё рисует и обрабатывает сам клиент NVIDIA.
 */
@Composable
fun GfnScreen(modifier: Modifier = Modifier, onImmersiveChange: (Boolean) -> Unit = {}) {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findActivity() }
    // v4.96.0: по умолчанию Android-UA — только в нём NVIDIA пускает в игру
    var desktopUa by remember { mutableStateOf(GfnHolder.desktopUa) }
    var fullscreenView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var statusText by remember { mutableStateOf<String?>("Загрузка клиента GeForce NOW…") }
    // v4.97.0: наша клавиатура (белая), перетаскиваемая и масштабируемая
    var kbOpen by remember { mutableStateOf(false) }

    val host = remember { GfnHolder.container(ctx) }
    val webView = remember { GfnHolder.webView(ctx) }

    GfnHolder.onShowCustomView = { view, cb ->
        fullscreenView = view
        fullscreenCallback = cb
    }
    GfnHolder.onHideCustomViewCallback = { fullscreenView = null }
    GfnHolder.onStatus = { text -> statusText = text }

    val isFull = fullscreenView != null

    // Полный экран игры: ландшафт + без системных панелей + экран не гаснет
    LaunchedEffect(isFull) {
        onImmersiveChange(isFull)
        applyImmersive(activity, isFull)
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                fullscreenCallback?.onCustomViewHidden()
            } catch (_: Throwable) {
            }
            fullscreenCallback = null
            fullscreenView = null
            onImmersiveChange(false)
            applyImmersive(activity, false)
            // v4.99.0: НЕ вызываем onPause — клиент на паузе давал чёрный экран при возврате на вкладку.
            // Просто отцепляем вид: сессия и рендерер остаются живыми.
            try {
                (webView.parent as? ViewGroup)?.removeView(webView)
            } catch (_: Throwable) {
            }
        }
    }

    // v4.99.0: при каждом входе на вкладку приводим вид в чувство (без перезагрузки, если страница жива)
    LaunchedEffect(Unit) { GfnHolder.onAttach(webView) }

    LaunchedEffect(desktopUa) {
        GfnHolder.applyUa(webView, desktopUa)
        if (GfnHolder.pageReady) GfnHolder.reload(webView, hard = true)
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {

        Column(Modifier.fillMaxSize().statusBarsPadding()) {

            if (!isFull) {
                // ── служебная полоса: НАД страницей, не поверх ──
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF101215))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    KeyChip("⟳") {
                        statusText = "Перезагрузка клиента…"
                        GfnHolder.reload(webView, hard = false)
                    }
                    KeyChip("⟳⟳") {
                        statusText = "Полный перезапуск страницы…"
                        GfnHolder.reload(webView, hard = true)
                    }
                    KeyChip(if (desktopUa) "Режим: десктоп" else "Режим: Android") {
                        desktopUa = !desktopUa
                    }
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
            }

            // WebView ВСЕГДА в дереве — их оверлей и сессия не умирают при входе в полный экран
            AndroidView(
                factory = {
                    (host.parent as? ViewGroup)?.removeView(host)
                    host
                },
                modifier = if (isFull) Modifier.fillMaxSize() else Modifier.fillMaxWidth().weight(1f)
            )

            if (!isFull) {
                // ── нужные клавиши: отдельная полоса ПОД страницей ──
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF101215)).padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    KeyChip("Esc") { sendKey(KeyEvent.KEYCODE_ESCAPE, activity, webView) }
                    KeyChip("Tab") { sendKey(KeyEvent.KEYCODE_TAB, activity, webView) }
                    KeyChip("Enter") { sendKey(KeyEvent.KEYCODE_ENTER, activity, webView) }
                    KeyChip(if (kbOpen) "⌨▾" else "⌨") { kbOpen = !kbOpen }
                }
            }
        }

        // ── их собственный полный экран: поверх страницы, страница остаётся живой ──
        if (fullscreenView != null) {
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
                        v.requestLayout()
                    }
                } catch (_: Throwable) {
                }
            }
            AndroidView(factory = { fsBox }, modifier = Modifier.fillMaxSize())

            // маленькие кнопки в углу, чтобы не закрывать их оверлей
            Row(
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KeyChip("Esc") { sendKey(KeyEvent.KEYCODE_ESCAPE, activity, webView) }
                KeyChip("Tab") { sendKey(KeyEvent.KEYCODE_TAB, activity, webView) }
                KeyChip("Enter") { sendKey(KeyEvent.KEYCODE_ENTER, activity, webView) }
                KeyChip(if (kbOpen) "⌨▾" else "⌨") { kbOpen = !kbOpen }
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

        // v4.97.0: наша белая клавиатура — поверх всего; тащится за шапку, масштабируется −/+
        if (kbOpen) {
            GfnKeyboardPanel(activity = activity, webView = webView, onClose = { kbOpen = false })
        }
    }
}

/** Ландшафт + скрытые системные панели на время их полного экрана. */
private fun applyImmersive(act: Activity?, on: Boolean) {
    if (act == null) return
    try {
        val win = act.window
        val ctrl = WindowCompat.getInsetsController(win, win.decorView)
        if (on) {
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            if (act.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            if (act.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            ctrl.show(WindowInsetsCompat.Type.systemBars())
            win.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    } catch (_: Throwable) {
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Клиент GeForce NOW живёт, пока живёт приложение: переключение вкладок не сбрасывает вход.
 */
private object GfnHolder {

    const val URL = "https://play.geforcenow.com/"

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"

    /** v4.96.0: по умолчанию Android-UA — в десктопном NVIDIA отказывает в трансляции. */
    var desktopUa: Boolean = false

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
                // их веб-клиент просит полноэкранный элемент — без этого нет ни игры, ни оверлея
                setGeolocationEnabled(true)
            }
            try {
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            } catch (_: Throwable) {
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    pageReady = true
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
                        pageReady = false
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

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    if (newProgress in 1..99) onStatus?.invoke("Загрузка клиента… $newProgress%")
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

    /** v4.99.0: страница дорисовалась хотя бы раз? */
    var pageReady: Boolean = false

    /**
     * v4.99.0: возврат на вкладку. Раньше мы ставили клиент на паузу при уходе, и после возврата
     * страница оставалась чёрной, а «⟳» ничего не менял. Теперь: будим, чиним раскладку и, если
     * страница так и не догрузилась, загружаем заново.
     */
    fun onAttach(wv: WebView) {
        try {
            wv.onResume()
            wv.resumeTimers()
            wv.visibility = View.VISIBLE
            wv.requestLayout()
            wv.invalidate()
        } catch (_: Throwable) {
        }
        if (!pageReady) reload(wv, hard = false)
    }

    /**
     * v4.99.0: настоящая перезагрузка.
     *  • hard = false — снова открыть клиент (loadUrl, а не reload: reload на «зависшем» рендерере
     *    часто ничего не делает, а вызов loadUrl перезапускает страницу с нуля);
     *  • hard = true  — сначала about:blank, потом клиент: это гарантированно новый рендерер.
     */
    fun reload(wv: WebView, hard: Boolean) {
        pageReady = false
        try {
            wv.stopLoading()
        } catch (_: Throwable) {
        }
        try {
            if (hard) {
                wv.loadUrl("about:blank")
                wv.postDelayed({
                    try {
                        wv.loadUrl(URL)
                    } catch (_: Throwable) {
                    }
                }, 200L)
            } else {
                wv.loadUrl(URL)
            }
            wv.invalidate()
        } catch (_: Throwable) {
        }
    }

    /** UA и Client Hints — всегда согласованно (и строка, и userAgentData). */
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
        color = Color(0xCC202124),
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

