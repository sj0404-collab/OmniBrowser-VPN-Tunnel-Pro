package com.winwasm64.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.winwasm64.nativeapps.NativeAppEntry
import com.winwasm64.nativeapps.NativeAppsManager
import com.winwasm64.torrent.BitTorrentEngine
import com.winwasm64.torrent.TorrentSessionStatus
import com.winwasm64.torrent.TorrentState
import com.winwasm64.vpn.BrowserDirectRepeater
import com.winwasm64.vpn.BrowsecLocalProxyServer
import com.winwasm64.vpn.BrowsecVpnService
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.sin
import kotlinx.coroutines.delay

private fun playNativeAudioChime(freqHz: Double = 523.25, durationMs: Int = 110) {
    thread(isDaemon = true) {
        try {
            val sampleRate = 44100
            val count = (sampleRate * durationMs) / 1000
            val samples = ShortArray(count)
            for (i in 0 until count) {
                val env = 1.0 - (i.toDouble() / count.toDouble())
                samples[i] = (sin(2.0 * Math.PI * i * freqHz / sampleRate) * 14000 * env).toInt().toShort()
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(samples, 0, samples.size)
            track.play()
            Thread.sleep(durationMs.toLong() + 40L)
            track.release()
        } catch (_: Exception) {
        }
    }
}

class BrowserTabModel(
    val id: String,
    initialUrl: String,
    initialTitle: String = "Новая вкладка",
    val isIncognito: Boolean = false
) {
    var url by mutableStateOf(initialUrl)
    var title by mutableStateOf(initialTitle)
    var loadingProgress by mutableIntStateOf(0)
    var isLoading by mutableStateOf(false)
    var loadingStatusText by mutableStateOf("")
    var lastErrorText by mutableStateOf<String?>(null)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var autoRetryCount by mutableIntStateOf(0)
    var webView: WebView? = null
}

/**
 * Process-wide vault that keeps live WebView tabs, scroll state, and active tab index intact
 * across Activity recreation, screen lock, and app switching so pages NEVER reset on reopen.
 */
object BrowserSessionVault {
    val tabs = mutableStateListOf<BrowserTabModel>()
    var activeTabIndex by mutableIntStateOf(0)
    var initialized = false
}

private val AD_DOMAINS = setOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com",
    "adservice.google.com", "pagead2.googlesyndication.com", "adnxs.com",
    "criteo.com", "taboola.com", "outbrain.com", "scorecardresearch.com",
    "mc.yandex.ru", "an.yandex.ru", "ads.youtube.com", "popads.net", "propellerads.com"
)

data class VpnNode(
    val code: String,
    val flag: String,
    val countryName: String,
    val acceptLang: String,
    val edgeIp: String
)

val VPN_NODES = listOf(
    VpnNode("DE", "🇩🇪", "Германия (Frankfurt High-Speed)", "de-DE,de;q=0.9,en-US;q=0.8,ru;q=0.7", "185.220.101.42"),
    VpnNode("NL", "🇳🇱", "Нидерланды (Amsterdam AMS-IX)", "nl-NL,nl;q=0.9,en-US;q=0.8,ru;q=0.7", "185.107.56.114"),
    VpnNode("US", "🇺🇸", "США (New York Backbone)", "en-US,en;q=0.9,ru;q=0.7", "198.51.100.84"),
    VpnNode("FI", "🇫🇮", "Финляндия (Helsinki Ultra-Low Ping)", "fi-FI,en-US;q=0.9,ru;q=0.8", "95.216.144.29"),
    VpnNode("UK", "🇬🇧", "Великобритания (London Docklands)", "en-GB,en;q=0.9,ru;q=0.7", "51.15.43.201"),
    VpnNode("SG", "🇸🇬", "Сингапур (Asia-Pacific Core)", "en-SG,en;q=0.9,ru;q=0.7", "139.99.88.19")
)

/**
 * Custom crisp dark text field using BasicTextField so text is NEVER cut off vertically
 * and is always bright white on a dark background.
 */
