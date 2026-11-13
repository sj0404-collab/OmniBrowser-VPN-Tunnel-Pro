package com.winwasm64.ui

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * v4.94.0: отдельная вкладка GeForce NOW — «как есть», без наших надстроек.
 *
 * Вы просили снести всё, что мы накрутили вокруг экрана игры (самодельная клавиатура,
 * синтетические события, виртуальный курсор, подмена диалогов) — это мешало запуску их
 * собственного оверлея. Здесь чистый WebView:
 *
 *  • никаких наших скриптов и вставок в страницу — всё рисует сам клиент GeForce NOW;
 *  • свои cookie и хранилище, клиент живёт пока живёт приложение: переключение вкладок
 *    не сбрасывает сессию и не заставляет логиниться заново;
 *  • HTML-fullscreen поддерживается как есть — оверлей сам просит полный экран;
 *  • поверх — только нужные кнопки: **Esc**, **Tab**, **Enter** и «⌨» (обычная системная
 *    клавиатура, если игра запросила поле ввода).
 */
@Composable
fun GfnScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var fullscreenView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var keyboardOn by remember { mutableStateOf(false) }

    val webView = remember { GfnHolder.obtain(ctx) }

    // Экран вкладки закрыли — сессию не убиваем, просто отцепляем вид и ставим на паузу.
    DisposableEffect(Unit) {
        onDispose {
            try {
                GfnHolder.onHideCustomView()
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.onPause()
            } catch (_: Throwable) {
            }
        }
    }
    LaunchedEffect(Unit) {
        try {
            webView.onResume()
        } catch (_: Throwable) {
        }
    }

    GfnHolder.onShowCustomView = { view, callback ->
        fullscreenView = view
        fullscreenCallback = callback
    }
    GfnHolder.onHideCustomViewCallback = {
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── только нужные клавиши ──
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            KeyChip("Esc") { pressKey(webView, KeyEvent.KEYCODE_ESCAPE) }
            KeyChip("Tab") { pressKey(webView, KeyEvent.KEYCODE_TAB) }
            KeyChip("Enter") { pressKey(webView, KeyEvent.KEYCODE_ENTER) }
            KeyChip(if (keyboardOn) "⌨ Скрыть" else "⌨") {
                keyboardOn = !keyboardOn
                if (keyboardOn) showKeyboard(ctx, webView) else hideKeyboard(ctx, webView)
            }
        }

        // ── полный экран: его просит сам клиент игры ──
        val fs = fullscreenView
        if (fs != null) {
            val host = remember(fs) {
                FrameLayout(ctx).apply { setBackgroundColor(android.graphics.Color.BLACK) }
            }
            LaunchedEffect(fs) {
                try {
                    host.removeAllViews()
                    (fs.parent as? ViewGroup)?.removeView(fs)
                    host.addView(
                        fs,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    fs.isFocusable = true
                    fs.isFocusableInTouchMode = true
                    fs.requestFocus()
                } catch (_: Throwable) {
                }
            }
            AndroidView(factory = { host }, modifier = Modifier.fillMaxSize())
            Row(
                Modifier.align(Alignment.TopEnd).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                KeyChip("Esc") { pressKey(webView, KeyEvent.KEYCODE_ESCAPE) }
                KeyChip("Tab") { pressKey(webView, KeyEvent.KEYCODE_TAB) }
                KeyChip("Enter") { pressKey(webView, KeyEvent.KEYCODE_ENTER) }
                KeyChip("Выйти") { GfnHolder.onHideCustomViewCallback?.invoke() }
            }
        }
    }
}

/**
 * Клиент GeForce NOW живёт, пока живёт приложение.
 * Наши обработчики полного экрана подставляются при показе вкладки.
 */
private object GfnHolder {

    private var webView: WebView? = null

    var onShowCustomView: ((View?, WebChromeClient.CustomViewCallback?) -> Unit)? = null
    var onHideCustomViewCallback: (() -> Unit)? = null

    fun onHideCustomView() {
        onHideCustomViewCallback?.invoke()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun obtain(ctx: android.content.Context): WebView {
        webView?.let { return it }
        val wv = WebView(ctx.applicationContext).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            with(settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                cacheMode = WebSettings.LOAD_DEFAULT
                // GeForce NOW открывает клиент для «настольного» браузера
                userAgentString =
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/126.0.0.0 Safari/537.36"
            }
            isFocusable = true
            isFocusableInTouchMode = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.requestFocus()
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
                    // микрофон (голосовой чат в игре) и остальное — как в обычном браузере
                    request?.grant(request.resources)
                }
            }
            loadUrl("https://play.geforcenow.com/")
        }
        webView = wv
        return wv
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    Surface(
        color = Color(0xCC202124),
        shape = RoundedCornerShape(50),
        modifier = Modifier.height(36.dp).clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 14.dp)
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

private fun showKeyboard(ctx: android.content.Context, wv: WebView?) {
    try {
        wv?.let {
            if (!it.hasFocus()) it.requestFocus()
            val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(it, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    } catch (_: Throwable) {
    }
}

private fun hideKeyboard(ctx: android.content.Context, wv: WebView?) {
    try {
        val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        wv?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
    } catch (_: Throwable) {
    }
}
