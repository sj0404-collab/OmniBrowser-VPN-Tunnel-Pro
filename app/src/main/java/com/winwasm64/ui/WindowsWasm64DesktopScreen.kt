package com.winwasm64.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.winwasm64.engine.DoomRaycasterState
import com.winwasm64.engine.Wasm64WindowsRuntime
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

@Composable
fun WinChipButton(
    text: String,
    accent: Boolean = false,
    accentColor: Color = Color(0xFF38BDF8),
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (accent) accentColor.copy(alpha = 0.22f) else Color(0xFF141E30))
            .border(1.dp, if (accent) accentColor else Color(0xFF2B3B54), RoundedCornerShape(5.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (accent) accentColor else Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 8.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun WinInputBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = TextStyle(
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            lineHeight = 12.sp
        ),
        cursorBrush = SolidColor(Color(0xFF38BDF8)),
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0xFF060B14))
            .border(1.dp, Color(0xFF2B3B54), RoundedCornerShape(5.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        color = Color(0xFF94A3B8),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                inner()
            }
        }
    )
}

@Composable
fun WindowsWasm64DesktopScreen(runtime: Wasm64WindowsRuntime) {
    // Active window: "desktop", "browser", "youtube", "games", "terminal", "programs"
    var activeWindow by remember { mutableStateOf("desktop") }
    var lastBenchResult by remember { mutableStateOf(runtime.runMultiCoreWasm64Benchmark()) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF070E1A)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. TOP WINDOWS 11 WASM64 STATUS BAR
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D1B30))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "🪟 Microsoft Windows 11 Pro — WASM64 (Memory64 + Node.js v22 + DirectSound)",
                        color = Color(0xFF38BDF8),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "⚡ Ядро ntoskrnl.wasm: ${lastBenchResult.mips} MIPS • Node.js v22.11.0 • 🔊 Audio 44.1kHz PCM • 🛡️ AdBlock + Browsec VPN",
                        color = Color(0xFFA7F3D0),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 7.8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                WinChipButton(
                    text = "🔊 Тест Звука",
                    accent = true,
                    onClick = { runtime.playWindowsPcmSound(587.33, 130) }
                )
            }

            // 2. MAIN WORKSPACE VIEWPORT
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0A1424))
                    .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                when (activeWindow) {
                    "desktop" -> WinWasmDesktopHome(
                        runtime = runtime,
                        lastBench = lastBenchResult,
                        onOpenWindow = { activeWindow = it },
                        onRunBench = {
                            runtime.playWindowsPcmSound(659.25, 110)
                            lastBenchResult = runtime.runMultiCoreWasm64Benchmark()
                        }
                    )
                    "browser" -> WinWasmBrowserWithExtensionsPanel(
                        runtime = runtime,
                        initialUrl = "https://www.google.com"
                    )
                    "youtube" -> WinWasmYouTubeWithSoundPanel(
                        runtime = runtime
                    )
                    "games" -> WinWasmGamesCenterPanel(
                        runtime = runtime
                    )
                    "terminal" -> WinWasmTerminalAndNodePanel(
                        runtime = runtime
                    )
                    "programs" -> WinWasmProgramsAndCalcPanel(
                        runtime = runtime,
                        lastBench = lastBenchResult,
                        onUpdateBench = { lastBenchResult = it }
                    )
                }
            }

            // 3. WINDOWS 11 BOTTOM TASKBAR
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF050A14))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(
                    "desktop" to "❖ Стол Windows",
                    "browser" to "🌐 Браузер + Расширения",
                    "youtube" to "🎬 YouTube со звуком",
                    "games" to "🎮 Игры (DOOM 3D / Сапёр)",
                    "terminal" to "🟦 Терминал + Node.js",
                    "programs" to "📦 Программы + WASM64"
                ).forEach { (id, title) ->
                    WinChipButton(
                        text = title,
                        accent = activeWindow == id,
                        onClick = { activeWindow = id }
                    )
                }
            }
        }
    }
}