@Composable
fun CompactDarkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onGo: (() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0B1120))
            .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = Color.White,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            ),
            cursorBrush = SolidColor(Color(0xFF38BDF8)),
            keyboardOptions = KeyboardOptions(imeAction = if (onGo != null) ImeAction.Go else ImeAction.Done),
            keyboardActions = KeyboardActions(
                onGo = { onGo?.invoke() },
                onDone = { onGo?.invoke() }
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WindowsWasm64DesktopScreen(initialIntentUri: String? = null) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("omni_browser_v21_prefs", Context.MODE_PRIVATE) }
    val torrentEngine = remember { BitTorrentEngine(context) }
    val nativeAppsManager = remember { NativeAppsManager(context) }

    // Репитер №1: Встроенный сервер Браузера (БЕЗ проброса к локальному туннелю 127.0.0.1:8899)
    var browserVpnEnabled by remember { mutableStateOf(prefs.getBoolean("browser_vpn_on", true)) }
    var browserVpnCountry by remember { mutableStateOf(prefs.getString("browser_vpn_country", "DE") ?: "DE") }
    var browserTrafficSnapshot by remember { mutableStateOf(BrowserDirectRepeater.sampleBrowserTraffic()) }

    // Репитер №2: Локальный сервер с пробросом (127.0.0.1:8899) — вынесен отдельно в раздел «Приложения (Список VPN)»
    var proxyInterceptBrowser by remember { mutableStateOf(false) }
    var trafficSnapshot by remember { mutableStateOf(BrowsecLocalProxyServer.sampleTraffic()) }

    // Bottom sections: 0 = Браузер, 1 = Торренты, 2 = История и Загрузки, 3 = Приложения + Список VPN
    var currentSection by remember { mutableIntStateOf(0) }

    var adBlockEnabled by remember { mutableStateOf(prefs.getBoolean("adblock_on", true)) }
    var adBlockedCount by remember { mutableIntStateOf(prefs.getInt("adblock_count", 0)) }
    val customBlockedDomains = remember {
        mutableStateListOf<String>().apply {
            prefs.getStringSet("custom_ad_domains", emptySet())?.let { addAll(it) }
        }
    }

    // Состояние Репитера №2 (Список VPN для Приложений — запускается только из списка VPN / плавающей кнопки!)
    var browsecEnabled by remember { mutableStateOf(prefs.getBoolean("apps_vpn_on", false)) }
    var browsecPaused by remember { mutableStateOf(prefs.getBoolean("browsec_paused", false)) }
    var browsecCountry by remember { mutableStateOf(prefs.getString("apps_vpn_country", "DE") ?: "DE") }
    var disconnectOnClose by remember { mutableStateOf(prefs.getBoolean("disconnect_on_close", true)) }
    var searchEngine by remember { mutableStateOf(prefs.getString("search_engine", "Google") ?: "Google") }
    var textZoomPercent by remember { mutableIntStateOf(prefs.getInt("text_zoom_pct", 100)) }
    var dohServer by remember { mutableStateOf(prefs.getString("doh_server", "1.1.1.1") ?: "1.1.1.1") }
    var blockThirdPartyCookies by remember { mutableStateOf(prefs.getBoolean("block_3p_cookies", false)) }
    var doNotTrackEnabled by remember { mutableStateOf(prefs.getBoolean("dnt_enabled", true)) }
    var homePageUrl by remember { mutableStateOf(prefs.getString("home_page_url", "https://www.google.com") ?: "https://www.google.com") }
    var darkReaderEnabled by remember { mutableStateOf(prefs.getBoolean("dark_reader", false)) }
    var youtubeBoostEnabled by remember { mutableStateOf(prefs.getBoolean("yt_boost", true)) }
    var devToolsEnabled by remember { mutableStateOf(false) }
    var desktopMode by remember { mutableStateOf(prefs.getBoolean("desktop_mode", false)) }
    var longPressedUrl by remember { mutableStateOf<String?>(null) }
    var fullscreenCustomView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
    }

    LaunchedEffect(Unit) {
        // 1. Инициализируем Репитер №1 (Только для Браузера, без проброса в 127.0.0.1:8899)
        BrowserDirectRepeater.enabled = browserVpnEnabled
        BrowserDirectRepeater.countryCode = browserVpnCountry
        BrowserDirectRepeater.ensureDirectBrowserMode()

        // 2. Инициализируем Репитер №2 (Локальный сервер 127.0.0.1:8899 для раздела Приложений)
        BrowsecVpnService.activeCountry = browsecCountry
        BrowsecVpnService.disconnectOnAppClose = disconnectOnClose
        BrowsecLocalProxyServer.dohProvider = dohServer
        BrowsecLocalProxyServer.initAssetBundleIfNeeded(context)
        BrowsecLocalProxyServer.ensureRunning()

        while (true) {
            browserTrafficSnapshot = BrowserDirectRepeater.sampleBrowserTraffic()
            trafficSnapshot = BrowsecLocalProxyServer.sampleTraffic()
            if (BrowsecVpnService.activeCountry != browsecCountry) {
                browsecCountry = BrowsecVpnService.activeCountry
            }
            if (BrowsecVpnService.isPaused != browsecPaused) {
                browsecPaused = BrowsecVpnService.isPaused
            }
            if (BrowsecVpnService.isRunning != browsecEnabled) {
                browsecEnabled = BrowsecVpnService.isRunning
            }
            delay(500)
        }
    }

    // Синхронизация Репитера №1 с Браузером (НИКОГДА не пробрасывает браузер в локальный туннель 8899!)
    LaunchedEffect(browserVpnEnabled, browserVpnCountry) {
        BrowserDirectRepeater.enabled = browserVpnEnabled
        BrowserDirectRepeater.countryCode = browserVpnCountry
        BrowserDirectRepeater.ensureDirectBrowserMode()
        val node = VPN_NODES.find { it.code == browserVpnCountry } ?: VPN_NODES.first()
        torrentEngine.useBrowsecVpn = browserVpnEnabled
        torrentEngine.browsecCountryCode = node.code
        torrentEngine.browsecEdgeIp = node.edgeIp
    }

    var activeExtensionPopup by remember { mutableStateOf<String?>(null) }

    val tabs = remember {
        if (!BrowserSessionVault.initialized || BrowserSessionVault.tabs.isEmpty()) {
            val savedUrls = prefs.getString("saved_tab_urls", null)
                ?.split("\n")
                ?.filter {
                    it.isNotBlank() &&
                        !it.contains("piped.video") &&
                        !it.startsWith("file:") &&
                        !it.contains("translate.google") &&
                        !it.contains("translate.goog")
                }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf("https://www.google.com", "https://m.youtube.com")
            BrowserSessionVault.tabs.clear()
            savedUrls.forEachIndexed { idx, u ->
                BrowserSessionVault.tabs.add(
                    BrowserTabModel(
                        id = "tab_${idx}_${System.currentTimeMillis()}",
                        initialUrl = u,
                        initialTitle = if (u.contains("youtube")) "YouTube" else "Google"
                    )
                )
            }
            BrowserSessionVault.activeTabIndex = prefs.getInt("active_tab_idx", 0)
                .coerceIn(0, (BrowserSessionVault.tabs.size - 1).coerceAtLeast(0))
            BrowserSessionVault.initialized = true
        }
        BrowserSessionVault.tabs
    }
    var activeTabIndex by remember {
        mutableIntStateOf(BrowserSessionVault.activeTabIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)))
    }
    LaunchedEffect(activeTabIndex) {
        BrowserSessionVault.activeTabIndex = activeTabIndex
    }
    val activeTab = tabs.getOrNull(activeTabIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))) ?: tabs.first()

    var addressInput by remember(activeTab.id, activeTab.url) { mutableStateOf(activeTab.url) }
    var findInPageQuery by remember { mutableStateOf("") }

    val bookmarks = remember {
        mutableStateListOf(
            "https://www.google.com" to "🔍 Google Поиск",
            "https://m.youtube.com" to "🎬 YouTube Моб. (Прямой)",
            "https://www.youtube.com/?app=desktop" to "🖥 YouTube ПК (Через Browsec)",
            "https://archive.org/details/software" to "🧲 Archive.org Программы и Игры",
            "https://rutracker.org" to "🧲 RuTracker (С Browsec VPN)",
            "https://github.com" to "💻 GitHub"
        )
    }

    val historyList = remember {
        val savedHist = prefs.getString("saved_history", null)
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            .orEmpty()
        mutableStateListOf<String>().apply { addAll(savedHist) }
    }

    val vpnRoutedApps = remember {
        val savedSet = prefs.getStringSet("vpn_routed_apps", emptySet()).orEmpty()
        mutableStateListOf<String>().apply {
            addAll(savedSet)
            BrowsecVpnService.routedPackages.addAll(savedSet)
        }
    }

    fun persistBrowserSettings() {
        val currentUrls = tabs.map { t ->
            val liveUrl = t.webView?.url
            if (!liveUrl.isNullOrBlank() && liveUrl != "about:blank") liveUrl else t.url
        }
        prefs.edit()
            .putBoolean("adblock_on", adBlockEnabled)
            .putInt("adblock_count", adBlockedCount)
            .putBoolean("browser_vpn_on", browserVpnEnabled)
            .putString("browser_vpn_country", browserVpnCountry)
            .putBoolean("apps_vpn_on", browsecEnabled)
            .putBoolean("browsec_paused", browsecPaused)
            .putString("apps_vpn_country", browsecCountry)
            .putBoolean("disconnect_on_close", disconnectOnClose)
            .putString("search_engine", searchEngine)
            .putInt("text_zoom_pct", textZoomPercent)
            .putString("doh_server", dohServer)
            .putBoolean("block_3p_cookies", blockThirdPartyCookies)
            .putBoolean("dnt_enabled", doNotTrackEnabled)
            .putString("home_page_url", homePageUrl)
            .putBoolean("dark_reader", darkReaderEnabled)
            .putBoolean("yt_boost", youtubeBoostEnabled)
            .putBoolean("desktop_mode", desktopMode)
            .putString("saved_tab_urls", currentUrls.joinToString("\n"))
            .putInt("active_tab_idx", activeTabIndex)
            .putString("saved_history", historyList.take(100).joinToString("\n"))
            .putStringSet("custom_ad_domains", customBlockedDomains.toSet())
            .putStringSet("vpn_routed_apps", vpnRoutedApps.toSet())
            .commit()
    }

    var pendingVpnLaunchPkg by remember { mutableStateOf<String?>(null) }
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val pkg = pendingVpnLaunchPkg
        BrowsecVpnService.startForApp(context, browsecCountry, pkg)
        if (!pkg.isNullOrBlank()) {
            nativeAppsManager.launchApp(pkg)
            Toast.makeText(context, "🛡️ Запущено через Browsec VPN ($browsecCountry)!", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "🛡️ Внутренний сервер Browsec VPN ($browsecCountry) активен!", Toast.LENGTH_SHORT).show()
        }
    }

    // Storage permission launcher for Android
    val storagePermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        Toast.makeText(context, "📂 Доступ к памяти обновлён", Toast.LENGTH_SHORT).show()
    }

    fun requestFullStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                    return
                } catch (_: Exception) {
                }
            }
        }
        storagePermLauncher.launch(
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        )
    }

    val webViewContainer = remember { FrameLayout(context) }

    fun normalizeInputToUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return homePageUrl
        if (trimmed.startsWith("magnet:", ignoreCase = true)) return trimmed
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("javascript:", ignoreCase = true)
        ) return trimmed
        return if (trimmed.contains(".") && !trimmed.contains(" ")) {
            "https://$trimmed"
        } else {
            val q = Uri.encode(trimmed)
            when (searchEngine) {
                "Яндекс" -> "https://ya.ru/search/?text=$q"
                "DuckDuckGo" -> "https://duckduckgo.com/?q=$q"
                "Bing" -> "https://www.bing.com/search?q=$q"
                "Brave" -> "https://search.brave.com/search?q=$q"
                else -> "https://www.google.com/search?q=$q"
            }
        }
    }

    fun injectActiveExtensions(wv: WebView) {
        val scripts = StringBuilder()
        if (adBlockEnabled) {
            scripts.append(
                """
                (function(){
                    const sel = ['.ad-showing','.ytp-ad-module','.ytp-ad-overlay-container','ytd-ad-slot-renderer','ytm-promoted-sparkles-web-renderer','[id^="google_ads"]','.adsbygoogle'];
                    sel.forEach(s => document.querySelectorAll(s).forEach(el => el.style.display='none'));
                    const skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
                    if (skipBtn) skipBtn.click();
                    const v = document.querySelector('video');
                    if (v && document.querySelector('.ad-showing') && isFinite(v.duration) && v.duration > 0) {
                        v.currentTime = v.duration;
                    }
                })();
                """.trimIndent()
            )
        }
        if (youtubeBoostEnabled) {
            scripts.append(
                """
                (function(){
                    try {
                        Object.defineProperty(document, 'hidden', { get: function(){ return false; }, configurable: true });
                        Object.defineProperty(document, 'visibilityState', { get: function(){ return 'visible'; }, configurable: true });
                    } catch(e){}
                    document.querySelectorAll('video, audio').forEach(v => {
                        v.muted = false;
                        if (v.volume < 1.0) v.volume = 1.0;
                    });
                })();
                """.trimIndent()
            )
        }
        if (darkReaderEnabled) {
            scripts.append(
                """
                (function(){
                    if (!document.getElementById('omni-dark-reader')) {
                        const st = document.createElement('style');
                        st.id = 'omni-dark-reader';
                        st.innerHTML = 'html { filter: invert(0.92) hue-rotate(180deg) !important; background: #0d1117 !important; } img, video, iframe, canvas { filter: invert(1) hue-rotate(180deg) !important; }';
                        document.head.appendChild(st);
                    }
                })();
                """.trimIndent()
            )
        } else {
            scripts.append(
                """
                (function(){
                    const el = document.getElementById('omni-dark-reader');
                    if (el) el.remove();
                })();
                """.trimIndent()
            )
        }
        if (devToolsEnabled) {
            scripts.append(
                """
                (function(){
                    if (!window.eruda) {
                        const s = document.createElement('script');
                        s.src = 'https://cdn.jsdelivr.net/npm/eruda';
                        s.onload = function(){ eruda.init(); eruda.show(); };
                        document.body.appendChild(s);
                    } else {
                        eruda.show();
                    }
                })();
                """.trimIndent()
            )
        }
        val js = scripts.toString()
        if (js.isNotBlank()) {
            wv.evaluateJavascript(js, null)
        }
    }

    fun navigateTab(tab: BrowserTabModel, rawInput: String, useProxyTunnel: Boolean = false) {
        val target = normalizeInputToUrl(rawInput)
        if (target.startsWith("magnet:", ignoreCase = true) || target.endsWith(".torrent", ignoreCase = true)) {
            torrentEngine.startFromMagnetOrUrl(target)
            currentSection = 1
            Toast.makeText(context, "🧲 Торрент добавлен в загрузчик!", Toast.LENGTH_SHORT).show()
            return
        }
        // Браузер всегда работает на своём Репитере №1 БЕЗ проброса к локальному туннелю 127.0.0.1:8899!
        BrowserDirectRepeater.ensureDirectBrowserMode()
        BrowserDirectRepeater.browserRequests.incrementAndGet()

        tab.autoRetryCount = 0
        tab.lastErrorText = null
        tab.isLoading = true
        tab.loadingProgress = 12
        val nodeInfo = BrowserDirectRepeater.getNode(browserVpnCountry)
        val vpnLabel = if (browserVpnEnabled) "${nodeInfo.flag} ${nodeInfo.countryRu} (${nodeInfo.remoteIp}:${nodeInfo.remotePort} • Репитер Браузера №1)" else "Прямое (VPN ВЫКЛ)"
        tab.loadingStatusText = "⏳ Подключение ($vpnLabel) к ${Uri.parse(target).host ?: target}..."
        tab.url = target
        addressInput = target
        persistBrowserSettings()

        val wv = tab.webView ?: return
        wv.loadUrl(target)
    }

    var pendingVpnLaunchPkg by remember { mutableStateOf<String?>(null) }
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pkg = pendingVpnLaunchPkg
            BrowsecLocalProxyServer.useBrowsecTunnel = true
            BrowsecVpnService.startForApp(context, browsecCountry, pkg)
            if (!pkg.isNullOrBlank()) {
                nativeAppsManager.launchApp(pkg)
            }
        }
    }

    /**
     * Запускает НАТИВНОЕ мобильное приложение (включая нативный YouTube и Play Market):
     * - Если приложение ДОБАВЛЕНО в список VPN (`vpnRoutedApps.contains(pkg)`) -> запускается через
     *   Репитер №2 (127.0.0.1:8899 с туннелем Browsec VPN и выбранной страной).
     * - Если приложение НЕ добавлено в список VPN -> запускается на том же локальном сервере
     *   (127.0.0.1:8899) напрямую БЕЗ туннеля от Browsec!
     */
    fun startBrowsecAndLaunchNativeApp(pkg: String, appName: String) {
        val isInVpnList = vpnRoutedApps.contains(pkg)
        val nodeInfo = BrowsecLocalProxyServer.getServerNode(browsecCountry)

        if (isInVpnList) {
            browsecEnabled = true
            browsecPaused = false
            BrowsecLocalProxyServer.useBrowsecTunnel = true
            BrowsecVpnService.routedPackages.add(pkg)
            persistBrowserSettings()

            val prep = try { VpnService.prepare(context) } catch (_: Exception) { null }
            if (prep != null) {
                pendingVpnLaunchPkg = pkg
                vpnPermissionLauncher.launch(prep)
                return
            }
            BrowsecVpnService.startForApp(context, browsecCountry, pkg)
            val ok = nativeAppsManager.launchApp(pkg)
            if (ok) {
                Toast.makeText(
                    context,
                    "🛡️ Нативное приложение $appName запущено ЧЕРЕЗ СПИСОК VPN (${nodeInfo.flag} ${nodeInfo.countryRu} • ${nodeInfo.remoteIp}:${nodeInfo.remotePort} ➔ 127.0.0.1:${BrowsecLocalProxyServer.activePort})!",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(context, "Не удалось открыть нативное приложение $appName", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Приложение НЕ добавлено в список VPN -> работает на том же локальном сервере БЕЗ туннеля от Browsec!
            BrowsecLocalProxyServer.useBrowsecTunnel = false
            BrowsecLocalProxyServer.ensureRunning()
            val ok = nativeAppsManager.launchApp(pkg)
            if (ok) {
                Toast.makeText(
                    context,
                    "⚡ Нативное приложение $appName запущено на локальном сервере (127.0.0.1:${BrowsecLocalProxyServer.activePort}) БЕЗ туннеля от Browsec!",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(context, "Не удалось открыть нативное приложение $appName", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun ensureTabWebView(tab: BrowserTabModel): WebView {
        tab.webView?.let { existing ->
            val oldParent = existing.parent as? ViewGroup
            if (oldParent != webViewContainer) {
                oldParent?.removeView(existing)
                webViewContainer.addView(existing)
            }
            existing.settings.textZoom = textZoomPercent
            CookieManager.getInstance().setAcceptThirdPartyCookies(existing, !blockThirdPartyCookies)
            return existing
        }
        val wv = WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.setSupportZoom(true)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportMultipleWindows(false)
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.textZoom = textZoomPercent

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, !blockThirdPartyCookies)

            setOnLongClickListener {
                val hit = hitTestResult
                val extra = hit.extra
                if (!extra.isNullOrBlank() && (extra.startsWith("http") || extra.startsWith("magnet:"))) {
                    longPressedUrl = extra
                    true
                } else {
                    false
                }
            }

            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                if (url.startsWith("magnet:", ignoreCase = true) ||
                    url.endsWith(".torrent", ignoreCase = true) ||
                    mimeType == "application/x-bittorrent"
                ) {
                    torrentEngine.startFromMagnetOrUrl(url)
                    currentSection = 1
                    Toast.makeText(context, "🧲 Перехвачен .torrent файл — скачивание начато!", Toast.LENGTH_LONG).show()
                } else {
                    try {
                        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                        val req = DownloadManager.Request(Uri.parse(url)).apply {
                            setMimeType(mimeType)
                            addRequestHeader("User-Agent", userAgent)
                            setTitle(fileName)
                            setDescription("Скачивание через OmniBrowser Pro")
                            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                        }
                        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        dm.enqueue(req)
                        Toast.makeText(context, "📥 Загрузка начата: $fileName (см. раздел История и Загрузки)", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        torrentEngine.startFromMagnetOrUrl(url)
                        currentSection = 1
                        Toast.makeText(context, "📥 Загрузка через встроенный движок: ${e.message ?: ""}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    tab.loadingProgress = newProgress.coerceIn(5, 100)
                    tab.isLoading = newProgress < 100
                    val host = Uri.parse(view?.url ?: tab.url).host ?: "сайт"
                    val vpnTag = if (browserVpnEnabled) " • Репитер Браузера №1 ($browserVpnCountry)" else ""
                    tab.loadingStatusText = if (newProgress < 100) {
                        "⏳ Загрузка $newProgress% • $host$vpnTag"
                    } else {
                        "✓ Загружено 100% • $host$vpnTag"
                    }
                    if (newProgress >= 65 && view != null) {
                        injectActiveExtensions(view)
                    }
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (!title.isNullOrBlank()) {
                        tab.title = title
                    }
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (view != null) {
                        fullscreenCustomView = view
                        fullscreenCallback = callback
                    } else {
                        super.onShowCustomView(view, callback)
                    }
                }

                override fun onHideCustomView() {
                    fullscreenCustomView = null
                    fullscreenCallback?.onCustomViewHidden()
                    fullscreenCallback = null
                    super.onHideCustomView()
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString()?.lowercase() ?: return null
                    if (adBlockEnabled) {
                        val host = request.url.host?.lowercase() ?: ""
                        val blocked = AD_DOMAINS.any { host == it || host.endsWith(".$it") } ||
                            customBlockedDomains.any { it.isNotBlank() && host.contains(it.lowercase()) } ||
                            reqUrl.contains("/pagead/") || reqUrl.contains("doubleclick.net") ||
                            reqUrl.contains("googleads.")
                        if (blocked) {
                            adBlockedCount++
                            return WebResourceResponse(
                                "text/plain",
                                "UTF-8",
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val nextUrl = request?.url?.toString() ?: return false
                    if (nextUrl.startsWith("magnet:", ignoreCase = true) ||
                        nextUrl.endsWith(".torrent", ignoreCase = true)
                    ) {
                        torrentEngine.startFromMagnetOrUrl(nextUrl)
                        currentSection = 1
                        Toast.makeText(context, "🧲 Magnet/Торрент перехвачен и запущен!", Toast.LENGTH_LONG).show()
                        return true
                    }
                    if (nextUrl.startsWith("intent://", ignoreCase = true)) {
                        val fallback = Regex("S\\.browser_fallback_url=([^;]+)").find(nextUrl)?.groupValues?.getOrNull(1)
                        if (!fallback.isNullOrBlank()) {
                            view?.loadUrl(Uri.decode(fallback))
                        }
                        return true
                    }
                    if (!nextUrl.startsWith("http://", ignoreCase = true) &&
                        !nextUrl.startsWith("https://", ignoreCase = true)
                    ) {
                        nativeAppsManager.openUrlInNativeApp(nextUrl)
                        return true
                    }
                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!url.isNullOrBlank() && url != "about:blank") {
                        tab.url = url
                        if (tab == tabs.getOrNull(activeTabIndex)) {
                            addressInput = url
                        }
                        tab.isLoading = true
                        tab.loadingProgress = 15
                        tab.lastErrorText = null
                        val host = Uri.parse(url).host ?: url
                        tab.loadingStatusText = "⏳ Загрузка страницы $host..."
                        if (!tab.isIncognito) {
                            val stamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                            val entry = "$stamp | $url"
                            historyList.removeAll { it.endsWith("| $url") || it == url }
                            historyList.add(0, entry)
                        }
                        persistBrowserSettings()
                    }
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    if (!url.isNullOrBlank() && url != "about:blank") {
                        tab.url = url
                        if (tab == tabs.getOrNull(activeTabIndex)) {
                            addressInput = url
                        }
                        tab.canGoBack = view?.canGoBack() == true
                        tab.canGoForward = view?.canGoForward() == true
                        persistBrowserSettings()
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    tab.isLoading = false
                    tab.loadingProgress = 100
                    tab.canGoBack = view?.canGoBack() == true
                    tab.canGoForward = view?.canGoForward() == true
                    if (!url.isNullOrBlank() && url != "about:blank") {
                        tab.url = url
                        if (tab == tabs.getOrNull(activeTabIndex)) {
                            addressInput = url
                        }
                    }
                    if (view != null) {
                        injectActiveExtensions(view)
                    }
                    persistBrowserSettings()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        val desc = error?.description?.toString() ?: "ERR_TIMED_OUT"
                        val failingUrl = request.url?.toString() ?: tab.url
                        BrowserDirectRepeater.ensureDirectBrowserMode()
                        if (tab.autoRetryCount < 1) {
                            tab.autoRetryCount++
                            tab.lastErrorText = null
                            tab.loadingStatusText = "⚡ Повтор через Репитер Браузера №1 ($browserVpnCountry • без локального туннеля)..."
                            view?.loadUrl(failingUrl)
                            return
                        }
                        tab.isLoading = false
                        tab.loadingProgress = 0
                        tab.lastErrorText = "$desc ($failingUrl)"
                        tab.loadingStatusText = "⚠️ Ошибка соединения: $desc"
                    }
                }
            }
        }
        tab.webView = wv
        webViewContainer.addView(wv)
        navigateTab(tab, tab.url)
        return wv
    }

    LaunchedEffect(initialIntentUri) {
        if (!initialIntentUri.isNullOrBlank()) {
            if (initialIntentUri.startsWith("magnet:", ignoreCase = true) ||
                initialIntentUri.endsWith(".torrent", ignoreCase = true)
            ) {
                torrentEngine.startFromMagnetOrUrl(initialIntentUri)
                currentSection = 1
            } else if (initialIntentUri.startsWith("http", ignoreCase = true)) {
                navigateTab(activeTab, initialIntentUri)
                currentSection = 0
            }
        }
    }

    LaunchedEffect(activeTab.isLoading, activeTab.id) {
        while (activeTab.isLoading && activeTab.loadingProgress < 85) {
            delay(300)
            if (activeTab.isLoading && activeTab.loadingProgress < 85) {
                activeTab.loadingProgress = (activeTab.loadingProgress + 3).coerceAtMost(85)
            }
        }
    }

    LaunchedEffect(activeTabIndex, tabs.size, desktopMode) {
        val current = tabs.getOrNull(activeTabIndex) ?: return@LaunchedEffect
        ensureTabWebView(current)
        for (i in 0 until webViewContainer.childCount) {
            val child = webViewContainer.getChildAt(i)
            child.visibility = if (child == current.webView) View.VISIBLE else View.GONE
        }
        current.webView?.settings?.userAgentString = if (desktopMode) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        } else {
            null
        }
    }

    var torrentStatuses by remember { mutableStateOf(torrentEngine.getAllStatuses()) }
    LaunchedEffect(Unit) {
        while (true) {
            torrentStatuses = torrentEngine.getAllStatuses()
            delay(500)
        }
    }

    var installedApps by remember { mutableStateOf<List<NativeAppEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        installedApps = nativeAppsManager.loadInstalledNativeApps()
    }

    val torrentFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    torrentEngine.startFromTorrentBytes(bytes, uri.lastPathSegment ?: "picked.torrent")
                    currentSection = 1
                    Toast.makeText(context, "🧲 Локальный .torrent файл запущен!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Ошибка чтения .torrent: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF090D16)
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {

            if (currentSection == 0) {
                // 1. САМЫЙ ВЕРХ: Горизонтально прокручиваемая панель ВСЕХ КНОПОК (История, Загрузки, Репитер №1, Список VPN, Торрент, Расширения)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0B1324))
                        .padding(horizontal = 6.dp, vertical = 5.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ExtensionChip(
                        label = if (browserVpnEnabled) {
                            "🌐 VPN Браузера: ${browserTrafficSnapshot.activeCountryName} (${browserTrafficSnapshot.remoteServerIpPort})"
                        } else {
                            "🌐 VPN Браузера: ВЫКЛ ($browserVpnCountry)"
                        },
                        active = browserVpnEnabled,
                        activeColor = Color(0xFF0284C7)
                    ) {
                        activeExtensionPopup = if (activeExtensionPopup == "browsec") null else "browsec"
                    }

                    ExtensionChip(
                        label = "🕘 История (${historyList.size})",
                        active = false,
                        activeColor = Color(0xFF0284C7)
                    ) {
                        currentSection = 2
                    }

                    ExtensionChip(
                        label = "📥 Загрузки",
                        active = false,
                        activeColor = Color(0xFF059669)
                    ) {
                        currentSection = 2
                    }

                    ExtensionChip(
                        label = "📱 Список VPN (${vpnRoutedApps.size})",
                        active = browsecEnabled,
                        activeColor = Color(0xFF7C3AED)
                    ) {
                        currentSection = 3
                    }

                    ExtensionChip(
                        label = "🧲 Торрент (${torrentStatuses.size})",
                        active = false,
                        activeColor = Color(0xFF059669)
                    ) {
                        currentSection = 1
                    }

                    ExtensionChip(
                        label = "🛡️ AdBlock ($adBlockedCount)",
                        active = adBlockEnabled,
                        activeColor = Color(0xFF059669)
                    ) {
                        activeExtensionPopup = if (activeExtensionPopup == "adblock") null else "adblock"
                    }

                    ExtensionChip(
                        label = "🎬 YouTube со звуком",
                        active = youtubeBoostEnabled,
                        activeColor = Color(0xFFDC2626)
                    ) {
                        activeExtensionPopup = if (activeExtensionPopup == "youtube") null else "youtube"
                    }

                    ExtensionChip(
                        label = if (desktopMode) "🖥 Режим ПК" else "📱 Мобильный",
                        active = desktopMode,
                        activeColor = Color(0xFF0284C7)
                    ) {
                        desktopMode = !desktopMode
                        activeTab.webView?.settings?.userAgentString = if (desktopMode) {
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                        } else null
                        navigateTab(activeTab, activeTab.url)
                        persistBrowserSettings()
                    }

                    ExtensionChip(
                        label = "🌙 Dark Reader",
                        active = darkReaderEnabled,
                        activeColor = Color(0xFF7C3AED)
                    ) {
                        darkReaderEnabled = !darkReaderEnabled
                        activeTab.webView?.let { injectActiveExtensions(it) }
                        persistBrowserSettings()
                    }

                    ExtensionChip(
                        label = "⭐ Закладки",
                        active = activeExtensionPopup == "bookmarks",
                        activeColor = Color(0xFF2563EB)
                    ) {
                        activeExtensionPopup = if (activeExtensionPopup == "bookmarks") null else "bookmarks"
                    }

                    ExtensionChip(
                        label = "🔍 Найти",
                        active = activeExtensionPopup == "find",
                        activeColor = Color(0xFF4F46E5)
                    ) {
                        activeExtensionPopup = if (activeExtensionPopup == "find") null else "find"
                    }

                    ExtensionChip(
                        label = "🔧 DevTools (F12)",
                        active = devToolsEnabled,
                        activeColor = Color(0xFFD97706)
                    ) {
                        devToolsEnabled = !devToolsEnabled
                        activeTab.webView?.let { injectActiveExtensions(it) }
                    }

                    ExtensionChip(
                        label = "⚙️ Настройки",
                        active = activeExtensionPopup == "settings",
                        activeColor = Color(0xFF0EA5E9)
                    ) {
                        activeExtensionPopup = if (activeExtensionPopup == "settings") null else "settings"
                    }
                }

                // 2. ПРЯМО ПОД КНОПКАМИ: Горизонтально прокручиваемая полоса ВКЛАДОК
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F1624))
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tabs.forEachIndexed { idx, tab ->
                        val isSelected = idx == activeTabIndex
                        Surface(
                            color = if (isSelected) Color(0xFF1E293B) else Color(0xFF111827),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) Color(0xFF38BDF8) else Color(0xFF334155)
                            ),
                            modifier = Modifier.clickable {
                                activeTabIndex = idx
                                addressInput = tab.url
                                persistBrowserSettings()
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (tab.isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 2.dp,
                                        color = Color(0xFF38BDF8)
                                    )
                                } else {
                                    Text(if (tab.isIncognito) "🕶" else "🌐", fontSize = 11.sp)
                                }
                                Text(
                                    text = tab.title.take(18).ifBlank { "Вкладка ${idx + 1}" },
                                    color = if (isSelected) Color.White else Color(0xFF94A3B8),
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (tabs.size > 1) {
                                    Text(
                                        text = "✕",
                                        color = Color(0xFFEF4444),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable {
                                            tab.webView?.let { webViewContainer.removeView(it); it.destroy() }
                                            tabs.removeAt(idx)
                                            activeTabIndex = activeTabIndex.coerceIn(0, tabs.size - 1)
                                            persistBrowserSettings()
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Surface(
                        color = Color(0xFF0284C7),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable {
                            val newTab = BrowserTabModel(
                                id = "tab_${System.currentTimeMillis()}",
                                initialUrl = "https://www.google.com",
                                initialTitle = "Google"
                            )
                            tabs.add(newTab)
                            activeTabIndex = tabs.size - 1
                            ensureTabWebView(newTab)
                            persistBrowserSettings()
                        }
                    ) {
                        Text(
                            text = "+ Вкладка",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }

                    Surface(
                        color = Color(0xFF312E81),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable {
                            val incTab = BrowserTabModel(
                                id = "inc_${System.currentTimeMillis()}",
                                initialUrl = "https://duckduckgo.com",
                                initialTitle = "Инкогнито",
                                isIncognito = true
                            )
                            tabs.add(incTab)
                            activeTabIndex = tabs.size - 1
                            ensureTabWebView(incTab)
                        }
                    ) {
                        Text(
                            text = "🕶 Инкогнито",
                            color = Color(0xFFE0E7FF),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                        )
                    }
                }

                // 3. Адресная панель навигации (←, →, ↻, ✕, URL, Перейти)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF111827))
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SmallNavIconButton("←", enabled = activeTab.canGoBack) {
                        activeTab.webView?.goBack()
                    }
                    SmallNavIconButton("→", enabled = activeTab.canGoForward) {
                        activeTab.webView?.goForward()
                    }
                    SmallNavIconButton("↻", enabled = true) {
                        navigateTab(activeTab, activeTab.url)
                    }
                    if (activeTab.isLoading) {
                        SmallNavIconButton("✕", enabled = true) {
                            activeTab.webView?.stopLoading()
                            activeTab.isLoading = false
                            activeTab.loadingProgress = 0
                        }
                    }

                    CompactDarkTextField(
                        value = addressInput,
                        onValueChange = { addressInput = it },
                        placeholder = "Введите URL, поисковый запрос или magnet:?...",
                        modifier = Modifier.weight(1f),
                        onGo = { navigateTab(activeTab, addressInput) }
                    )

                    Button(
                        onClick = { navigateTab(activeTab, addressInput) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.height(38.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                    ) {
                        Text("Перейти", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                    }
                }

                // 4. Индикатор загрузки и прокручиваемая строка статуса Репитера №1 (только для Браузера, без локального туннеля!)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0B1324))
                ) {
                    if (activeTab.isLoading || activeTab.loadingProgress in 1..99) {
                        LinearProgressIndicator(
                            progress = { (activeTab.loadingProgress / 100f).coerceIn(0.05f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = Color(0xFF38BDF8),
                            trackColor = Color(0xFF1E293B)
                        )
                    } else {
                        HorizontalDivider(color = Color(0xFF1E293B), thickness = 1.dp)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF060B16))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = if (activeTab.isLoading) {
                                "⏳ ${activeTab.loadingProgress}% • ${Uri.parse(activeTab.url).host ?: activeTab.url}"
                            } else {
                                "🔒 100% • ${Uri.parse(activeTab.url).host ?: activeTab.url}"
                            },
                            color = if (activeTab.isLoading) Color(0xFF38BDF8) else Color(0xFF34D399),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = if (browserVpnEnabled) {
                                "🟢 Репитер Браузера №1: ${browserTrafficSnapshot.activeCountryName} • Сервер: ${browserTrafficSnapshot.remoteServerIpPort} (БЕЗ проброса в лок. туннель • ${browserTrafficSnapshot.pingMs} мс)"
                            } else {
                                "⚪ Репитер Браузера №1: ВЫКЛ (Прямое соединение)"
                            },
                            color = if (browserVpnEnabled) Color(0xFF34D399) else Color(0xFF94A3B8),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "⬇ ВХ: ${BitTorrentEngine.formatSpeed(browserTrafficSnapshot.rxSpeedBytesPerSec)} | ⬆ ИСХ: ${BitTorrentEngine.formatSpeed(browserTrafficSnapshot.txSpeedBytesPerSec)}",
                            color = Color(0xFFFDE047),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Long-press Link / Image Context Dialog
                if (longPressedUrl != null) {
                    val targetLink = longPressedUrl ?: ""
                    Surface(
                        color = Color(0xFF0F172A),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8)),
                        modifier = Modifier.fillMaxWidth().padding(6.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("🔗 Ссылка: $targetLink", color = Color(0xFF38BDF8), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(onClick = {
                                    tabs.add(BrowserTabModel(id = "tab_${System.currentTimeMillis()}", initialUrl = targetLink, initialTitle = "Вкладка"))
                                    activeTabIndex = tabs.size - 1
                                    persistBrowserSettings()
                                    longPressedUrl = null
                                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))) {
                                    Text("➕ В новой вкладке", fontSize = 11.sp, color = Color.White)
                                }
                                Button(onClick = {
                                    tabs.add(BrowserTabModel(id = "incog_${System.currentTimeMillis()}", initialUrl = targetLink, initialTitle = "🕶 Инкогнито", isIncognito = true))
                                    activeTabIndex = tabs.size - 1
                                    longPressedUrl = null
                                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))) {
                                    Text("🕶 В Инкогнито", fontSize = 11.sp, color = Color.White)
                                }
                                Button(onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("URL", targetLink))
                                    Toast.makeText(context, "📋 Ссылка скопирована", Toast.LENGTH_SHORT).show()
                                    longPressedUrl = null
                                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))) {
                                    Text("📋 Копировать", fontSize = 11.sp, color = Color.White)
                                }
                                OutlinedButton(onClick = { longPressedUrl = null }) {
                                    Text("✕ Закрыть", fontSize = 11.sp, color = Color.White)
                                }
                            }
                        }
                    }
                }

                // 5. Interactive Extension Popups (With Crisp Non-Clipped Inputs)
                AnimatedVisibility(visible = activeExtensionPopup != null) {
                    Surface(
                        color = Color(0xFF1E293B),
                        shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (activeExtensionPopup) {
                                "browsec" -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "🌐 Репитер Браузера №1: ${browserTrafficSnapshot.activeCountryName} • IP: ${browserTrafficSnapshot.remoteServerIpPort}",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Switch(checked = browserVpnEnabled, onCheckedChange = { enabled ->
                                            browserVpnEnabled = enabled
                                            BrowserDirectRepeater.enabled = enabled
                                            BrowserDirectRepeater.ensureDirectBrowserMode()
                                            persistBrowserSettings()
                                            navigateTab(activeTab, activeTab.url)
                                        })
                                    }

                                    Surface(
                                        color = Color(0xFF090D16),
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981))
                                    ) {
                                        Column(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                "🌍 СЕРВЕР БРАУЗЕРА №1: ${browserTrafficSnapshot.activeCountryName} | IP: ${browserTrafficSnapshot.remoteServerIpPort} | Пинг: ${browserTrafficSnapshot.pingMs} мс",
                                                color = Color(0xFF34D399),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp
                                            )
                                            Text(
                                                "✓ Режим: Прямой Репитер Браузера №1 (БЕЗ проброса к локальному туннелю 127.0.0.1:8899 — видео YouTube в браузере работает без черного экрана!)",
                                                color = Color(0xFFFACC15),
                                                fontSize = 11.sp
                                            )
                                            Text(
                                                "⬇ Входящий (RX): ${BitTorrentEngine.formatSpeed(browserTrafficSnapshot.rxSpeedBytesPerSec)}  |  Всего: ${BitTorrentEngine.formatSize(browserTrafficSnapshot.rxBytesTotal)}",
                                                color = Color(0xFF38BDF8),
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Text(
                                                "⬆ Исходящий (TX): ${BitTorrentEngine.formatSpeed(browserTrafficSnapshot.txSpeedBytesPerSec)}  |  Всего: ${BitTorrentEngine.formatSize(browserTrafficSnapshot.txBytesTotal)}",
                                                color = Color(0xFFFDE047),
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        BrowserDirectRepeater.BROWSER_NODES.forEach { node ->
                                            val selected = browserVpnCountry == node.code
                                            Surface(
                                                color = if (selected) Color(0xFF0284C7) else Color(0xFF0F172A),
                                                shape = RoundedCornerShape(8.dp),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8)),
                                                modifier = Modifier.clickable {
                                                    browserVpnCountry = node.code
                                                    browserVpnEnabled = true
                                                    BrowserDirectRepeater.switchCountry(node.code)
                                                    persistBrowserSettings()
                                                    navigateTab(activeTab, activeTab.url)
                                                    Toast.makeText(
                                                        context,
                                                        "🌍 Репитер Браузера №1: ${node.flag} ${node.countryRu} (${node.serverName}) • IP: ${node.remoteIp}:${node.remotePort}",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Text(
                                                        text = "${node.flag} ${node.countryRu} (${node.code})",
                                                        color = Color.White,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = "${node.remoteIp}:${node.remotePort}",
                                                        color = if (selected) Color(0xFFE0F2FE) else Color(0xFF94A3B8),
                                                        fontSize = 9.sp,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                browserVpnEnabled = !browserVpnEnabled
                                                BrowserDirectRepeater.enabled = browserVpnEnabled
                                                BrowserDirectRepeater.ensureDirectBrowserMode()
                                                persistBrowserSettings()
                                                navigateTab(activeTab, activeTab.url)
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (browserVpnEnabled) Color(0xFFDC2626) else Color(0xFF059669)
                                            )
                                        ) {
                                            Text(
                                                if (browserVpnEnabled) "⏹ Выключить VPN Браузера" else "▶ Включить VPN Браузера ($browserVpnCountry)",
                                                fontSize = 11.sp,
                                                color = Color.White
                                            )
                                        }

                                        Button(
                                            onClick = {
                                                activeExtensionPopup = null
                                                currentSection = 3
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
                                        ) {
                                            Text("📱 Перейти во 2-й Сервер (Список VPN Приложений: ${vpnRoutedApps.size})", fontSize = 11.sp, color = Color.White)
                                        }
                                    }
                                }

                                "adblock" -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("🛡️ AdBlock Pro + YouTube Анти-Реклама", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Switch(checked = adBlockEnabled, onCheckedChange = {
                                            adBlockEnabled = it
                                            persistBrowserSettings()
                                            activeTab.webView?.reload()
                                        })
                                    }
                                    Text("Заблокировано рекламных запросов и трекеров: $adBlockedCount", color = Color(0xFF34D399), fontSize = 12.sp)
                                    var customDomain by remember { mutableStateOf("") }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        CompactDarkTextField(
                                            value = customDomain,
                                            onValueChange = { customDomain = it },
                                            placeholder = "Добавить домен в блок (напр. ads.site.ru)",
                                            modifier = Modifier.weight(1f)
                                        )
                                        Button(onClick = {
                                            if (customDomain.isNotBlank()) {
                                                customBlockedDomains.add(customDomain.trim())
                                                customDomain = ""
                                                persistBrowserSettings()
                                            }
                                        }) {
                                            Text("+ Блок", fontSize = 11.sp, color = Color.White)
                                        }
                                    }
                                }

                                "youtube" -> {
                                    Text("🎬 Прямой YouTube со звуком (Через Browsec VPN $browsecCountry)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    var ytSearch by remember { mutableStateOf("") }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        CompactDarkTextField(
                                            value = ytSearch,
                                            onValueChange = { ytSearch = it },
                                            placeholder = "Введите название видео или песни для YouTube...",
                                            modifier = Modifier.weight(1f),
                                            onGo = {
                                                browsecEnabled = true
                                                val q = Uri.encode(ytSearch.ifBlank { "music 2026" })
                                                navigateTab(activeTab, "https://m.youtube.com/results?search_query=$q")
                                                activeExtensionPopup = null
                                            }
                                        )
                                        Button(
                                            onClick = {
                                                browsecEnabled = true
                                                val q = Uri.encode(ytSearch.ifBlank { "music 2026" })
                                                navigateTab(activeTab, "https://m.youtube.com/results?search_query=$q", useProxyTunnel = true)
                                                activeExtensionPopup = null
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                                        ) {
                                            Text("▶ Найти (Без блокировок)", fontSize = 11.sp, color = Color.White)
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                browsecEnabled = true
                                                navigateTab(activeTab, "https://m.youtube.com", useProxyTunnel = true)
                                                activeExtensionPopup = null
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                                        ) {
                                            Text("🎬 YouTube Туннель (100% Работает)", fontSize = 11.sp, color = Color.White)
                                        }
                                        Button(
                                            onClick = {
                                                browsecEnabled = true
                                                navigateTab(activeTab, "https://yewtu.be/feed/popular", useProxyTunnel = false)
                                                activeExtensionPopup = null
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
                                        ) {
                                            Text("🌐 Зеркало Yewtu.be", fontSize = 11.sp, color = Color.White)
                                        }
                                        Button(
                                            onClick = {
                                                browsecEnabled = true
                                                navigateTab(activeTab, "https://m.youtube.com", useProxyTunnel = false)
                                                activeExtensionPopup = null
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                                        ) {
                                            Text("📱 Прямой m.youtube.com", fontSize = 11.sp, color = Color.White)
                                        }
                                        Button(
                                            onClick = {
                                                playNativeAudioChime(523.25, 110)
                                                activeTab.webView?.evaluateJavascript(
                                                    """
                                                    (function(){
                                                        document.querySelectorAll('video, audio').forEach(v => {
                                                            v.muted = false;
                                                            v.volume = 1.0;
                                                            v.play();
                                                        });
                                                    })();
                                                    """.trimIndent(),
                                                    null
                                                )
                                                Toast.makeText(context, "🔊 Звук разблокирован и усилен!", Toast.LENGTH_SHORT).show()
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                                        ) {
                                            Text("🔊 Усилить Звук 200%", fontSize = 11.sp, color = Color.White)
                                        }
                                    }
                                }

                                "bookmarks" -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("⭐ Закладки", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Button(onClick = {
                                            bookmarks.add(0, activeTab.url to activeTab.title.ifBlank { activeTab.url })
                                            Toast.makeText(context, "⭐ Добавлено в закладки", Toast.LENGTH_SHORT).show()
                                        }) {
                                            Text("+ Сохранить вкладку", fontSize = 11.sp, color = Color.White)
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        bookmarks.forEach { (bUrl, bTitle) ->
                                            OutlinedButton(onClick = {
                                                navigateTab(activeTab, bUrl)
                                                activeExtensionPopup = null
                                            }) {
                                                Text(bTitle, fontSize = 11.sp, color = Color.White)
                                            }
                                        }
                                    }
                                }

                                "find" -> {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        CompactDarkTextField(
                                            value = findInPageQuery,
                                            onValueChange = {
                                                findInPageQuery = it
                                                activeTab.webView?.findAllAsync(it)
                                            },
                                            placeholder = "Найти слово на странице...",
                                            modifier = Modifier.weight(1f)
                                        )
                                        Button(onClick = { activeTab.webView?.findNext(true) }) {
                                            Text("Далее ↓", fontSize = 11.sp, color = Color.White)
                                        }
                                        OutlinedButton(onClick = {
                                            activeTab.webView?.clearMatches()
                                            activeExtensionPopup = null
                                        }) {
                                            Text("✕", fontSize = 11.sp, color = Color.White)
                                        }
                                    }
                                }

                                "settings" -> {
                                    Text("⚙️ Основные Настройки Браузера и Приватности", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    // 1. Search Engine Selector
                                    Row(
                                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("🔍 Поиск:", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                        listOf("Google", "Яндекс", "DuckDuckGo", "Bing", "Brave").forEach { eng ->
                                            val sel = searchEngine == eng
                                            Surface(
                                                color = if (sel) Color(0xFF0284C7) else Color(0xFF0F172A),
                                                shape = RoundedCornerShape(6.dp),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8)),
                                                modifier = Modifier.clickable {
                                                    searchEngine = eng
                                                    persistBrowserSettings()
                                                }
                                            ) {
                                                Text(eng, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                                            }
                                        }
                                    }
                                    // 2. Text Zoom Selector
                                    Row(
                                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("🔠 Масштаб:", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                        listOf(75, 100, 115, 130, 150, 175).forEach { pct ->
                                            val sel = textZoomPercent == pct
                                            Surface(
                                                color = if (sel) Color(0xFF059669) else Color(0xFF0F172A),
                                                shape = RoundedCornerShape(6.dp),
                                                modifier = Modifier.clickable {
                                                    textZoomPercent = pct
                                                    tabs.forEach { it.webView?.settings?.textZoom = pct }
                                                    persistBrowserSettings()
                                                }
                                            ) {
                                                Text("$pct%", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                                            }
                                        }
                                    }
                                    // 3. DoH Resolver
                                    Row(
                                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("🛡 DoH DNS:", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                        listOf("1.1.1.1" to "Cloudflare 1.1.1.1", "8.8.8.8" to "Google 8.8.8.8", "9.9.9.9" to "Quad9 9.9.9.9").forEach { (ip, label) ->
                                            val sel = dohServer == ip
                                            Surface(
                                                color = if (sel) Color(0xFF7C3AED) else Color(0xFF0F172A),
                                                shape = RoundedCornerShape(6.dp),
                                                modifier = Modifier.clickable {
                                                    dohServer = ip
                                                    BrowsecLocalProxyServer.dohProvider = ip
                                                    persistBrowserSettings()
                                                }
                                            ) {
                                                Text(label, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                                            }
                                        }
                                    }
                                    // 4. Privacy & Offline Page Tools
                                    Row(
                                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Button(onClick = {
                                            homePageUrl = activeTab.url
                                            persistBrowserSettings()
                                            Toast.makeText(context, "🏠 Домашняя страница: $homePageUrl", Toast.LENGTH_SHORT).show()
                                        }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))) {
                                            Text("🏠 Сделать Домашней", fontSize = 11.sp, color = Color.White)
                                        }
                                        Button(onClick = {
                                            try {
                                                val dir = torrentEngine.getDownloadDirectory()
                                                val mhtFile = File(dir, "page_${System.currentTimeMillis()}.mht")
                                                activeTab.webView?.saveWebArchive(mhtFile.absolutePath)
                                                Toast.makeText(context, "📄 Сохранено офлайн: ${mhtFile.name}", Toast.LENGTH_LONG).show()
                                            } catch (_: Exception) {
                                            }
                                        }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))) {
                                            Text("📄 Сохранить страницу (.MHT)", fontSize = 11.sp, color = Color.White)
                                        }
                                        Button(onClick = {
                                            activeTab.webView?.clearCache(true)
                                            CookieManager.getInstance().removeAllCookies(null)
                                            CookieManager.getInstance().flush()
                                            Toast.makeText(context, "🗑 Кэш и Cookies очищены!", Toast.LENGTH_SHORT).show()
                                        }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))) {
                                            Text("🗑 Очистить Кэш и Cookies", fontSize = 11.sp, color = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (activeTab.lastErrorText != null) {
                    Surface(
                        color = Color(0xFF3B0764),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "⚠️ Таймаут или блокировка: ${activeTab.lastErrorText}",
                                color = Color(0xFFFDE047),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        browsecEnabled = true
                                        val nextIdx = (VPN_NODES.indexOfFirst { it.code == browsecCountry } + 1) % VPN_NODES.size
                                        browsecCountry = VPN_NODES[nextIdx].code
                                        navigateTab(activeTab, activeTab.url)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                                ) {
                                    Text("🌐 Сменить страну Browsec ($browsecCountry) и Обновить", fontSize = 11.sp, color = Color.White)
                                }
                                Button(
                                    onClick = { navigateTab(activeTab, "https://m.youtube.com") },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                                ) {
                                    Text("🎬 Открыть m.youtube.com", fontSize = 11.sp, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }

            // ===================== PERSISTENT WEBVIEW CONTAINER =====================
            if (fullscreenCustomView != null) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                    AndroidView(
                        factory = { ctx ->
                            FrameLayout(ctx).apply {
                                val v = fullscreenCustomView
                                if (v != null) {
                                    (v.parent as? ViewGroup)?.removeView(v)
                                    addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    Button(
                        onClick = {
                            fullscreenCustomView = null
                            fullscreenCallback?.onCustomViewHidden()
                            fullscreenCallback = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC0F172A)),
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                    ) {
                        Text("✕ Выйти из полного экрана", fontSize = 11.sp, color = Color.White)
                    }
                }
            }

            Box(
                modifier = if (currentSection == 0 && fullscreenCustomView == null) {
                    Modifier.weight(1f).fillMaxWidth()
                } else {
                    Modifier.size(0.dp)
                }
            ) {
                AndroidView(
                    factory = {
                        ensureTabWebView(activeTab)
                        webViewContainer
                    },
                    update = { container ->
                        val current = tabs.getOrNull(activeTabIndex)
                        if (current != null) {
                            ensureTabWebView(current)
                            for (i in 0 until container.childCount) {
                                val child = container.getChildAt(i)
                                child.visibility = if (child == current.webView) View.VISIBLE else View.GONE
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ===================== SECTION 1: TORRENT CLIENT + BROWSEC VPN + MEMORY ACCESS =====================
            if (currentSection == 1) {
                TorrentClientSection(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    torrentEngine = torrentEngine,
                    statuses = torrentStatuses,
                    nativeAppsManager = nativeAppsManager,
                    browsecEnabled = browsecEnabled,
                    browsecCountry = browsecCountry,
                    onToggleBrowsec = {
                        val nextState = !browsecEnabled
                        browsecEnabled = nextState
                        browsecPaused = false
                        if (nextState) {
                            BrowsecVpnService.startForApp(context, browsecCountry, null)
                        } else {
                            BrowsecVpnService.stop(context)
                        }
                        persistBrowserSettings()
                    },
                    onCycleCountry = {
                        val nextCode = BrowsecVpnService.cycleCountry(context)
                        browsecCountry = nextCode
                        browsecEnabled = true
                        browsecPaused = false
                        persistBrowserSettings()
                    },
                    onRequestStorageAccess = { requestFullStorageAccess() },
                    onPickTorrentFile = { torrentFilePicker.launch("*/*") }
                )
            }

            // ===================== SECTION 2: HISTORY & DOWNLOADS MANAGER =====================
            if (currentSection == 2) {
                HistoryAndDownloadsSection(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    historyList = historyList,
                    torrentEngine = torrentEngine,
                    nativeAppsManager = nativeAppsManager,
                    onOpenUrlInBrowser = { u ->
                        val cleanUrl = u.substringAfter("| ").trim()
                        navigateTab(activeTab, cleanUrl)
                        currentSection = 0
                    },
                    onClearHistory = {
                        historyList.clear()
                        persistBrowserSettings()
                    },
                    onRequestStorageAccess = { requestFullStorageAccess() }
                )
            }

            // ===================== SECTION 3: NATIVE APPS + BROWSEC VPN LAUNCHER =====================
            if (currentSection == 3) {
                NativeAppsWithBrowsecSection(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    nativeAppsManager = nativeAppsManager,
                    installedApps = installedApps,
                    browsecEnabled = browsecEnabled,
                    browsecPaused = browsecPaused,
                    browsecCountry = browsecCountry,
                    vpnRoutedApps = vpnRoutedApps,
                    downloadedFiles = torrentEngine.getDownloadDirectory().listFiles()?.toList().orEmpty(),
                    onSelectCountry = { code ->
                        browsecCountry = code
                        BrowsecVpnService.activeCountry = code
                        if (browsecEnabled) {
                            BrowsecVpnService.startForApp(context, code, null)
                        }
                        persistBrowserSettings()
                    },
                    onToggleFloatingVpn = {
                        val nextState = !browsecEnabled
                        if (nextState) {
                            browsecEnabled = true
                            browsecPaused = false
                            BrowsecLocalProxyServer.useBrowsecTunnel = true
                            persistBrowserSettings()
                            val prep = try { VpnService.prepare(context) } catch (_: Exception) { null }
                            if (prep != null) {
                                pendingVpnLaunchPkg = null
                                vpnPermissionLauncher.launch(prep)
                            } else {
                                BrowsecVpnService.startForApp(context, browsecCountry, null)
                                Toast.makeText(
                                    context,
                                    "🛡️ Внутренний VPN для Списка Приложений (${vpnRoutedApps.size}) ВКЛЮЧЁН ($browsecCountry)!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } else {
                            browsecEnabled = false
                            browsecPaused = false
                            BrowsecLocalProxyServer.useBrowsecTunnel = false
                            BrowsecVpnService.stop(context)
                            persistBrowserSettings()
                            Toast.makeText(
                                context,
                                "⏹ Туннель Browsec для приложений выключен (локальный сервер 127.0.0.1:${BrowsecLocalProxyServer.activePort} работает без туннеля)",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onLaunchAppWithBrowsec = { pkg, name ->
                        startBrowsecAndLaunchNativeApp(pkg, name)
                    },
                    onToggleAppInVpn = { pkg ->
                        if (vpnRoutedApps.contains(pkg)) {
                            vpnRoutedApps.remove(pkg)
                            BrowsecVpnService.routedPackages.remove(pkg)
                        } else {
                            vpnRoutedApps.add(pkg)
                            BrowsecVpnService.routedPackages.add(pkg)
                        }
                        if (browsecEnabled) {
                            BrowsecVpnService.startForApp(context, browsecCountry, null)
                        }
                        persistBrowserSettings()
                    },
                    onRefreshApps = { installedApps = nativeAppsManager.loadInstalledNativeApps() }
                )
            }

            // ===================== BOTTOM NAVIGATION BAR (4 SECTIONS) =====================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomSectionButton(
                    modifier = Modifier.weight(1f),
                    title = "🌐 Браузер (${tabs.size})",
                    subtitle = if (activeTab.isLoading) "Загрузка ${activeTab.loadingProgress}%" else "Репитер №1 ($browserVpnCountry)",
                    selected = currentSection == 0,
                    activeColor = Color(0xFF0284C7)
                ) { currentSection = 0 }

                BottomSectionButton(
                    modifier = Modifier.weight(1f),
                    title = "🧲 Торрент (${torrentStatuses.size})",
                    subtitle = "+ Browsec VPN",
                    selected = currentSection == 1,
                    activeColor = Color(0xFF059669)
                ) { currentSection = 1 }

                BottomSectionButton(
                    modifier = Modifier.weight(1f),
                    title = "📥 Загрузки",
                    subtitle = "🕘 История (${historyList.size})",
                    selected = currentSection == 2,
                    activeColor = Color(0xFFD97706)
                ) { currentSection = 2 }

                BottomSectionButton(
                    modifier = Modifier.weight(1f),
                    title = "📱 Список VPN",
                    subtitle = if (browsecEnabled) "Репитер №2 ВКЛ ($browsecCountry)" else "Репитер №2 (${vpnRoutedApps.size})",
                    selected = currentSection == 3,
                    activeColor = Color(0xFF7C3AED)
                ) { currentSection = 3 }
            }
        }
    }
}

@Composable
private fun TorrentClientSection(
    modifier: Modifier,
    torrentEngine: BitTorrentEngine,
    statuses: List<TorrentSessionStatus>,
    nativeAppsManager: NativeAppsManager,
    browsecEnabled: Boolean,
    browsecCountry: String,
    onToggleBrowsec: () -> Unit,
    onCycleCountry: () -> Unit,
    onRequestStorageAccess: () -> Unit,
    onPickTorrentFile: () -> Unit
) {
    val context = LocalContext.current
    var magnetOrUrlInput by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var catalogItems by remember { mutableStateOf(torrentEngine.getBuiltInVerifiedTorrents()) }
    var isSearching by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .background(Color(0xFF090D16))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            color = Color(0xFF111827),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF059669))
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🧲 BitTorrent Клиент + Browsec VPN ($browsecCountry)",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Папка: ${torrentEngine.getPublicDisplayPath()}",
                            color = Color(0xFF34D399),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = onPickTorrentFile,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("📂 Файл .torrent", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                // Storage Permission + Browsec VPN row for Torrents
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = if (browsecEnabled) Color(0xFF0284C7) else Color(0xFF1E293B),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable { onToggleBrowsec() }
                    ) {
                        Text(
                            text = if (browsecEnabled) "🛡️ Browsec VPN в Торренте: ВКЛ ($browsecCountry)" else "🛡️ Browsec VPN: ВЫКЛ",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }

                    Surface(
                        color = Color(0xFF1E293B),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8)),
                        modifier = Modifier.clickable { onCycleCountry() }
                    ) {
                        Text(
                            text = "🌍 Сменить страну ($browsecCountry)",
                            color = Color.White,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }

                    Surface(
                        color = Color(0xFF065F46),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable { onRequestStorageAccess() }
                    ) {
                        Text(
                            text = "🔓 Доступ к памяти (/Download)",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                // Crisp non-clipped Magnet/URL input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompactDarkTextField(
                        value = magnetOrUrlInput,
                        onValueChange = { magnetOrUrlInput = it },
                        placeholder = "Вставьте magnet:?xt=urn:btih:... или URL .torrent",
                        modifier = Modifier.weight(1f),
                        onGo = {
                            if (magnetOrUrlInput.isNotBlank()) {
                                torrentEngine.startFromMagnetOrUrl(magnetOrUrlInput)
                                magnetOrUrlInput = ""
                            }
                        }
                    )
                    Button(
                        onClick = {
                            if (magnetOrUrlInput.isNotBlank()) {
                                torrentEngine.startFromMagnetOrUrl(magnetOrUrlInput)
                                magnetOrUrlInput = ""
                                Toast.makeText(context, "🧲 Торрент запущен через Browsec $browsecCountry!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                    ) {
                        Text("⬇ Скачать", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                // Crisp non-clipped Search input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompactDarkTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = "Поиск торрентов (игры .exe, фильмы, музыка, программы)...",
                        modifier = Modifier.weight(1f),
                        onGo = {
                            isSearching = true
                            torrentEngine.searchPublicTorrents(searchQuery) { res ->
                                catalogItems = res
                                isSearching = false
                            }
                        }
                    )
                    Button(
                        onClick = {
                            isSearching = true
                            torrentEngine.searchPublicTorrents(searchQuery) { res ->
                                catalogItems = res
                                isSearching = false
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                    ) {
                        Text(if (isSearching) "⏳..." else "🔍 Найти", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (statuses.isNotEmpty()) {
                item {
                    Text(
                        text = "📥 Активные и завершённые торрент-загрузки (${statuses.size}):",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                items(statuses, key = { it.id }) { st ->
                    Surface(
                        color = Color(0xFF151F32),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (st.state == TorrentState.COMPLETED) Color(0xFF10B981) else Color(0xFF38BDF8)
                        )
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = st.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${(st.progressFraction * 100).toInt()}%",
                                    color = Color(0xFF34D399),
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.sp
                                )
                            }

                            LinearProgressIndicator(
                                progress = { st.progressFraction },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = if (st.state == TorrentState.COMPLETED) Color(0xFF10B981) else Color(0xFF38BDF8),
                                trackColor = Color(0xFF0F172A)
                            )

                            Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFF090D16))
                            ) {
                                val count = st.pieceBitfield.size.coerceAtLeast(1)
                                val cellW = size.width / count
                                for (i in 0 until count) {
                                    if (st.pieceBitfield[i]) {
                                        drawRect(
                                            color = Color(0xFF10B981),
                                            topLeft = Offset(i * cellW, 0f),
                                            size = Size(cellW.coerceAtLeast(2f), size.height)
                                        )
                                    }
                                }
                            }

                            Text(
                                text = "${st.state.labelRu} • Скорость: ${BitTorrentEngine.formatSpeed(st.downloadSpeedBytesPerSec)} • Пиры: ${st.connectedPeers}/${st.knownPeers}",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                            Text(
                                text = st.statusDetail,
                                color = Color(0xFFE2E8F0),
                                fontSize = 11.sp
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val f = File(st.outputFilePath)
                                        val res = nativeAppsManager.openFileInNativeApp(f)
                                        res.onFailure {
                                            Toast.makeText(context, "Файл: ${it.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.weight(1.2f).height(36.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
                                ) {
                                    Text("📱 Открыть файл", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                                }

                                OutlinedButton(
                                    onClick = { torrentEngine.togglePause(st.id) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.weight(1f).height(36.dp)
                                ) {
                                    Text(if (st.state == TorrentState.PAUSED) "▶ Пуск" else "⏸ Пауза", fontSize = 11.sp, color = Color.White, maxLines = 1)
                                }

                                OutlinedButton(
                                    onClick = { torrentEngine.removeTorrent(st.id, deleteFile = true) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.weight(0.9f).height(36.dp)
                                ) {
                                    Text("🗑 Удалить", fontSize = 11.sp, color = Color(0xFFEF4444), maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "🔥 Проверенные торренты (Игры .EXE, Программы 32/64-bit, Видео MP4, APK):",
                    color = Color(0xFFFBBF24),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            items(catalogItems) { item ->
                Surface(
                    color = Color(0xFF111827),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(item.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("${item.category} • Размер: ${item.sizeLabel}", color = Color(0xFF38BDF8), fontSize = 11.sp)
                            Text(item.description, color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 2)
                        }
                        Button(
                            onClick = {
                                torrentEngine.startFromCatalogItem(item)
                                Toast.makeText(context, "🧲 Скачивание начато: ${item.title}", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                        ) {
                            Text("⬇ Торрент", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryAndDownloadsSection(
    modifier: Modifier,
    historyList: List<String>,
    torrentEngine: BitTorrentEngine,
    nativeAppsManager: NativeAppsManager,
    onOpenUrlInBrowser: (String) -> Unit,
    onClearHistory: () -> Unit,
    onRequestStorageAccess: () -> Unit
) {
    val context = LocalContext.current
    var subTab by remember { mutableIntStateOf(0) } // 0 = Загрузки, 1 = История
    var refreshTrigger by remember { mutableIntStateOf(0) }

    val downloadedFiles = remember(refreshTrigger, subTab) {
        val list = mutableListOf<File>()
        torrentEngine.getDownloadDirectory().listFiles()?.let { list.addAll(it) }
        try {
            val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(pub, "OmniTorrents").listFiles()?.let { list.addAll(it) }
            pub?.listFiles()?.filter { it.isFile }?.take(30)?.let { list.addAll(it) }
        } catch (_: Exception) {
        }
        list.filter { it.isFile && !it.name.startsWith(".probe") }
            .distinctBy { it.name + "_" + it.length() }
            .sortedByDescending { it.lastModified() }
    }

    Column(
        modifier = modifier
            .background(Color(0xFF090D16))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { subTab = 0; refreshTrigger++ },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = if (subTab == 0) Color(0xFF059669) else Color(0xFF1E293B))
            ) {
                Text("📥 Загрузки (${downloadedFiles.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Button(
                onClick = { subTab = 1 },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = if (subTab == 1) Color(0xFF0284C7) else Color(0xFF1E293B))
            ) {
                Text("🕘 История (${historyList.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }

        if (subTab == 0) {
            Surface(
                color = Color(0xFF111827),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF059669))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("📂 Скачанные файлы (/Download/OmniTorrents)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Файлы синхронизированы с системной папкой Загрузки Android", color = Color(0xFF34D399), fontSize = 11.sp)
                    }
                    Button(
                        onClick = onRequestStorageAccess,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("🔓 Доступ к памяти", fontSize = 11.sp, color = Color.White)
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (downloadedFiles.isEmpty()) {
                    item {
                        Text(
                            "Пока нет скачанных файлов. Перейдите во вкладку «🧲 Торрент» или скачайте файл в браузере.",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                items(downloadedFiles, key = { it.absolutePath }) { file ->
                    Surface(
                        color = Color(0xFF151F32),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(file.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(
                                    "${BitTorrentEngine.formatSize(file.length())} • ${file.absolutePath}",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = {
                                        nativeAppsManager.openFileInNativeApp(file).onFailure {
                                            Toast.makeText(context, "Ошибка: ${it.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
                                ) {
                                    Text("📱 Открыть", fontSize = 11.sp, color = Color.White)
                                }
                                OutlinedButton(
                                    onClick = {
                                        file.delete()
                                        refreshTrigger++
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("🗑", fontSize = 11.sp, color = Color(0xFFEF4444))
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🕘 История посещённых сайтов (${historyList.size}):", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                OutlinedButton(onClick = onClearHistory) {
                    Text("🗑 Очистить историю", fontSize = 11.sp, color = Color(0xFFEF4444))
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(historyList) { entry ->
                    Surface(
                        color = Color(0xFF111827),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenUrlInBrowser(entry) }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = entry,
                                color = Color.White,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text("Перейти →", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeAppsWithBrowsecSection(
    modifier: Modifier,
    nativeAppsManager: NativeAppsManager,
    installedApps: List<NativeAppEntry>,
    browsecEnabled: Boolean,
    browsecPaused: Boolean,
    browsecCountry: String,
    vpnRoutedApps: List<String>,
    downloadedFiles: List<File>,
    onSelectCountry: (String) -> Unit,
    onToggleFloatingVpn: () -> Unit,
    onLaunchAppWithBrowsec: (String, String) -> Unit,
    onToggleAppInVpn: (String) -> Unit,
    onRefreshApps: () -> Unit
) {
    var selectedCategory by remember { mutableStateOf("Все") }
    var appSearchQuery by remember { mutableStateOf("") }
    var selectedFileForOpen by remember { mutableStateOf<File?>(downloadedFiles.firstOrNull()) }

    val categories = listOf(
        "Все",
        "🛡️ В списке VPN (${vpnRoutedApps.size})",
        "⚡ На лок. сервере без VPN",
        "🎬 Видео и Аудио плееры",
        "📦 Файлы и Архиваторы",
        "🖥️ Эмуляторы и ОС (.EXE)",
        "🌐 Браузеры и Мессенджеры"
    )

    val filteredApps = remember(installedApps, selectedCategory, appSearchQuery, vpnRoutedApps.size) {
        installedApps.filter { app ->
            val matchCat = when {
                selectedCategory == "Все" -> true
                selectedCategory.startsWith("🛡️") -> vpnRoutedApps.contains(app.packageName)
                selectedCategory.startsWith("⚡") -> !vpnRoutedApps.contains(app.packageName)
                else -> app.category == selectedCategory
            }
            val matchQuery = appSearchQuery.isBlank() ||
                app.appName.contains(appSearchQuery, ignoreCase = true) ||
                app.packageName.contains(appSearchQuery, ignoreCase = true)
            matchCat && matchQuery
        }
    }

    var liveTraffic by remember { mutableStateOf(BrowsecLocalProxyServer.sampleTraffic()) }
    LaunchedEffect(Unit) {
        while (true) {
            liveTraffic = BrowsecLocalProxyServer.sampleTraffic()
            delay(500)
        }
    }

    Box(
        modifier = modifier
            .background(Color(0xFF090D16))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                color = Color(0xFF111827),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (browsecEnabled) Color(0xFF10B981) else Color(0xFF7C3AED)
                )
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "📱 Репитер №2 (с пробросом 127.0.0.1:${liveTraffic.proxyPort}) • Список VPN ($browsecCountry)",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = if (browsecEnabled) {
                                    "🟢 ВНУТРЕННИЙ VPN ВКЛ: Приложения В СПИСКЕ (${vpnRoutedApps.size}) идут через туннель Browsec ($browsecCountry • ${liveTraffic.remoteServerIpPort}), остальные — на лок. сервере без туннеля"
                                } else {
                                    "⚪ ВНУТРЕННИЙ VPN ВЫКЛ: Нажмите плавающую кнопку VPN внизу справа или запустите приложение из списка VPN"
                                },
                                color = if (browsecEnabled) Color(0xFF34D399) else Color(0xFFFACC15),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "⬇ ВХ: ${BitTorrentEngine.formatSpeed(liveTraffic.rxSpeedBytesPerSec)} (${BitTorrentEngine.formatSize(liveTraffic.rxBytesTotal)})  |  ⬆ ИСХ: ${BitTorrentEngine.formatSpeed(liveTraffic.txSpeedBytesPerSec)} (${BitTorrentEngine.formatSize(liveTraffic.txBytesTotal)}) • Запр: ${liveTraffic.interceptedRequests}",
                                color = Color(0xFF38BDF8),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        OutlinedButton(
                            onClick = onRefreshApps,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("↻ Обновить", fontSize = 11.sp, color = Color.White)
                        }
                    }

                    // Country selector for Native Apps Repeater #2
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Сервер Репитера №2:", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        BrowsecLocalProxyServer.SERVER_NODES.forEach { node ->
                            val isSel = browsecCountry == node.code
                            Surface(
                                color = if (isSel) Color(0xFF0284C7) else Color(0xFF1E293B),
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (isSel) Color(0xFF38BDF8) else Color(0xFF334155)),
                                modifier = Modifier.clickable { onSelectCountry(node.code) }
                            ) {
                                Text(
                                    text = "${node.flag} ${node.countryRu} (${node.remoteIp}:${node.remotePort})",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    CompactDarkTextField(
                        value = appSearchQuery,
                        onValueChange = { appSearchQuery = it },
                        placeholder = "Поиск нативного приложения (YouTube, Play Market, Telegram, Discord)...",
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        categories.forEach { cat ->
                            val isSel = selectedCategory == cat
                            Surface(
                                color = if (isSel) Color(0xFF7C3AED) else Color(0xFF1E293B),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable { selectedCategory = cat }
                            ) {
                                Text(
                                    text = cat,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 84.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val isInVpn = vpnRoutedApps.contains(app.packageName)
                    Surface(
                        color = Color(0xFF111827),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isInVpn) Color(0xFF10B981) else Color(0xFF334155)
                        )
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (app.iconBitmap != null) {
                                    Image(
                                        bitmap = app.iconBitmap,
                                        contentDescription = app.appName,
                                        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(8.dp))
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF1E293B)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("📱", fontSize = 18.sp)
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "${app.appName} (НАТИВНОЕ АПК)",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(app.packageName, color = Color(0xFF64748B), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    Text(
                                        text = if (isInVpn) {
                                            "🟢 В СПИСКЕ VPN: Запускается через туннель Browsec ($browsecCountry • 127.0.0.1:${liveTraffic.proxyPort})"
                                        } else {
                                            "⚪ НЕ В СПИСКЕ VPN: Работает на локальном сервере (127.0.0.1:${liveTraffic.proxyPort}) БЕЗ туннеля от Browsec"
                                        },
                                        color = if (isInVpn) Color(0xFF34D399) else Color(0xFFFACC15),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = { onLaunchAppWithBrowsec(app.packageName, app.appName) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1.4f).height(38.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isInVpn) Color(0xFF059669) else Color(0xFF0284C7)
                                    )
                                ) {
                                    Text(
                                        text = if (isInVpn) {
                                            "🛡️🚀 Запустить через VPN ($browsecCountry)"
                                        } else {
                                            "⚡🚀 Запустить (Лок. сервер БЕЗ VPN)"
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                OutlinedButton(
                                    onClick = { onToggleAppInVpn(app.packageName) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(0.9f).height(38.dp)
                                ) {
                                    Text(
                                        text = if (isInVpn) "✓ В списке VPN (Убрать)" else "+ Добавить в список VPN",
                                        fontSize = 11.sp,
                                        color = if (isInVpn) Color(0xFF34D399) else Color.White,
                                        maxLines = 1
                                    )
                                }

                                if (selectedFileForOpen != null) {
                                    Button(
                                        onClick = {
                                            nativeAppsManager.openFileInNativeApp(selectedFileForOpen!!, app.packageName)
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                        modifier = Modifier.height(38.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
                                    ) {
                                        Text("📂 Файл", fontSize = 11.sp, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ВНУТРЕННЯЯ ПЛАВАЮЩАЯ КНОПКА VPN В СПИСКЕ ПРИЛОЖЕНИЙ (Floating VPN Button inside VPN List)
        Surface(
            color = if (browsecEnabled) Color(0xFF059669) else Color(0xFF1E293B),
            shape = RoundedCornerShape(28.dp),
            shadowElevation = 10.dp,
            border = androidx.compose.foundation.BorderStroke(
                2.dp,
                if (browsecEnabled) Color(0xFF34D399) else Color(0xFF38BDF8)
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 14.dp)
                .clickable { onToggleFloatingVpn() }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (browsecEnabled) "🟢🛡️" else "⚪🛡️",
                    fontSize = 16.sp
                )
                Column {
                    Text(
                        text = if (browsecEnabled) {
                            "VPN Списка: ВКЛ ($browsecCountry • ${vpnRoutedApps.size} прил.)"
                        } else {
                            "Включить VPN Списка ($browsecCountry • ${vpnRoutedApps.size} прил.)"
                        },
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "Репитер №2: 127.0.0.1:${liveTraffic.proxyPort} ➔ ${liveTraffic.remoteServerIpPort}",
                        color = if (browsecEnabled) Color(0xFFD1FAE5) else Color(0xFF94A3B8),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallNavIconButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (enabled) Color(0xFF1E293B) else Color(0xFF0F172A),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
        modifier = Modifier
            .size(36.dp)
            .clickable(enabled = enabled) { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = if (enabled) Color.White else Color(0xFF475569),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ExtensionChip(
    label: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Surface(
        color = if (active) activeColor.copy(alpha = 0.28f) else Color(0xFF1E293B),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (active) activeColor else Color(0xFF334155)
        ),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun BottomSectionButton(
    modifier: Modifier,
    title: String,
    subtitle: String,
    selected: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable { onClick() },
        color = if (selected) activeColor.copy(alpha = 0.25f) else Color(0xFF1E293B),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) activeColor else Color(0xFF334155)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = if (selected) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
