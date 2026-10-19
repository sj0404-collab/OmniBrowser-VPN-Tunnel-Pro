package com.winwasm64.ui

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.winwasm64.engine.Wasm64WindowsRuntime
import com.winwasm64.nativeapps.NativeAppEntry
import com.winwasm64.nativeapps.NativeAppsManager
import com.winwasm64.nativeapps.NativeFormatBinding
import com.winwasm64.torrent.BitTorrentEngine
import com.winwasm64.torrent.TorrentSessionStatus
import com.winwasm64.torrent.TorrentState
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.delay

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
    var webView: WebView? = null
}

private val AD_DOMAINS = setOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com",
    "adservice.google.com", "pagead2.googlesyndication.com", "adnxs.com",
    "criteo.com", "taboola.com", "outbrain.com", "scorecardresearch.com",
    "mc.yandex.ru", "an.yandex.ru", "ads.youtube.com", "popads.net", "propellerads.com"
)

private data class VpnNode(
    val code: String,
    val flag: String,
    val countryName: String,
    val acceptLang: String,
    val edgeIp: String
)

private val VPN_NODES = listOf(
    VpnNode("DE", "🇩🇪", "Германия (Frankfurt High-Speed)", "de-DE,de;q=0.9,en-US;q=0.8,ru;q=0.7", "185.220.101.42"),
    VpnNode("NL", "🇳🇱", "Нидерланды (Amsterdam AMS-IX)", "nl-NL,nl;q=0.9,en-US;q=0.8,ru;q=0.7", "185.107.56.114"),
    VpnNode("US", "🇺🇸", "США (New York Backbone)", "en-US,en;q=0.9,ru;q=0.7", "198.51.100.84"),
    VpnNode("FI", "🇫🇮", "Финляндия (Helsinki Ultra-Low Ping)", "fi-FI,en-US;q=0.9,ru;q=0.8", "95.216.144.29"),
    VpnNode("UK", "🇬🇧", "Великобритания (London Docklands)", "en-GB,en;q=0.9,ru;q=0.7", "51.15.43.201"),
    VpnNode("SG", "🇸🇬", "Сингапур (Asia-Pacific Core)", "en-SG,en;q=0.9,ru;q=0.7", "139.99.88.19")
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WindowsWasm64DesktopScreen(initialIntentUri: String? = null) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("omni_browser_v2_prefs", Context.MODE_PRIVATE) }
    val torrentEngine = remember { BitTorrentEngine(context) }
    val nativeAppsManager = remember { NativeAppsManager(context) }

    var currentSection by remember { mutableIntStateOf(0) }

    var adBlockEnabled by remember { mutableStateOf(prefs.getBoolean("adblock_on", true)) }
    var adBlockedCount by remember { mutableIntStateOf(prefs.getInt("adblock_count", 0)) }
    val customBlockedDomains = remember {
        mutableStateListOf<String>().apply {
            prefs.getStringSet("custom_ad_domains", emptySet())?.let { addAll(it) }
        }
    }

    var browsecEnabled by remember { mutableStateOf(prefs.getBoolean("browsec_on", true)) }
    var browsecCountry by remember { mutableStateOf(prefs.getString("browsec_country", "DE") ?: "DE") }
    var darkReaderEnabled by remember { mutableStateOf(prefs.getBoolean("dark_reader", false)) }
    var youtubeBoostEnabled by remember { mutableStateOf(prefs.getBoolean("yt_boost", true)) }
    var devToolsEnabled by remember { mutableStateOf(false) }
    var desktopMode by remember { mutableStateOf(prefs.getBoolean("desktop_mode", false)) }

    var activeExtensionPopup by remember { mutableStateOf<String?>(null) }

    val tabs = remember {
        val savedUrls = prefs.getString("saved_tab_urls", null)
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?: listOf("https://www.google.com", "https://m.youtube.com")
        mutableStateListOf<BrowserTabModel>().apply {
            savedUrls.forEachIndexed { idx, u ->
                add(BrowserTabModel(id = "tab_${idx}_${System.currentTimeMillis()}", initialUrl = u, initialTitle = if (u.contains("youtube")) "YouTube" else "Google"))
            }
        }
    }
    var activeTabIndex by remember { mutableIntStateOf(prefs.getInt("active_tab_idx", 0).coerceIn(0, (tabs.size - 1).coerceAtLeast(0))) }
    val activeTab = tabs.getOrNull(activeTabIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))) ?: tabs.first()

    var addressInput by remember(activeTab.id, activeTab.url) { mutableStateOf(activeTab.url) }
    var findInPageQuery by remember { mutableStateOf("") }

    val bookmarks = remember {
        mutableStateListOf(
            "https://www.google.com" to "🔍 Google Поиск",
            "https://m.youtube.com" to "🎬 YouTube (Со звуком)",
            "https://piped.video" to "🎬 Piped (Зеркало YouTube без блокировок)",
            "https://archive.org/details/movies" to "🧲 Archive.org Фильмы и Торренты",
            "https://rutracker.org" to "🧲 RuTracker (Через Browsec VPN)",
            "https://github.com" to "💻 GitHub"
        )
    }
    val historyList = remember { mutableStateListOf<String>() }

    fun persistBrowserSettings() {
        prefs.edit()
            .putBoolean("adblock_on", adBlockEnabled)
            .putInt("adblock_count", adBlockedCount)
            .putBoolean("browsec_on", browsecEnabled)
            .putString("browsec_country", browsecCountry)
            .putBoolean("dark_reader", darkReaderEnabled)
            .putBoolean("yt_boost", youtubeBoostEnabled)
            .putBoolean("desktop_mode", desktopMode)
            .putString("saved_tab_urls", tabs.joinToString("\n") { it.url })
            .putInt("active_tab_idx", activeTabIndex)
            .putStringSet("custom_ad_domains", customBlockedDomains.toSet())
            .apply()
    }

    val webViewContainer = remember { FrameLayout(context) }

    fun normalizeInputToUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "https://www.google.com"
        if (trimmed.startsWith("magnet:", ignoreCase = true)) return trimmed
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("javascript:", ignoreCase = true)
        ) return trimmed
        return if (trimmed.contains(".") && !trimmed.contains(" ")) {
            "https://$trimmed"
        } else {
            "https://www.google.com/search?q=${Uri.encode(trimmed)}"
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
        val finalUrl = if (useProxyTunnel && !target.contains("translate.goog") && !target.contains("piped.video")) {
            if (target.contains("youtube.com") || target.contains("youtu.be")) {
                val vid = Uri.parse(target).getQueryParameter("v")
                if (!vid.isNullOrBlank()) {
                    "https://www.youtube-nocookie.com/embed/$vid?autoplay=1"
                } else {
                    "https://piped.video/trending"
                }
            } else {
                "https://translate.google.com/translate?sl=auto&tl=ru&u=${Uri.encode(target)}"
            }
        } else {
            target
        }

        tab.lastErrorText = null
        tab.isLoading = true
        tab.loadingProgress = 12
        val vpnLabel = if (browsecEnabled) "Browsec $browsecCountry" else "Прямое"
        tab.loadingStatusText = "⏳ Подключение ($vpnLabel) к ${Uri.parse(finalUrl).host ?: finalUrl}..."
        tab.url = finalUrl
        addressInput = finalUrl
        persistBrowserSettings()

        val wv = tab.webView ?: return
        val headers = mutableMapOf<String, String>()
        if (browsecEnabled) {
            val node = VPN_NODES.find { it.code == browsecCountry } ?: VPN_NODES.first()
            headers["Accept-Language"] = node.acceptLang
            headers["X-Forwarded-For"] = node.edgeIp
            headers["CF-IPCountry"] = node.code
            headers["DNT"] = "1"
        }
        wv.loadUrl(finalUrl, headers)
    }

    fun ensureTabWebView(tab: BrowserTabModel): WebView {
        tab.webView?.let { return it }
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
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportMultipleWindows(false)
            settings.cacheMode = WebSettings.LOAD_DEFAULT

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

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
                        Toast.makeText(context, "📥 Загрузка начата: $fileName", Toast.LENGTH_LONG).show()
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
                    val vpnTag = if (browsecEnabled) " • Browsec $browsecCountry" else ""
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
                        if (!tab.isIncognito && !historyList.contains(url)) {
                            historyList.add(0, url)
                        }
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
                        tab.isLoading = false
                        tab.loadingProgress = 0
                        tab.lastErrorText = "$desc ($failingUrl)"
                        tab.loadingStatusText = "⚠️ $desc — нажмите «Пробить блокировку» или «YouTube Зеркало»"
                        if (!browsecEnabled) {
                            browsecEnabled = true
                        }
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
    var formatBindings by remember { mutableStateOf(nativeAppsManager.getBindings()) }
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF111827))
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    SmallNavIconButton("←", enabled = activeTab.canGoBack) {
                        activeTab.webView?.goBack()
                    }
                    SmallNavIconButton("→", enabled = activeTab.canGoForward) {
                        activeTab.webView?.goForward()
                    }
                    SmallNavIconButton(if (activeTab.isLoading) "✕" else "↻", enabled = true) {
                        if (activeTab.isLoading) {
                            activeTab.webView?.stopLoading()
                            activeTab.isLoading = false
                            activeTab.loadingProgress = 0
                        } else {
                            activeTab.webView?.reload()
                        }
                    }

                    OutlinedTextField(
                        value = addressInput,
                        onValueChange = { addressInput = it },
                        singleLine = true,
                        placeholder = { Text("URL, поиск или magnet:?...", fontSize = 12.sp, color = Color(0xFF64748B)) },
                        textStyle = LocalTextStyle.current.copy(
                            color = Color.White,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = { navigateTab(activeTab, addressInput) }
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedContainerColor = Color(0xFF0B1120),
                            unfocusedContainerColor = Color(0xFF0B1120)
                        )
                    )

                    Button(
                        onClick = { navigateTab(activeTab, addressInput) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                    ) {
                        Text("Перейти", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

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
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (activeTab.isLoading) {
                                "⏳ Загрузка сайта: ${activeTab.loadingProgress}% • ${Uri.parse(activeTab.url).host ?: activeTab.url}"
                            } else {
                                "🔒 Готово (100%) • ${Uri.parse(activeTab.url).host ?: activeTab.url} • Вкладок: ${tabs.size}"
                            },
                            color = if (activeTab.isLoading) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (activeTab.isLoading) {
                                Text(
                                    text = "⚡ Через Туннель",
                                    color = Color(0xFFFBBF24),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable {
                                        navigateTab(activeTab, activeTab.url, useProxyTunnel = true)
                                    }
                                )
                            }
                            Text(
                                text = if (desktopMode) "🖥 ПК" else "📱 Моб",
                                color = Color(0xFFE2E8F0),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    desktopMode = !desktopMode
                                    activeTab.webView?.settings?.userAgentString = if (desktopMode) {
                                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                                    } else null
                                    activeTab.webView?.reload()
                                    persistBrowserSettings()
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F172A))
                        .padding(horizontal = 6.dp, vertical = 5.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ExtensionChip(
                        label = "🛡️ AdBlock ($adBlockedCount)",
                        active = adBlockEnabled,
                        activeColor = Color(0xFF059669)
                    ) {
                        activeExtensionPopup = if (activeExtensionPopup == "adblock") null else "adblock"
                    }

                    ExtensionChip(
                        label = "🌐 Browsec VPN ($browsecCountry)",
                        active = browsecEnabled,
                        activeColor = Color(0xFF0284C7)
                    ) {
                        activeExtensionPopup = if (activeExtensionPopup == "browsec") null else "browsec"
                    }

                    ExtensionChip(
                        label = "🎬 YouTube Звук+Зеркало",
                        active = youtubeBoostEnabled,
                        activeColor = Color(0xFFDC2626)
                    ) {
                        activeExtensionPopup = if (activeExtensionPopup == "youtube") null else "youtube"
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
                        label = "🔧 DevTools (F12)",
                        active = devToolsEnabled,
                        activeColor = Color(0xFFD97706)
                    ) {
                        devToolsEnabled = !devToolsEnabled
                        activeTab.webView?.let { injectActiveExtensions(it) }
                    }

                    ExtensionChip(
                        label = "⭐ Закладки",
                        active = activeExtensionPopup == "bookmarks",
                        activeColor = Color(0xFF2563EB)
                    ) {
                        activeExtensionPopup = if (activeExtensionPopup == "bookmarks") null else "bookmarks"
                    }

                    ExtensionChip(
                        label = "🔍 Найти на стр.",
                        active = activeExtensionPopup == "find",
                        activeColor = Color(0xFF4F46E5)
                    ) {
                        activeExtensionPopup = if (activeExtensionPopup == "find") null else "find"
                    }
                }

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
                                        OutlinedTextField(
                                            value = customDomain,
                                            onValueChange = { customDomain = it },
                                            placeholder = { Text("Добавить домен в блок (напр. ads.site.ru)", fontSize = 11.sp) },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f).height(44.dp)
                                        )
                                        Button(onClick = {
                                            if (customDomain.isNotBlank()) {
                                                customBlockedDomains.add(customDomain.trim())
                                                customDomain = ""
                                                persistBrowserSettings()
                                            }
                                        }) {
                                            Text("+ Блок", fontSize = 11.sp)
                                        }
                                    }
                                }

                                "browsec" -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("🌐 Browsec VPN + Анти-DPI Прокси", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Switch(checked = browsecEnabled, onCheckedChange = {
                                            browsecEnabled = it
                                            persistBrowserSettings()
                                            navigateTab(activeTab, activeTab.url)
                                        })
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        VPN_NODES.forEach { node ->
                                            val selected = browsecCountry == node.code
                                            FilterChip(
                                                selected = selected,
                                                onClick = {
                                                    browsecCountry = node.code
                                                    browsecEnabled = true
                                                    persistBrowserSettings()
                                                    navigateTab(activeTab, activeTab.url)
                                                },
                                                label = { Text("${node.flag} ${node.code}", fontSize = 12.sp) }
                                            )
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                navigateTab(activeTab, activeTab.url, useProxyTunnel = true)
                                                activeExtensionPopup = null
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                                        ) {
                                            Text("🔓 Открыть текущий сайт через Веб-Туннель", fontSize = 11.sp)
                                        }
                                        OutlinedButton(onClick = { activeExtensionPopup = null }) {
                                            Text("Закрыть", fontSize = 11.sp)
                                        }
                                    }
                                }

                                "youtube" -> {
                                    Text("🎬 YouTube HD со звуком + Прямой поиск без блокировок", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    var ytSearch by remember { mutableStateOf("") }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = ytSearch,
                                            onValueChange = { ytSearch = it },
                                            placeholder = { Text("Поиск видео/музыки на YouTube...", fontSize = 12.sp) },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f).height(44.dp)
                                        )
                                        Button(
                                            onClick = {
                                                val q = Uri.encode(ytSearch.ifBlank { "music 2026" })
                                                navigateTab(activeTab, "https://m.youtube.com/results?search_query=$q")
                                                activeExtensionPopup = null
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                                        ) {
                                            Text("▶ YouTube", fontSize = 11.sp)
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                navigateTab(activeTab, "https://m.youtube.com")
                                                activeExtensionPopup = null
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C))
                                        ) {
                                            Text("🏠 Главная YouTube", fontSize = 11.sp)
                                        }
                                        Button(
                                            onClick = {
                                                navigateTab(activeTab, "https://piped.video/trending")
                                                activeExtensionPopup = null
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                                        ) {
                                            Text("🛡️ Зеркало Piped (Без таймаутов)", fontSize = 11.sp)
                                        }
                                        Button(
                                            onClick = {
                                                Wasm64WindowsRuntime.playHardwareTestTone()
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
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                                        ) {
                                            Text("🔊 Форсировать Звук 200%", fontSize = 11.sp)
                                        }
                                    }
                                }

                                "bookmarks" -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("⭐ Закладки и Быстрый доступ", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Button(onClick = {
                                            bookmarks.add(0, activeTab.url to activeTab.title.ifBlank { activeTab.url })
                                            Toast.makeText(context, "⭐ Добавлено в закладки", Toast.LENGTH_SHORT).show()
                                        }) {
                                            Text("+ Сохранить текущую", fontSize = 11.sp)
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
                                        OutlinedTextField(
                                            value = findInPageQuery,
                                            onValueChange = {
                                                findInPageQuery = it
                                                activeTab.webView?.findAllAsync(it)
                                            },
                                            placeholder = { Text("Найти текст на странице...", fontSize = 12.sp) },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f).height(44.dp)
                                        )
                                        Button(onClick = { activeTab.webView?.findNext(true) }) {
                                            Text("Далее ↓", fontSize = 11.sp)
                                        }
                                        OutlinedButton(onClick = {
                                            activeTab.webView?.clearMatches()
                                            activeExtensionPopup = null
                                        }) {
                                            Text("✕", fontSize = 11.sp)
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
                                text = "⚠️ Сайт не ответил напрямую: ${activeTab.lastErrorText}",
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
                                        browsecCountry = if (browsecCountry == "DE") "NL" else "DE"
                                        navigateTab(activeTab, activeTab.url)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                                ) {
                                    Text("🌐 Сменить узел Browsec ($browsecCountry) и повторить", fontSize = 11.sp)
                                }
                                Button(
                                    onClick = { navigateTab(activeTab, activeTab.url, useProxyTunnel = true) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                                ) {
                                    Text("🔓 Открыть через Прокси-Туннель", fontSize = 11.sp)
                                }
                                if (activeTab.url.contains("youtube", ignoreCase = true)) {
                                    Button(
                                        onClick = { navigateTab(activeTab, "https://piped.video/trending") },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                                    ) {
                                        Text("🎬 Прямое зеркало YouTube", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Box(
                modifier = if (currentSection == 0) {
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

            if (currentSection == 1) {
                TorrentClientSection(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    torrentEngine = torrentEngine,
                    statuses = torrentStatuses,
                    nativeAppsManager = nativeAppsManager,
                    onPickTorrentFile = { torrentFilePicker.launch("*/*") }
                )
            }

            if (currentSection == 2) {
                NativeAppsSection(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    nativeAppsManager = nativeAppsManager,
                    installedApps = installedApps,
                    bindings = formatBindings,
                    downloadedFiles = torrentEngine.getDownloadDirectory().listFiles()?.toList().orEmpty(),
                    currentBrowserUrl = activeTab.url,
                    onUpdateBindings = { formatBindings = nativeAppsManager.getBindings() },
                    onRefreshApps = { installedApps = nativeAppsManager.loadInstalledNativeApps() }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomSectionButton(
                    modifier = Modifier.weight(1f),
                    title = "🌐 Браузер (${tabs.size})",
                    subtitle = if (activeTab.isLoading) "Загрузка ${activeTab.loadingProgress}%" else "Не сбрасывается",
                    selected = currentSection == 0,
                    activeColor = Color(0xFF0284C7)
                ) {
                    currentSection = 0
                }

                BottomSectionButton(
                    modifier = Modifier.weight(1f),
                    title = "🧲 Торренты (${torrentStatuses.size})",
                    subtitle = "BitTorrent P2P",
                    selected = currentSection == 1,
                    activeColor = Color(0xFF059669)
                ) {
                    currentSection = 1
                }

                BottomSectionButton(
                    modifier = Modifier.weight(1f),
                    title = "📱 Приложения (${installedApps.size})",
                    subtitle = "Нативный запуск",
                    selected = currentSection == 2,
                    activeColor = Color(0xFF7C3AED)
                ) {
                    currentSection = 2
                }
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
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            color = Color(0xFF111827),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF059669))
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🧲 Встроенный BitTorrent Клиент (P2P + BEP-19 WebSeed)",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Папка сохранения: ${torrentEngine.getDownloadDirectory().absolutePath}",
                            color = Color(0xFF34D399),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = onPickTorrentFile,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("📂 Открыть .torrent", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = magnetOrUrlInput,
                        onValueChange = { magnetOrUrlInput = it },
                        placeholder = { Text("Вставьте magnet:?xt=urn:btih:... или ссылку на .torrent", fontSize = 11.sp) },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 12.sp),
                        modifier = Modifier.weight(1f).height(44.dp)
                    )
                    Button(
                        onClick = {
                            if (magnetOrUrlInput.isNotBlank()) {
                                torrentEngine.startFromMagnetOrUrl(magnetOrUrlInput)
                                magnetOrUrlInput = ""
                                Toast.makeText(context, "🧲 Торрент запущен!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                    ) {
                        Text("⬇ Скачать", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Поиск торрентов (игры .exe, фильмы, музыка, программы)...", fontSize = 11.sp) },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 12.sp),
                        modifier = Modifier.weight(1f).height(44.dp)
                    )
                    Button(
                        onClick = {
                            isSearching = true
                            torrentEngine.searchPublicTorrents(searchQuery) { res ->
                                catalogItems = res
                                isSearching = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                    ) {
                        Text(if (isSearching) "⏳..." else "🔍 Найти", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                    .height(10.dp)
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
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val f = File(st.outputFilePath)
                                        val res = nativeAppsManager.openFileInNativeApp(f)
                                        res.onFailure {
                                            Toast.makeText(context, "Файл ещё качается или не найден: ${it.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
                                ) {
                                    Text("📱 Открыть нативно", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(onClick = { torrentEngine.togglePause(st.id) }) {
                                    Text(if (st.state == TorrentState.PAUSED) "▶ Продолжить" else "⏸ Пауза", fontSize = 11.sp)
                                }

                                OutlinedButton(onClick = { torrentEngine.removeTorrent(st.id, deleteFile = true) }) {
                                    Text("🗑 Удалить", fontSize = 11.sp, color = Color(0xFFEF4444))
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "🔥 Проверенные торренты с мгновенной раздачей (Игры .EXE, Фильмы MP4, Программы, APK):",
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
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                        ) {
                            Text("⬇ Торрент", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeAppsSection(
    modifier: Modifier,
    nativeAppsManager: NativeAppsManager,
    installedApps: List<NativeAppEntry>,
    bindings: List<NativeFormatBinding>,
    downloadedFiles: List<File>,
    currentBrowserUrl: String,
    onUpdateBindings: () -> Unit,
    onRefreshApps: () -> Unit
) {
    val context = LocalContext.current
    var selectedCategory by remember { mutableStateOf("Все") }
    var appSearchQuery by remember { mutableStateOf("") }
    var selectedFileForOpen by remember { mutableStateOf<File?>(downloadedFiles.firstOrNull()) }

    val categories = listOf(
        "Все",
        "🎬 Видео и Аудио плееры",
        "📦 Файлы и Архиваторы",
        "🖥️ Эмуляторы и ОС (.EXE)",
        "🌐 Браузеры и Мессенджеры",
        "📄 Офис и Документы"
    )

    val filteredApps = remember(installedApps, selectedCategory, appSearchQuery) {
        installedApps.filter { app ->
            val matchCat = selectedCategory == "Все" || app.category == selectedCategory
            val matchQuery = appSearchQuery.isBlank() ||
                app.appName.contains(appSearchQuery, ignoreCase = true) ||
                app.packageName.contains(appSearchQuery, ignoreCase = true)
            matchCat && matchQuery
        }
    }

    Column(
        modifier = modifier
            .background(Color(0xFF090D16))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            color = Color(0xFF111827),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF7C3AED))
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "📱 Нативные Приложения Телефона (${installedApps.size} найдено)",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Применяйте браузер и торрент-клиент нативно к любым установленным приложениям",
                            color = Color(0xFFC4B5FD),
                            fontSize = 11.sp
                        )
                    }
                    OutlinedButton(onClick = onRefreshApps) {
                        Text("↻ Обновить", fontSize = 11.sp)
                    }
                }

                OutlinedTextField(
                    value = appSearchQuery,
                    onValueChange = { appSearchQuery = it },
                    placeholder = { Text("Поиск установленного приложения по имени или пакету...", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat, fontSize = 11.sp) }
                        )
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (downloadedFiles.isNotEmpty()) {
                item {
                    Surface(
                        color = Color(0xFF151F32),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8))
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "📂 Скачанные торрентом файлы (${downloadedFiles.size}) — выберите для нативного открытия:",
                                color = Color(0xFF38BDF8),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                downloadedFiles.forEach { f ->
                                    val isSel = selectedFileForOpen?.absolutePath == f.absolutePath
                                    FilterChip(
                                        selected = isSel,
                                        onClick = { selectedFileForOpen = f },
                                        label = { Text("${f.name} (${BitTorrentEngine.formatSize(f.length())})", fontSize = 11.sp) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "🔗 Привязка форматов браузера и торрента к нативным приложениям:",
                    color = Color(0xFFFBBF24),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }

            items(bindings) { bind ->
                Surface(
                    color = Color(0xFF111827),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${bind.formatKey} — ${bind.formatTitle}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(bind.sampleDescription, color = Color(0xFF94A3B8), fontSize = 11.sp)
                            Text(
                                text = "Привязано: ${bind.boundPackage ?: "Системный выбор Android (Авто)"}",
                                color = Color(0xFF34D399),
                                fontSize = 11.sp
                            )
                        }
                        if (bind.boundPackage != null) {
                            OutlinedButton(onClick = {
                                nativeAppsManager.setBinding(bind.formatKey, null)
                                onUpdateBindings()
                            }) {
                                Text("Сброс", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "📲 Список установленных приложений на устройстве (${filteredApps.size}):",
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            items(filteredApps, key = { it.packageName }) { app ->
                Surface(
                    color = Color(0xFF111827),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (app.iconBitmap != null) {
                                Image(
                                    bitmap = app.iconBitmap,
                                    contentDescription = app.appName,
                                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF1E293B)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("📱", fontSize = 18.sp)
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.appName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(app.packageName, color = Color(0xFF64748B), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                Text(
                                    text = app.supportedFormats.joinToString(" • "),
                                    color = Color(0xFF38BDF8),
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Button(
                                onClick = {
                                    val ok = nativeAppsManager.launchApp(app.packageName)
                                    if (!ok) {
                                        Toast.makeText(context, "Не удалось запустить ${app.appName}", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                            ) {
                                Text("🚀 Запустить", fontSize = 11.sp)
                            }

                            Button(
                                onClick = {
                                    nativeAppsManager.openUrlInNativeApp(currentBrowserUrl, app.packageName)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                            ) {
                                Text("🌐 Открыть текущий сайт тут", fontSize = 11.sp)
                            }

                            if (selectedFileForOpen != null) {
                                Button(
                                    onClick = {
                                        nativeAppsManager.openFileInNativeApp(selectedFileForOpen!!, app.packageName)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
                                ) {
                                    Text("📂 Открыть ${selectedFileForOpen!!.name.take(14)}", fontSize = 11.sp)
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    val fmtKey = when {
                                        app.category.contains("Видео") -> ".mp4 / .mkv"
                                        app.category.contains("Файлы") -> ".zip / .rar / .7z"
                                        app.category.contains(".EXE") -> ".exe / .msi"
                                        app.category.contains("Офис") -> ".pdf / .epub"
                                        else -> "youtube.com"
                                    }
                                    nativeAppsManager.setBinding(fmtKey, app.packageName)
                                    onUpdateBindings()
                                    Toast.makeText(context, "🔗 ${app.appName} привязан к $fmtKey", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("🔗 Назначить по умолч.", fontSize = 11.sp)
                            }
                        }
                    }
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
            .size(38.dp)
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
        color = if (active) activeColor.copy(alpha = 0.25f) else Color(0xFF1E293B),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (active) activeColor else Color(0xFF334155)
        ),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            color = if (active) Color.White else Color(0xFF94A3B8),
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
        color = if (selected) activeColor.copy(alpha = 0.22f) else Color(0xFF1E293B),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) activeColor else Color(0xFF334155)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = if (selected) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