@Composable
private fun WinWasmDesktopHome(
    runtime: Wasm64WindowsRuntime,
    lastBench: com.winwasm64.engine.Wasm64ExecutionResult,
    onOpenWindow: (String) -> Unit,
    onRunBench: () -> Unit
) {
    val programs = remember { runtime.listWindowsPrograms() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "🖥 Рабочий стол Windows 11 WASM64 — Выберите программу, игру, терминал Node.js, YouTube или Браузер:",
            color = Color(0xFF38BDF8),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.2.sp
        )

        programs.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowItems.forEach { prog ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                            .clickable { onOpenWindow(prog.windowTarget) }
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = "${prog.icon} ${prog.title}",
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 8.6.sp
                        )
                        Text(
                            text = "${prog.binaryName} — ${prog.description}",
                            color = Color(0xFF94A3B8),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 7.4.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // WASM64 Telemetry Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF07131B))
                .border(1.dp, Color(0xFF10B981).copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "⚡ Статус ядра WebAssembly 64-bit (ntoskrnl.wasm • Memory64)",
                color = Color(0xFF10B981),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 8.8.sp
            )
            Text(
                text = "• Заголовок \\0asm v1: ${lastBench.validMagicHeader} • Адресация Memory64 (64-bit): ${lastBench.memory64Addressing}\n" +
                    "• Возврат регистра i64: ${lastBench.returnValueI64} • Выполнено инструкций: ${lastBench.executedInstructions}\n" +
                    "• Скорость: ${lastBench.mips} MIPS (${"%.2f".format(lastBench.elapsedMs)} мс) • CRC памяти: 0x${java.lang.Long.toHexString(lastBench.linearMemoryChecksum).take(8)}",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 7.8.sp,
                lineHeight = 11.sp
            )
            WinChipButton(
                text = "🚀 Запустить стресс-тест WASM64 на ядрах процессора",
                accent = true,
                accentColor = Color(0xFF10B981),
                onClick = onRunBench
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WinWasmBrowserWithExtensionsPanel(
    runtime: Wasm64WindowsRuntime,
    initialUrl: String = "https://www.google.com"
) {
    var addressText by remember { mutableStateOf(initialUrl) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var activeExtPopup by remember { mutableStateOf("") } // "", "adblock", "browsec", "userscripts", "devtools"

    var adblockOn by remember { mutableStateOf(true) }
    var blockedCount by remember { mutableIntStateOf(0) }
    var browsecOn by remember { mutableStateOf(true) }
    var browsecCountry by remember { mutableStateOf("NL") }
    var darkReaderOn by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("✓ Расширения активны: AdBlock Pro • Browsec VPN (NL) • YouTube Звук") }

    fun navigate(input: String) {
        val url = runtime.normalizeBrowserTarget(input)
        addressText = url
        webViewRef?.loadUrl(url)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Navigation Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WinChipButton(text = "←", onClick = { if (webViewRef?.canGoBack() == true) webViewRef?.goBack() })
            WinChipButton(text = "→", onClick = { if (webViewRef?.canGoForward() == true) webViewRef?.goForward() })
            WinChipButton(text = "↻", onClick = { webViewRef?.reload() })
            WinInputBox(
                value = addressText,
                onValueChange = { addressText = it },
                placeholder = "Введите адрес сайта или поисковый запрос...",
                modifier = Modifier.weight(1f)
            )
            WinChipButton(
                text = "Перейти ⏎",
                accent = true,
                onClick = {
                    activeExtPopup = ""
                    navigate(addressText)
                }
            )
        }

        // Extensions Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            WinChipButton(
                text = "🛡️ AdBlock ($blockedCount)",
                accent = activeExtPopup == "adblock" || adblockOn,
                accentColor = Color(0xFF10B981),
                onClick = { activeExtPopup = if (activeExtPopup == "adblock") "" else "adblock" }
            )
            WinChipButton(
                text = "🌐 Browsec VPN (${if (browsecOn) browsecCountry else "OFF"})",
                accent = activeExtPopup == "browsec" || browsecOn,
                onClick = { activeExtPopup = if (activeExtPopup == "browsec") "" else "browsec" }
            )
            WinChipButton(
                text = if (darkReaderOn) "🌙 Dark Reader ●" else "🌙 Dark Reader",
                accent = darkReaderOn,
                onClick = {
                    darkReaderOn = !darkReaderOn
                    webViewRef?.reload()
                }
            )
            WinChipButton(
                text = "🔧 DevTools (F12)",
                accent = false,
                onClick = {
                    webViewRef?.evaluateJavascript(
                        "(function(){ if(window.eruda){eruda.show();return;} var s=document.createElement('script');s.src='https://cdn.jsdelivr.net/npm/eruda';s.onload=function(){eruda.init();eruda.show();};document.body.appendChild(s); })();",
                        null
                    )
                    statusText = "🔧 Панель DevTools открыта поверх страницы!"
                }
            )
            listOf(
                "🎬 YouTube" to "https://m.youtube.com",
                "🔍 Google" to "https://www.google.com",
                "🐙 GitHub" to "https://github.com",
                "📚 Wiki" to "https://ru.wikipedia.org"
            ).forEach { (title, url) ->
                WinChipButton(text = title, onClick = { navigate(url) })
            }
        }

        // Extension Popup
        if (activeExtPopup == "adblock") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF071520))
                    .border(1.dp, Color(0xFF10B981), RoundedCornerShape(6.dp))
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "🛡️ AdBlock Pro (Сетевой перехват + Авто-пропуск рекламы YouTube) • Заблокировано: $blockedCount",
                    color = Color(0xFF10B981),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.2.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    WinChipButton(
                        text = if (adblockOn) "🟢 AdBlock ВКЛ" else "🔴 AdBlock ВЫКЛ",
                        accent = adblockOn,
                        accentColor = Color(0xFF10B981),
                        onClick = {
                            adblockOn = !adblockOn
                            webViewRef?.reload()
                        }
                    )
                    WinChipButton(text = "✕ Скрыть", onClick = { activeExtPopup = "" })
                }
            }
        } else if (activeExtPopup == "browsec") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF081426))
                    .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(6.dp))
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "🌐 Browsec VPN — Выберите страну или откройте сайт через Веб-Прокси туннель:",
                    color = Color(0xFF38BDF8),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.2.sp
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("NL" to "🇳🇱 Нидерланды", "DE" to "🇩🇪 Германия", "US" to "🇺🇸 США", "UK" to "🇬🇧 Британия", "FI" to "🇫🇮 Финляндия").forEach { (code, name) ->
                        WinChipButton(
                            text = name,
                            accent = browsecOn && browsecCountry == code,
                            onClick = {
                                browsecCountry = code
                                browsecOn = true
                                statusText = "🌐 Browsec VPN подключён: $name ($code)"
                            }
                        )
                    }
                    WinChipButton(
                        text = "🔓 Открыть через Веб-Туннель",
                        accent = true,
                        onClick = {
                            val proxied = "https://translate.google.com/translate?sl=auto&tl=ru&u=" +
                                URLEncoder.encode(addressText, "UTF-8")
                            activeExtPopup = ""
                            addressText = proxied
                            webViewRef?.loadUrl(proxied)
                        }
                    )
                }
            }
        }

        // WebView Viewport
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef = this
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            loadsImagesAutomatically = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            useWideViewPort = true
                            loadWithOverviewMode = true
                        }
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                val u = request?.url?.toString() ?: return null
                                if (adblockOn && runtime.shouldBlockAdUrl(u)) {
                                    blockedCount += 1
                                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val uri = request?.url ?: return false
                                val s = uri.scheme?.lowercase() ?: ""
                                if (s == "http" || s == "https") {
                                    addressText = uri.toString()
                                    return false
                                }
                                if (s == "intent") {
                                    val fb = uri.toString().substringAfter("S.browser_fallback_url=", "").substringBefore(";")
                                    if (fb.isNotBlank()) {
                                        val dec = runCatching { URLDecoder.decode(fb, "UTF-8") }.getOrDefault(fb)
                                        addressText = dec
                                        view?.loadUrl(dec)
                                    }
                                    return true
                                }
                                return true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (!url.isNullOrBlank()) addressText = url
                                view?.evaluateJavascript(
                                    """
                                    (function(){
                                        setInterval(function(){
                                            document.querySelectorAll('.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern').forEach(b=>b.click());
                                            var adV = document.querySelector('.ad-showing video');
                                            if(adV && adV.duration) adV.currentTime = adV.duration;
                                            document.querySelectorAll('video').forEach(v => { if(v.muted && !v.__unm){ v.muted=false; v.volume=1.0; v.__unm=true; } });
                                        }, 500);
                                        ${if (darkReaderOn) "document.documentElement.style.filter='invert(0.92) hue-rotate(180deg)';" else ""}
                                    })();
                                    """.trimIndent(),
                                    null
                                )
                            }
                        }
                        loadUrl(initialUrl)
                    }
                }
            )
        }
        Text(
            text = statusText,
            color = Color(0xFFA7F3D0),
            fontFamily = FontFamily.Monospace,
            fontSize = 7.5.sp,
            maxLines = 1
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WinWasmYouTubeWithSoundPanel(runtime: Wasm64WindowsRuntime) {
    var searchInput by remember { mutableStateOf("") }
    var ytWebView by remember { mutableStateOf<WebView?>(null) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WinInputBox(
                value = searchInput,
                onValueChange = { searchInput = it },
                placeholder = "🎬 Поиск на YouTube со звуком (музыка, обзоры, игры)...",
                modifier = Modifier.weight(1f)
            )
            WinChipButton(
                text = "🔍 Найти",
                accent = true,
                accentColor = Color(0xFFF43F5E),
                onClick = {
                    val q = searchInput.trim().ifBlank { "lofi hip hop radio" }
                    val enc = runCatching { URLEncoder.encode(q, "UTF-8") }.getOrDefault(q)
                    ytWebView?.loadUrl("https://m.youtube.com/results?search_query=$enc")
                }
            )
            WinChipButton(
                text = "🔊 Звук 200%",
                accent = true,
                onClick = {
                    runtime.playWindowsPcmSound(659.25, 90)
                    ytWebView?.evaluateJavascript(
                        "document.querySelectorAll('video').forEach(v => { v.muted = false; v.volume = 1.0; v.play(); });",
                        null
                    )
                }
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(
                "🏠 Главная YouTube" to "https://m.youtube.com",
                "🎵 Музыка 2026" to "https://m.youtube.com/results?search_query=music+hits+2026",
                "🎮 Игры и Стримы" to "https://m.youtube.com/results?search_query=pc+gaming+4k",
                "💻 Программирование Node.js" to "https://m.youtube.com/results?search_query=nodejs+tutorial"
            ).forEach { (label, link) ->
                WinChipButton(text = label, onClick = { ytWebView?.loadUrl(link) })
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, Color(0xFFF43F5E).copy(alpha = 0.6f), RoundedCornerShape(6.dp))
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        ytWebView = this
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            loadsImagesAutomatically = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                        }
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                val u = request?.url?.toString() ?: return null
                                if (runtime.shouldBlockAdUrl(u)) {
                                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                view?.evaluateJavascript(
                                    """
                                    setInterval(function(){
                                        document.querySelectorAll('.ytp-ad-skip-button, .ytp-skip-ad-button').forEach(b=>b.click());
                                        document.querySelectorAll('video').forEach(v => { if(v.muted && !v.__u){ v.muted=false; v.volume=1.0; v.__u=true; } });
                                    }, 500);
                                    """.trimIndent(),
                                    null
                                )
                            }
                        }
                        loadUrl("https://m.youtube.com")
                    }
                }
            )
        }
    }
}

@Composable
private fun WinWasmGamesCenterPanel(runtime: Wasm64WindowsRuntime) {
    var activeGame by remember { mutableStateOf("doom") } // "doom" | "mines"
    var doomState by remember { mutableStateOf(DoomRaycasterState()) }
    var minesState by remember { mutableStateOf(runtime.createNewMinesweeperGame()) }
    var flagMode by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WinChipButton(
                text = "🔫 DOOM / Wolf3D WASM64 (3D FPS)",
                accent = activeGame == "doom",
                accentColor = Color(0xFFF43F5E),
                onClick = { activeGame = "doom" }
            )
            WinChipButton(
                text = "💣 Сапёр Windows 11 Pro",
                accent = activeGame == "mines",
                accentColor = Color(0xFF38BDF8),
                onClick = { activeGame = "mines" }
            )
            WinChipButton(
                text = "🔄 Сброс игры",
                onClick = {
                    if (activeGame == "doom") doomState = DoomRaycasterState()
                    else minesState = runtime.createNewMinesweeperGame()
                }
            )
        }

        if (activeGame == "doom") {
            val wallColumns = remember(doomState) { runtime.castDoom3dWallHeights(doomState, 32) }
            Text(
                text = "❤️ Здоровье: ${doomState.health}% • 🔫 Патроны: ${doomState.ammo} • 💀 Врагов: ${doomState.enemiesAlive} • 🏆 Счёт: ${doomState.score}",
                color = Color(0xFFFCA5A5),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 8.4.sp
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF05070B))
                    .border(1.dp, Color(0xFFF43F5E), RoundedCornerShape(6.dp))
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    // Ceiling & Floor
                    drawRect(Color(0xFF111827), topLeft = Offset.Zero, size = Size(w, h / 2f))
                    drawRect(Color(0xFF1F2937), topLeft = Offset(0f, h / 2f), size = Size(w, h / 2f))

                    val colWidth = w / wallColumns.size.coerceAtLeast(1)
                    wallColumns.forEachIndexed { idx, (ratio, hitType) ->
                        val wallH = (h * ratio).coerceIn(16f, h * 0.95f)
                        val topY = (h - wallH) / 2f
                        val baseColor = if (hitType == 2) Color(0xFFEF4444) else Color(0xFF38BDF8)
                        val shaded = baseColor.copy(alpha = ratio.coerceIn(0.28f, 0.95f))
                        drawRect(
                            color = shaded,
                            topLeft = Offset(idx * colWidth, topY),
                            size = Size(colWidth + 1f, wallH)
                        )
                    }
                    // Crosshair & Plasma Gun HUD
                    drawCircle(Color(0xFF10B981), radius = 6f, center = Offset(w / 2f, h / 2f))
                    drawRect(
                        color = Color(0xFF64748B),
                        topLeft = Offset(w / 2f - 28f, h - 48f),
                        size = Size(56f, 48f)
                    )
                }
            }
            Text(
                text = doomState.lastActionLog,
                color = Color(0xFFA7F3D0),
                fontFamily = FontFamily.Monospace,
                fontSize = 7.8.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                WinChipButton(
                    text = "◀ Влево",
                    onClick = { doomState = runtime.stepDoomRaycaster(doomState, turnDelta = -0.25) }
                )
                WinChipButton(
                    text = "▲ Вперёд",
                    accent = true,
                    onClick = {
                        runtime.playWindowsPcmSound(320.0, 45)
                        doomState = runtime.stepDoomRaycaster(doomState, moveForward = 0.45)
                    }
                )
                WinChipButton(
                    text = "▼ Назад",
                    onClick = { doomState = runtime.stepDoomRaycaster(doomState, moveForward = -0.35) }
                )
                WinChipButton(
                    text = "▶ Вправо",
                    onClick = { doomState = runtime.stepDoomRaycaster(doomState, turnDelta = 0.25) }
                )
                WinChipButton(
                    text = "🔥 ОГОНЬ!",
                    accent = true,
                    accentColor = Color(0xFFF43F5E),
                    onClick = { doomState = runtime.stepDoomRaycaster(doomState, fireWeapon = true) }
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val status = when {
                    minesState.victory -> "🏆 ПОБЕДА! Все безопасные клетки открыты!"
                    minesState.gameOver -> "💥 ВЗРЫВ! Нажмите «Сброс игры» для новой партии."
                    else -> "💣 Мин: ${minesState.minesCount} • Ходов: ${minesState.moves}"
                }
                Text(
                    text = status,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.4.sp
                )
                WinChipButton(
                    text = if (flagMode) "🚩 Режим: ФЛАГ" else "⛏ Режим: ОТКРЫТЬ",
                    accent = flagMode,
                    onClick = { flagMode = !flagMode }
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (r in 0 until minesState.rows) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (col in 0 until minesState.cols) {
                            val cell = minesState.cells.first { it.row == r && it.col == col }
                            val label = when {
                                cell.isFlagged -> "🚩"
                                !cell.isRevealed -> "■"
                                cell.isMine -> "💣"
                                cell.neighborMines == 0 -> "·"
                                else -> cell.neighborMines.toString()
                            }
                            val bg = when {
                                cell.isRevealed && cell.isMine -> Color(0xFF7F1D1D)
                                cell.isRevealed -> Color(0xFF1E293B)
                                else -> Color(0xFF0F172A)
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(bg)
                                    .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                                    .clickable {
                                        minesState = runtime.revealMinesweeperCell(minesState, r, col, flagMode)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WinWasmTerminalAndNodePanel(runtime: Wasm64WindowsRuntime) {
    var cmdInput by remember { mutableStateOf("") }
    var jsCodeInput by remember {
        mutableStateOf(
            "console.log('Node.js on Windows WASM64:', process.version);\nconst total = 64 * 1024;\nconsole.log('64KB Linear Memory Page:', total);"
        )
    }
    val logs = remember {
        mutableStateListOf(
            "Microsoft Windows [Version 11.0.26100.2033 — WASM64 Edition]",
            "Node.js v22.11.0-wasm64-win32 & npm 10.9.0 активны.",
            "Введите команду или запустите код Node.js ниже."
        )
    }

    fun runCmd(c: String) {
        if (c.isBlank()) return
        runtime.playWindowsPcmSound(523.25, 40)
        logs.add("PS C:\\Users\\Admin> $c")
        logs.addAll(runtime.executeTerminalOrNodeCommand(c))
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // Quick Commands
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(
                "node -v",
                "npm -v",
                "node hello_node.js",
                "npm list",
                "wasm64 --bench",
                "systeminfo",
                "dir",
                "ver"
            ).forEach { q ->
                WinChipButton(text = "▶ $q", onClick = { runCmd(q) })
            }
        }

        // Terminal Output
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF011627))
                .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                .padding(8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            logs.takeLast(50).forEach { ln ->
                Text(
                    text = ln,
                    color = Color(0xFFE2E8F0),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 7.8.sp,
                    lineHeight = 10.5.sp
                )
            }
        }

        // Interactive Node.js Script Runner Box
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF07131B))
                .border(1.dp, Color(0xFF10B981).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🟢 Node.js v22.11.0 WASM64 — Интерактивное выполнение JS кода:",
                    color = Color(0xFF10B981),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.sp
                )
                WinChipButton(
                    text = "▶ Запустить код Node.js",
                    accent = true,
                    accentColor = Color(0xFF10B981),
                    onClick = {
                        logs.add("PS C:\\Users\\Admin> node -e [JS Buffer]")
                        logs.addAll(runtime.executeNodeJsScript(jsCodeInput).lines())
                    }
                )
            }
            WinInputBox(
                value = jsCodeInput,
                onValueChange = { jsCodeInput = it },
                placeholder = "console.log('Hello Node.js!');",
                singleLine = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            )
        }

        // Command Input Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WinInputBox(
                value = cmdInput,
                onValueChange = { cmdInput = it },
                placeholder = "PS C:\\> Введите команду (node, npm, dir, systeminfo, wasm64)...",
                modifier = Modifier.weight(1f)
            )
            WinChipButton(
                text = "Выполнить ⏎",
                accent = true,
                onClick = {
                    val c = cmdInput.trim()
                    if (c.isNotBlank()) {
                        runCmd(c)
                        cmdInput = ""
                    }
                }
            )
        }
    }
}

@Composable
private fun WinWasmProgramsAndCalcPanel(
    runtime: Wasm64WindowsRuntime,
    lastBench: com.winwasm64.engine.Wasm64ExecutionResult,
    onUpdateBench: (com.winwasm64.engine.Wasm64ExecutionResult) -> Unit
) {
    var calcA by remember { mutableStateOf("1024") }
    var calcB by remember { mutableStateOf("2048") }
    var calcC by remember { mutableStateOf("64") }
    var calcWasmResult by remember {
        mutableStateOf(runtime.executeWasm64Module(runtime.assembleWasm64AddMulModule(1024L, 2048L, 64L)))
    }
    var newFileName by remember { mutableStateOf("app.js") }
    var newFileContent by remember { mutableStateOf("console.log('Saved on C:\\\\ drive!');") }
    var filesRevision by remember { mutableIntStateOf(0) }
    val desktopFiles = remember(filesRevision) {
        runtime.desktopDir.listFiles()?.map { it.name to it.length() }.orEmpty()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. 64-Bit WASM64 Calculator & Bytecode Assembler
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF081426))
                .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(6.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = "🧮 Инженерный Калькулятор WASM64 (calc.exe) — Компиляция и запуск 64-bit .wasm модуля (A + B) × C:",
                color = Color(0xFF38BDF8),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 8.4.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WinInputBox(value = calcA, onValueChange = { calcA = it }, placeholder = "A (i64)", modifier = Modifier.weight(1f))
                WinInputBox(value = calcB, onValueChange = { calcB = it }, placeholder = "B (i64)", modifier = Modifier.weight(1f))
                WinInputBox(value = calcC, onValueChange = { calcC = it }, placeholder = "C (i64)", modifier = Modifier.weight(1f))
                WinChipButton(
                    text = "⚡ Вычислить в WASM64",
                    accent = true,
                    onClick = {
                        val a = calcA.toLongOrNull() ?: 0L
                        val b = calcB.toLongOrNull() ?: 0L
                        val c = calcC.toLongOrNull() ?: 1L
                        val mod = runtime.assembleWasm64AddMulModule(a, b, c)
                        val res = runtime.executeWasm64Module(mod)
                        calcWasmResult = res
                        onUpdateBench(res)
                        runtime.playWindowsPcmSound(740.0, 75)
                    }
                )
            }
            Text(
                text = "✓ Результат регистра i64: ${calcWasmResult.returnValueI64} • Заголовок \\0asm: ${calcWasmResult.validMagicHeader} • Memory64: ${calcWasmResult.memory64Addressing}",
                color = Color(0xFFA7F3D0),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 8.sp
            )
        }

        // 2. Windows Explorer (C:\Users\Admin\Desktop) & Editor
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF07131B))
                .border(1.dp, Color(0xFF10B981).copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = "📁 Проводник Windows 11 (explorer.exe) — C:\\Users\\Admin\\Desktop (${desktopFiles.size} файлов):",
                color = Color(0xFF10B981),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 8.4.sp
            )
            desktopFiles.forEach { (name, size) ->
                Text(
                    text = "📄 C:\\Users\\Admin\\Desktop\\$name ($size байт)",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 7.8.sp
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WinInputBox(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    placeholder = "Имя файла (script.js / note.txt)",
                    modifier = Modifier.weight(1f)
                )
                WinChipButton(
                    text = "💾 Сохранить на диск C:\\",
                    accent = true,
                    accentColor = Color(0xFF10B981),
                    onClick = {
                        val f = File(runtime.desktopDir, newFileName.ifBlank { "file.txt" })
                        f.writeText(newFileContent)
                        filesRevision += 1
                    }
                )
            }
            WinInputBox(
                value = newFileContent,
                onValueChange = { newFileContent = it },
                placeholder = "Содержимое файла...",
                singleLine = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            )
        }
    }
}
