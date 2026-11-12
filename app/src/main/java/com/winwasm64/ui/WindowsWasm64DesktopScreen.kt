package com.winwasm64.ui

import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.webkit.ValueCallback
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.verticalScroll
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.winwasm64.extensions.ChromeWebStore
import com.winwasm64.extensions.ExtTab
import com.winwasm64.extensions.ExtensionRuntime
import com.winwasm64.extensions.ExtensionStore
import com.winwasm64.extensions.ExtensionTabHost
import com.winwasm64.extensions.ExtensionProxy
import com.winwasm64.extensions.StoreBridge
import com.winwasm64.ui.browser.*
import com.winwasm64.ui.theme.Omni
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
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
import com.winwasm64.vpn.ALL_VPN_COUNTRIES_LIST
import com.winwasm64.vpn.BrowserDirectRepeater
import com.winwasm64.vpn.BrowsecLocalProxyServer
import com.winwasm64.vpn.BrowsecVpnService
import com.winwasm64.vpn.TransparentTunEngine
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var proxyRepairCount by mutableIntStateOf(0)
    /** v4.3.0: на странице замечено видео/аудио — показываем плавающую кнопку «Скачать». */
    var hasMedia by mutableStateOf(false)
    /** v4.9.2: «Версия для ПК» только для этой вкладки (меню → «Открыть в версии для ПК»). */
    var desktopSite by mutableStateOf(false)
    @Volatile var mediaPlaying: Boolean = false // v4.2.0: во вкладке играет видео/аудио
    var webView: WebView? = null
    val numericId: Int = BrowserSessionVault.nextNumericId()
}

/** v4.3.0: состояние шторки «Скачать видео»: null в options — ещё ищем. */
class MediaGrabState(val title: String) {
    var options by mutableStateOf<List<MediaGrabber.Option>?>(null)
    var notice by mutableStateOf("")
    /** v4.3.7: адрес страницы/видео для «скачать через сторонние сервисы». */
    var shareUrl: String = ""
}

/**
 * Process-wide vault that keeps live WebView tabs, scroll state, and active tab index intact
 * across Activity recreation, screen lock, and app switching so pages NEVER reset on reopen.
 */
object BrowserSessionVault {
    val tabs = mutableStateListOf<BrowserTabModel>()
    var activeTabIndex by mutableIntStateOf(0)
    var initialized = false
    private var numericCounter = 1
    fun nextNumericId(): Int = numericCounter++
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

val VPN_NODES = ALL_VPN_COUNTRIES_LIST.map { node ->
    VpnNode(
        code = node.code,
        flag = node.flag,
        countryName = "${node.countryRu} (${node.serverName})",
        acceptLang = "${node.code.lowercase()}-${node.code},ru-RU,ru;q=0.9,en-US;q=0.8",
        edgeIp = node.remoteIp
    )
}

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
            .background(Omni.Surface)
            .border(1.dp, Omni.Accent, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                color = Omni.TextDim,
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
            cursorBrush = SolidColor(Omni.Accent),
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
    var syncBothVpnsAuto by remember { mutableStateOf(prefs.getBoolean("sync_both_vpns_auto", false)) }
    var autoHealEnabled by remember { mutableStateOf(prefs.getBoolean("auto_heal_vpn_enabled", false)) }
    var showServerPickerModal by remember { mutableStateOf(false) }
    var serverPickerTarget by remember { mutableStateOf("both") } // "browser", "apps", "both", или "app:<pkg>"
    var lastHandledPickerReq by remember { mutableIntStateOf(BrowsecVpnService.openServerPickerRequestCount) }
    var appServerMapVersion by remember { mutableIntStateOf(0) }
    var disconnectOnClose by remember { mutableStateOf(prefs.getBoolean("disconnect_on_close", true)) }
    var searchEngine by remember { mutableStateOf(prefs.getString("search_engine", "Google") ?: "Google") }
    var textZoomPercent by remember { mutableIntStateOf(prefs.getInt("text_zoom_pct", 100)) }
    var dohServer by remember { mutableStateOf(prefs.getString("doh_server", "1.1.1.1") ?: "1.1.1.1") }
    var blockThirdPartyCookies by remember { mutableStateOf(prefs.getBoolean("block_3p_cookies", false)) }
    // v4.10.9: плавающая кнопка «Скачать видео» перекрывала поле ввода на сайтах без видео — её можно выключить
    var hideMediaButton by remember { mutableStateOf(prefs.getBoolean("hide_media_button", false)) }
    // v4.12.0: держать один сервер Browsec (IP не меняется) и обходить чужой VPN на телефоне
    var pinSessionServer by remember { mutableStateOf(prefs.getBoolean("pin_session_server", true)) }
    var bypassForeignVpn by remember { mutableStateOf(prefs.getBoolean("bypass_foreign_vpn", true)) }
    var webrtcTcpAll by remember { mutableStateOf(prefs.getBoolean("webrtc_tcp_all", false)) }
    var autoNextVideo by remember { mutableStateOf(prefs.getBoolean("auto_next_video", true)) }
    var doNotTrackEnabled by remember { mutableStateOf(prefs.getBoolean("dnt_enabled", true)) }
    var homePageUrl by remember { mutableStateOf(prefs.getString("home_page_url", "https://www.google.com") ?: "https://www.google.com") }
    var darkReaderEnabled by remember { mutableStateOf(prefs.getBoolean("dark_reader", false)) }
    var youtubeBoostEnabled by remember { mutableStateOf(prefs.getBoolean("yt_boost", true)) }
    var devToolsEnabled by remember { mutableStateOf(false) }
    var desktopMode by remember { mutableStateOf(prefs.getBoolean("desktop_mode", false)) }
    var directSitesVersion by remember { mutableIntStateOf(0) } // v4.10.1: перерисовка пункта «Сайт напрямую»
    var longPressedUrl by remember { mutableStateOf<String?>(null) }
    var fullscreenCustomView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    // v4.19.0: своя панель ввода для полноэкранных игр (клавиатура сайта не всегда доносит нажатия)
    var showKeyPanel by remember { mutableStateOf(false) }

    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
    }

    LaunchedEffect(Unit) {
        // Восстанавливаем индивидуальные серверы для каждого приложения
        prefs.getStringSet("vpn_app_server_map", emptySet())?.forEach { entry ->
            val pkg = entry.substringBefore("=", "").trim()
            val code = entry.substringAfter("=", "").trim()
            if (pkg.isNotEmpty() && code.isNotEmpty()) {
                BrowsecLocalProxyServer.setAppServerCountry(pkg, code, resetTraffic = false)
            }
        }

        // 1. Инициализируем Репитер №1 (Только для Браузера, без проброса в 127.0.0.1:8899)
        BrowserDirectRepeater.enabled = browserVpnEnabled
        BrowserDirectRepeater.countryCode = browserVpnCountry
        BrowserDirectRepeater.syncWithAppsVpn = syncBothVpnsAuto
        BrowserDirectRepeater.autoFailoverEnabled = autoHealEnabled
        // v4.30.0: резервные каналы — поднимаем то, что уже скачано, и восстанавливаем переключатель
        com.winwasm64.vpn.ReserveChain.autoEnabled = prefs.getBoolean("reserve_auto_enabled", true)
        com.winwasm64.vpn.ReserveChain.preferReserve = prefs.getBoolean("reserve_prefer", false)
        com.winwasm64.vpn.ReserveChain.applyPool(com.winwasm64.vpn.ReservePool.load(context))
        BrowserDirectRepeater.ensureDirectBrowserMode()

        // 2. Инициализируем Репитер №2 (Локальный сервер 127.0.0.1:8899 для раздела Приложений)
        BrowsecVpnService.activeCountry = browsecCountry
        BrowsecVpnService.disconnectOnAppClose = disconnectOnClose
        BrowsecLocalProxyServer.dohProvider = dohServer
        BrowsecLocalProxyServer.autoFailoverEnabled = autoHealEnabled
        BrowsecLocalProxyServer.initAssetBundleIfNeeded(context)
        BrowsecLocalProxyServer.ensureRunning()

        BrowserDirectRepeater.onCountryAutoSwitched = { newCode, _ ->
            browserVpnCountry = newCode
            if (syncBothVpnsAuto) {
                browsecCountry = newCode
            }
        }
        BrowsecLocalProxyServer.onCountryAutoSwitched = { newCode, _ ->
            browsecCountry = newCode
            if (syncBothVpnsAuto) {
                browserVpnCountry = newCode
            }
        }

        while (true) {
            browserTrafficSnapshot = BrowserDirectRepeater.sampleBrowserTraffic()
            trafficSnapshot = BrowsecLocalProxyServer.sampleTraffic()
            if (BrowserDirectRepeater.countryCode != browserVpnCountry) {
                browserVpnCountry = BrowserDirectRepeater.countryCode
            }
            if (BrowsecVpnService.activeCountry != browsecCountry) {
                browsecCountry = BrowsecVpnService.activeCountry
            }
            if (BrowsecVpnService.isPaused != browsecPaused) {
                browsecPaused = BrowsecVpnService.isPaused
            }
            if (BrowsecVpnService.isRunning != browsecEnabled) {
                browsecEnabled = BrowsecVpnService.isRunning
            }
            if (BrowsecVpnService.openServerPickerRequestCount != lastHandledPickerReq) {
                lastHandledPickerReq = BrowsecVpnService.openServerPickerRequestCount
                serverPickerTarget = "both"
                showServerPickerModal = true
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
    remember {
        // Инкогнито-профиль очищается при каждом запуске (если WebView поддерживает профили).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                ProfileStore.getInstance().deleteProfile("omni_incognito")
            } catch (_: Throwable) {
            }
        }
        ExtensionRuntime.init(context.applicationContext)
        true
    }
    var extPopupFor by remember { mutableStateOf<String?>(null) }
    var extOptionsFor by remember { mutableStateOf<String?>(null) }
    var showExtensionsSheet by remember { mutableStateOf(false) }
    var showAiSheet by remember { mutableStateOf(false) }
    var showTabsSheet by remember { mutableStateOf(false) }
    var showMenuSheet by remember { mutableStateOf(false) }
    var findBarVisible by remember { mutableStateOf(false) }
    var findTotal by remember { mutableIntStateOf(0) }
    var findCurrent by remember { mutableIntStateOf(0) }
    var longPressImageUrl by remember { mutableStateOf<String?>(null) }
    var fileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val blobBridge = remember { BlobDownloadBridge(context.applicationContext) }

    // v4.3.0: плавающая кнопка «Скачать видео» → шторка с вариантами качества / только аудио
    var grabSheet by remember { mutableStateOf<MediaGrabState?>(null) }
    fun openMediaGrabber(tab: BrowserTabModel) {
        val wv = tab.webView ?: return
        val state = MediaGrabState(tab.title)
        grabSheet = state
        com.winwasm64.ui.browser.InPageDownloads.register(tab.id, wv)
        val pageUrl = wv.url ?: tab.url
        state.shareUrl = pageUrl
        val ua = try { wv.settings.userAgentString } catch (_: Exception) { null }
        val cookie = if (tab.isIncognito) null else try { CookieManager.getInstance().getCookie(pageUrl) } catch (_: Exception) { null }
        wv.evaluateJavascript("(function(){ try { return window.__omniListMedia ? window.__omniListMedia() : '{}'; } catch (e) { return '{}'; } })()") { raw ->
            val json = try { (org.json.JSONTokener(raw ?: "\"{}\"").nextValue() as? String) ?: "{}" } catch (_: Exception) { "{}" }
            thread(name = "omni-grab") {
                val opts = try { MediaGrabber.buildOptions(json, pageUrl, ua, cookie, tab.id) } catch (e: Exception) {
                    com.winwasm64.vpn.VpnLog.w(MediaGrabber.TAG, "поиск источников: ${e.message ?: e.javaClass.simpleName}")
                    emptyList()
                }
                opts.forEach { it.job.tabId = tab.id }
                com.winwasm64.vpn.VpnLog.i(MediaGrabber.TAG, "«${state.title.take(40)}»: найдено вариантов — ${opts.size}")
                val notice = MediaGrabber.lastNotice
                Handler(Looper.getMainLooper()).post { state.notice = notice; state.options = opts }
            }
        }
    }

    val tabs = remember {
        if (!BrowserSessionVault.initialized || BrowserSessionVault.tabs.isEmpty()) {
            val savedUrls = prefs.getString("saved_tab_urls", null)
                ?.split("\n")
                ?.filter {
                    it.isNotBlank() &&
                        !it.contains("piped.video") &&
                        !it.startsWith("file:") &&
                        !it.startsWith("data:") &&
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
        val list = mutableStateListOf<Pair<String, String>>()
        val saved = prefs.getString("bookmarks_v4", null)
        if (saved != null) {
            saved.split("\n").forEach { line ->
                val i = line.indexOf('\t')
                if (i > 0) list.add(line.substring(0, i) to line.substring(i + 1))
            }
        } else {
            list.add("https://www.google.com" to "Google")
            list.add("https://m.youtube.com" to "YouTube")
            list.add("https://github.com" to "GitHub")
            list.add("https://archive.org" to "Internet Archive")
        }
        list
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
            // v4.11.0: маршрут сервиса ровно такой же, как список в настройках (без «тихо добавленных»)
            BrowsecVpnService.syncRoutedFromPrefs(context)
        }
    }

    fun persistBrowserSettings() {
        val currentUrls = tabs.filter { !it.isIncognito }.map { t ->
            val liveUrl = t.webView?.url
            if (!liveUrl.isNullOrBlank() && liveUrl != "about:blank") liveUrl else t.url
        }
        val appServerSet = vpnRoutedApps.map { pkg ->
            "$pkg=${BrowsecLocalProxyServer.getAppServerCountry(pkg)}"
        }.toSet()
        prefs.edit()
            .putBoolean("adblock_on", adBlockEnabled)
            .putInt("adblock_count", adBlockedCount)
            .putBoolean("browser_vpn_on", browserVpnEnabled)
            .putString("browser_vpn_country", browserVpnCountry)
            .putBoolean("apps_vpn_on", browsecEnabled)
            .putBoolean("browsec_paused", browsecPaused)
            .putString("apps_vpn_country", browsecCountry)
            .putBoolean("sync_both_vpns_auto", syncBothVpnsAuto)
            .putBoolean("auto_heal_vpn_enabled", autoHealEnabled)
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
            .putStringSet("vpn_app_server_map", appServerSet)
            .putString("bookmarks_v4", bookmarks.joinToString("\n") { "${it.first}\t${it.second}" })
            .apply()
    }

    var pendingVpnLaunchPkg by remember { mutableStateOf<String?>(null) }
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val pkg = pendingVpnLaunchPkg
        BrowsecLocalProxyServer.useBrowsecTunnel = true
        val appCountry = if (!pkg.isNullOrBlank()) {
            BrowsecLocalProxyServer.getAppServerCountry(pkg)
        } else {
            browsecCountry
        }
        BrowsecVpnService.startForApp(context, appCountry, pkg)
        if (!pkg.isNullOrBlank()) {
            nativeAppsManager.launchApp(pkg, useVpnTunnel = true)
            Toast.makeText(context, "🛡️ Запущено через индивидуальный сервер VPN ($appCountry)!", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "🛡️ Внутренний VPN списка приложений ($browsecCountry) активен!", Toast.LENGTH_SHORT).show()
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

    fun startWebDownload(tab: BrowserTabModel, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        when {
            url.startsWith("blob:", ignoreCase = true) -> {
                tab.webView?.evaluateJavascript(blobBridge.scriptFor(url, fileName), null)
                Toast.makeText(context, "Сохраняю $fileName…", Toast.LENGTH_SHORT).show()
            }
            url.startsWith("data:", ignoreCase = true) -> {
                try {
                    val decoded = DownloadSaver.decodeDataUrl(url) ?: throw IllegalArgumentException("неверная data:-ссылка")
                    val where = DownloadSaver.saveBytes(context, fileName, mimeType?.ifBlank { null } ?: decoded.first, decoded.second)
                    Toast.makeText(context, "Сохранено: $where", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Не удалось сохранить: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            else -> {
                // v4.49.0: качаем в папку приложения и регистрируем загрузку, чтобы её было видно
                // во вкладке «Загрузки», а не только в шторке системного загрузчика
                val ref = tab.webView?.url
                val cookie = if (!tab.isIncognito) CookieManager.getInstance().getCookie(url) else null
                val id = com.winwasm64.torrent.AppDownloads.enqueue(
                    context = context,
                    url = url,
                    fileName = fileName,
                    mimeType = mimeType,
                    userAgent = userAgent,
                    cookie = cookie,
                    referer = ref
                )
                if (id == null) {
                    Toast.makeText(context, "Не удалось начать загрузку: $fileName", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(
                        context,
                        "⬇ $fileName → Загрузки/${com.winwasm64.torrent.AppDownloads.SUBDIR}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * v4.10.9: «скачивался сам .torrent-файл вместо содержимого» — сайты отдают торрент ссылкой
     * `download.php?id=...`, с `Content-Disposition: attachment; filename="x.torrent"` или типом
     * `application/octet-stream`. Проверяем не только конец URL, но и имя файла из заголовка.
     */
    fun isTorrentLink(url: String, mimeType: String?, contentDisposition: String?): Boolean {
        val u = url.trim()
        if (u.startsWith("magnet:", ignoreCase = true)) return true
        val mime = (mimeType ?: "").lowercase()
        if (mime.contains("bittorrent")) return true
        val cd = (contentDisposition ?: "").lowercase()
        if (cd.contains(".torrent")) return true
        val path = u.substringBefore('#').substringBefore('?').lowercase()
        return path.endsWith(".torrent") || path.contains(".torrent/")
    }

    /** v4.33.0: локальный файл — это .torrent? (по имени или по типу из ContentResolver). */
    fun isLocalTorrentUri(uriStr: String): Boolean {
        val lower = uriStr.lowercase()
        if (lower.contains(".torrent")) return true
        // по URI файла тип определяем только если он явно торрентный: иначе любой content://
        // (картинка, apk, документ) уходил бы в торрент-движок
        return try {
            val type = context.contentResolver.getType(Uri.parse(uriStr))?.lowercase().orEmpty()
            type.contains("bittorrent")
        } catch (_: Exception) {
            false
        }
    }

    /** v4.33.0: схемы, которые относятся к обмену файлами, но не magnet и не http. */
    fun isTorrentishScheme(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("magnet:") || u.startsWith("thunder:") || u.startsWith("td:") ||
            u.startsWith("ed2k:") || u.startsWith("dchttp:") || u.startsWith("dchub:")
    }

    /** Тело настоящего .torrent — bencode-словарь с `announce`/`info`. */
    fun looksLikeTorrentBytes(b: ByteArray): Boolean {
        if (b.size < 32) return false
        val head = String(b, 0, minOf(b.size, 4096), Charsets.ISO_8859_1)
        return head.startsWith("d") && (head.contains("8:announce") || head.contains("4:info"))
    }

    /**
     * v4.33.0: открыть .torrent, пришедший файлом (content:// или file://) — например,
     * когда пользователь нажал на скачанный .torrent и выбрал OmniBrowser.
     */
    fun openTorrentFromUri(uriStr: String) {
        Toast.makeText(context, "🧲 Читаю файл .torrent…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val uri = Uri.parse(uriStr)
                val bytes = if (uriStr.startsWith("content:", ignoreCase = true)) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw java.io.IOException("пустой файл")
                } else {
                    java.io.File(uri.path ?: throw java.io.IOException("нет пути")).readBytes()
                }
                if (!looksLikeTorrentBytes(bytes)) throw java.io.IOException("это не .torrent (${bytes.size} Б)")
                val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file.torrent"
                val id = torrentEngine.startFromTorrentBytes(bytes, name)
                Handler(Looper.getMainLooper()).post {
                    currentSection = 1
                    Toast.makeText(context, "🧲 Торрент запущен: ${id.take(8)}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                com.winwasm64.vpn.VpnLog.w("Торрент", "не удалось открыть .torrent файлом: ${e.message}")
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Не удалось открыть .torrent: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Тело ответа через локальный прокси браузера (внутри VPN). */
    fun fetchTorrentBytesViaProxy(url: String, headers: Map<String, String>): ByteArray {
        val c = com.winwasm64.ui.browser.MediaGrabber.open(url, headers)
        return try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            com.winwasm64.ui.browser.MediaGrabber.readAll(c.inputStream, 16 * 1024 * 1024)
        } finally {
            c.disconnect()
        }
    }

    /** Тело ответа напрямую, без прокси (если VPN/прокси сейчас не поднят). */
    fun fetchTorrentBytesDirect(url: String, headers: Map<String, String>): ByteArray {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        return try {
            c.connectTimeout = 15_000
            c.readTimeout = 25_000
            c.instanceFollowRedirects = true
            for ((k, v) in headers) if (v.isNotBlank()) c.setRequestProperty(k, v)
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            com.winwasm64.ui.browser.MediaGrabber.readAll(c.inputStream, 16 * 1024 * 1024)
        } finally {
            c.disconnect()
        }
    }

    /**
     * Скачивает .torrent по ссылке и отдаёт его торрент-движку (а не в «Загрузки»).
     * Если по ссылке пришёл не торрент — откатываемся на обычную загрузку файла.
     */
    fun startTorrentFromUrl(tab: BrowserTabModel, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        Toast.makeText(context, "🧲 Читаю .torrent и запускаю загрузку…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val headers = LinkedHashMap<String, String>()
                headers["Referer"] = tab.webView?.url ?: url
                if (!userAgent.isNullOrBlank()) headers["User-Agent"] = userAgent
                if (!tab.isIncognito) CookieManager.getInstance().getCookie(url)?.let { headers["Cookie"] = it }
                val bytes = try {
                    fetchTorrentBytesViaProxy(url, headers)
                } catch (e: Exception) {
                    com.winwasm64.vpn.VpnLog.w("Торрент", "через прокси не вышло (${e.message}) — читаю напрямую")
                    fetchTorrentBytesDirect(url, headers)
                }
                if (!looksLikeTorrentBytes(bytes)) throw java.io.IOException("по ссылке не .torrent (${bytes.size} Б)")
                val fallbackName = URLUtil.guessFileName(url, contentDisposition, mimeType).ifBlank { "link.torrent" }
                val id = torrentEngine.startFromTorrentBytes(bytes, fallbackName)
                val hostLabel = try { java.net.URI(url).host ?: "сайт" } catch (_: Exception) { "сайт" }
                com.winwasm64.vpn.VpnLog.i("Торрент", "по ссылке $hostLabel: разобран .torrent (${bytes.size} Б), задача $id")
                Handler(Looper.getMainLooper()).post {
                    currentSection = 1
                    Toast.makeText(context, "🧲 Торрент запущен: ${id.take(8)}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                // v4.33.0: раньше здесь был откат в обычную загрузку — .torrent падал в «Загрузки»,
                // а при нажатии на него Android предлагал сторонние торрент-клиенты.
                // Теперь никаких внешних приложений: либо свой клиент, либо понятная ошибка.
                com.winwasm64.vpn.VpnLog.w("Торрент", "не удалось прочитать .torrent по ссылке: ${e.message}")
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        context,
                        "Не удалось прочитать .torrent по ссылке: ${e.message}. Скачайте файл и откройте его через «Файл .torrent».",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    fun normalizeInputToUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return homePageUrl
        if (trimmed.startsWith("magnet:", ignoreCase = true)) return trimmed
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("javascript:", ignoreCase = true) ||
            trimmed.startsWith("data:", ignoreCase = true) ||
            trimmed.startsWith("file:", ignoreCase = true) ||
            trimmed.startsWith("content:", ignoreCase = true) ||
            trimmed.startsWith("about:", ignoreCase = true)
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
        val repeaterPort = BrowserDirectRepeater.activePort
        val scripts = StringBuilder()
        // 1. Всегда гарантируем видимость видеоплеера YouTube (#movie_player и <video>) и приоритет аппаратного H.264 (avc1)
        scripts.append(
            """
            (function(){
                try {
                    if (!window.__omniCodecPatched && window.MediaSource && MediaSource.isTypeSupported) {
                        window.__omniCodecPatched = true;
                        const origSupported = MediaSource.isTypeSupported.bind(MediaSource);
                        MediaSource.isTypeSupported = function(mime) {
                            if (!mime) return false;
                            const m = String(mime).toLowerCase();
                            if (m.indexOf('av01') !== -1 || m.indexOf('vp9') !== -1 || m.indexOf('vp09') !== -1) {
                                return false;
                            }
                            return origSupported(mime);
                        };
                    }
                } catch(e){}
                ['#movie_player', '.html5-video-player', '#player-container-id', '#player', 'ytm-player', 'video'].forEach(sel => {
                    document.querySelectorAll(sel).forEach(el => {
                        if (el.style.display === 'none') el.style.display = 'block';
                        el.style.visibility = 'visible';
                        el.style.opacity = '1';
                    });
                });
            })();
            """.trimIndent()
        )
        if (adBlockEnabled) {
            scripts.append(
                """
                (function(){
                    // ВАЖНО: Никогда не скрываем .ad-showing целиком, так как этот класс вешается на сам #movie_player!
                    const sel = ['.ytp-ad-module','.ytp-ad-overlay-container','.ytp-ad-player-overlay','ytd-ad-slot-renderer','ytm-promoted-sparkles-web-renderer','[id^="google_ads"]','.adsbygoogle'];
                    sel.forEach(s => document.querySelectorAll(s).forEach(el => el.style.display='none'));
                    const skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-overlay-close-button');
                    if (skipBtn) skipBtn.click();
                    const player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                    const v = document.querySelector('video');
                    if (v && player && player.classList.contains('ad-showing') && isFinite(v.duration) && v.duration > 0 && v.duration < 300) {
                        v.currentTime = v.duration - 0.1;
                    }
                })();
                """.trimIndent()
            )
        }
        if (youtubeBoostEnabled) {
            val allowYtProxy = if (browserVpnEnabled) "true" else "false"
            scripts.append(
                """
                (function(){
                    try {
                        Object.defineProperty(document, 'hidden', { get: function(){ return false; }, configurable: true });
                        Object.defineProperty(document, 'visibilityState', { get: function(){ return 'visible'; }, configurable: true });
                    } catch(e){}
                    document.querySelectorAll('video, audio').forEach(v => {
                        v.style.display = 'block';
                        v.style.visibility = 'visible';
                        v.style.opacity = '1';
                        v.muted = false;
                        if (v.volume < 1.0) v.volume = 1.0;
                        const src = v.currentSrc || v.src || '';
                        if ($allowYtProxy && src.indexOf('googlevideo.com/videoplayback') !== -1 && src.indexOf('127.0.0.1') === -1) {
                            if (v.readyState === 0 && !v.dataset.omniProxied) {
                                if (!v.dataset.omniStallStart) {
                                    v.dataset.omniStallStart = String(Date.now());
                                } else if (Date.now() - Number(v.dataset.omniStallStart) > 2200) {
                                    v.dataset.omniProxied = '1';
                                    const t = v.currentTime || 0;
                                    v.src = 'http://127.0.0.1:$repeaterPort/ytstream?url=' + encodeURIComponent(src);
                                    v.currentTime = t;
                                    v.play().catch(function(){});
                                }
                            }
                        }
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
        // v4.2.1: учёт воспроизведения — и там, где WebView не поддерживает скрипты «до загрузки» (старые версии)
        try { wv.evaluateJavascript(MediaState.DOCUMENT_START_SCRIPT, null) } catch (_: Exception) {}
        try { wv.evaluateJavascript(MediaGrabber.SNIFF_SCRIPT, null) } catch (_: Exception) {}
    }

    // v4.10.0: мобильный User-Agent как у Chrome для Android (без «; wv» и «Version/4.0»): сайты вроде GeForce NOW,
    // Google-вход, Stadia-подобные веб-приложения отказывают «Android WebView», но работают в Chrome/Brave/Edge.
    val mobileUa = remember { BrowserUa.mobile(context) }
    fun uaFor(url: String?, tab: BrowserTabModel? = null): String =
        if (desktopMode || tab?.desktopSite == true || StoreBridge.isStoreUrl(url)) ExtensionStore.DESKTOP_UA else mobileUa
    /** Ставит UA и подсказки клиента (Sec-CH-UA / navigator.userAgentData) согласованно. */
    fun applyUa(wv: WebView?, url: String?, tab: BrowserTabModel?) {
        if (wv == null) return
        val ua = uaFor(url, tab)
        if (wv.settings.userAgentString != ua) wv.settings.userAgentString = ua
        BrowserUa.applyClientHints(wv, ua == ExtensionStore.DESKTOP_UA)
    }

    fun navigateTab(tab: BrowserTabModel, rawInput: String, useProxyTunnel: Boolean = false) {
        val target = normalizeInputToUrl(rawInput)
        if (isTorrentLink(target, null, null)) {
            // v4.10.9: `.torrent?token=…` и ссылки download.php?id=… тоже идут в торрент-движок
            startTorrentFromUrl(tab, target, null, null, null)
            currentSection = 1
            Toast.makeText(context, "🧲 Торрент добавлен в загрузчик!", Toast.LENGTH_SHORT).show()
            return
        }
        BrowserDirectRepeater.recordWebViewActivity(txEstimate = 768L, rxEstimate = 2048L)

        tab.autoRetryCount = 0
        tab.proxyRepairCount = 0
        tab.lastErrorText = null
        tab.isLoading = true
        tab.loadingProgress = 10
        val nodeInfo = BrowserDirectRepeater.getNode(browserVpnCountry)
        val vpnLabel = if (browserVpnEnabled) {
            "${nodeInfo.flag} ${nodeInfo.countryRu} (127.0.0.1:${BrowserDirectRepeater.activePort} ➔ ${nodeInfo.remoteIp}:${nodeInfo.remotePort})"
        } else {
            "Прямое (VPN ВЫКЛ)"
        }
        tab.loadingStatusText = "⏳ Подключение ($vpnLabel) к ${Uri.parse(target).host ?: target}..."
        tab.url = target
        addressInput = target
        persistBrowserSettings()

        val wv = tab.webView ?: return
        applyUa(wv, target, tab)
        val extraHeaders = buildMap {
            put("X-Forwarded-For", nodeInfo.remoteIp)
            put("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8")
            if (doNotTrackEnabled) {
                put("DNT", "1")
                put("Sec-GPC", "1")
            }
        }
        // v4.2.10: при старте приложения расширение-прокси (Browsec) поднимается ~1–3 с; первая страница раньше
        // уходила напрямую (без VPN) и «расширение включалось» только после обновления. Ждём его готовности (до 6 с).
        val waitExt = com.winwasm64.extensions.ExtensionProxy.control == null && !target.startsWith("about:") && !target.startsWith("file:") &&
            android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime() < 25_000L &&
            try { com.winwasm64.extensions.ExtensionStore.enabledExtensions().any { "proxy" in it.manifest.permissions } } catch (_: Exception) { false }
        val doLoad = {
            BrowserDirectRepeater.ensureDirectBrowserMode {
                wv.post {
                    wv.loadUrl(target, extraHeaders)
                }
            }
        }
        if (waitExt) {
            tab.loadingStatusText = "⏳ Запуск расширения VPN…"
            val startedWait = android.os.SystemClock.elapsedRealtime()
            lateinit var tick: Runnable
            tick = Runnable {
                val waited = android.os.SystemClock.elapsedRealtime() - startedWait
                if (com.winwasm64.extensions.ExtensionProxy.control != null) {
                    com.winwasm64.vpn.VpnLog.i("Браузер", "расширение-прокси готово через $waited мс — открываем первую страницу через него")
                    doLoad()
                } else if (waited >= 6_000L) {
                    com.winwasm64.vpn.VpnLog.w("Браузер", "расширение-прокси не поднялось за $waited мс — открываем страницу без него (обновится маршрут — обновите страницу)")
                    doLoad()
                } else wv.postDelayed(tick, 150)
            }
            wv.post(tick)
        } else doLoad()
    }

    /**
     * Запускает НАТИВНОЕ мобильное приложение (включая YouTube, Telegram, браузеры и Play Market):
     * - Если приложение ДОБАВЛЕНО в список VPN (`vpnRoutedApps.contains(pkg)`) -> запускается через
     *   СВОЙ СОБСТВЕННЫЙ выделенный туннельный порт (`127.0.0.1:890X`) и СВОЙ выбранный сервер!
     * - Если приложение НЕ добавлено в список VPN -> запускается напрямую через обычную сеть БЕЗ VPN!
     */
    fun startBrowsecAndLaunchNativeApp(pkg: String, appName: String) {
        val isInVpnList = vpnRoutedApps.contains(pkg)
        val appCountry = BrowsecLocalProxyServer.getAppServerCountry(pkg)
        val profile = BrowsecLocalProxyServer.getOrCreateAppProfile(pkg, appCountry)
        val nodeInfo = BrowsecLocalProxyServer.getServerNode(profile.countryCode)

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
            BrowsecVpnService.startForApp(context, profile.countryCode, pkg)
            val ok = nativeAppsManager.launchApp(pkg, useVpnTunnel = true)
            if (ok) {
                Toast.makeText(
                    context,
                    "🛡️ $appName запущен через СВОЙ сервер VPN (${nodeInfo.flag} ${nodeInfo.countryRu} • порт 127.0.0.1:${profile.localPort} ➔ ${nodeInfo.remoteIp}:${nodeInfo.remotePort})!",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(context, "Не удалось открыть нативное приложение $appName", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Приложение НЕ добавлено в список VPN -> работает напрямую БЕЗ VPN и без обхода блокировок!
            val ok = nativeAppsManager.launchApp(pkg, useVpnTunnel = false)
            if (ok) {
                Toast.makeText(
                    context,
                    "⚪ $appName запущен напрямую БЕЗ VPN (приложение не в списке VPN)",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(context, "Не удалось открыть нативное приложение $appName", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val fileChooserLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cb = fileChooserCallback
        fileChooserCallback = null
        val data = result.data
        val uris: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK && data != null) {
            val clip = data.clipData
            if (clip != null && clip.itemCount > 0) Array(clip.itemCount) { clip.getItemAt(it).uri } else data.data?.let { arrayOf(it) }
        } else {
            null
        }
        cb?.onReceiveValue(uris)
    }

    // v4.8.0: файлы для ИИ-чата
    var aiFilesCallback by remember { mutableStateOf<((List<Uri>) -> Unit)?>(null) }
    val aiFilesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val cb = aiFilesCallback
        aiFilesCallback = null
        if (uris.isNotEmpty()) {
            for (u in uris) try { context.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            cb?.invoke(uris)
        }
    }
    val extensionFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    ExtensionRuntime.installFromBytes(bytes) { err, ext ->
                        Toast.makeText(context, if (err != null) "Не удалось установить: $err" else "Установлено: ${ext?.manifest?.name}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Ошибка чтения файла: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun extTabOf(t: BrowserTabModel): ExtTab {
        val idx = tabs.indexOf(t)
        return ExtTab(t.numericId, idx.coerceAtLeast(0), t.url, t.title, idx == activeTabIndex, t.isIncognito, t.isLoading)
    }

    fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("text", text))
        Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
    }

    fun shareText(text: String) {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(send, "Поделиться").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(context, "Не удалось поделиться: ${e.message}", Toast.LENGTH_SHORT).show()
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
            if (tab.isIncognito && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                try {
                    ProfileStore.getInstance().getOrCreateProfile("omni_incognito")
                    WebViewCompat.setProfile(this, "omni_incognito")
                } catch (_: Throwable) {
                }
            }
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
            if (tab.isIncognito) settings.cacheMode = WebSettings.LOAD_NO_CACHE
            applyUa(this, tab.url, tab) // v4.10.0: UA «как Chrome» с самого первого запроса

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, !blockThirdPartyCookies)

            setOnLongClickListener {
                val hit = hitTestResult
                val extra = hit.extra
                when (hit.type) {
                    WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                        val msg = Handler(Looper.getMainLooper()).obtainMessage()
                        requestFocusNodeHref(msg)
                        val href = msg.data?.getString("url")
                        longPressImageUrl = extra?.takeIf { it.isNotBlank() }
                        longPressedUrl = href?.takeIf { it.isNotBlank() } ?: extra
                        true
                    }
                    WebView.HitTestResult.IMAGE_TYPE -> {
                        if (!extra.isNullOrBlank()) {
                            longPressImageUrl = extra
                            longPressedUrl = null
                            true
                        } else {
                            false
                        }
                    }
                    else -> if (!extra.isNullOrBlank() && (extra.startsWith("http") || extra.startsWith("magnet:"))) {
                        longPressImageUrl = null
                        longPressedUrl = extra
                        true
                    } else {
                        false
                    }
                }
            }

            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                when {
                    // v4.33.0: торрент-подобные схемы — только внутрь, наружу не отдаём
                    isTorrentishScheme(url) && !url.startsWith("magnet:", ignoreCase = true) -> {
                        Toast.makeText(
                            context,
                            "Формат «${url.substringBefore(':')}» не поддерживается. Нужна magnet-ссылка или файл .torrent.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    url.startsWith("magnet:", ignoreCase = true) -> {
                        torrentEngine.startFromMagnetOrUrl(url)
                        currentSection = 1
                        Toast.makeText(context, "🧲 Magnet добавлен в загрузчик", Toast.LENGTH_LONG).show()
                    }
                    // v4.10.9: торрент может прийти ссылкой download.php?id=… с именем файла в заголовке
                    isTorrentLink(url, mimeType, contentDisposition) ->
                        startTorrentFromUrl(tab, url, userAgent, contentDisposition, mimeType)
                    else -> startWebDownload(tab, url, userAgent, contentDisposition, mimeType)
                }
            }

            addJavascriptInterface(blobBridge, "OmniBlobSaver")

            setFindListener { activeMatch, matches, _ ->
                findCurrent = activeMatch
                findTotal = matches
            }

            webChromeClient = object : WebChromeClient() {
                override fun getDefaultVideoPoster(): Bitmap? {
                    return try {
                        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                    } catch (_: Exception) {
                        super.getDefaultVideoPoster()
                    }
                }

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
                    if (newProgress >= 15 && view != null) {
                        injectActiveExtensions(view)
                    }
                    if (newProgress >= 100 && view != null) {
                        // v4.19.0: приёмник клавиш для своей панели ввода
                        try { view.evaluateJavascript(com.winwasm64.ui.browser.OMNI_KEY_JS, null) } catch (_: Throwable) {}
                    }
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (!title.isNullOrBlank() && title != tab.title) {
                        tab.title = title
                        if (!tab.isIncognito) ExtensionRuntime.onTabUpdated(extTabOf(tab), null, false, true)
                    }
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = filePathCallback
                    return try {
                        val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                        if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                        fileChooserLauncher.launch(intent)
                        true
                    } catch (e: Exception) {
                        fileChooserCallback = null
                        Toast.makeText(context, "Не удалось открыть выбор файла: ${e.message}", Toast.LENGTH_SHORT).show()
                        false
                    }
                }

                // v4.10.0: запросы разрешений со страниц: DRM (Widevine) — всегда; микрофон/камера — если выданы приложению
                override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                    if (request == null) return
                    try {
                        val granted = request.resources.filter { r ->
                            when (r) {
                                android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID, android.webkit.PermissionRequest.RESOURCE_MIDI_SYSEX -> true
                                android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE -> androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE -> androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                else -> false
                            }
                        }
                        com.winwasm64.vpn.VpnLog.i("Браузер", "${request.origin?.host ?: "страница"} запросила ${request.resources.joinToString()} → разрешено: ${if (granted.isEmpty()) "ничего" else granted.joinToString()}")
                        if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
                    } catch (_: Exception) { try { request.deny() } catch (_: Exception) {} }
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
                    BrowserDirectRepeater.recordWebViewActivity(txEstimate = 420L, rxEstimate = 3200L)
                    // v4.17.0: журнал соединений — кто вызвал (страница), куда, по какому протоколу и зачем
                    try {
                        val rh = request?.url?.host.orEmpty()
                        if (rh.isNotEmpty()) {
                            val pageHost = try { android.net.Uri.parse(tab.url).host.orEmpty() } catch (_: Throwable) { "" }
                            val rport = if ((request?.url?.port ?: -1) > 0) request!!.url.port
                            else if (reqUrl.startsWith("http://")) 80 else 443
                            com.winwasm64.vpn.ConnTrace.request(
                                initiator = pageHost,
                                host = rh,
                                port = rport,
                                purpose = requestPurpose(request, reqUrl),
                                blocked = false
                            )
                        }
                    } catch (_: Throwable) {
                    }
                    if (!tab.isIncognito) {
                        ExtensionRuntime.interceptTabRequest(tab.numericId, request)?.let { return it }
                    }
                    if (adBlockEnabled) {
                        val host = request.url.host?.lowercase() ?: ""
                        val pageHost = try { android.net.Uri.parse(tab.url).host.orEmpty() } catch (_: Throwable) { "" }
                        // v4.18.0: журнал 4.17.0 показал, что AdBlock резал СВОЙ ЖЕ API сервисов —
                        // gx-target-experiments-frontend-api.gx.nvidia.com (из-за него GeForce NOW
                        // не мог получить настройки сессии). Теперь: запросы того же сайта и адреса,
                        // похожие на API сервиса, не блокируются никогда.
                        val sameSite = isSameSite(pageHost, host)
                        val serviceApi = isServiceApiHost(host)
                        val blocked = !sameSite && !serviceApi && (
                            AD_DOMAINS.any { host == it || host.endsWith(".$it") } ||
                                com.winwasm64.ui.browser.AdBlockList.isBlocked(host) ||
                                customBlockedDomains.any { it.isNotBlank() && host.contains(it.lowercase()) } ||
                                reqUrl.contains("/pagead/") || reqUrl.contains("doubleclick.net") ||
                                reqUrl.contains("googleads.")
                            )
                        if (blocked) {
                            adBlockedCount++
                            try {
                                com.winwasm64.vpn.ConnTrace.request(
                                    pageHost, host,
                                    if ((request.url.port) > 0) request.url.port else 443,
                                    adBlockReason(host), true
                                )
                            } catch (_: Throwable) {
                            }
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
                    if (nextUrl.startsWith("magnet:", ignoreCase = true)) {
                        torrentEngine.startFromMagnetOrUrl(nextUrl)
                        currentSection = 1
                        Toast.makeText(context, "🧲 Magnet перехвачен и запущен!", Toast.LENGTH_LONG).show()
                        return true
                    }
                    // v4.10.9: `…/x.torrent?token=…` раньше не ловился (проверялось окончание всего URL)
                    if (isTorrentLink(nextUrl, null, null)) {
                        startTorrentFromUrl(tab, nextUrl, null, null, null)
                        currentSection = 1
                        return true
                    }
                    if (nextUrl.startsWith("intent://", ignoreCase = true)) {
                        val fallback = Regex("S\\.browser_fallback_url=([^;]+)").find(nextUrl)?.groupValues?.getOrNull(1)
                        if (!fallback.isNullOrBlank()) {
                            view?.loadUrl(Uri.decode(fallback))
                        }
                        return true
                    }
                    // v4.33.0: торрент-подобные схемы никогда не отдаём наружу — раньше Android
                    // показывал список сторонних торрент-клиентов вместо нашей загрузки
                    if (isTorrentishScheme(nextUrl)) {
                        if (nextUrl.startsWith("magnet:", ignoreCase = true)) {
                            torrentEngine.startFromMagnetOrUrl(nextUrl)
                            currentSection = 1
                            Toast.makeText(context, "🧲 Magnet перехвачен и запущен!", Toast.LENGTH_LONG).show()
                        } else {
                            val kind = nextUrl.substringBefore(':').lowercase()
                            Toast.makeText(
                                context,
                                "Формат «$kind» не поддерживается этим клиентом. Нужна magnet-ссылка или файл .torrent.",
                                Toast.LENGTH_LONG
                            ).show()
                            com.winwasm64.vpn.VpnLog.w("Торрент", "ссылка вида $kind не поддерживается — внешние приложения не предлагаем")
                        }
                        return true
                    }
                    if (!nextUrl.startsWith("http://", ignoreCase = true) &&
                        !nextUrl.startsWith("https://", ignoreCase = true) &&
                        !nextUrl.startsWith("file://", ignoreCase = true)
                    ) {
                        nativeAppsManager.openUrlInNativeApp(nextUrl)
                        return true
                    }
                    if (view != null && request.isForMainFrame) {
                        val wantDesktop = uaFor(nextUrl, tab) == ExtensionStore.DESKTOP_UA
                        val isDesktop = view.settings.userAgentString == ExtensionStore.DESKTOP_UA
                        if (wantDesktop != isDesktop) {
                            applyUa(view, nextUrl, tab)
                            view.loadUrl(nextUrl)
                            return true
                        }
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
                        tab.hasMedia = false
                        val host = Uri.parse(url).host ?: url
                        tab.loadingStatusText = "⏳ Загрузка страницы $host..."
                        if (!tab.isIncognito) ExtensionRuntime.onTabUpdated(extTabOf(tab), "loading", true, false)
                        if (!tab.isIncognito) {
                            val stamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                            val entry = "$stamp | $url"
                            historyList.removeAll { it.endsWith("| $url") || it == url }
                            historyList.add(0, entry)
                        }
                        if (view != null) {
                            injectActiveExtensions(view)
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
                        if (!tab.isIncognito) ExtensionRuntime.onTabHistoryStateUpdated(extTabOf(tab))
                        if (view != null) {
                            injectActiveExtensions(view)
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
                        if (!tab.isIncognito) ExtensionRuntime.injectFallback(view)
                        if (!tab.isIncognito) StoreBridge.injectFallback(view, url)
                    }
                    if (!tab.isIncognito) ExtensionRuntime.onTabUpdated(extTabOf(tab), "complete", false, false)
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
                        // v4.0.2: локальный прокси перестал принимать соединения → перезапускаем его и сразу повторяем
                        // (сервер и настройки не меняются, страница перезагружается для проверки)
                        if (desc.contains("ERR_PROXY_CONNECTION_FAILED") && tab.proxyRepairCount < 2 && view != null) {
                            tab.proxyRepairCount++
                            tab.lastErrorText = null
                            tab.loadingStatusText = "Перезапуск локального прокси и повтор загрузки…"
                            Thread {
                                if (ExtensionProxy.isActive) {
                                    // v4.1.1: в журнал — почему WebView не достучался до локального прокси
                                    com.winwasm64.vpn.VpnLog.w("Браузер", "ERR_PROXY_CONNECTION_FAILED (${Uri.parse(failingUrl).host}): ${ExtensionProxy.diagnose()}")
                                    ExtensionProxy.repairNow { view.postDelayed({ view.loadUrl(failingUrl) }, 300) }
                                } else {
                                    BrowserDirectRepeater.healthCheckAndRepair(force = true)
                                    view.postDelayed({ view.loadUrl(failingUrl) }, 700)
                                }
                            }.start()
                            return
                        }
                        // 1. Как в v3.6.0: мгновенно загружаем страницу через десинхронизированный сокет Репитера №1
                        // (с перебором чистых Anycast IP), чтобы экран net::ERR_CONNECTION_CLOSED никогда не оставался!
                        if (browserVpnEnabled && !ExtensionProxy.isActive && tab.autoRetryCount < 4 && view != null) {
                            tab.autoRetryCount++
                            tab.lastErrorText = null
                            val ua = view.settings.userAgentString
                            tab.loadingStatusText = "⚡ Прямой обход DPI через Репитер №1 ($browserVpnCountry • ${browserTrafficSnapshot.pingMs} мс)..."
                            Thread {
                                val html = BrowserDirectRepeater.fetchUnblockedHtml(failingUrl, ua)
                                if (!html.isNullOrBlank()) {
                                    view.post {
                                        tab.isLoading = false
                                        tab.loadingProgress = 100
                                        tab.lastErrorText = null
                                        tab.loadingStatusText = "✓ Загружено через Репитер №1 ($browserVpnCountry • ${browserTrafficSnapshot.pingMs} мс)"
                                        view.loadDataWithBaseURL(
                                            failingUrl,
                                            html,
                                            "text/html",
                                            "UTF-8",
                                            failingUrl
                                        )
                                    }
                                } else if (autoHealEnabled) {
                                    val healMsg = BrowserDirectRepeater.triggerAutoHealOrFailover()
                                    view.post {
                                        browserVpnCountry = BrowserDirectRepeater.countryCode
                                        tab.loadingStatusText = healMsg
                                        view.loadUrl(failingUrl)
                                    }
                                } else {
                                    view.post {
                                        view.loadUrl(failingUrl)
                                    }
                                }
                            }.start()
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
        // v4.2.0: учёт воспроизведения + фоновое видео (страница не видит, что скрыта)
        try {
            wv.addJavascriptInterface(OmniMediaBridge(tab), "OmniMedia")
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(wv, MediaState.DOCUMENT_START_SCRIPT, setOf("*"))
                WebViewCompat.addDocumentStartJavaScript(wv, MediaGrabber.SNIFF_SCRIPT, setOf("*"))
            }
        } catch (_: Exception) {
        }
        webViewContainer.addView(wv)
        if (!tab.isIncognito) {
            ExtensionRuntime.attachTab(wv, tab.numericId)
            ExtensionRuntime.onTabCreated(extTabOf(tab))
            StoreBridge.attach(wv)
        }
        navigateTab(tab, tab.url)
        return wv
    }

    fun openNewTab(url: String, incognito: Boolean = false, activate: Boolean = true): BrowserTabModel {
        val t = BrowserTabModel(
            id = (if (incognito) "inc_" else "tab_") + System.nanoTime(),
            initialUrl = url,
            initialTitle = if (incognito) "Инкогнито" else "Новая вкладка",
            isIncognito = incognito
        )
        tabs.add(t)
        if (activate) {
            activeTabIndex = tabs.size - 1
            currentSection = 0
        }
        ensureTabWebView(t)
        persistBrowserSettings()
        return t
    }

    var closeConfirmTabId by remember { mutableStateOf("") }
    fun closeTabAt(index: Int) {
        val t = tabs.getOrNull(index) ?: return
        // v4.4.0: во вкладке идёт загрузка силами страницы — закрытие её прервёт; просим подтвердить вторым нажатием
        if (MediaDownloadService.activeTabIds.contains(t.id) && closeConfirmTabId != t.id) {
            closeConfirmTabId = t.id
            Toast.makeText(context, "Во вкладке идёт загрузка видео — она прервётся. Нажмите ещё раз, чтобы закрыть", Toast.LENGTH_LONG).show()
            return
        }
        closeConfirmTabId = ""
        t.webView?.let { w ->
            if (!t.isIncognito) ExtensionRuntime.detachTab(w, t.numericId)
            webViewContainer.removeView(w)
            w.destroy()
        }
        t.webView = null
        tabs.removeAt(index)
        if (tabs.isEmpty()) {
            // Раньше закрытие последней вкладки роняло приложение (coerceIn(0, -1)).
            tabs.add(BrowserTabModel(id = "tab_" + System.nanoTime(), initialUrl = homePageUrl, initialTitle = "Новая вкладка"))
            activeTabIndex = 0
        } else if (index < activeTabIndex || activeTabIndex >= tabs.size) {
            activeTabIndex = (activeTabIndex - 1).coerceIn(0, tabs.size - 1)
        }
        persistBrowserSettings()
    }

    val extTabHost = remember {
        object : ExtensionTabHost {
            override fun tabs(): List<ExtTab> = tabs.filter { !it.isIncognito }.map { extTabOf(it) }
            override fun createTab(url: String, active: Boolean): ExtTab? = extTabOf(openNewTab(url, false, active))
            override fun updateTab(tabId: Int, url: String?, active: Boolean?): ExtTab? {
                val idx = tabs.indexOfFirst { it.numericId == tabId }
                if (idx < 0) return null
                val t = tabs[idx]
                if (active == true) {
                    activeTabIndex = idx
                    currentSection = 0
                }
                if (!url.isNullOrBlank()) navigateTab(t, url)
                return extTabOf(t)
            }
            override fun removeTab(tabId: Int) {
                val idx = tabs.indexOfFirst { it.numericId == tabId }
                if (idx >= 0) closeTabAt(idx)
            }
            override fun reloadTab(tabId: Int) {
                tabs.firstOrNull { it.numericId == tabId }?.webView?.reload()
            }
            override fun webViewOf(tabId: Int): WebView? = tabs.firstOrNull { it.numericId == tabId }?.webView
            override fun openPopup(extId: String) {
                extPopupFor = extId
            }
            override fun closePopup(extId: String) {
                if (extPopupFor == extId) extPopupFor = null
                com.winwasm64.extensions.ExtensionPageActivity.closeFor(extId)
            }
            override fun toast(text: String) {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        }
    }
    DisposableEffect(extTabHost) {
        ExtensionRuntime.tabHost = extTabHost
        onDispose {
            if (ExtensionRuntime.tabHost === extTabHost) ExtensionRuntime.tabHost = null
        }
    }

    // v4.4.0: ссылки, пришедшие во время работы (из других приложений, из уведомления об обновлении)
    val openAppsReq = com.winwasm64.ExternalIntents.openAppsRequest.intValue
    LaunchedEffect(openAppsReq) {
        if (openAppsReq > 0) {
            currentSection = 3
            Toast.makeText(context, "Плитка: разрешите VPN и включите «Приложения через серверы Browsec» здесь — дальше плитка будет работать из шторки", Toast.LENGTH_LONG).show()
        }
    }
    val pendingExternal = com.winwasm64.ExternalIntents.pendingUrl
    LaunchedEffect(pendingExternal) {
        val u = pendingExternal ?: return@LaunchedEffect
        com.winwasm64.ExternalIntents.pendingUrl = null
        if (u == initialIntentUri) return@LaunchedEffect
        // v4.34.0: файл .torrent (content:// или file://) идёт в свой клиент ПЕРВОЙ проверкой.
        // Раньше такой URI уходил в startFromMagnetOrUrl и превращался в ошибку
        // «unknown protocol: content» — ровно как на скриншоте с catorrent.net.
        if ((u.startsWith("content:", ignoreCase = true) || u.startsWith("file:", ignoreCase = true)) && isLocalTorrentUri(u)) {
            openTorrentFromUri(u); currentSection = 1
        } else if (u.startsWith("magnet:", ignoreCase = true) || isTorrentLink(u, null, null)) {
            torrentEngine.startFromMagnetOrUrl(u); currentSection = 1
        } else if (u.startsWith("http", ignoreCase = true) || u.startsWith("content:", ignoreCase = true) || u.startsWith("file:", ignoreCase = true)) {
            openNewTab(u); currentSection = 0
        } else {
            // v4.5.1: текст без ссылки («Поделиться», «Найти в OmniBrowser», системный поиск) — в поисковик
            openNewTab(normalizeInputToUrl(u)); currentSection = 0
        }
    }
    LaunchedEffect(Unit) {
        // v4.5.0: незавершённые загрузки продолжаются после перезапуска приложения
        try { com.winwasm64.ui.browser.MediaDownloadService.resumePending(context) } catch (_: Exception) {}
        com.winwasm64.vpn.NetworkBypass.init(context)
        com.winwasm64.vpn.NetworkBypass.enabled = bypassForeignVpn
        // v4.13.0: держим весь процесс на физической сети, пока обход включён (на прошивках с EPERM это единственный путь)
        if (bypassForeignVpn) com.winwasm64.vpn.NetworkBypass.ensure()
        com.winwasm64.extensions.ExtensionProxy.pinSessionServer = pinSessionServer
        com.winwasm64.ui.browser.UpdateChecker.autoCheck(context)
        com.winwasm64.ui.browser.AdBlockList.init(context, adBlockEnabled)
    }

    LaunchedEffect(initialIntentUri) {
        if (!initialIntentUri.isNullOrBlank()) {
            if ((initialIntentUri.startsWith("content:", ignoreCase = true) || initialIntentUri.startsWith("file:", ignoreCase = true)) && isLocalTorrentUri(initialIntentUri)) {
                openTorrentFromUri(initialIntentUri)
                currentSection = 1
            } else if (initialIntentUri.startsWith("magnet:", ignoreCase = true) ||
                isTorrentLink(initialIntentUri, null, null)
            ) {
                torrentEngine.startFromMagnetOrUrl(initialIntentUri)
                currentSection = 1
            } else if (initialIntentUri.startsWith("http", ignoreCase = true) || initialIntentUri.startsWith("content:", ignoreCase = true) || initialIntentUri.startsWith("file:", ignoreCase = true)) {
                navigateTab(activeTab, initialIntentUri)
                currentSection = 0
            }
        }
    }

    // v4.2.0: вкладка с играющим видео остаётся «видимой» для WebView (уводится за экран), а не GONE — иначе
    // Chromium ставит воспроизведение на паузу при переключении вкладок
    fun applyTabVisibility(container: ViewGroup, current: BrowserTabModel) {
        // v4.2.1: видеосайты держим «видимыми» всегда — даже если страница ещё не сообщила о воспроизведении
        val playing = tabs.filter { it !== current && (it.mediaPlaying || MediaState.isMediaSite(it.url)) }.mapNotNull { it.webView }.toSet()
        // Играющая вкладка остаётся на месте и видимой — просто под активной (по z-порядку); так движок не считает её
        // скрытой ни по видимости, ни по положению на экране
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.translationX = 0f
            child.visibility = if (child == current.webView || child in playing) View.VISIBLE else View.GONE
        }
        current.webView?.let { if (it.parent === container) it.bringToFront() }
    }

    // v4.2.0: «картинка в картинке» — раскладка «только видео» внутри страницы и параметры авто-входа (Android 12+)
    LaunchedEffect(MediaState.inPip) {
        val tab = tabs.getOrNull(activeTabIndex) ?: return@LaunchedEffect
        val wv = tab.webView ?: return@LaunchedEffect
        val wasPlaying = tab.mediaPlaying
        if (MediaState.inPip && fullscreenCustomView != null) {
            // v4.2.4: HTML5-полный экран (отдельная поверхность) в окне PiP рисуется чёрным — возвращаем видео на страницу
            try { fullscreenCallback?.onCustomViewHidden() } catch (_: Exception) {}
            fullscreenCallback = null
            fullscreenCustomView = null
        }
        try { wv.evaluateJavascript(MediaState.pipLayoutScript(MediaState.inPip), null) } catch (_: Exception) {}
        // v4.2.10: в окне PiP — только один WebView (текущая вкладка); остальные скрываем, при выходе возвращаем как было
        try {
            if (MediaState.inPip) {
                for (i in 0 until webViewContainer.childCount) {
                    val child = webViewContainer.getChildAt(i)
                    child.visibility = if (child === wv) View.VISIBLE else View.GONE
                }
                wv.bringToFront()
                wv.evaluateJavascript("(function(){ try { if (window.__omniFixYtFlags) window.__omniFixYtFlags(); if (window.__omniPipMark) window.__omniPipMark(); } catch (e) {} })();", null)
            } else {
                applyTabVisibility(webViewContainer, tab)
                wv.evaluateJavascript("(function(){ try { if (window.__omniPipReport) window.__omniPipReport(); } catch (e) {} })();", null)
            }
        } catch (_: Exception) {}
        if (MediaState.inPip) {
            // v4.2.4: после смены размера окна принудительно перекладываем WebView и повторяем раскладку «только видео»;
            // если при входе в PiP движок/сайт поставил видео на паузу — возобновляем
            var elapsed = 0L
            for (d in listOf(300L, 900L, 1800L)) {
                delay(d - elapsed); elapsed = d
                if (!MediaState.inPip) break
                try {
                    webViewContainer.requestLayout()
                    wv.requestLayout()
                    wv.invalidate()
                    wv.evaluateJavascript(MediaState.pipLayoutScript(true), null)
                    val userTouched = android.os.SystemClock.elapsedRealtime() - MediaState.lastUserControlAt < 5_000L
                    if (wasPlaying && !tab.mediaPlaying && !userTouched) wv.evaluateJavascript(MediaState.mediaControlScript("play"), null)
                } catch (_: Exception) {}
            }
            try {
                wv.evaluateJavascript(MediaState.PIP_DIAG_SCRIPT) { r ->
                    com.winwasm64.vpn.VpnLog.i("PiP", "окно ${webViewContainer.width}×${webViewContainer.height} px, WebView ${wv.width}×${wv.height} px (${wv.visibility}), страница: ${r ?: "?"}")
                }
            } catch (_: Exception) {}
        }
    }
    LaunchedEffect(MediaState.activeMediaPlaying) {
        (context as? com.winwasm64.MainActivity)?.updatePipParams(MediaState.activeMediaPlaying)
    }
    // v4.2.3: кнопки окна PiP управляют видео активной вкладки
    DisposableEffect(Unit) {
        MediaState.controlHandler = { cmd ->
            val wv = tabs.getOrNull(activeTabIndex)?.webView
            try { wv?.evaluateJavascript(MediaState.mediaControlScript(cmd), null) } catch (_: Exception) {}
        }
        onDispose { MediaState.controlHandler = null }
    }

    LaunchedEffect(activeTabIndex, tabs.size, desktopMode) {
        val current = tabs.getOrNull(activeTabIndex) ?: return@LaunchedEffect
        ensureTabWebView(current)
        if (!current.isIncognito) ExtensionRuntime.onTabActivated(current.numericId)
        MediaState.activeTabId = current.id
        MediaState.activeMediaPlaying = current.mediaPlaying
        applyTabVisibility(webViewContainer, current)
        applyUa(current.webView, current.url, current)
        // v4.2.9: активная вкладка держит аудиофокус (системные «pause» игнорирует), остальные — нет
        val playingBg = tabs.filter { it !== current && it.mediaPlaying }.map { "«${it.title.take(30)}»" }
        com.winwasm64.vpn.VpnLog.i("Видео", "переключение на вкладку «${current.title.take(40)}»" +
            (if (current.mediaPlaying) " (в ней играет видео)" else "") + (if (playingBg.isNotEmpty()) "; в фоне играет: ${playingBg.joinToString()}" else ""))
        for (t in tabs) {
            try { t.webView?.evaluateJavascript(MediaState.holdFocusScript(t === current), null) } catch (_: Exception) {}
        }
    }
    // v4.2.9: когда во вкладке началось воспроизведение — остальные играющие вкладки ставим на паузу явно (JS),
    // иначе Chromium гоняет аудиофокус между двумя плеерами и оба дёргаются раз в секунду
    DisposableEffect(Unit) {
        MediaState.onTabStartedPlaying = { id ->
            for (t in tabs) {
                if (t.id != id && t.mediaPlaying) {
                    com.winwasm64.vpn.VpnLog.i("Видео", "«${t.title.take(40)}» ставим на паузу: началось воспроизведение в другой вкладке")
                    try { t.webView?.evaluateJavascript(MediaState.mediaControlScript("pause"), null) } catch (_: Exception) {}
                }
            }
        }
        onDispose { MediaState.onTabStartedPlaying = null }
    }

    var torrentStatuses by remember { mutableStateOf(torrentEngine.getAllStatuses()) }
    LaunchedEffect(Unit) {
        // v4.61.0: сторож главного потока — если интерфейс снова «провалится», это будет видно в журнале
        com.winwasm64.torrent.MainThreadWatchdog.start()
        while (true) {
            // v4.61.0: если торрент-задач нет — не трогаем состояние (иначе перерисовка каждые 0,5 с впустую)
            val snapshot = torrentEngine.getAllStatuses()
            if (snapshot.isNotEmpty() || torrentStatuses.isNotEmpty()) torrentStatuses = snapshot
            delay(800)
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

    val hostActivity = remember(context) { context.findOmniActivity() }
    BackHandler(enabled = true) {
        when {
            fullscreenCustomView != null -> {
                fullscreenCustomView = null
                fullscreenCallback?.onCustomViewHidden()
                fullscreenCallback = null
            }
            findBarVisible -> {
                findBarVisible = false
                findInPageQuery = ""
                activeTab.webView?.clearMatches()
            }
            currentSection != 0 -> currentSection = 0
            activeTab.webView?.canGoBack() == true -> activeTab.webView?.goBack()
            else -> hostActivity?.moveTaskToBack(true)
        }
    }
    DisposableEffect(fullscreenCustomView != null) {
        val act = hostActivity
        val win = act?.window
        if (fullscreenCustomView != null && act != null && win != null) {
            val ctrl = WindowCompat.getInsetsController(win, win.decorView)
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            // v4.16.0: страница в полноэкранном режиме должна получать ввод — иначе запрос клавиатуры
            // из оверлея игры уходит в никуда и клавиатура тут же закрывается
            try { activeTab.webView?.requestFocus() } catch (_: Exception) {}
            try { win.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Exception) {} // v4.10.0: экран не гаснет в полноэкранной игре/видео
        }
        onDispose {
            // v4.19.0: вышли из полного экрана — панель ввода больше не нужна
            showKeyPanel = false
            if (act != null && win != null && act.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                WindowCompat.getInsetsController(win, win.decorView).show(WindowInsetsCompat.Type.systemBars())
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                try { win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Exception) {}
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Omni.Bg
    ) {
        // v4.2.3: в окне «картинка в картинке» WebView НЕ переносится в другой контейнер (перенос = «скрытие» для движка,
        // и видео вставало на паузу); просто прячем панели, а контейнер страницы занимает всё окно
        val inPip = MediaState.inPip
        Column(modifier = if (inPip) Modifier.fillMaxSize() else Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {

            if (currentSection == 0 && !inPip) {
                // 1. Верхняя панель: VPN · адрес · вкладки · расширения · меню
                if (fullscreenCustomView == null) {
                    if (findBarVisible) {
                        FindInPageBar(
                            query = findInPageQuery,
                            onQueryChange = { q ->
                                findInPageQuery = q
                                if (q.isEmpty()) {
                                    activeTab.webView?.clearMatches()
                                    findTotal = 0
                                    findCurrent = 0
                                } else {
                                    activeTab.webView?.findAllAsync(q)
                                }
                            },
                            current = findCurrent,
                            total = findTotal,
                            onPrev = { activeTab.webView?.findNext(false) },
                            onNext = { activeTab.webView?.findNext(true) },
                            onClose = {
                                findBarVisible = false
                                findInPageQuery = ""
                                findTotal = 0
                                findCurrent = 0
                                activeTab.webView?.clearMatches()
                            }
                        )
                    } else {
                        val extBadge = remember(ExtensionRuntime.stateVersion) {
                            ExtensionStore.installed.count { it.enabled }.takeIf { it > 0 }?.toString()
                        }
                        OmniTopBar(
                            url = activeTab.url,
                            isLoading = activeTab.isLoading,
                            progress = activeTab.loadingProgress,
                            isIncognito = activeTab.isIncognito,
                            vpnOn = browserVpnEnabled || ExtensionProxy.ownerName != null,
                            tabsCount = tabs.size,
                            extensionsBadge = extBadge,
                            onSubmit = { input -> navigateTab(activeTab, input) },
                            onReload = {
                                val w = activeTab.webView
                                if (w != null) w.reload() else navigateTab(activeTab, activeTab.url)
                            },
                            onStop = {
                                activeTab.webView?.stopLoading()
                                activeTab.isLoading = false
                            },
                            onVpnClick = { activeExtensionPopup = "browsec" },
                            onTabsClick = { showTabsSheet = true },
                            onExtensionsClick = { showExtensionsSheet = true },
                            onMenuClick = { showMenuSheet = true }
                        )
                    }
                    val storeId = if (ChromeWebStore.isDetailPage(activeTab.url)) ChromeWebStore.extractId(activeTab.url) else null
                    if (storeId != null) {
                        val installedNow = remember(ExtensionRuntime.stateVersion, storeId) { ExtensionStore.get(storeId) != null }
                        StoreInstallBanner(
                            installed = installedNow,
                            busyText = ExtensionRuntime.busy[storeId],
                            onInstall = {
                                ExtensionRuntime.installFromStore(storeId) { err, ext ->
                                    Toast.makeText(context, if (err != null) "Не удалось установить: $err" else "Установлено: ${ext?.manifest?.name}", Toast.LENGTH_LONG).show()
                                }
                            }
                        )
                    }
                }

                // v4.3.0: шторка «Скачать видео»
                val gs = grabSheet
                if (gs != null) {
                    OmniSheet(onDismiss = { grabSheet = null }) {
                        SheetTitle("Скачать видео или аудио", gs.title)
                        val opts = gs.options
                        when {
                            opts == null -> Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Omni.Accent)
                                Spacer(Modifier.width(14.dp))
                                Text("Ищу источники на странице…", color = Omni.TextDim, fontSize = 14.sp)
                            }
                            opts.isEmpty() -> {
                                Text(
                                    (if (gs.notice.isNotBlank()) "⚠ " + gs.notice + "\n\n" else "") + "Не удалось найти прямые ссылки на видео. Запустите воспроизведение на странице и попробуйте ещё раз — " +
                                        "адреса медиа появляются, когда плеер начинает их запрашивать. DRM-потоки (Netflix и т.п.) скачать нельзя.",
                                    color = Omni.TextDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                                )
                                SheetDivider()
                                SheetItem(OmniIcons.Globe, "Через сторонние сервисы", "SaveFrom, Cobalt, Invidious и другие — в новой вкладке", tint = Omni.Accent, onClick = {
                                    openNewTab(MediaGrabber.altServicesUrl(gs.shareUrl, gs.title))
                                    grabSheet = null
                                })
                            }
                            else -> {
                                if (gs.notice.isNotBlank()) Text(
                                    "⚠ " + gs.notice, color = Omni.Warning, fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                                )
                                val videos = opts.filter { !it.isAudio }
                                val audios = opts.filter { it.isAudio }
                                if (videos.isNotEmpty()) SheetSectionLabel("Видео")
                                for (o in videos) {
                                    SheetItem(OmniIcons.Download, o.title, o.subtitle, tint = Omni.Accent, onClick = {
                                        MediaDownloadService.start(context, o.job)
                                        grabSheet = null
                                        Toast.makeText(context, "⬇ ${o.job.fileName}", Toast.LENGTH_SHORT).show()
                                    })
                                }
                                if (audios.isNotEmpty()) SheetSectionLabel("Только звук")
                                for (o in audios) {
                                    SheetItem(OmniIcons.Play, o.title, o.subtitle, tint = Omni.Success, onClick = {
                                        MediaDownloadService.start(context, o.job)
                                        grabSheet = null
                                        Toast.makeText(context, "⬇ ${o.job.fileName}", Toast.LENGTH_SHORT).show()
                                    })
                                }
                                SheetDivider()
                                SheetItem(OmniIcons.Globe, "Через сторонние сервисы", "SaveFrom, Cobalt, Invidious и другие — в новой вкладке", tint = Omni.TextDim, onClick = {
                                    openNewTab(MediaGrabber.altServicesUrl(gs.shareUrl, gs.title))
                                    grabSheet = null
                                })
                                Text(
                                    "Файлы сохраняются в Movies/OmniBrowser и Music/OmniBrowser, прогресс — в уведомлении.",
                                    color = Omni.TextFaint, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                // Шторка вкладок
                if (showTabsSheet) {
                    OmniSheet(onDismiss = { showTabsSheet = false }, scrollable = false) {
                        TabsSheetContent(
                            tabs = tabs.map { TabRowInfo(it.title, it.url, it.isIncognito, it.isLoading) },
                            activeIndex = activeTabIndex,
                            onSelect = { i ->
                                activeTabIndex = i
                                showTabsSheet = false
                            },
                            onClose = { i -> closeTabAt(i) },
                            onNewTab = {
                                showTabsSheet = false
                                openNewTab(homePageUrl)
                            },
                            onNewIncognito = {
                                showTabsSheet = false
                                openNewTab("https://duckduckgo.com", incognito = true)
                            }
                        )
                    }
                }

                // Шторка расширений Chrome
                if (showExtensionsSheet) {
                    OmniSheet(onDismiss = { showExtensionsSheet = false }, scrollable = false) {
                        ExtensionsSheetContent(
                            onOpenPopup = { id ->
                                showExtensionsSheet = false
                                extPopupFor = id
                            },
                            onOpenOptions = { id ->
                                showExtensionsSheet = false
                                extOptionsFor = id
                            },
                            onOpenUrl = { u ->
                                showExtensionsSheet = false
                                openNewTab(u)
                            },
                            onPickFile = { extensionFilePicker.launch("*/*") }
                        )
                    }
                }
                // Шторка ИИ-ассистента (v4.6.0; v4.7.0 — агенты/MCP, мост читает актуальную вкладку)
                if (showAiSheet) {
                    val aiHost = remember {
                        object : com.winwasm64.ui.browser.AiHost {
                            private fun tab(): BrowserTabModel? = tabs.getOrNull(activeTabIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)))
                            override fun currentPage(cb: (com.winwasm64.ui.browser.AiAssistant.PageInfo?) -> Unit) {
                                val wv = tab()?.webView
                                if (wv == null) { cb(null); return }
                                try { wv.evaluateJavascript(com.winwasm64.ui.browser.AiAssistant.PAGE_JS) { raw -> cb(com.winwasm64.ui.browser.AiAssistant.parsePage(raw)) } } catch (_: Exception) { cb(null) }
                            }
                            override fun runJs(js: String, cb: (String?) -> Unit) {
                                val wv = tab()?.webView
                                if (wv == null) { cb(null); return }
                                try { wv.evaluateJavascript(js) { raw -> cb(raw) } } catch (e: Exception) { cb("error: ${e.message}") }
                            }
                            override fun openUrl(url: String) { openNewTab(url) }
                            override fun download(url: String) {
                                val t = tab() ?: return
                                startWebDownload(t, url, t.webView?.settings?.userAgentString, null, null)
                            }
                            override fun pickFiles(cb: (List<Uri>) -> Unit) {
                                aiFilesCallback = cb
                                try { aiFilesPicker.launch(arrayOf("*/*")) } catch (e: Exception) { Toast.makeText(context, "Не удалось открыть выбор файлов: ${e.message}", Toast.LENGTH_SHORT).show() }
                            }
                        }
                    }
                    OmniSheet(onDismiss = { showAiSheet = false }, scrollable = false) {
                        com.winwasm64.ui.browser.AiSheetContent(
                            host = aiHost,
                            onOpenUrl = { u ->
                                showAiSheet = false
                                openNewTab(u)
                            },
                            onDismiss = { showAiSheet = false }
                        )
                    }
                }
                extPopupFor?.let { id -> ExtensionPageDialog(id, options = false) { extPopupFor = null } }
                extOptionsFor?.let { id -> ExtensionPageDialog(id, options = true) { extOptionsFor = null } }

                // Главное меню
                if (showMenuSheet) {
                    val isBookmarked = bookmarks.any { it.first == activeTab.url }
                    OmniSheet(onDismiss = { showMenuSheet = false }) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            OmniIconButton(OmniIcons.Back, "Назад", {
                                activeTab.webView?.goBack()
                                showMenuSheet = false
                            }, enabled = activeTab.canGoBack)
                            OmniIconButton(OmniIcons.Forward, "Вперёд", {
                                activeTab.webView?.goForward()
                                showMenuSheet = false
                            }, enabled = activeTab.canGoForward)
                            OmniIconButton(if (isBookmarked) OmniIcons.Star else OmniIcons.StarOutline, "Закладка", {
                                if (isBookmarked) {
                                    bookmarks.removeAll { it.first == activeTab.url }
                                } else {
                                    bookmarks.add(0, activeTab.url to activeTab.title.ifBlank { activeTab.url })
                                }
                                persistBrowserSettings()
                            }, tint = if (isBookmarked) Omni.Accent else Omni.Text)
                            OmniIconButton(OmniIcons.Refresh, "Обновить", {
                                activeTab.webView?.reload()
                                showMenuSheet = false
                            })
                            OmniIconButton(OmniIcons.Home, "Домой", {
                                navigateTab(activeTab, homePageUrl)
                                showMenuSheet = false
                            })
                        }
                        SheetDivider()
                        SheetItem(OmniIcons.Add, "Новая вкладка", onClick = {
                            showMenuSheet = false
                            openNewTab(homePageUrl)
                        })
                        SheetItem(OmniIcons.Incognito, "Новая вкладка инкогнито", onClick = {
                            showMenuSheet = false
                            openNewTab("https://duckduckgo.com", incognito = true)
                        })
                        SheetItem(OmniIcons.Star, "Закладки", subtitle = "Сохранено: ${bookmarks.size}", onClick = {
                            showMenuSheet = false
                            activeExtensionPopup = "bookmarks"
                        })
                        SheetItem(OmniIcons.History, "История и загрузки", onClick = {
                            showMenuSheet = false
                            currentSection = 2
                        })
                        SheetItem(OmniIcons.Search, "Найти на странице", onClick = {
                            showMenuSheet = false
                            findBarVisible = true
                        })
                        SheetItem(OmniIcons.Share, "Видео в окне (картинка в картинке)", onClick = {
                            showMenuSheet = false
                            val ok = (context as? com.winwasm64.MainActivity)?.enterPipNow() ?: false
                            if (!ok) Toast.makeText(context, "Картинка в картинке недоступна на этом устройстве", Toast.LENGTH_SHORT).show()
                        })
                        SheetItem(OmniIcons.Share, "Поделиться", onClick = {
                            showMenuSheet = false
                            shareText(activeTab.url)
                        })
                        SheetItem(OmniIcons.Copy, "Копировать адрес", onClick = {
                            showMenuSheet = false
                            copyToClipboard(activeTab.url)
                        })
                        // v4.10.1: сайт напрямую, без VPN браузера (стриминг игр, банки)
                        val curSite = com.winwasm64.extensions.DirectSites.siteKey(try { Uri.parse(activeTab.url).host } catch (_: Exception) { null })
                        if (curSite.isNotEmpty()) {
                            val builtInDirect = com.winwasm64.extensions.DirectSites.isBuiltIn(curSite)
                            val userDirect = directSitesVersion >= 0 && com.winwasm64.extensions.DirectSites.isUser(curSite)
                            SheetSwitchItem(
                                OmniIcons.Globe,
                                "Сайт напрямую, без VPN браузера",
                                subtitle = if (builtInDirect) "$curSite всегда идёт напрямую (встроенное правило для стриминга игр)" else if (userDirect) "$curSite и поддомены идут мимо прокси" else "Для $curSite: игровые стримы (GeForce NOW), банки, сайты, которым мешает прокси",
                                checked = builtInDirect || userDirect,
                                onCheckedChange = { v ->
                                    if (!builtInDirect) {
                                        com.winwasm64.extensions.DirectSites.set(context, curSite, v)
                                        directSitesVersion++
                                        showMenuSheet = false
                                        activeTab.webView?.reload()
                                    }
                                }
                            )
                        }
                        // v4.9.2: версия для ПК только для текущей вкладки — для сайтов, которые в мобильном виде не открываются
                        val tabDesktop = activeTab.desktopSite || desktopMode
                        SheetItem(
                            OmniIcons.Desktop,
                            if (tabDesktop) "Открыть в мобильной версии" else "Открыть в версии для ПК",
                            subtitle = if (tabDesktop) "Эта вкладка сейчас открыта как на компьютере" else "Только эта вкладка: страница перезагрузится как на компьютере",
                            tint = if (tabDesktop) Omni.Accent else Omni.Text,
                            onClick = {
                                showMenuSheet = false
                                val wv = activeTab.webView
                                if (desktopMode && !activeTab.desktopSite) {
                                    desktopMode = false
                                    persistBrowserSettings()
                                } else {
                                    activeTab.desktopSite = !activeTab.desktopSite
                                }
                                applyUa(wv, activeTab.url, activeTab)
                                com.winwasm64.vpn.VpnLog.i("Браузер", (if (uaFor(activeTab.url, activeTab) == ExtensionStore.DESKTOP_UA) "вкладка открыта в версии для ПК: " else "вкладка вернулась в мобильную версию: ") + activeTab.url.take(80))
                                wv?.reload()
                            }
                        )
                        SheetItem(OmniIcons.Save, "Сохранить страницу офлайн", subtitle = "MHTML целиком: текст, картинки, стили · раздел «История → Загрузки»", onClick = {
                            showMenuSheet = false
                            val wv = activeTab.webView
                            if (wv == null || activeTab.url.startsWith("file:")) Toast.makeText(context, "Нечего сохранять", Toast.LENGTH_SHORT).show()
                            else com.winwasm64.ui.browser.PageArchiver.save(context, wv, activeTab.title, activeTab.url) { f ->
                                Toast.makeText(context, if (f != null) "✅ Сохранено: ${f.name}" else "⚠ Не удалось сохранить страницу", Toast.LENGTH_LONG).show()
                            }
                        })
                        SheetDivider()
                        SheetItem(OmniIcons.Code, "ИИ-ассистент", subtitle = "Кратко о странице, перевод, вопросы · бесплатные модели", tint = Omni.Accent, onClick = {
                            showMenuSheet = false
                            showAiSheet = true
                        })
                        SheetItem(OmniIcons.Extension, "Расширения", subtitle = "Chrome Web Store", onClick = {
                            showMenuSheet = false
                            showExtensionsSheet = true
                        })
                        SheetItem(
                            OmniIcons.Shield,
                            "VPN браузера",
                            subtitle = ExtensionProxy.ownerName?.let { "Через расширение «$it»" } ?: (if (browserVpnEnabled) "Включён · $browserVpnCountry" else "Выключен"),
                            tint = if (browserVpnEnabled) Omni.Success else Omni.TextDim,
                            onClick = {
                                showMenuSheet = false
                                activeExtensionPopup = "browsec"
                            }
                        )
                        SheetItem(OmniIcons.Block, "Блокировка рекламы", subtitle = if (adBlockEnabled) "Заблокировано: $adBlockedCount" else "Выключена", onClick = {
                            showMenuSheet = false
                            activeExtensionPopup = "adblock"
                        })
                        SheetItem(OmniIcons.Play, "YouTube", onClick = {
                            showMenuSheet = false
                            activeExtensionPopup = "youtube"
                        })
                        SheetSwitchItem(OmniIcons.Desktop, "Версия для ПК для всех вкладок", subtitle = "Для одной страницы — пункт «Открыть в версии для ПК» выше", checked = desktopMode, onCheckedChange = { v ->
                            desktopMode = v
                            applyUa(activeTab.webView, activeTab.url, activeTab)
                            activeTab.webView?.reload()
                            persistBrowserSettings()
                        })
                        SheetSwitchItem(OmniIcons.Contrast, "Инверсия цветов", subtitle = "Для настоящей тёмной темы установите Dark Reader", checked = darkReaderEnabled, onCheckedChange = { v ->
                            darkReaderEnabled = v
                            activeTab.webView?.let { injectActiveExtensions(it) }
                            persistBrowserSettings()
                        })
                        SheetItem(OmniIcons.Settings, "Настройки", onClick = {
                            showMenuSheet = false
                            activeExtensionPopup = "settings"
                        })
                    }
                }

                // Долгое нажатие на ссылку или картинку
                if (longPressedUrl != null || longPressImageUrl != null) {
                    val link = longPressedUrl
                    val img = longPressImageUrl
                    OmniSheet(onDismiss = {
                        longPressedUrl = null
                        longPressImageUrl = null
                    }) {
                        SheetTitle(if (link != null) "Ссылка" else "Изображение", link ?: img)
                        if (link != null) {
                            SheetItem(OmniIcons.Add, "Открыть в новой вкладке", onClick = {
                                longPressedUrl = null
                                longPressImageUrl = null
                                openNewTab(link)
                            })
                            SheetItem(OmniIcons.Incognito, "Открыть в инкогнито", onClick = {
                                longPressedUrl = null
                                longPressImageUrl = null
                                openNewTab(link, incognito = true)
                            })
                            SheetItem(OmniIcons.Copy, "Копировать ссылку", onClick = {
                                longPressedUrl = null
                                longPressImageUrl = null
                                copyToClipboard(link)
                            })
                            SheetItem(OmniIcons.Download, "Скачать по ссылке", onClick = {
                                longPressedUrl = null
                                longPressImageUrl = null
                                startWebDownload(activeTab, link, activeTab.webView?.settings?.userAgentString, null, null)
                            })
                            SheetItem(OmniIcons.Share, "Поделиться ссылкой", onClick = {
                                longPressedUrl = null
                                longPressImageUrl = null
                                shareText(link)
                            })
                        }
                        if (img != null) {
                            if (link != null) SheetDivider()
                            SheetItem(OmniIcons.Image, "Открыть изображение", onClick = {
                                longPressedUrl = null
                                longPressImageUrl = null
                                openNewTab(img)
                            })
                            SheetItem(OmniIcons.Save, "Сохранить изображение", onClick = {
                                longPressedUrl = null
                                longPressImageUrl = null
                                startWebDownload(activeTab, img, activeTab.webView?.settings?.userAgentString, null, null)
                            })
                            SheetItem(OmniIcons.Copy, "Копировать адрес изображения", onClick = {
                                longPressedUrl = null
                                longPressImageUrl = null
                                copyToClipboard(img)
                            })
                        }
                    }
                }

                // 5. Панели (VPN, блокировщик, YouTube, закладки, настройки) — единая шторка
                if (activeExtensionPopup != null) {
                    OmniSheet(onDismiss = { activeExtensionPopup = null }, scrollable = false) {
                        Column(
                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            when (activeExtensionPopup) {
                                "browsec" -> {
                                    ExtensionProxy.ownerName?.let { owner ->
                                        Column(
                                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Omni.AccentContainer).padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text("Трафиком браузера сейчас управляет расширение «$owner»", color = Omni.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                            Text("Пока оно включено, сайты открываются через его серверы. Встроенный обход DPI используется только для сайтов, которые расширение пускает напрямую.", color = Omni.TextDim, fontSize = 12.sp)
                                            TextButton(onClick = { ExtensionProxy.release(null) }) { Text("Отключить прокси расширения", color = Omni.Accent) }
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "Репитер Браузера №1: ${browserTrafficSnapshot.activeCountryName} • IP: ${browserTrafficSnapshot.remoteServerIpPort}",
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
                                        color = Omni.Bg,
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Omni.Accent)
                                    ) {
                                        Column(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                "СЕРВЕР БРАУЗЕРА №1: ${browserTrafficSnapshot.activeCountryName} | IP: ${browserTrafficSnapshot.remoteServerIpPort} | Пинг: ${browserTrafficSnapshot.pingMs} мс",
                                                color = Omni.Success,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp
                                            )
                                            Text(
                                                "Настройка сервера: ${browserTrafficSnapshot.currentStrategyName} • ${browserTrafficSnapshot.watchdogStatusText}",
                                                color = Omni.Warning,
                                                fontSize = 10.sp
                                            )
                                            Text(
                                                "Входящий (только этот сервер): ${BitTorrentEngine.formatSpeed(browserTrafficSnapshot.rxSpeedBytesPerSec)}  |  Объём сервера: ${BitTorrentEngine.formatSize(browserTrafficSnapshot.rxBytesTotal)}",
                                                color = Omni.Accent,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Text(
                                                "Исходящий (только этот сервер): ${BitTorrentEngine.formatSpeed(browserTrafficSnapshot.txSpeedBytesPerSec)}  |  Объём сервера: ${BitTorrentEngine.formatSize(browserTrafficSnapshot.txBytesTotal)}",
                                                color = Omni.Warning,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }

                                    // Панель ручного выбора сервера, синхронизации обоих VPN и авто-смены настроек
                                    Row(
                                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                serverPickerTarget = "browser"
                                                showServerPickerModal = true
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer)
                                        ) {
                                            Text("Выбрать сервер из списка (22 страны)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                        }

                                        Button(
                                            onClick = {
                                                BrowsecVpnService.synchronizeBothVpns(context, fromBrowserToApps = true)
                                                browsecCountry = browserVpnCountry
                                                appServerMapVersion++
                                                persistBrowserSettings()
                                                Toast.makeText(
                                                    context,
                                                    "🔗 Синхронизировано! Сервер Браузера ($browserVpnCountry) применён ко всем Приложениям в VPN!",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer)
                                        ) {
                                            Text("Синхр.: Браузер ➔ Приложения", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                        }

                                        Button(
                                            onClick = {
                                                BrowsecVpnService.synchronizeBothVpns(context, fromBrowserToApps = false)
                                                browserVpnCountry = browsecCountry
                                                persistBrowserSettings()
                                                navigateTab(activeTab, activeTab.url)
                                                Toast.makeText(
                                                    context,
                                                    "🔗 Синхронизировано! Сервер Приложений ($browsecCountry) применён к Браузеру!",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer)
                                        ) {
                                            Text("Синхр.: Приложения ➔ Браузер", fontSize = 11.sp, color = Color.White)
                                        }

                                        Button(
                                            onClick = {
                                                val msg = BrowserDirectRepeater.triggerAutoHealOrFailover()
                                                browserVpnCountry = BrowserDirectRepeater.countryCode
                                                if (syncBothVpnsAuto) {
                                                    browsecCountry = browserVpnCountry
                                                }
                                                persistBrowserSettings()
                                                navigateTab(activeTab, activeTab.url)
                                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer)
                                        ) {
                                            Text("Авто-смена настроек / переход (с возвратом прошлого)", fontSize = 11.sp, color = Color.White)
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        BrowserDirectRepeater.BROWSER_NODES.forEach { node ->
                                            val selected = browserVpnCountry == node.code
                                            val st = BrowserDirectRepeater.getServerState(node.code)
                                            Surface(
                                                color = if (selected) Omni.AccentContainer else Omni.Surface,
                                                shape = RoundedCornerShape(8.dp),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, Omni.Accent),
                                                modifier = Modifier.clickable {
                                                    browserVpnCountry = node.code
                                                    browserVpnEnabled = true
                                                    BrowserDirectRepeater.switchCountry(node.code, resetSessionTraffic = false)
                                                    if (syncBothVpnsAuto) {
                                                        browsecCountry = node.code
                                                        BrowsecVpnService.selectSpecificCountry(context, node.code, resetTraffic = false)
                                                    }
                                                    persistBrowserSettings()
                                                    navigateTab(activeTab, activeTab.url)
                                                    Toast.makeText(
                                                        context,
                                                        "🌍 Выбран сервер Браузера: ${node.flag} ${node.countryRu} (${node.remoteIp}:${node.remotePort}) • Страница обновляется!",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Text(
                                                        text = "${node.flag} ${node.countryRu} (${node.code}) • ⚡ ${st.pingMs} мс",
                                                        color = Color.White,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = "${node.remoteIp}:${node.remotePort}",
                                                        color = if (selected) Omni.Text else Omni.TextDim,
                                                        fontSize = 9.sp,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                    Text(
                                                        text = "${BrowsecVpnService.formatBytesShort(st.rxBytes.get())} ⬆${BrowsecVpnService.formatBytesShort(st.txBytes.get())}",
                                                        color = if (selected) Omni.Warning else Omni.Accent,
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
                                                containerColor = if (browserVpnEnabled) Omni.DangerContainer else Omni.AccentContainer
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
                                            colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer)
                                        ) {
                                            Text("Перейти во 2-й Сервер (Список VPN Приложений: ${vpnRoutedApps.size})", fontSize = 11.sp, color = Color.White)
                                        }
                                    }
                                }

                                "adblock" -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("AdBlock Pro + YouTube Анти-Реклама", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Switch(checked = adBlockEnabled, onCheckedChange = {
                                            adBlockEnabled = it
                                            persistBrowserSettings()
                                            activeTab.webView?.reload()
                                        })
                                    }
                                    Text("Заблокировано рекламных запросов и трекеров: $adBlockedCount", color = Omni.Success, fontSize = 12.sp)
                                    var adListInfo by remember { mutableStateOf("") }
                                    val adListSize = com.winwasm64.ui.browser.AdBlockList.size
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (adListSize > 0) "Список AdGuard DNS: $adListSize доменов" else "Список AdGuard DNS ещё не загружен",
                                            color = Omni.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f)
                                        )
                                        Surface(color = Omni.Surface, shape = RoundedCornerShape(6.dp), modifier = Modifier.clickable {
                                            adListInfo = "Обновляю…"
                                            com.winwasm64.ui.browser.AdBlockList.refreshNow(context) { r -> Handler(Looper.getMainLooper()).post { adListInfo = r } }
                                        }) { Text("Обновить", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) }
                                    }
                                    if (adListInfo.isNotBlank()) Text(adListInfo, color = Omni.TextDim, fontSize = 11.sp)
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
                                    var ytSearch by remember { mutableStateOf("") }
                                    var audioBoost by remember { mutableStateOf(false) }
                                    Text("YouTube", color = Omni.Text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                                    Text(
                                        if (browserVpnEnabled) "Страницы идут через VPN браузера (обход DPI, $browserVpnCountry)." else "VPN браузера выключен — YouTube открывается напрямую.",
                                        color = Omni.TextDim,
                                        fontSize = 12.sp
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        CompactDarkTextField(
                                            value = ytSearch,
                                            onValueChange = { ytSearch = it },
                                            placeholder = "Поиск видео на YouTube",
                                            modifier = Modifier.weight(1f),
                                            onGo = {
                                                if (ytSearch.isNotBlank()) {
                                                    navigateTab(activeTab, "https://m.youtube.com/results?search_query=" + Uri.encode(ytSearch))
                                                    activeExtensionPopup = null
                                                }
                                            }
                                        )
                                        Button(
                                            onClick = {
                                                if (ytSearch.isNotBlank()) {
                                                    navigateTab(activeTab, "https://m.youtube.com/results?search_query=" + Uri.encode(ytSearch))
                                                    activeExtensionPopup = null
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Omni.Accent, contentColor = Color.White)
                                        ) { Text("Найти") }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = {
                                            navigateTab(activeTab, "https://m.youtube.com")
                                            activeExtensionPopup = null
                                        }) { Text("Мобильная версия", color = Omni.Text, fontSize = 13.sp) }
                                        OutlinedButton(onClick = {
                                            navigateTab(activeTab, "https://www.youtube.com/?app=desktop")
                                            activeExtensionPopup = null
                                        }) { Text("Версия для ПК", color = Omni.Text, fontSize = 13.sp) }
                                    }
                                    SettingSwitchRow(
                                        "Громкость ×2",
                                        "Настоящее усиление через WebAudio для видео на этой странице (видео с чужих доменов без CORS пропускаются, иначе они бы замолчали).",
                                        audioBoost
                                    ) { on ->
                                        audioBoost = on
                                        activeTab.webView?.evaluateJavascript(omniAudioBoostJs(if (on) 2.0 else 1.0)) { r ->
                                            val res = r?.trim('"') ?: ""
                                            val msg = when {
                                                res == "no-webaudio" || res.startsWith("err") -> "WebAudio недоступен на этой странице"
                                                res.startsWith("0/") -> "На странице нет подходящих видео"
                                                on -> "Громкость ×2 включена"
                                                else -> "Обычная громкость"
                                            }
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    Text("SponsorBlock, Return YouTube Dislike и блокировщики рекламы ставятся в разделе «Расширения».", color = Omni.TextFaint, fontSize = 12.sp)
                                }

                                "bookmarks" -> {
                                    val isBookmarked = bookmarks.any { it.first == activeTab.url }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Закладки", color = Omni.Text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                                        TextButton(onClick = {
                                            if (isBookmarked) {
                                                bookmarks.removeAll { it.first == activeTab.url }
                                            } else {
                                                bookmarks.add(0, activeTab.url to activeTab.title.ifBlank { activeTab.url })
                                            }
                                            persistBrowserSettings()
                                        }) { Text(if (isBookmarked) "Убрать эту страницу" else "Добавить эту страницу", color = Omni.Accent) }
                                    }
                                    if (bookmarks.isEmpty()) Text("Закладок пока нет", color = Omni.TextDim, fontSize = 13.sp)
                                    bookmarks.toList().forEach { (bUrl, bTitle) ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(Omni.SurfaceHigh)
                                                .clickable {
                                                    navigateTab(activeTab, bUrl)
                                                    activeExtensionPopup = null
                                                }
                                                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(bTitle, color = Omni.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(omniDisplayUrl(bUrl), color = Omni.TextDim, fontSize = 12.sp, maxLines = 1)
                                            }
                                            OmniIconButton(OmniIcons.Delete, "Удалить закладку", {
                                                bookmarks.removeAll { it.first == bUrl }
                                                persistBrowserSettings()
                                            }, tint = Omni.TextDim, size = 36.dp, iconSize = 18.dp)
                                        }
                                    }
                                }

                                "settings" -> {
                                    // v4.34.0: необязательные настройки VPN спрятаны. Пять нажатий на заголовок —
                                    // и появляется блок «для разработчика». Смысл: в обычной работе нужны
                                    // только сервер/страна и журнал, а не десяток переключателей.
                                    var devUnlock by remember { mutableStateOf(prefs.getBoolean("dev_vpn_unlocked", false)) }
                                    var devTaps by remember { mutableIntStateOf(0) }
                                    Text(
                                        "Настройки" + if (devUnlock) " · режим разработчика" else "",
                                        color = Omni.Text,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 18.sp,
                                        modifier = Modifier.clickable {
                                            if (devUnlock) return@clickable
                                            devTaps++
                                            if (devTaps >= 5) {
                                                devUnlock = true
                                                prefs.edit().putBoolean("dev_vpn_unlocked", true).apply()
                                                Toast.makeText(context, "Показаны настройки для разработчика", Toast.LENGTH_SHORT).show()
                                            } else if (devTaps >= 3) {
                                                Toast.makeText(context, "Ещё ${5 - devTaps} нажатия — настройки для разработчика", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                    if (!devUnlock) {
                                        Text(
                                            "Дополнительные настройки VPN, WebRTC и резервных каналов скрыты: нажмите 5 раз на этот заголовок, чтобы показать.",
                                            color = Omni.TextDim,
                                            fontSize = 10.sp
                                        )
                                    }
                                    // v4.4.0: обновления из GitHub Releases
                                    var updateCheckOn by remember { mutableStateOf(prefs.getBoolean("update_check_on", true)) }
                                    var updateStatus by remember { mutableStateOf("") }
                                    val latestUpdate = com.winwasm64.ui.browser.UpdateChecker.latest
                                    SettingSwitchRow("Проверять обновления", "Раз в 6 часов смотреть новые версии на GitHub (установлена ${com.winwasm64.ui.browser.UpdateChecker.currentVersion(context)})", updateCheckOn) { v ->
                                        updateCheckOn = v
                                        prefs.edit().putBoolean("update_check_on", v).apply()
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Surface(color = Omni.Surface, shape = RoundedCornerShape(6.dp), modifier = Modifier.clickable {
                                            updateStatus = "Проверяю…"
                                            com.winwasm64.ui.browser.UpdateChecker.check(context, manual = true) { info, err ->
                                                Handler(Looper.getMainLooper()).post {
                                                    updateStatus = when {
                                                        err != null -> "Не удалось проверить: $err"
                                                        info != null -> "Доступна версия ${info.version}"
                                                        else -> "У вас последняя версия"
                                                    }
                                                }
                                            }
                                        }) { Text("Проверить сейчас", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) }
                                        if (latestUpdate != null) Surface(color = Omni.AccentContainer, shape = RoundedCornerShape(6.dp), modifier = Modifier.clickable {
                                            navigateTab(activeTab, latestUpdate.apkUrl); currentSection = 0
                                            Toast.makeText(context, "Скачиваю ${latestUpdate.version}… после загрузки нажмите на уведомление, чтобы установить", Toast.LENGTH_LONG).show()
                                        }) { Text("Скачать ${latestUpdate.version}", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) }
                                        if (updateStatus.isNotBlank()) Text(updateStatus, color = Omni.TextDim, fontSize = 11.sp)
                                    }
                                    // v4.10.9: кнопка «Скачать видео» мешала на сайтах без видео (перекрывала поле ввода)
                                    SettingSwitchRow(
                                        "Скрыть кнопку «Скачать видео»",
                                        "Не показывать плавающую кнопку на страницах (кнопка и так прячется, пока открыта клавиатура)",
                                        hideMediaButton
                                    ) { v ->
                                        hideMediaButton = v
                                        prefs.edit().putBoolean("hide_media_button", v).apply()
                                    }
                                    // v4.12.0: один сервер на сессию и обход чужого VPN
                                    if (devUnlock) {
                                        SettingSwitchRow(
                                        "Держать один сервер Browsec (IP не меняется)",
                                        "Все соединения браузера идут через один сервер сессии; смена — только если он не ответил за 4 с (в журнале)",
                                        pinSessionServer
                                    ) { v ->
                                        pinSessionServer = v
                                        com.winwasm64.extensions.ExtensionProxy.pinSessionServer = v
                                        prefs.edit().putBoolean("pin_session_server", v).apply()
                                    }
                                    }
                                    if (devUnlock) {
                                        SettingSwitchRow(
                                        "Обходить чужой VPN на телефоне",
                                        "Наши сокеты привязываются к Wi-Fi/мобильной сети и идут мимо другого VPN — тогда выход только через Browsec, а «домашний IP» настоящий",
                                        bypassForeignVpn
                                    ) { v ->
                                        bypassForeignVpn = v
                                        com.winwasm64.vpn.NetworkBypass.enabled = v
                                        if (v) com.winwasm64.vpn.NetworkBypass.ensure() else com.winwasm64.vpn.NetworkBypass.unbindProcess()
                                        prefs.edit().putBoolean("bypass_foreign_vpn", v).apply()
                                    }
                                    }
                                    // v4.4.0: экспорт / импорт настроек
                                    var backupStatus by remember { mutableStateOf("") }
                                    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                                        if (uri != null) {
                                            try {
                                                val n = com.winwasm64.ui.browser.SettingsBackup.import(context, uri)
                                                backupStatus = "Импортировано ключей: $n. Перезапустите приложение, чтобы настройки применились."
                                                Toast.makeText(context, backupStatus, Toast.LENGTH_LONG).show()
                                            } catch (e: Exception) {
                                                backupStatus = "Импорт не удался: ${e.message}"
                                            }
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Резервная копия:", color = Omni.TextDim, fontSize = 11.sp)
                                        Surface(color = Omni.Surface, shape = RoundedCornerShape(6.dp), modifier = Modifier.clickable {
                                            persistBrowserSettings()
                                            backupStatus = try { "Сохранено: " + com.winwasm64.ui.browser.SettingsBackup.export(context, includeJournal = false) } catch (e: Exception) { "Не удалось: ${e.message}" }
                                        }) { Text("Экспорт настроек", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) }
                                        Surface(color = Omni.Surface, shape = RoundedCornerShape(6.dp), modifier = Modifier.clickable {
                                            persistBrowserSettings()
                                            backupStatus = try { "Сохранено: " + com.winwasm64.ui.browser.SettingsBackup.export(context, includeJournal = true) } catch (e: Exception) { "Не удалось: ${e.message}" }
                                        }) { Text("+ журнал", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) }
                                        Surface(color = Omni.Surface, shape = RoundedCornerShape(6.dp), modifier = Modifier.clickable {
                                            importLauncher.launch("application/json")
                                        }) { Text("Импорт", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) }
                                    }
                                    if (backupStatus.isNotBlank()) Text(backupStatus, color = Omni.TextDim, fontSize = 11.sp)
                                    // v4.30.0: резервные VPN — на случай, когда у основного канала и у Psiphon лимит
                                    var reserveStatus by remember { mutableStateOf(com.winwasm64.vpn.ReserveChain.statusLine()) }
                                    var reserveBusy by remember { mutableStateOf(false) }
                                    if (devUnlock) {
                                        Surface(color = Omni.Surface, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text("Резервные VPN", color = Color.White, fontSize = 12.sp)
                                            Text(
                                                "Когда основной канал или Psiphon упёрлись в лимит, браузер уходит на следующий живой резерв: локальный прокси Psiphon, затем публичные HTTP/SOCKS5 из пула. Прокси проверяются настоящим рукопожатием (CONNECT/SOCKS5), а не «по списку».",
                                                color = Omni.TextDim,
                                                fontSize = 10.sp
                                            )
                                            Text(
                                                if (reserveBusy) "проверяю…" else reserveStatus,
                                                color = if (reserveBusy) Omni.Accent else Omni.TextDim,
                                                fontSize = 11.sp
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Surface(
                                                    color = Omni.Surface,
                                                    shape = RoundedCornerShape(6.dp),
                                                    modifier = Modifier.clickable(enabled = !reserveBusy) {
                                                        reserveBusy = true
                                                        reserveStatus = "скачиваю списки прокси…"
                                                        Thread {
                                                            val n = com.winwasm64.vpn.ReservePool.refresh(context)
                                                            reserveStatus = "скачано $n, проверяю живые…"
                                                            val cached = com.winwasm64.vpn.ReservePool.load(context)
                                                            val alive = com.winwasm64.vpn.ReserveDialer.validateAll(
                                                                cached,
                                                                limit = 24,
                                                                protect = { s -> runCatching { BrowsecVpnService.protectSocket(s) } }
                                                            )
                                                            val ranked = com.winwasm64.vpn.ReservePool.sanitize(alive.map { it.first }, 20)
                                                            com.winwasm64.vpn.ReserveChain.applyPool(ranked)
                                                            reserveStatus = if (alive.isEmpty()) {
                                                                "живых прокси не нашлось (списки отдали $n) — резервом остаётся Psiphon"
                                                            } else {
                                                                "живых ${alive.size} из ${cached.size} · лучший ${alive.first().first.label} · ${alive.first().second} мс"
                                                            }
                                                            reserveBusy = false
                                                        }.start()
                                                    }
                                                ) { Text("Обновить пул прокси", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) }
                                                Surface(
                                                    color = Omni.Surface,
                                                    shape = RoundedCornerShape(6.dp),
                                                    modifier = Modifier.clickable(enabled = !reserveBusy) {
                                                        reserveBusy = true
                                                        reserveStatus = "проверяю резервы…"
                                                        Thread {
                                                            val cached = com.winwasm64.vpn.ReservePool.load(context)
                                                            val alive = com.winwasm64.vpn.ReserveDialer.validateAll(
                                                                cached,
                                                                limit = 24,
                                                                protect = { s -> runCatching { BrowsecVpnService.protectSocket(s) } }
                                                            )
                                                            com.winwasm64.vpn.ReserveChain.applyPool(com.winwasm64.vpn.ReservePool.sanitize(alive.map { it.first }, 20))
                                                            reserveStatus = if (alive.isEmpty()) "живых резервов нет · " + com.winwasm64.vpn.ReserveChain.statusLine() else "живых ${alive.size} · " + com.winwasm64.vpn.ReserveChain.statusLine()
                                                            reserveBusy = false
                                                        }.start()
                                                    }
                                                ) { Text("Проверить резервы", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) }
                                                Surface(
                                                    color = Omni.Surface,
                                                    shape = RoundedCornerShape(6.dp),
                                                    modifier = Modifier.clickable {
                                                        com.winwasm64.vpn.ReserveChain.reset()
                                                        reserveStatus = com.winwasm64.vpn.ReserveChain.statusLine()
                                                    }
                                                ) { Text("Сброс", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) }
                                            }
                                            SettingSwitchRow(
                                                "Сначала резерв (вручную)",
                                                "Основной канал не трогаем: весь трафик сразу идёт через резервный. Нужно, когда свой канал упёрся в лимит или отдаёт ошибки. Выключите, когда отпустит",
                                                com.winwasm64.vpn.ReserveChain.preferReserve
                                            ) { v ->
                                                com.winwasm64.vpn.ReserveChain.preferReserve = v
                                                prefs.edit().putBoolean("reserve_prefer", v).apply()
                                                reserveStatus = com.winwasm64.vpn.ReserveChain.statusLine()
                                            }
                                            SettingSwitchRow(
                                                "Автоперебор резерва",
                                                "При отказе или лимите у текущего канала браузер сам переключается на следующий живой резерв",
                                                com.winwasm64.vpn.ReserveChain.autoEnabled
                                            ) { v ->
                                                com.winwasm64.vpn.ReserveChain.autoEnabled = v
                                                prefs.edit().putBoolean("reserve_auto_enabled", v).apply()
                                                reserveStatus = com.winwasm64.vpn.ReserveChain.statusLine()
                                            }
                                        }
                                    }
                                    }
                                    SettingSwitchRow("Не отслеживать (DNT и GPC)", "Отправлять сайтам запрет на отслеживание", doNotTrackEnabled) { v ->
                                        doNotTrackEnabled = v
                                        persistBrowserSettings()
                                    }
                                    SettingSwitchRow("Автозапуск следующего видео", "YouTube: после окончания ролика — следующий из рекомендаций/плейлиста (и в фоне, и в PiP); другие сайты — кнопка «Следующее»", autoNextVideo) { v ->
                                        autoNextVideo = v
                                        com.winwasm64.ui.MediaPositions.autoNext = v
                                        prefs.edit().putBoolean("auto_next_video", v).apply()
                                    }
                                    if (devUnlock) {
                                        SettingSwitchRow("WebRTC через прокси (TCP) на всех сайтах", "Как «блокировка WebRTC» в Browsec: медиапоток не уходит с домашнего IP по UDP, а идёт через сервер VPN. Для GeForce NOW/NVIDIA включено всегда; звонки на других сайтах могут стать медленнее", webrtcTcpAll) { v ->
                                        webrtcTcpAll = v
                                        com.winwasm64.ui.MediaPositions.webrtcTcpAll = v
                                        prefs.edit().putBoolean("webrtc_tcp_all", v).apply()
                                    }
                                    }
                                    SettingSwitchRow("Блокировать сторонние cookies", "Меньше слежки; вход через Google/VK на некоторых сайтах может сломаться", blockThirdPartyCookies) { v ->
                                        blockThirdPartyCookies = v
                                        tabs.forEach { t -> t.webView?.let { CookieManager.getInstance().setAcceptThirdPartyCookies(it, !v) } }
                                        persistBrowserSettings()
                                    }
                                    SettingSwitchRow("Версия для ПК", "Открывать сайты как на компьютере", desktopMode) { v ->
                                        desktopMode = v
                                        applyUa(activeTab.webView, activeTab.url, activeTab)
                                        activeTab.webView?.reload()
                                        persistBrowserSettings()
                                    }
                                    SettingSwitchRow("Инверсия цветов", "Простая тёмная тема для любых сайтов", darkReaderEnabled) { v ->
                                        darkReaderEnabled = v
                                        activeTab.webView?.let { injectActiveExtensions(it) }
                                        persistBrowserSettings()
                                    }
                                    if (devUnlock) {
                                        SettingSwitchRow("Консоль разработчика (Eruda)", "Загружается с jsdelivr на текущую страницу", devToolsEnabled) { v ->
                                        devToolsEnabled = v
                                        activeTab.webView?.let { injectActiveExtensions(it) }
                                    }
                                    }
                                    // 1. Search Engine Selector
                                    Row(
                                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Поиск:", color = Omni.TextDim, fontSize = 11.sp)
                                        listOf("Google", "Яндекс", "DuckDuckGo", "Bing", "Brave").forEach { eng ->
                                            val sel = searchEngine == eng
                                            Surface(
                                                color = if (sel) Omni.AccentContainer else Omni.Surface,
                                                shape = RoundedCornerShape(6.dp),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, Omni.Accent),
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
                                        Text("Масштаб:", color = Omni.TextDim, fontSize = 11.sp)
                                        listOf(75, 100, 115, 130, 150, 175).forEach { pct ->
                                            val sel = textZoomPercent == pct
                                            Surface(
                                                color = if (sel) Omni.AccentContainer else Omni.Surface,
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
                                        Text("DoH DNS:", color = Omni.TextDim, fontSize = 11.sp)
                                        listOf("1.1.1.1" to "Cloudflare 1.1.1.1", "8.8.8.8" to "Google 8.8.8.8", "9.9.9.9" to "Quad9 9.9.9.9").forEach { (ip, label) ->
                                            val sel = dohServer == ip
                                            Surface(
                                                color = if (sel) Omni.AccentContainer else Omni.Surface,
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
                                        }, colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer)) {
                                            Text("Сделать Домашней", fontSize = 11.sp, color = Color.White)
                                        }
                                        Button(onClick = {
                                            try {
                                                val dir = torrentEngine.getDownloadDirectory()
                                                val mhtFile = File(dir, "page_${System.currentTimeMillis()}.mht")
                                                activeTab.webView?.saveWebArchive(mhtFile.absolutePath)
                                                Toast.makeText(context, "📄 Сохранено офлайн: ${mhtFile.name}", Toast.LENGTH_LONG).show()
                                            } catch (_: Exception) {
                                            }
                                        }, colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer)) {
                                            Text("Сохранить страницу (.MHT)", fontSize = 11.sp, color = Color.White)
                                        }
                                        Button(onClick = {
                                            activeTab.webView?.clearCache(true)
                                            // v4.2.5: cookies сервисов расширений (вход в Browsec) сохраняем — иначе после очистки расширение «слетало»
                                            val cm = CookieManager.getInstance()
                                            val keepUrls = listOf("https://browsec.com", "https://api.browsec.com", "https://www.browsec.com", "https://app.browsec.com")
                                            val kept = keepUrls.associateWith { cm.getCookie(it) }.filterValues { !it.isNullOrBlank() }
                                            cm.removeAllCookies {
                                                for ((u, c) in kept) c!!.split(';').forEach { kv ->
                                                    val t = kv.trim(); if (t.contains('=')) cm.setCookie(u, "$t; Max-Age=31536000; Path=/; Secure")
                                                }
                                                cm.flush()
                                            }
                                            Toast.makeText(context, "🗑 Кэш и Cookies очищены!", Toast.LENGTH_SHORT).show()
                                        }, colors = ButtonDefaults.buttonColors(containerColor = Omni.DangerContainer)) {
                                            Text("Очистить Кэш и Cookies", fontSize = 11.sp, color = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (activeTab.lastErrorText != null) {
                    Surface(
                        color = Omni.DangerContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Таймаут или блокировка: ${activeTab.lastErrorText}",
                                color = Omni.Warning,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (ExtensionProxy.isActive) {
                                            // Страница идёт через прокси расширения — чиним именно его, а не встроенный VPN
                                            ExtensionProxy.repairNow {
                                                android.os.Handler(android.os.Looper.getMainLooper()).post { navigateTab(activeTab, activeTab.url) }
                                            }
                                            Toast.makeText(context, "Прокси расширения перезапущен", Toast.LENGTH_SHORT).show()
                                        } else {
                                            browserVpnEnabled = true
                                            BrowserDirectRepeater.enabled = true
                                            BrowserDirectRepeater.healthCheckAndRepair()
                                            val msg = BrowserDirectRepeater.triggerAutoHealOrFailover()
                                            browserVpnCountry = BrowserDirectRepeater.countryCode
                                            persistBrowserSettings()
                                            navigateTab(activeTab, activeTab.url)
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer)
                                ) {
                                    Text(
                                        if (ExtensionProxy.isActive) "Перезапустить прокси (${ExtensionProxy.ownerName ?: "расширение"}) и обновить"
                                        else "Сменить настройку / сервер ($browserVpnCountry) и Обновить страницу",
                                        fontSize = 11.sp,
                                        color = Color.White
                                    )
                                }
                                Button(
                                    onClick = {
                                        if (ExtensionProxy.isActive) {
                                            ExtensionProxy.release(null)
                                            navigateTab(activeTab, activeTab.url)
                                        } else {
                                            navigateTab(activeTab, "https://m.youtube.com")
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Omni.DangerContainer)
                                ) {
                                    Text(
                                        if (ExtensionProxy.isActive) "Отключить прокси расширения" else "Открыть m.youtube.com",
                                        fontSize = 11.sp,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ===================== PERSISTENT WEBVIEW CONTAINER =====================
            if (fullscreenCustomView != null) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                    // v4.19.0: постоянный контейнер + явная перепривязка и фокус.
                    // Раньше вид создавался внутри factory и после пересборки оставался без фокуса —
                    // из-за этого тапы и клавиатура «оживали» только после выхода и повторного входа
                    // в полноэкранный режим.
                    val fsHost = remember { FrameLayout(context) }
                    LaunchedEffect(fullscreenCustomView) {
                        val v = fullscreenCustomView
                        try {
                            fsHost.removeAllViews()
                            if (v != null) {
                                (v.parent as? ViewGroup)?.removeView(v)
                                fsHost.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                                v.isFocusable = true
                                v.isFocusableInTouchMode = true
                                v.requestFocus()
                                v.requestLayout()
                                com.winwasm64.vpn.VpnLog.i("Браузер", "полный экран: вид игры подключён заново и получил фокус — тапы и клавиатура должны работать сразу")
                            }
                        } catch (_: Throwable) {
                        }
                    }
                    AndroidView(
                        factory = { fsHost },
                        modifier = Modifier.fillMaxSize()
                    )
                    // v4.24.0: курсор виртуальной мыши рисуем здесь, поверх вида игры.
                    // В самой странице его не видно: клиент GeForce NOW занимает экран
                    // через HTML-fullscreen, и наши элементы остаются под этим слоем.
                    if (com.winwasm64.ui.browser.OmniCursor.active) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val cx = com.winwasm64.ui.browser.OmniCursor.fx * size.width
                            val cy = com.winwasm64.ui.browser.OmniCursor.fy * size.height
                            drawCircle(color = Color(0x55FFC857), radius = 22f, center = Offset(cx, cy))
                            drawCircle(color = Color(0xFFFFC857), radius = 10f, center = Offset(cx, cy))
                            drawCircle(color = Color(0xCC101216), radius = 4f, center = Offset(cx, cy))
                        }
                    }
                    if (!inPip) Row(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                        // v4.16.0: в полноэкранной игре (GeForce NOW и подобные) клавиатура, которую просит
                        // страница, могла не открыться — даём кнопку, которая показывает её принудительно.
                        Button(
                            onClick = { showKeyPanel = !showKeyPanel },
                            colors = ButtonDefaults.buttonColors(containerColor = Omni.Surface.copy(alpha = 0.80f)),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(if (showKeyPanel) "⌨ Скрыть ввод" else "⌨ Клавиатура", fontSize = 11.sp, color = Color.White)
                        }
                        Button(
                            onClick = {
                                fullscreenCustomView = null
                                fullscreenCallback?.onCustomViewHidden()
                                fullscreenCallback = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Omni.Surface.copy(alpha = 0.80f))
                        ) {
                            Text("Выйти из полного экрана", fontSize = 11.sp, color = Color.White)
                        }
                    }
                    if (showKeyPanel && !inPip) {
                        // v4.19.0: своя панель ввода — символы уходят прямо в страницу
                        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                            com.winwasm64.ui.browser.OmniKeyPanel(
                                webView = activeTab.webView,
                                onClose = { showKeyPanel = false },
                                onShowSystemKeyboard = { forceShowKeyboard(context, activeTab.webView) }
                            )
                        }
                    }
                }
            }

            Box(
                modifier = if ((currentSection == 0 || inPip) && fullscreenCustomView == null) {
                    Modifier.weight(1f).fillMaxWidth()
                } else {
                    Modifier.size(0.dp)
                }
            ) {
                AndroidView(
                    factory = {
                        ensureTabWebView(activeTab)
                        (webViewContainer.parent as? ViewGroup)?.removeView(webViewContainer)
                        webViewContainer
                    },
                    update = { container ->
                        val current = tabs.getOrNull(activeTabIndex)
                        if (current != null) {
                            ensureTabWebView(current)
                            applyTabVisibility(container, current)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                // v4.3.0: плавающая кнопка «Скачать видео» (появляется, когда страница запросила медиа)
                val grabTab = tabs.getOrNull(activeTabIndex)
                // v4.10.9: клавиатура открыта (человек пишет сообщение/логин) — кнопка не закрывает поле ввода
                val typingNow = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                if (!inPip && grabTab != null && grabTab.hasMedia && currentSection == 0 && !typingNow && !hideMediaButton) {
                    SmallFloatingActionButton(
                        onClick = { openMediaGrabber(grabTab) },
                        containerColor = Omni.Accent,
                        contentColor = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 22.dp)
                    ) {
                        Icon(OmniIcons.Download, contentDescription = "Скачать видео", modifier = Modifier.size(22.dp))
                    }
                }
            }

            // ===================== SECTION 1: TORRENT CLIENT + BROWSEC VPN + MEMORY ACCESS =====================
            if (currentSection == 1 && !inPip) {
                // v4.47.0: в «Торрентах» теперь три режима — торренты, файлы напрямую и фото/обложки
                // v4.49.0: режим тоже переживает переходы между вкладками
                var searchMode by remember { mutableIntStateOf(com.winwasm64.torrent.SearchCache.mode.coerceIn(0, 2)) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // v4.71.0: «Видео» больше не режим внутри «Торрентов» — это своя вкладка в навигации
                    listOf("🧲 Торренты", "📦 Файлы", "🖼 Фото").forEachIndexed { idx, label ->
                        Surface(
                            color = if (searchMode == idx) Omni.AccentContainer else Omni.SurfaceHighest,
                            shape = RoundedCornerShape(9.dp),
                            modifier = Modifier.clickable {
                                searchMode = idx
                                com.winwasm64.torrent.SearchCache.mode = idx
                            }
                        ) {
                            Text(
                                label,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                if (searchMode == 2) {
                    PhotoSection(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        initialQuery = torrentEngine.cachedSearchQuery
                    )
                } else if (searchMode == 1) {
                    DirectFilesSection(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        initialQuery = torrentEngine.cachedSearchQuery,
                        onOpenUrlInBrowser = { u -> navigateTab(activeTab, u) }
                    )
                } else TorrentClientSection(
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

            // ===================== SECTION 6: ВИДЕО (своя вкладка) =====================
            if (currentSection == 6 && !inPip) {
                VideoHomeSection(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    initialQuery = com.winwasm64.torrent.SearchCache.videoQuery
                        .ifBlank { torrentEngine.cachedSearchQuery },
                    onOpenUrl = { u -> navigateTab(activeTab, u) }
                )
            }

            // ===================== SECTION 2: HISTORY & DOWNLOADS MANAGER =====================
            if (currentSection == 2 && !inPip) {
                HistoryAndDownloadsSection(
                    initialSubTab = 1,
                    lockSubTab = true,
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

            // ===================== SECTION 4: DOWNLOADS (отдельная вкладка) =====================
            if (currentSection == 4 && !inPip) {
                HistoryAndDownloadsSection(
                    initialSubTab = 0,
                    lockSubTab = true,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    historyList = historyList,
                    torrentEngine = torrentEngine,
                    nativeAppsManager = nativeAppsManager,
                    onOpenUrlInBrowser = { u ->
                        val cleanUrl = u.substringAfter("| ").trim()
                        navigateTab(activeTab, cleanUrl)
                        currentSection = 0
                    },
                    onClearHistory = { historyList.clear(); persistBrowserSettings() },
                    onRequestStorageAccess = { requestFullStorageAccess() }
                )
            }

            // ===================== SECTION 5: VPN И КАНАЛЫ (отдельная вкладка) =====================
            if (currentSection == 5 && !inPip) {
                VpnChannelsSection(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
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
                    }
                )
            }

            // ===================== SECTION 3: NATIVE APPS + BROWSEC VPN LAUNCHER =====================
            if (currentSection == 3 && !inPip) {
                NativeAppsWithBrowsecSection(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    nativeAppsManager = nativeAppsManager,
                    installedApps = installedApps,
                    browsecEnabled = browsecEnabled,
                    browsecPaused = browsecPaused,
                    browsecCountry = browsecCountry,
                    browserVpnCountry = browserVpnCountry,
                    syncBothVpnsAuto = syncBothVpnsAuto,
                    autoHealEnabled = autoHealEnabled,
                    appServerMapVersion = appServerMapVersion,
                    vpnRoutedApps = vpnRoutedApps,
                    downloadedFiles = torrentEngine.getDownloadDirectory().listFiles()?.toList().orEmpty(),
                    onSelectCountry = { code ->
                        browsecCountry = code
                        BrowsecVpnService.selectSpecificCountry(context, code, resetTraffic = false)
                        if (syncBothVpnsAuto) {
                            browserVpnCountry = code
                        }
                        persistBrowserSettings()
                    },
                    onOpenServerPicker = { target ->
                        serverPickerTarget = target
                        showServerPickerModal = true
                    },
                    onSyncFromBrowserToApps = {
                        BrowsecVpnService.synchronizeBothVpns(context, fromBrowserToApps = true)
                        browsecCountry = browserVpnCountry
                        appServerMapVersion++
                        persistBrowserSettings()
                        Toast.makeText(
                            context,
                            "🔗 Сервер Браузера ($browserVpnCountry) синхронизирован во все Приложения!",
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    onSyncFromAppsToBrowser = {
                        BrowsecVpnService.synchronizeBothVpns(context, fromBrowserToApps = false)
                        browserVpnCountry = browsecCountry
                        persistBrowserSettings()
                        Toast.makeText(
                            context,
                            "🔗 Сервер Приложений ($browsecCountry) синхронизирован в Браузер!",
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    onToggleAutoSync = { enabled ->
                        syncBothVpnsAuto = enabled
                        BrowserDirectRepeater.syncWithAppsVpn = enabled
                        if (enabled) {
                            BrowsecVpnService.synchronizeBothVpns(context, fromBrowserToApps = true)
                            browsecCountry = browserVpnCountry
                            appServerMapVersion++
                        }
                        persistBrowserSettings()
                    },
                    onToggleAutoHeal = { enabled ->
                        autoHealEnabled = enabled
                        BrowserDirectRepeater.autoFailoverEnabled = enabled
                        BrowsecLocalProxyServer.autoFailoverEnabled = enabled
                        persistBrowserSettings()
                    },
                    onTriggerAutoHealNow = {
                        val msg = BrowsecLocalProxyServer.triggerAutoHealOrFailover()
                        browsecCountry = BrowsecVpnService.activeCountry
                        if (syncBothVpnsAuto) {
                            browserVpnCountry = browsecCountry
                        }
                        appServerMapVersion++
                        persistBrowserSettings()
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    },
                    onSelectAppSpecificCountry = { pkg, code ->
                        BrowsecLocalProxyServer.setAppServerCountry(pkg, code, resetTraffic = false)
                        if (!vpnRoutedApps.contains(pkg)) {
                            vpnRoutedApps.add(pkg)
                            BrowsecVpnService.routedPackages.add(pkg)
                        }
                        appServerMapVersion++
                        if (browsecEnabled) {
                            BrowsecVpnService.startForApp(context, code, pkg)
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
                                "⏹ Туннель VPN для приложений выключен",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onLaunchAppWithBrowsec = { pkg, name ->
                        startBrowsecAndLaunchNativeApp(pkg, name)
                    },
                    onOpenVpnTab = { currentSection = 5 },
                    onToggleAppInVpn = { pkg ->
                        if (vpnRoutedApps.contains(pkg)) {
                            vpnRoutedApps.remove(pkg)
                            BrowsecVpnService.routedPackages.remove(pkg)
                        } else {
                            vpnRoutedApps.add(pkg)
                            BrowsecVpnService.routedPackages.add(pkg)
                            BrowsecLocalProxyServer.getOrCreateAppProfile(pkg, browsecCountry)
                        }
                        appServerMapVersion++
                        if (browsecEnabled) {
                            BrowsecVpnService.startForApp(context, browsecCountry, null)
                        }
                        persistBrowserSettings()
                    },
                    onRefreshApps = { installedApps = nativeAppsManager.loadInstalledNativeApps() }
                )
            }

            if (showServerPickerModal) {
                ManualServerPickerDialog(
                    targetMode = serverPickerTarget,
                    browserVpnCountry = browserVpnCountry,
                    appsVpnCountry = browsecCountry,
                    onChangeTargetMode = { serverPickerTarget = it },
                    onPickServer = { chosenCode ->
                        when {
                            serverPickerTarget == "browser" -> {
                                browserVpnCountry = chosenCode
                                browserVpnEnabled = true
                                BrowserDirectRepeater.switchCountry(chosenCode, resetSessionTraffic = false)
                                navigateTab(activeTab, activeTab.url)
                            }
                            serverPickerTarget == "apps" -> {
                                browsecCountry = chosenCode
                                BrowsecVpnService.selectSpecificCountry(context, chosenCode, resetTraffic = false)
                            }
                            serverPickerTarget.startsWith("app:") -> {
                                val pkg = serverPickerTarget.removePrefix("app:")
                                BrowsecLocalProxyServer.setAppServerCountry(pkg, chosenCode, resetTraffic = false)
                                if (!vpnRoutedApps.contains(pkg)) {
                                    vpnRoutedApps.add(pkg)
                                    BrowsecVpnService.routedPackages.add(pkg)
                                }
                                appServerMapVersion++
                                if (browsecEnabled) {
                                    BrowsecVpnService.startForApp(context, chosenCode, pkg)
                                }
                            }
                            else -> {
                                // "both" — синхронно для Браузера и Приложений
                                browserVpnCountry = chosenCode
                                browsecCountry = chosenCode
                                browserVpnEnabled = true
                                BrowserDirectRepeater.switchCountry(chosenCode, resetSessionTraffic = false)
                                BrowsecVpnService.selectSpecificCountry(context, chosenCode, resetTraffic = false)
                                for (pkg in vpnRoutedApps) {
                                    BrowsecLocalProxyServer.setAppServerCountry(pkg, chosenCode, resetTraffic = false)
                                }
                                appServerMapVersion++
                                navigateTab(activeTab, activeTab.url)
                            }
                        }
                        persistBrowserSettings()
                        showServerPickerModal = false
                    },
                    onRestoreServerOriginal = { code ->
                        BrowserDirectRepeater.getServerState(code).restoreToOriginalState()
                        BrowsecLocalProxyServer.getServerState(code).restoreToOriginalState()
                        Toast.makeText(
                            context,
                            "🔄 Сервер $code возвращён в исходное состояние!",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onDismiss = { showServerPickerModal = false }
                )
            }

            // ===================== НИЖНЯЯ НАВИГАЦИЯ =====================
            if (fullscreenCustomView == null && !inPip) {
                // v4.75.0: полоска «кто сейчас выполняет задачу» — фоновые задачи агента
                val agentTasks by com.winwasm64.torrent.AgentTasks.tasks
                LaunchedEffect(Unit) { com.winwasm64.torrent.AgentTasks.load(context) }
                val runningTask = agentTasks.firstOrNull {
                    it.status == com.winwasm64.torrent.AgentTasks.RUNNING ||
                        it.status == com.winwasm64.torrent.AgentTasks.WAITING
                }
                if (runningTask != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(Omni.AccentContainer.copy(alpha = 0.35f))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            (if (runningTask.status == com.winwasm64.torrent.AgentTasks.WAITING) "📶 ждёт сеть · " else "⏳ ") +
                                runningTask.title.take(32) + " · " + runningTask.step.take(40),
                            color = Color.White, fontSize = 10.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        Text("открыть", color = Omni.Accent, fontSize = 10.sp)
                    }
                }
                OmniBottomBar(
                    selected = currentSection,
                    items = listOf(
                        // v4.36.0: у КАЖДОГО пункта теперь указан свой раздел.
                        // Раньше «Загрузки» вели в раздел 4, а «Приложения» оставались без номера и получали
                        // номер по позиции — тоже 4. Две вкладки подсвечивались одновременно, и «Приложения»
                        // не открывались: нажатие выбирало уже выбранный раздел.
                        OmniNavItem("Браузер", OmniIcons.Globe, section = 0),
                        OmniNavItem("Торренты", OmniIcons.Torrent, section = 1),
                        // v4.71.0: «Видео» — отдельная вкладка, чтобы поиск роликов был виден сразу
                        OmniNavItem("Видео", OmniIcons.Play, section = 6),
                        OmniNavItem("История", OmniIcons.History, section = 2),
                        OmniNavItem("Загрузки", OmniIcons.Download, section = 4),
                        OmniNavItem("Приложения", OmniIcons.Apps, section = 3),
                        // v4.36.0: VPN — отдельная вкладка после «Приложений», как и просили
                        OmniNavItem("VPN", OmniIcons.Shield, section = 5)
                    ),
                    onSelect = { currentSection = it }
                )
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
    // v4.41.0: запрос и результаты берём из движка — они переживают переход на другую вкладку.
    // Раньше это был remember, и при возврате в «Торренты» список был пуст.
    var searchQuery by remember { mutableStateOf(torrentEngine.cachedSearchQuery) }
    var catalogItems by remember {
        mutableStateOf(
            torrentEngine.cachedSearchResults.ifEmpty { torrentEngine.getBuiltInVerifiedTorrents() }
        )
    }
    var isSearching by remember { mutableStateOf(false) }
    // v4.42.0/v4.49.0: фильтр по категориям — игры/видео/музыка/книги/софт (переживает вкладки)
    var categoryFilter by remember { mutableStateOf(com.winwasm64.torrent.SearchCache.torrentKind) }
    // v4.51.0: «чистый» запрос пользователя. Категория добавляет уточняющее слово, но не портит базу —
    // раньше каждое переключение категории дописывало слово к уже дописанному.
    var baseQuery by remember { mutableStateOf(com.winwasm64.torrent.SearchCache.torrentQuery) }
    var searchDepth by remember { mutableStateOf(com.winwasm64.torrent.SearchSettings.depth(context, "torrents")) }
    // v4.43.0: фильтр по качеству/формату — «хочу 360p» или «только FLAC»
    var qualityFilter by remember { mutableStateOf(com.winwasm64.torrent.SearchCache.torrentQuality) }
    // v4.49.0: сортировка — сначала новые/старые/тяжёлые/лёгкие
    var sortMode by remember { mutableIntStateOf(com.winwasm64.torrent.SearchCache.torrentSort) }
    val byCategory = remember(catalogItems, categoryFilter) {
        if (categoryFilter == "Все") catalogItems
        else catalogItems.filter {
            com.winwasm64.torrent.TorrentSearchAgent.categoryMatches(categoryFilter, it.kind)
        }
    }
    // какие качества реально есть в выдаче — показываем только их, чтобы не врать
    val qualityOptions = remember(byCategory) {
        byCategory.map { it.quality }.filter { it.isNotBlank() }.distinct()
    }
    val visibleItems = remember(byCategory, qualityFilter, sortMode) {
        val base = if (qualityFilter.isBlank()) byCategory else byCategory.filter { it.quality == qualityFilter }
        when (sortMode) {
            1 -> base.sortedByDescending { it.seeders }
            2 -> base.sortedBy { it.seeders }
            3 -> base.sortedByDescending { it.estimatedSizeBytes }
            4 -> base.sortedBy { it.estimatedSizeBytes }
            else -> base
        }
    }
    // поиск выполняется в фоне, поэтому состояние обновляем на главном потоке
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    fun runSearch(query: String, keepBase: Boolean = false) {
        isSearching = true
        if (!keepBase) baseQuery = query
        com.winwasm64.torrent.SearchCache.torrentQuery = query
        catalogItems = emptyList()
        // v4.66.0: агент вернулся, но на моделях ИИ-чата (OpenCode/LLM7/OpenRouter/…) и строго в фоне:
        // варианты берём только готовые из памяти, а расчёт идёт параллельно с поиском.
        com.winwasm64.torrent.AiCurator.prefetch(context, query, 2)
        val aiExtra = com.winwasm64.torrent.AiCurator.cachedVariants(query)
        torrentEngine.searchPublicTorrents(query, searchDepth, aiExtra) { res ->
            mainHandler.post {
                catalogItems = res
                isSearching = false
            }
            // отбраковка явно не того — тоже в фоне, выдачу не задерживает
            if (res.isNotEmpty() && com.winwasm64.torrent.AiCurator.isEnabled(context)) {
                Thread({
                    val keep = com.winwasm64.torrent.AiCurator.keepIndices(
                        context, query, res.map { it.title }, 30
                    )
                    if (keep != null) {
                        mainHandler.post {
                            catalogItems = res.filterIndexed { idx, _ -> idx in keep || idx >= 30 }
                        }
                    }
                }, "AiFilter").apply { isDaemon = true; start() }
            }
        }
    }

    Column(
        modifier = modifier
            .background(Omni.Bg)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            color = Omni.Surface,
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Omni.Outline)
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        // v4.57.0: «лишний текст сверху» мешал листать — оставили только счётчики
                        Text(
                            text = "BitTorrent + Browsec VPN ($browsecCountry)",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        // v4.38.0: автопоиск живой копии вместо пустышки — видно и отключаемо
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            var autoRescue by remember { mutableStateOf(torrentEngine.autoRescue) }
                            Surface(
                                color = if (autoRescue) Omni.AccentContainer else Omni.SurfaceHighest,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable {
                                    autoRescue = !autoRescue
                                    torrentEngine.updateAutoRescue(autoRescue)
                                }
                            ) {
                                Text(
                                    text = if (autoRescue) "🔁 Автозамена мёртвых раздач: ВКЛ" else "🔁 Автозамена мёртвых раздач: ВЫКЛ",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                        // v4.33.0: трафик и скорость — по всем задачам сразу, обновляется каждые 0,5 с
                        val totalsDown = statuses.sumOf { it.downloadedBytes }
                        val totalsSize = statuses.sumOf { it.totalBytes }
                        val totalsSpeed = statuses.sumOf { it.downloadSpeedBytesPerSec }
                        val totalsPeers = statuses.sumOf { it.connectedPeers }
                        Text(
                            text = "📥 Скачано ${BitTorrentEngine.formatSize(totalsDown)}" +
                                (if (totalsSize > 0) " из ${BitTorrentEngine.formatSize(totalsSize)}" else "") +
                                " · скорость ↓ ${BitTorrentEngine.formatSpeed(totalsSpeed)}" +
                                " · пиры $totalsPeers · задач ${statuses.size}",
                            color = if (totalsSpeed > 0) Omni.Success else Omni.TextDim,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = onPickTorrentFile,
                        colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("Файл .torrent", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                // Storage Permission + Browsec VPN row for Torrents
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = if (browsecEnabled) Omni.AccentContainer else Omni.SurfaceHighest,
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
                        color = Omni.SurfaceHighest,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Omni.Accent),
                        modifier = Modifier.clickable { onCycleCountry() }
                    ) {
                        Text(
                            text = "Сменить страну ($browsecCountry)",
                            color = Color.White,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }

                    Surface(
                        color = Omni.SurfaceHighest,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable { onRequestStorageAccess() }
                    ) {
                        Text(
                            text = "Доступ к памяти (/Download)",
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
                        colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer)
                    ) {
                        Text("Скачать", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
                        placeholder = "Что найти? Например: elliot quest switch, apk, flac, фильм 2024...",
                        modifier = Modifier.weight(1f),
                        onGo = { runSearch(searchQuery) }
                    )
                    // v4.44.0: то, чего нет в каталогах, ищем настоящими поисковиками
                    // (Google/Яндекс/DuckDuckGo) во встроенном WebView
                    Button(
                        onClick = {
                            if (searchQuery.isBlank()) {
                                Toast.makeText(context, "Сначала введите название", Toast.LENGTH_SHORT).show()
                            } else {
                                context.startActivity(
                                    android.content.Intent(
                                        context,
                                        com.winwasm64.torrent.TorrentWebSearchActivity::class.java
                                    )
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        .putExtra("query", searchQuery)
                                )
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Omni.SurfaceHighest)
                    ) {
                        Text("🌐", fontSize = 16.sp)
                    }
                    Button(
                        onClick = { runSearch(searchQuery) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer)
                    ) {
                        Text(if (isSearching) "⏳..." else "🔍 Найти", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
                // v4.54.0: сколько запросов отправлять — выбирает человек
                SearchDepthRow("torrents") { searchDepth = it }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (statuses.isNotEmpty()) {
                item {
                    Text(
                        text = "Активные и завершённые торрент-загрузки (${statuses.size}):",
                        color = Omni.Accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                items(statuses, key = { it.id }) { st ->
                    Surface(
                        color = Omni.SurfaceHigh,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (st.state == TorrentState.COMPLETED) Omni.Accent else Omni.TextDim
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
                                    color = Omni.Success,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.sp
                                )
                            }

                            LinearProgressIndicator(
                                progress = { st.progressFraction },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = if (st.state == TorrentState.COMPLETED) Omni.Success else Omni.Accent,
                                trackColor = Omni.Surface
                            )

                            Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Omni.Bg)
                            ) {
                                val count = st.pieceBitfield.size.coerceAtLeast(1)
                                val cellW = size.width / count
                                for (i in 0 until count) {
                                    if (st.pieceBitfield[i]) {
                                        drawRect(
                                            color = Omni.Success,
                                            topLeft = Offset(i * cellW, 0f),
                                            size = Size(cellW.coerceAtLeast(2f), size.height)
                                        )
                                    }
                                }
                            }

                            Text(
                                text = "${st.state.labelRu} • Скорость: ${BitTorrentEngine.formatSpeed(st.downloadSpeedBytesPerSec)} • Пиры: ${st.connectedPeers}/${st.knownPeers}",
                                color = Omni.TextDim,
                                fontSize = 11.sp
                            )
                            Text(
                                text = st.statusDetail,
                                color = Omni.Text,
                                fontSize = 11.sp
                            )

                            // v4.39.0: аналогов показываем списком, а не запускаем молча —
                            // человек сам решает, годится ли «другое издание» вместо исходного
                            if (st.suggestions.isNotEmpty()) {
                                Text(
                                    "Похожие с пирами — можно взять вместо этой:",
                                    color = Omni.Warning, fontSize = 11.sp, fontWeight = FontWeight.Bold
                                )
                                st.suggestions.forEach { sug ->
                                    Surface(
                                        color = Omni.SurfaceHighest,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                                                Text(sug.name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                                                Text(
                                                    sug.reason.ifBlank { "${sug.sizeLabel} · ${sug.source}" },
                                                    color = Omni.TextDim, fontSize = 10.sp, maxLines = 2
                                                )
                                            }
                                            Button(
                                                onClick = {
                                                    torrentEngine.startFromFound(sug)
                                                    Toast.makeText(context, "Запущено: ${sug.name.take(40)}", Toast.LENGTH_SHORT).show()
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                modifier = Modifier.height(30.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer)
                                            ) {
                                                Text("Скачать", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                            }
                                        }
                                    }
                                }
                            }

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
                                    colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer)
                                ) {
                                    Text("Открыть файл", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
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
                                    Text("Удалить", fontSize = 11.sp, color = Omni.Danger, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }

            if (catalogItems.isEmpty()) {
                // v4.33.0: список по умолчанию пустой — никаких «демо-раздач»
                item {
                    Surface(
                        color = Omni.Surface,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Omni.Outline)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // v4.59.0: пока идёт поиск — «ищу», а не «пусто»; после — причина, а не заглушка
                            if (isSearching) {
                                Text(
                                    "Ищу «${searchQuery.trim()}» на всех трекерах…",
                                    color = Omni.Warning, fontWeight = FontWeight.Bold, fontSize = 12.sp
                                )
                                Text(
                                    "Rutor, apibay/TPB, nnmclub, nyaa, открытые индексы. Результаты появятся сами — жать ничего не нужно.",
                                    color = Omni.TextDim, fontSize = 11.sp
                                )
                            } else if (searchQuery.isNotBlank()) {
                                Text(
                                    "По «${searchQuery.trim()}» ничего не нашлось",
                                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp
                                )
                                Text(
                                    "Проверено: rutor, apibay (The Pirate Bay), nnmclub, nyaa, открытые индексы и Archive.org. " +
                                        "Бывает, что трекеры не отвечают на кириллицу — попробуйте латиницу (karlik nos) " +
                                        "или короткое название, и нажмите «Найти» ещё раз.",
                                    color = Omni.TextDim,
                                    fontSize = 11.sp
                                )
                            } else {
                                Text("Список раздач пуст", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text(
                                    "Введите название и нажмите «Найти», вставьте magnet-ссылку выше или откройте .torrent файлом.",
                                    color = Omni.TextDim,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            } else {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        com.winwasm64.torrent.TorrentSearchAgent.CATEGORIES.forEach { cat ->
                            val sel = cat == categoryFilter
                            Surface(
                                color = if (sel) Omni.AccentContainer else Omni.SurfaceHighest,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable {
                                    // v4.52.0: НИКАКОГО повторного поиска и никаких слов в запросе —
                                    // одна выдача уже размечена по категориям, переключение мгновенное.
                                    categoryFilter = cat
                                    com.winwasm64.torrent.SearchCache.torrentKind = cat
                                }
                            ) {
                                // счётчик прямо в чипе: видно, сколько раздач в каждой категории
                                val chipCount = if (cat == "Все") catalogItems.size else {
                                    catalogItems.count {
                                        com.winwasm64.torrent.TorrentSearchAgent.categoryMatches(cat, it.kind)
                                    }
                                }
                                Text(
                                    "$cat" + (if (catalogItems.isNotEmpty()) " $chipCount" else ""),
                                    color = if (!sel && chipCount == 0) Omni.TextDim else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
                if (qualityOptions.size > 1) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // «Любое» + только те разрешения, что есть у найденных раздач
                            listOf("").plus(qualityOptions).forEach { q ->
                                val sel = q == qualityFilter
                                Surface(
                                    color = if (sel) Omni.AccentContainer else Omni.SurfaceHighest,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.clickable {
                                        qualityFilter = q
                                        com.winwasm64.torrent.SearchCache.torrentQuality = q
                                    }
                                ) {
                                    Text(
                                        if (q.isBlank()) "Любое качество" else q,
                                        color = Color.White, fontSize = 10.sp,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    // v4.49.0: сортировка — новые/старые (по сидам), тяжёлые/лёгкие (по весу)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("По релевантности", "🔥 Популярные", "❄ Старые", "⬇ Тяжёлые", "⬆ Лёгкие")
                            .forEachIndexed { idx, label ->
                                val sel = sortMode == idx
                                Surface(
                                    color = if (sel) Omni.AccentContainer else Omni.SurfaceHighest,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.clickable {
                                        sortMode = idx
                                        com.winwasm64.torrent.SearchCache.torrentSort = idx
                                    }
                                ) {
                                    Text(
                                        label, color = Color.White, fontSize = 10.sp,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                    )
                                }
                            }
                    }
                }
                item {
                    // v4.46.0: считаем то, что реально видно. Раньше писалось «13», а список после
                    // фильтра был пуст — цифра и экран противоречили друг другу.
                    // v4.57.0: краткая сводка — лучшая раздача и сколько у неё сидов/пиров/вес
                    val topSeed = visibleItems.maxByOrNull { it.seeders }
                    val topLine = if (topSeed != null && (topSeed.seeders > 0 || topSeed.leechers > 0)) {
                        " · лучшая: 🌱 ${topSeed.seeders} / 👤 ${topSeed.leechers} · ${topSeed.sizeLabel} · ${topSeed.category.substringAfterLast('·').trim()}"
                    } else ""
                    Text(
                        text = "Найдено раздач: ${visibleItems.size}" +
                            (if (visibleItems.size != catalogItems.size) " из ${catalogItems.size}" else "") +
                            (if (searchQuery.isNotBlank()) " по «${searchQuery.trim()}»" else "") +
                            (if (isSearching) " · ищу…" else "") +
                            (if (categoryFilter != "Все") " · категория: $categoryFilter" else "") +
                            (if (qualityFilter.isNotBlank()) " · качество: $qualityFilter" else "") +
                            topLine,
                        color = Omni.Warning,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
                if (visibleItems.isEmpty() && catalogItems.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Под этот фильтр ничего не подошло. В выдаче есть ${catalogItems.size} раздач(и) — в других категориях.",
                                color = Omni.TextDim,
                                fontSize = 12.sp
                            )
                            Button(
                                onClick = {
                                    categoryFilter = "Все"
                                    qualityFilter = ""
                                    com.winwasm64.torrent.SearchCache.torrentKind = "Все"
                                    com.winwasm64.torrent.SearchCache.torrentQuality = ""
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.height(36.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Omni.SurfaceHighest)
                            ) {
                                Text("Показать все категории (${catalogItems.size})", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            items(visibleItems, key = { it.torrentUrl + "|" + it.title }) { item ->
                Surface(
                    color = Omni.Surface,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Omni.Outline)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // v4.41.0: превью-обложка (og:image со страницы раздачи) вместо голого текста
                        // v4.47.0: у карточки своя обложка; если её нет — иконка СВОЕЙ категории
                        // (игра/видео/музыка/книга/софт), а не одна «🎬» на всех
                        resultCover(item.poster, item.kind)

                        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(item.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            // v4.57.0: «раз это ИИ — покажи сид, пиры, раздают кратко»
                            val seedsLine = if (item.seeders > 0 || item.leechers > 0) {
                                "🌱 ${item.seeders} · 👤 ${item.leechers}"
                            } else "🌱 нет данных"
                            Text(
                                seedsLine + " · " + item.sizeLabel +
                                    (if (item.quality.isNotBlank()) " · ${item.quality}" else "") +
                                    (if (item.codec.isNotBlank()) " · ${item.codec}" else "") +
                                    " · " + item.category,
                                color = Omni.Accent, fontSize = 11.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(item.description, color = Omni.TextDim, fontSize = 11.sp, maxLines = 2)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = {
                                        torrentEngine.startFromCatalogItem(item)
                                        Toast.makeText(context, "🧲 Скачивание начато: ${item.title}", Toast.LENGTH_SHORT).show()
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer)
                                ) {
                                    Text("⬇ Скачать", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                                // смотреть/слушать без полной загрузки — куски идут по порядку
                                Button(
                                    onClick = {
                                        context.startActivity(
                                            android.content.Intent(context, com.winwasm64.torrent.TorrentPlayerActivity::class.java)
                                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                .putExtra("name", item.title)
                                                .putExtra("magnet", item.magnetUri)
                                                .putExtra("torrentUrl", item.torrentUrl)
                                                .putExtra("referer", item.referer)
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Omni.SurfaceHighest)
                                ) {
                                    Text("▶ Смотреть", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** v4.49.0: человеческий размер — «1.2 ГБ», а не «1288490188». */
private fun fmtBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 Б"
    val units = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.toDouble()
    var idx = 0
    while (value >= 1024 && idx < units.lastIndex) {
        value /= 1024
        idx++
    }
    return String.format(java.util.Locale.US, if (value >= 100 || idx == 0) "%.0f %s" else "%.1f %s", value, units[idx])
}

@Composable
private fun HistoryAndDownloadsSection(
    modifier: Modifier,
    initialSubTab: Int = 0,
    lockSubTab: Boolean = false,
    historyList: List<String>,
    torrentEngine: BitTorrentEngine,
    nativeAppsManager: NativeAppsManager,
    onOpenUrlInBrowser: (String) -> Unit,
    onClearHistory: () -> Unit,
    onRequestStorageAccess: () -> Unit
) {
    val context = LocalContext.current
    // v4.34.0: 0 = Загрузки, 1 = История. Раздел можно открыть сразу на нужной вкладке,
    // а переключатель сверху спрятать — так «История» и «Загрузки» стали отдельными вкладками.
    var subTab by remember { mutableIntStateOf(initialSubTab.coerceIn(0, 1)) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    // v4.49.0: живые загрузки приложения (DownloadManager) и торрент-сессии — с прогрессом
    var systemDownloads by remember { mutableStateOf<List<com.winwasm64.torrent.AppDownloads.Item>>(emptyList()) }
    var downloadTick by remember { mutableIntStateOf(0) }
    var ownDownloads by remember { mutableStateOf<List<com.winwasm64.torrent.StreamDownloader.Item>>(emptyList()) }
    LaunchedEffect(subTab) {
        while (subTab == 0) {
            systemDownloads = try {
                com.winwasm64.torrent.AppDownloads.list(context)
            } catch (_: Throwable) {
                emptyList()
            }
            // v4.55.0: собственный загрузчик (видео с YouTube/VK/RuTube)
            ownDownloads = try {
                val live = com.winwasm64.torrent.StreamDownloader.snapshot()
                live.ifEmpty { com.winwasm64.torrent.StreamDownloader.history(context) }
            } catch (_: Throwable) {
                emptyList()
            }
            kotlinx.coroutines.delay(1000)
            downloadTick++
        }
    }

    val downloadedFiles = remember(refreshTrigger, subTab) {
        val list = mutableListOf<File>()
        torrentEngine.getDownloadDirectory().listFiles()?.let { list.addAll(it) }
        try {
            val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(pub, "OmniTorrents").listFiles()?.let { list.addAll(it) }
            File(pub, "OmniBrowser").listFiles()?.let { list.addAll(it) }
            pub?.listFiles()?.filter { it.isFile }?.take(30)?.let { list.addAll(it) }
        } catch (_: Exception) {
        }
        list.filter { it.isFile && !it.name.startsWith(".probe") }
            .distinctBy { it.name + "_" + it.length() }
            .sortedByDescending { it.lastModified() }
    }

    Column(
        modifier = modifier
            .background(Omni.Bg)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (lockSubTab) {
            // отдельная вкладка: заголовок вместо переключателя
            Text(
                text = if (subTab == 0) "Загрузки (${downloadedFiles.size})" else "История (${historyList.size})",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { subTab = 0; refreshTrigger++ },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = if (subTab == 0) Omni.AccentContainer else Omni.SurfaceHighest)
                ) {
                    Text("Загрузки (${downloadedFiles.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Button(
                    onClick = { subTab = 1 },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = if (subTab == 1) Omni.AccentContainer else Omni.SurfaceHighest)
                ) {
                    Text("История (${historyList.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        if (subTab == 0) {
            Surface(
                color = Omni.Surface,
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Omni.Outline)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Скачанные файлы · Загрузки/${com.winwasm64.torrent.AppDownloads.SUBDIR}",
                            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp
                        )
                        Text(
                            "Торренты: папка приложения · прямые ссылки и браузер: Загрузки/${com.winwasm64.torrent.AppDownloads.SUBDIR}",
                            color = Omni.Success, fontSize = 11.sp
                        )
                    }
                    Button(
                        onClick = onRequestStorageAccess,
                        colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Доступ к памяти", fontSize = 11.sp, color = Color.White)
                    }
                }
            }

            // v4.55.0: видео, скачанное своим загрузчиком (с проверкой размера)
            if (ownDownloads.isNotEmpty()) {
                Surface(
                    color = Omni.Surface,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Omni.AccentContainer)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Скачано приложением (${ownDownloads.size})",
                            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp
                        )
                        for (d in ownDownloads.take(10)) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    d.name, color = Color.White, fontSize = 12.sp, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Text(
                                    when (d.state) {
                                        com.winwasm64.torrent.StreamDownloader.STATE_DONE ->
                                            "✅ готово · ${fmtBytes(d.bytes)}"
                                        com.winwasm64.torrent.StreamDownloader.STATE_ERROR ->
                                            "❌ ${d.note}"
                                        else -> "⏳ ${d.note} · ${d.progress}% · ${fmtBytes(d.bytes)}"
                                    },
                                    color = when (d.state) {
                                        com.winwasm64.torrent.StreamDownloader.STATE_DONE -> Omni.Success
                                        com.winwasm64.torrent.StreamDownloader.STATE_ERROR -> Omni.Danger
                                        else -> Omni.Warning
                                    },
                                    fontSize = 11.sp
                                )
                                if (d.state == com.winwasm64.torrent.StreamDownloader.STATE_RUNNING && d.total > 0) {
                                    androidx.compose.material3.LinearProgressIndicator(
                                        progress = { d.progress / 100f },
                                        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                                        color = Omni.Accent, trackColor = Omni.SurfaceHighest
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // v4.49.0: загрузки, которые раньше были видны только в шторке уведомлений
            if (systemDownloads.isNotEmpty()) {
                Surface(
                    color = Omni.Surface,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Omni.AccentContainer)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Загрузки приложения (${systemDownloads.size}) · Загрузки/${com.winwasm64.torrent.AppDownloads.SUBDIR}",
                            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp
                        )
                        // v4.68.0: «кнопки продолжить либо повторить… и не могу открыть файл»
                        for (d in systemDownloads.take(20)) {
                            val file = if (d.isDone) com.winwasm64.torrent.AppDownloads.fileOf(context, d) else null
                            fun openIt() {
                                val f = com.winwasm64.torrent.AppDownloads.fileOf(context, d)
                                if (f == null) {
                                    Toast.makeText(context, "файл не найден — возможно, он удалён", Toast.LENGTH_SHORT).show()
                                    return
                                }
                                val isVideo = listOf(".mp4", ".mkv", ".webm", ".m4v", ".mov", ".ts", ".m3u8", ".avi")
                                    .any { f.name.lowercase().endsWith(it) }
                                if (isVideo) {
                                    context.startActivity(
                                        Intent(context, com.winwasm64.ui.NativeVideoPlayerActivity::class.java)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            .putExtra("url", android.net.Uri.fromFile(f).toString())
                                            .putExtra("title", f.name)
                                    )
                                } else {
                                    nativeAppsManager.openFileInNativeApp(f).onFailure {
                                        Toast.makeText(context, "Не открылось: ${it.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable(enabled = d.isDone) { openIt() }
                                    .padding(vertical = 3.dp)
                            ) {
                                Text(
                                    d.name.ifBlank { "файл ${d.id}" },
                                    color = Color.White, fontSize = 12.sp, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Text(
                                    when {
                                        d.isDone -> "✅ готово · ${fmtBytes(d.total)}" + (if (file != null) " · нажмите, чтобы открыть" else " · файл не найден")
                                        d.isFailed -> "❌ ошибка (${d.reason}) · ${fmtBytes(d.bytes)} из ${fmtBytes(d.total)}"
                                        else -> "⏳ качается ${d.progress}% · ${fmtBytes(d.bytes)} из ${fmtBytes(d.total)}"
                                    },
                                    color = when {
                                        d.isDone -> Omni.Success
                                        d.isFailed -> Omni.Danger
                                        else -> Omni.Warning
                                    },
                                    fontSize = 11.sp
                                )
                                if (d.isActive) {
                                    androidx.compose.material3.LinearProgressIndicator(
                                        progress = { d.progress / 100f },
                                        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                                        color = Omni.Accent, trackColor = Omni.SurfaceHighest
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                                    if (d.isDone) {
                                        Button(
                                            onClick = { openIt() },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer),
                                            modifier = Modifier.height(30.dp)
                                        ) { Text("▶ Открыть", fontSize = 10.sp, color = Color.White) }
                                    }
                                    if (d.isFailed || d.status == android.app.DownloadManager.STATUS_PAUSED) {
                                        Button(
                                            onClick = {
                                                val ok = com.winwasm64.torrent.AppDownloads.retry(context, d)
                                                Toast.makeText(
                                                    context,
                                                    if (ok != null) "⟳ Повторяю загрузку" else "Не удалось повторить",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer),
                                            modifier = Modifier.height(30.dp)
                                        ) { Text("⟳ Повторить", fontSize = 10.sp, color = Color.White) }
                                    }
                                    if (d.status == android.app.DownloadManager.STATUS_PAUSED) {
                                        OutlinedButton(
                                            onClick = { com.winwasm64.torrent.AppDownloads.resume(context, d) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) { Text("➡ Продолжить", fontSize = 10.sp, color = Color.White) }
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            com.winwasm64.torrent.AppDownloads.remove(context, d)
                                            Toast.makeText(context, "Удалено", Toast.LENGTH_SHORT).show()
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) { Text("🗑", fontSize = 10.sp, color = Omni.Danger) }
                                }
                            }
                        }
                        Text(
                            "Всё складывается в одну папку приложения, а не в общий котёл /Download.",
                            color = Omni.TextDim, fontSize = 10.sp
                        )
                    }
                }
            }

            // v4.49.0: торрент-задачи прямо здесь — раньше скачанный торрент нигде не показывался
            val torrentRows = try {
                torrentEngine.getAllStatuses()
            } catch (_: Throwable) {
                emptyList<com.winwasm64.torrent.TorrentSessionStatus>()
            }
            if (torrentRows.isNotEmpty()) {
                Surface(
                    color = Omni.Surface,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Omni.Outline)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Торрент-задачи (${torrentRows.size})",
                            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp
                        )
                        for (t in torrentRows.take(20)) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    t.name.ifBlank { t.id },
                                    color = Color.White, fontSize = 12.sp, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Text(
                                    "🧲 ${t.state.labelRu} · ${fmtBytes(t.downloadedBytes)} из ${fmtBytes(t.totalBytes)}" +
                                        if (t.connectedPeers > 0) " · пиров ${t.connectedPeers}" else "",
                                    color = if (t.state == com.winwasm64.torrent.TorrentState.COMPLETED) Omni.Success
                                    else if (t.state == com.winwasm64.torrent.TorrentState.ERROR) Omni.Danger
                                    else Omni.Warning,
                                    fontSize = 11.sp
                                )
                                if (t.totalBytes > 0) {
                                    androidx.compose.material3.LinearProgressIndicator(
                                        progress = { (t.downloadedBytes.toFloat() / t.totalBytes).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                                        color = Omni.Accent, trackColor = Omni.SurfaceHighest
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // v4.5.0: очередь загрузок браузера — пауза / продолжить / удалить, переживает перезапуск
            val dlVersion = com.winwasm64.ui.browser.DownloadQueue.version.intValue
            val dlEntries = remember(dlVersion) { com.winwasm64.ui.browser.DownloadQueue.snapshot() }
            if (dlEntries.isNotEmpty()) {
                Surface(
                    color = Omni.Surface,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Omni.Outline)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Загрузки браузера (${dlEntries.size})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        for (e in dlEntries) {
                            val st = e.state
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(e.job.fileName, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(
                                    com.winwasm64.ui.browser.DownloadQueue.fmt(e),
                                    color = when (st) { com.winwasm64.ui.browser.DownloadQueue.ERROR -> Omni.Danger; com.winwasm64.ui.browser.DownloadQueue.RUNNING -> Omni.Success; else -> Omni.TextDim },
                                    fontSize = 11.sp
                                )
                                if (e.total > 0) {
                                    androidx.compose.material3.LinearProgressIndicator(
                                        progress = { (e.done.toFloat() / e.total).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                                        color = Omni.Accent, trackColor = Omni.SurfaceHighest
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                                    if (st == com.winwasm64.ui.browser.DownloadQueue.RUNNING || st == com.winwasm64.ui.browser.DownloadQueue.QUEUED) {
                                        Button(onClick = { com.winwasm64.ui.browser.MediaDownloadService.pause(context, e.id) },
                                            colors = ButtonDefaults.buttonColors(containerColor = Omni.SurfaceHighest),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("⏸ Пауза", fontSize = 11.sp, color = Color.White) }
                                    } else {
                                        Button(onClick = { com.winwasm64.ui.browser.MediaDownloadService.resume(context, e.id) },
                                            colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text(if (st == com.winwasm64.ui.browser.DownloadQueue.ERROR) "↻ Повторить" else "▶ Продолжить", fontSize = 11.sp, color = Color.White) }
                                    }
                                    Button(onClick = { com.winwasm64.ui.browser.MediaDownloadService.cancel(context, e.id) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Omni.SurfaceHighest),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("✕ Удалить", fontSize = 11.sp, color = Omni.TextDim) }
                                }
                            }
                        }
                    }
                }
            }

            // v4.5.0: сохранённые офлайн страницы (MHTML)
            val savedPages = remember(refreshTrigger) { com.winwasm64.ui.browser.PageArchiver.list(context) }
            if (savedPages.isNotEmpty()) {
                Surface(
                    color = Omni.Surface,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Omni.Outline)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Сохранённые страницы (${savedPages.size})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        for (f in savedPages.take(30)) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(f.name.removeSuffix(".mht"), color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Text(com.winwasm64.ui.browser.MediaGrabber.fmtSize(f.length()) + " · " + java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(f.lastModified())), color = Omni.TextDim, fontSize = 11.sp)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(onClick = { onOpenUrlInBrowser(com.winwasm64.ui.browser.PageArchiver.fileUrl(f)) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("Открыть", fontSize = 11.sp, color = Color.White) }
                                    OutlinedButton(onClick = { f.delete(); refreshTrigger++ }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("🗑", fontSize = 11.sp, color = Omni.Danger) }
                                }
                            }
                        }
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
                            color = Omni.TextDim,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                items(downloadedFiles, key = { it.absolutePath }) { file ->
                    Surface(
                        color = Omni.SurfaceHigh,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Omni.Outline)
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
                                    color = Omni.Accent,
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
                                    colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer)
                                ) {
                                    Text("Открыть", fontSize = 11.sp, color = Color.White)
                                }
                                // v4.66.0: скачанное видео открывается своим плеером (Media3), а не «чем-нибудь»
                                val isVideoFile = file.name.lowercase().let { n ->
                                    listOf(".mp4", ".mkv", ".webm", ".m4v", ".mov", ".ts", ".m3u8", ".avi").any { n.endsWith(it) }
                                }
                                if (isVideoFile) {
                                    OutlinedButton(
                                        onClick = {
                                            context.startActivity(
                                                Intent(context, com.winwasm64.ui.NativeVideoPlayerActivity::class.java)
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    .putExtra("url", android.net.Uri.fromFile(file).toString())
                                                    .putExtra("title", file.name)
                                            )
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) { Text("🎞 Плеер", fontSize = 11.sp, color = Color.White) }
                                }
                                OutlinedButton(
                                    onClick = {
                                        file.delete()
                                        refreshTrigger++
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("🗑", fontSize = 11.sp, color = Omni.Danger)
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
                Text("История посещённых сайтов (${historyList.size}):", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                OutlinedButton(onClick = onClearHistory) {
                    Text("Очистить историю", fontSize = 11.sp, color = Omni.Danger)
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(historyList) { entry ->
                    Surface(
                        color = Omni.Surface,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Omni.Outline),
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
                            Text("Перейти →", color = Omni.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
    browserVpnCountry: String,
    syncBothVpnsAuto: Boolean,
    autoHealEnabled: Boolean,
    appServerMapVersion: Int,
    vpnRoutedApps: List<String>,
    downloadedFiles: List<File>,
    onSelectCountry: (String) -> Unit,
    onOpenServerPicker: (String) -> Unit,
    onSyncFromBrowserToApps: () -> Unit,
    onSyncFromAppsToBrowser: () -> Unit,
    onToggleAutoSync: (Boolean) -> Unit,
    onToggleAutoHeal: (Boolean) -> Unit,
    onTriggerAutoHealNow: () -> Unit,
    onSelectAppSpecificCountry: (String, String) -> Unit,
    onToggleFloatingVpn: () -> Unit,
    onLaunchAppWithBrowsec: (String, String) -> Unit,
    onToggleAppInVpn: (String) -> Unit,
    onRefreshApps: () -> Unit,
    onOpenVpnTab: () -> Unit = {}
) {
    val context = LocalContext.current
    var selectedCategory by remember { mutableStateOf("Все") }
    var appSearchQuery by remember { mutableStateOf("") }
    var showVpnDetails by remember { mutableStateOf(false) } // v4.0.4: краткий вид по умолчанию
    var selectedFileForOpen by remember { mutableStateOf<File?>(downloadedFiles.firstOrNull()) }

    val categories = listOf(
        "Все",
        "🛡️ В списке VPN (${vpnRoutedApps.size})",
        "⚪ Без VPN (Прямая сеть)",
        "🎬 Видео и Аудио плееры",
        "📦 Файлы и Архиваторы",
        "🖥️ Эмуляторы и ОС (.EXE)",
        "🌐 Браузеры и Мессенджеры"
    )

    val filteredApps = remember(installedApps, selectedCategory, appSearchQuery, vpnRoutedApps.size, appServerMapVersion) {
        installedApps.filter { app ->
            val matchCat = when {
                selectedCategory == "Все" -> true
                selectedCategory.startsWith("🛡️") -> vpnRoutedApps.contains(app.packageName)
                selectedCategory.startsWith("⚪") -> !vpnRoutedApps.contains(app.packageName)
                else -> app.category == selectedCategory
            }
            val matchQuery = appSearchQuery.isBlank() ||
                app.appName.contains(appSearchQuery, ignoreCase = true) ||
                app.packageName.contains(appSearchQuery, ignoreCase = true)
            matchCat && matchQuery
        }
    }

    var liveTraffic by remember { mutableStateOf(BrowsecLocalProxyServer.sampleTraffic()) }
    var refreshTick by remember { mutableIntStateOf(0) }
    // v4.11.0: «значок VPN ≠ VPN для всех приложений» — область действия видна и переключается явно
    var perAppOnly by remember { mutableStateOf(BrowsecVpnService.perAppMode) }
    var ipCheckLine by remember { mutableStateOf("") }
    var ipChecking by remember { mutableStateOf(false) }
    var realServers by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var realServersTotal by remember { mutableIntStateOf(0) }
    var telegramStatus by remember { mutableStateOf("") }
    var telegramChecking by remember { mutableStateOf(false) }
    LaunchedEffect(refreshTick / 4) {
        if (realServers.isEmpty()) {
            withContext(Dispatchers.IO) {
                val keys = try { com.winwasm64.extensions.ExtensionProxy.tunnelServerKeysRanked() } catch (_: Exception) { emptyList() }
                val rows = keys.take(10).map { k ->
                    val ip = try { MediaGrabber.egressIp(k) } catch (_: Exception) { null }
                    k to (ip ?: "IP не проверить")
                }
                withContext(Dispatchers.Main) {
                    realServers = rows
                    realServersTotal = keys.size
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            liveTraffic = BrowsecLocalProxyServer.sampleTraffic()
            refreshTick++
            delay(500)
        }
    }

    Box(
        modifier = modifier
            .background(Omni.Bg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 84.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "vpn_header") {
            // v4.37.0: из этой вкладки убрано ВСЁ про VPN (профили обхода, «Проверить мой IP»,
            // перебор прокси Telegram, синхронизация браузера и приложений, авто-лечение, выбор сервера).
            // Здесь остаётся только то, ради чего вкладка нужна: список программ, поиск и запуск.
            // Всё про каналы — на вкладке «VPN», чтобы не было двух источников правды.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Приложения", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Список программ и запуск. Настройки VPN, серверы и диагностика — на вкладке «VPN».",
                        color = Omni.TextDim, fontSize = 10.sp
                    )
                }
                OutlinedButton(
                    onClick = onRefreshApps,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Обновить список", fontSize = 11.sp, color = Color.White)
                }
            }

                    CompactDarkTextField(
                        value = appSearchQuery,
                        onValueChange = { appSearchQuery = it },
                        placeholder = "Поиск нативного приложения (YouTube, Play Market, Telegram, Chrome)...",
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        categories.forEach { cat ->
                            val isSel = selectedCategory == cat
                            Surface(
                                color = if (isSel) Omni.AccentContainer else Omni.SurfaceHighest,
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
                items(filteredApps, key = { it.packageName }) { app ->
                    var itemExpanded by remember(app.packageName) { mutableStateOf(false) }
                    val isInVpn = vpnRoutedApps.contains(app.packageName)
                    val appProfile = remember(app.packageName, appServerMapVersion, refreshTick) {
                        BrowsecLocalProxyServer.getOrCreateAppProfile(app.packageName, browsecCountry)
                    }
                    val appNode = BrowsecLocalProxyServer.getServerNode(appProfile.countryCode)
                    val appServerState = BrowsecLocalProxyServer.getServerState(appProfile.countryCode)
                    val isTelegramApp = nativeAppsManager.isTelegramPackage(app.packageName)

                    Surface(
                        color = Omni.Surface,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isInVpn) Omni.Accent else Omni.Outline
                        ),
                        modifier = Modifier.fillMaxWidth().clickable { itemExpanded = !itemExpanded }
                    ) {
                        Column(modifier = Modifier.padding(if (itemExpanded) 10.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                            .background(Omni.SurfaceHighest),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("📱", fontSize = 18.sp)
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        app.appName,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    if (!itemExpanded) {
                                        Text(
                                            text = if (isInVpn) "🟢 в VPN · ${appNode.code}" else "⚪ вне VPN · напрямую",
                                            color = if (isInVpn) Omni.Success else Omni.TextDim,
                                            fontSize = 11.sp
                                        )
                                    }
                                    if (itemExpanded) Text(app.packageName, color = Omni.TextFaint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    if (itemExpanded) Text(
                                        text = if (isInVpn) {
                                            "🟢 В СПИСКЕ VPN • Свой сервер: ${appNode.flag} ${appNode.countryRu} (${appNode.remoteIp}:${appNode.remotePort}) • ⚡ Пинг: ${appServerState.pingMs} мс • Порт: 127.0.0.1:${appProfile.localPort}"
                                        } else {
                                            "⚪ ВНЕ VPN: идёт напрямую, VPN к этому приложению не применяется. " +
                                                "Если открывается — значит не заблокировано, VPN тут ни при чём."
                                        },
                                        color = if (isInVpn) Omni.Success else Omni.Warning,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (isInVpn && itemExpanded) {
                                        Text(
                                            text = "Трафик этого приложения: ⬇ ВХ: ${BrowsecVpnService.formatSpeedShort(appProfile.rxSpeedBps)} (${BrowsecVpnService.formatBytesShort(appProfile.rxBytes.get())})  |  ⬆ ИСХ: ${BrowsecVpnService.formatSpeedShort(appProfile.txSpeedBps)} (${BrowsecVpnService.formatBytesShort(appProfile.txBytes.get())})",
                                            color = Omni.Accent,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        // v4.2.14: точные замеры — время активности, соединения, задержка, сервер, итог за всё время
                                        val nowMs = System.currentTimeMillis()
                                        val sess = BrowsecVpnService.sessionStartedAt
                                        val sessTxt = if (sess > 0L && BrowsecVpnService.isRunning) "в VPN ${com.winwasm64.vpn.AppTrafficStore.fmtDur(nowMs - sess)}" else "VPN выключен"
                                        val okN = appProfile.connOk.get(); val badN = appProfile.connFail.get()
                                        val lastAct = appProfile.lastActivityAt
                                        Text(
                                            text = "Сеанс: $sessTxt · активно ${com.winwasm64.vpn.AppTrafficStore.fmtDur(appProfile.activeMs.get())}" +
                                                (if (lastAct > 0L) " · последняя активность ${com.winwasm64.vpn.AppTrafficStore.fmtDur(nowMs - lastAct)} назад" else " · трафика ещё не было") +
                                                " · соединений $okN" + (if (badN > 0) " (ошибок $badN)" else "") +
                                                (if (appProfile.avgConnectMs() > 0) " · подключение ср. ${appProfile.avgConnectMs()} мс" else "") +
                                                (appProfile.lastServer?.let { " · сервер ${it.substringBefore('.')}" } ?: ""),
                                            color = Omni.TextDim,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = "За всё время: ↓${BrowsecVpnService.formatBytesShort(appProfile.allTimeRx + (appProfile.rxBytes.get() - appProfile.flushedRx))} ↑${BrowsecVpnService.formatBytesShort(appProfile.allTimeTx + (appProfile.txBytes.get() - appProfile.flushedTx))}" +
                                                " · активно ${com.winwasm64.vpn.AppTrafficStore.fmtDur(appProfile.allTimeActiveMs + (appProfile.activeMs.get() - appProfile.flushedActiveMs))}" +
                                                " · соединений ${appProfile.allTimeConns + (appProfile.connOk.get() - appProfile.flushedConns)}" +
                                                (appProfile.lastError?.let { if (nowMs - appProfile.lastErrorAt < 600_000L) " · последняя ошибка: ${it.take(60)}" else "" } ?: ""),
                                            color = Omni.TextFaint,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        TextButton(
                                            onClick = {
                                                com.winwasm64.vpn.AppTrafficStore.reportNow(appProfile)
                                                Toast.makeText(context, "Итоги по ${app.appName} записаны в журнал VPN", Toast.LENGTH_SHORT).show()
                                            },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) { Text("📊 Итоги сейчас → в журнал", fontSize = 11.sp) }
                                    }
                                }
                                if (!itemExpanded) {
                                    OutlinedButton(
                                        onClick = { onToggleAppInVpn(app.packageName) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text(if (isInVpn) "✓ VPN" else "+ VPN", fontSize = 12.sp, color = Color.White)
                                    }
                                }
                            }

                            // Индивидуальный выбор сервера специально для ЭТОГО приложения
                            if (itemExpanded) {
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = Omni.AccentContainer,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.clickable {
                                        onOpenServerPicker("app:${app.packageName}")
                                    }
                                ) {
                                    Text(
                                        text = "Свой сервер (${appNode.flag} ${appNode.code})",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }

                                ALL_VPN_COUNTRIES_LIST.take(10).forEach { n ->
                                    val sel = appProfile.countryCode == n.code
                                    Surface(
                                        color = if (sel) Omni.AccentContainer else Omni.SurfaceHighest,
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.clickable {
                                            onSelectAppSpecificCountry(app.packageName, n.code)
                                            Toast.makeText(
                                                context,
                                                "🛡️ Для ${app.appName} назначен персональный сервер ${n.flag} ${n.countryRu} (порт 127.0.0.1:${appProfile.localPort})",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    ) {
                                        Text(
                                            text = "${n.flag}${n.code}",
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            // Специальная кнопка для Telegram (DC1..DC5 / Видео DC2 / Файлы)
                            if (isTelegramApp) {
                                Button(
                                    onClick = {
                                        if (!isInVpn) {
                                            onToggleAppInVpn(app.packageName)
                                        }
                                        val dedicatedPort = nativeAppsManager.connectTelegramBuiltInProxy(app.packageName)
                                        Toast.makeText(
                                            context,
                                            "✈️ В Telegram передан SOCKS5-туннель 127.0.0.1:$dedicatedPort (DC1-DC5 / DC2 видео и файлы): нажмите «Включить» в окне Telegram!",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier.fillMaxWidth().height(36.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer)
                                ) {
                                    Text(
                                        text = "Подключить SOCKS5 туннель (DC1-DC5 / Видео DC2 и Файлы) в Telegram (127.0.0.1:${appProfile.localPort})",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
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
                                        containerColor = if (isInVpn) Omni.AccentContainer else Omni.SurfaceHighest
                                    )
                                ) {
                                    Text(
                                        text = if (isInVpn) {
                                            "🛡️🚀 Запустить через ${appNode.flag}${appNode.code} (:${appProfile.localPort})"
                                        } else {
                                            "⚪🚀 Запустить напрямую БЕЗ VPN"
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
                                        text = if (isInVpn) "✓ В VPN (Убрать)" else "+ Добавить в VPN",
                                        fontSize = 11.sp,
                                        color = if (isInVpn) Omni.Success else Color.White,
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
                                        colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer)
                                    ) {
                                        Text("Файл", fontSize = 11.sp, color = Color.White)
                                    }
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
            color = if (browsecEnabled) Omni.AccentContainer else Omni.SurfaceHighest,
            shape = RoundedCornerShape(28.dp),
            shadowElevation = 4.dp,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (browsecEnabled) Omni.Accent else Omni.TextDim
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 10.dp)
                .clickable { onToggleFloatingVpn() }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(text = if (browsecEnabled) "🛡️" else "○", fontSize = 13.sp)
                Text(
                    text = if (browsecEnabled) {
                        "$browsecCountry · ${vpnRoutedApps.size} · ⬇${BrowsecVpnService.formatSpeedShort(liveTraffic.rxSpeedBytesPerSec)} ⬆${BrowsecVpnService.formatSpeedShort(liveTraffic.txSpeedBytesPerSec)}"
                    } else {
                        "VPN списка выкл · $browsecCountry"
                    },
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Полноценное окно самостоятельного выбора любого из 22 серверов:
 * открывается как из приложения, так и напрямую по кнопке «🎯 Выбрать сервер» в шторке уведомлений Android!
 * Показывает раздельный входящий и исходящий трафик каждого сервера и позволяет выбрать сервер
 * для Браузера, для Приложений, для обоих сразу (Синхронно) или для конкретного приложения.
 */
@Composable
private fun ManualServerPickerDialog(
    targetMode: String,
    browserVpnCountry: String,
    appsVpnCountry: String,
    onChangeTargetMode: (String) -> Unit,
    onPickServer: (String) -> Unit,
    onRestoreServerOriginal: (String) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Omni.Surface,
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(2.dp, Omni.Accent),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Выбор Сервера VPN (22 страны)",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp
                    )
                    OutlinedButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Закрыть", fontSize = 11.sp, color = Color.White)
                    }
                }

                // Переключатель цели выбора сервера
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val modes = listOf(
                        "both" to "🔗 Оба VPN сразу (Синхронно)",
                        "browser" to "🌐 Только Браузер ($browserVpnCountry)",
                        "apps" to "📱 Только Приложения ($appsVpnCountry)"
                    )
                    modes.forEach { (mKey, mTitle) ->
                        val sel = targetMode == mKey
                        Surface(
                            color = if (sel) Omni.AccentContainer else Omni.SurfaceHighest,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (sel) Omni.Accent else Omni.Outline),
                            modifier = Modifier.clickable { onChangeTargetMode(mKey) }
                        ) {
                            Text(
                                text = mTitle,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                if (targetMode.startsWith("app:")) {
                    val pkg = targetMode.removePrefix("app:")
                    Text(
                        text = "Индивидуальный сервер для приложения: $pkg",
                        color = Omni.Success,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(ALL_VPN_COUNTRIES_LIST, key = { it.code }) { node ->
                        val brState = BrowserDirectRepeater.getServerState(node.code)
                        val apState = BrowsecLocalProxyServer.getServerState(node.code)
                        val isActiveBrowser = browserVpnCountry == node.code
                        val isActiveApps = appsVpnCountry == node.code

                        Surface(
                            color = if (isActiveBrowser || isActiveApps) Omni.SurfaceHighest else Omni.Surface,
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isActiveBrowser || isActiveApps) Omni.Accent else Omni.Outline
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPickServer(node.code) }
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${node.flag} ${node.countryRu} (${node.code}) • ${node.remoteIp}:${node.remotePort}",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = "${brState.pingMs} мс / ${apState.pingMs} мс",
                                        color = Omni.Success,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Text(
                                    text = "Браузер: ⬇${BrowsecVpnService.formatSpeedShort(brState.rxSpeedBps)} (${BrowsecVpnService.formatBytesShort(brState.rxBytes.get())}) | ⬆${BrowsecVpnService.formatSpeedShort(brState.txSpeedBps)} (${BrowsecVpnService.formatBytesShort(brState.txBytes.get())})",
                                    color = Omni.Accent,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Прилож.: ⬇${BrowsecVpnService.formatSpeedShort(apState.rxSpeedBps)} (${BrowsecVpnService.formatBytesShort(apState.rxBytes.get())}) | ⬆${BrowsecVpnService.formatSpeedShort(apState.txSpeedBps)} (${BrowsecVpnService.formatBytesShort(apState.txBytes.get())})",
                                    color = Omni.Warning,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Настройка: ${apState.currentStrategy.shortRu} (DNS: ${apState.currentDoh})",
                                    color = Omni.TextDim,
                                    fontSize = 10.sp
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Button(
                                        onClick = { onPickServer(node.code) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.weight(1f).height(32.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer)
                                    ) {
                                        Text("Подключить ${node.flag} ${node.countryRu}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }

                                    if (apState.tuningStep > 0 || brState.tuningStep > 0) {
                                        OutlinedButton(
                                            onClick = { onRestoreServerOriginal(node.code) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text("В исходное", fontSize = 10.sp, color = Omni.Warning)
                                        }
                                    }
                                }
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
        color = if (enabled) Omni.SurfaceHighest else Omni.Surface,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Omni.Outline),
        modifier = Modifier
            .size(36.dp)
            .clickable(enabled = enabled) { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = if (enabled) Color.White else Omni.TextFaint,
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
        color = if (active) activeColor.copy(alpha = 0.28f) else Omni.SurfaceHighest,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (active) activeColor else Omni.Outline
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
        color = if (selected) activeColor.copy(alpha = 0.25f) else Omni.SurfaceHighest,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) activeColor else Omni.Outline
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
                color = if (selected) Omni.Accent else Omni.TextDim,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun Context.findOmniActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Усиление громкости через WebAudio. Чужие (не same-origin, без CORS) медиа пропускаются — иначе они замолчат. */
private fun omniAudioBoostJs(gain: Double): String = """
(function(g){
  try {
    var AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return 'no-webaudio';
    var ctx = window.__omniAudioCtx || (window.__omniAudioCtx = new AC());
    if (ctx.state === 'suspended') ctx.resume();
    var n = 0, skipped = 0;
    document.querySelectorAll('video, audio').forEach(function(m){
      if (!m.__omniGain) {
        var src = m.currentSrc || m.src || '';
        var foreign = false;
        try { foreign = !!src && src.indexOf('blob:') !== 0 && src.indexOf('data:') !== 0 && new URL(src, location.href).origin !== location.origin && !m.crossOrigin; } catch (e) {}
        if (foreign) { skipped++; return; }
        try {
          var node = ctx.createMediaElementSource(m);
          var gn = ctx.createGain();
          node.connect(gn); gn.connect(ctx.destination);
          m.__omniGain = gn;
        } catch (e) { skipped++; return; }
      }
      m.__omniGain.gain.value = g; n++;
    });
    return n + '/' + skipped;
  } catch (e) { return 'err:' + e.message; }
})($gain);
"""

/**
 * v4.16.0: принудительно показать экранную клавиатуру для страницы в полноэкранном режиме.
 *
 * Зачем: в оверлее GeForce NOW (и других игр) клавиатура открывалась и сразу закрывалась.
 * До этой версии приложение вообще не трогало IME — то есть показать её повторно было нечем.
 * Теперь есть кнопка «⌨ Клавиатура»: она отдаёт фокус WebView и просит IME показать клавиатуру
 * с режимом SHOW_IMPLICIT — тот же, которым пользуется сама страница.
 */
private fun forceShowKeyboard(ctx: android.content.Context, wv: android.webkit.WebView?) {
    try {
        if (wv == null) return
        // v4.19.0: requestFocus() у WebView сбрасывал DOM-фокус на document.body — именно из-за этого
        // клавиатура открывалась, а нажатия до игры не доходили. Теперь фокус забираем только если
        // WebView его вообще не имеет.
        if (!wv.hasFocus()) wv.requestFocus()
        val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager ?: return
        imm.showSoftInput(wv, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        com.winwasm64.vpn.VpnLog.i("Браузер", "полный экран: клавиатура запрошена вручную (кнопка ⌨)")
    } catch (e: Throwable) {
        com.winwasm64.vpn.VpnLog.w("Браузер", "клавиатуру показать не удалось: ${e.message ?: e.javaClass.simpleName}")
    }
}

/**
 * v4.17.0: «зачем» для журнала соединений — по адресу запроса и его типу.
 * Нужен, чтобы в журнале было не просто «куда», но и что это за запрос:
 * страница, скрипт, картинка, медиа, API или реклама.
 */
private fun requestPurpose(request: android.webkit.WebResourceRequest?, reqUrl: String): String {
    try {
        if (request?.isForMainFrame == true) return "страница (переход)"
    } catch (_: Throwable) {
    }
    val path = reqUrl.substringBefore('?')
    return when {
        reqUrl.contains("googlevideo.com") || path.endsWith(".m3u8") || path.endsWith(".mpd") ||
            path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".ts") -> "медиа (видео/аудио)"
        path.endsWith(".js") || path.endsWith(".mjs") -> "скрипт"
        path.endsWith(".css") -> "стиль"
        path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
            path.endsWith(".webp") || path.endsWith(".svg") || path.endsWith(".gif") || path.endsWith(".ico") -> "картинка"
        path.endsWith(".woff2") || path.endsWith(".woff") || path.endsWith(".ttf") -> "шрифт"
        reqUrl.contains("/api") || reqUrl.contains("/graphql") || path.endsWith(".json") ||
            reqUrl.contains("i/v1/") || reqUrl.contains("/ajax") -> "API/данные"
        else -> "подресурс"
    }
}

/**
 * v4.18.0: «тот же сайт» — грубо, но безопасно: сравниваем два последних сегмента домена
 * (play.geforcenow.com → geforcenow.com). Запросы своего же сайта не блокируем никогда:
 * именно так AdBlock ломал API GeForce NOW.
 */
private fun isSameSite(pageHost: String?, reqHost: String): Boolean {
    if (pageHost.isNullOrBlank() || reqHost.isBlank()) return false
    fun tail(h: String): String = h.split('.').takeLast(2).joinToString(".")
    return tail(pageHost) == tail(reqHost)
}

/**
 * v4.18.0: адрес похож на API сервиса, а не на рекламу — такие не блокируем.
 * В журнале 4.17.0 под «реклама/трекер» попали gx-target-…-frontend-api.gx.nvidia.com и
 * gx-target-survey-frontend-api.gx.nvidia.com — это рабочие API GeForce NOW, а не трекеры.
 */
private fun isServiceApiHost(host: String): Boolean {
    if (host.isBlank()) return false
    val h = host.lowercase()
    if (h.contains("telemetry") || h.contains("analytics") || h.contains("otel") || h.contains("metrics")) return false
    return h.contains("-api.") || h.startsWith("api.") || h.contains("cloudmatch") ||
        h.contains("gx-target-") || h.endsWith("nvidia.com") || h.endsWith("geforcenow.com") ||
        h.endsWith("nvidiagrid.net")
}

/** v4.18.0: честная подпись в журнале — телеметрия это или реклама. */
private fun adBlockReason(host: String): String {
    val h = host.lowercase()
    return if (h.contains("telemetry") || h.contains("analytics") || h.contains("otel") ||
        h.contains("metrics") || h.contains("kaizen") || h.contains("tracker") || h.contains("stats")
    ) "телеметрия — отсечено (на работу сайта не влияет)" else "реклама/трекер — отсечено"
}

/**
 * v4.36.0: вкладка «VPN».
 *
 * Раньше всё про VPN было разбросано: тумблеры в «Приложениях», журнал отдельным экраном,
 * а понять, какой канал сейчас работает, было нельзя вообще. Здесь одно место:
 * состояние → диагностика каналов одной кнопкой → журнал.
 * Маршрутизация по конкретным приложениям осталась в «Приложениях» — там ей и место.
 */
@Composable
private fun VpnChannelsSection(
    modifier: Modifier = Modifier,
    browsecEnabled: Boolean,
    browsecCountry: String,
    onToggleBrowsec: () -> Unit,
    onCycleCountry: () -> Unit
) {
    val context = LocalContext.current
    Column(modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("VPN и каналы", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            "Когда что-то не открывается — нажмите «Диагностика каналов». Она реальными соединениями " +
                "проверит сеть, DNS (оба уровня), Browsec, Psiphon, резервные прокси и выход в интернет " +
                "и покажет, что именно отказало и что делать.",
            fontSize = 12.sp, color = Omni.TextDim
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onToggleBrowsec,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (browsecEnabled) Omni.AccentContainer else Color(0xFF2A2B30)
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text(
                    if (browsecEnabled) "Browsec VPN: ВКЛ" else "Browsec VPN: ВЫКЛ",
                    fontSize = 12.sp, color = Color.White
                )
            }
            OutlinedButton(
                onClick = onCycleCountry,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text("Сменить страну ($browsecCountry)", fontSize = 12.sp, color = Color.White)
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(context, com.winwasm64.vpn.VpnLogActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            modifier = Modifier.height(36.dp)
        ) {
            Text("📜 Журнал VPN и браузера", fontSize = 12.sp, color = Color.White)
        }
        // v4.66.0: WireGuard — свой сервер или .conf провайдера, с согласием Android и взаимоисключением с Browsec
        Spacer(Modifier.height(10.dp))
        com.winwasm64.ui.WireGuardCard(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                com.winwasm64.vpn.VpnStatusView(ctx) { pkgs ->
                    pkgs.forEach { pkg -> com.winwasm64.vpn.BrowsecVpnService.setRoutedPackage(ctx, pkg, true) }
                }.apply { compact = false }
            },
            update = { v -> v.compact = false; v.refresh() },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Маршрутизация по отдельным приложениям, серверы для каждой программы и запуск приложений " +
                "через туннель — во вкладке «Приложения».",
            fontSize = 11.sp, color = Omni.TextDim
        )
    }
}

/**
 * v4.41.0: превью раздачи в списке результатов — обложка со страницы (og:image) или заглушка.
 * Картинки кешируются в памяти ([com.winwasm64.ui.browser.CoverCache]), повторно не качаются.
 */
@Composable
private fun resultCover(posterUrl: String, kind: String = "other") {
    val placeholder: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Omni.SurfaceHighest),
            contentAlignment = Alignment.Center
        ) {
            Text(com.winwasm64.torrent.TorrentSearchAgent.kindEmoji(kind), fontSize = 20.sp)
        }
    }
    if (posterUrl.isBlank()) {
        placeholder()
        return
    }
    var bitmap by remember(posterUrl) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(posterUrl) {
        if (bitmap == null) {
            bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.winwasm64.ui.browser.CoverCache.load(posterUrl)
            }
        }
    }
    val bmp = bitmap
    if (bmp == null) {
        placeholder()
    } else {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
        )
    }
}

/* ============================================================================
 * v4.47.0 / v4.49.0: «Файлы» — прямые ссылки, «Фото» — галерея, «Видео» — ролики.
 * v4.49.0: поиск запоминается между вкладками, категория уходит в сам запрос,
 * есть страницы («показать ещё»), сортировка и единая папка загрузок.
 * ============================================================================ */

/** Скачать прямую ссылку в папку приложения и показать её во вкладке «Загрузки». */
private fun enqueueDirectDownload(context: android.content.Context, url: String, fileName: String) {
    val id = com.winwasm64.torrent.AppDownloads.enqueue(context, url, fileName)
    if (id == null) {
        Toast.makeText(context, "Не удалось начать скачивание", Toast.LENGTH_LONG).show()
    } else {
        Toast.makeText(
            context,
            "⬇ $fileName → Загрузки/${com.winwasm64.torrent.AppDownloads.SUBDIR}",
            Toast.LENGTH_LONG
        ).show()
    }
}


/**
 * v4.54.0: ручная «глубина поиска» — сколько запросов/страниц отправлять.
 * 1 — быстро (только точное слово), до 8 — долго, но с синонимами, ассоциациями и листанием.
 */
/**
 * v4.58.0: «нету индикации — работает агент, спит или размышляет, и нет кнопки выключить/заменить».
 *
 * Строка обновляется каждые полсекунды и показывает настоящее состояние агента:
 *  💤 спит · ждёт запроса → 🤔 размышляет · придумываю варианты → ⏳ спрашивает модель →
 *  🟢 работал · вариантов 3 / оставил 18 из 30 → 🔴 ошибка; ⛔ выключен, если его выключили.
 * Рядом: «⏻ Выключить агента», «🔁 Заменить (умный/быстрый)», «✓ проверить».
 */
@Composable
private fun AiAgentRow() {
    val context = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var enabled by remember { mutableStateOf(com.winwasm64.torrent.AiCurator.isEnabled(context)) }
    var modelLabel by remember { mutableStateOf(com.winwasm64.torrent.AiCurator.modelLabel(context)) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            now = System.currentTimeMillis()
        }
    }
    val line = com.winwasm64.torrent.AiCurator.liveLine(context, now)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "🤖 Агент: $line",
            color = when {
                !enabled -> Omni.TextDim
                line.startsWith("🔴") -> Color(0xFFFF6B6B)
                line.startsWith("🟢") -> Omni.Success
                else -> Omni.Warning
            },
            fontSize = 9.sp,
            maxLines = 2
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (enabled) "⏻ Выключить" else "▶ Включить",
                color = Omni.Accent, fontSize = 9.sp,
                modifier = Modifier.clickable {
                    enabled = !enabled
                    com.winwasm64.torrent.AiCurator.setEnabled(context, enabled)
                    now = System.currentTimeMillis()
                }
            )
            Text(
                "🔁 Заменить ($modelLabel)",
                color = Omni.Accent, fontSize = 9.sp,
                modifier = Modifier.clickable {
                    modelLabel = com.winwasm64.torrent.AiCurator.cycleModel(context)
                    now = System.currentTimeMillis()
                }
            )
            Text(
                if (busy) "⏳ проверяю…" else "✓ проверить",
                color = Omni.Accent, fontSize = 9.sp,
                modifier = Modifier.clickable {
                    if (!busy) {
                        busy = true
                        Thread({
                            com.winwasm64.torrent.AiCurator.selfTest(context)
                            busy = false
                        }, "AiPing").also { it.isDaemon = true; it.start() }
                    }
                }
            )
        }
        Text(
            "последний ответ: ${com.winwasm64.torrent.AiCurator.status(context)}",
            color = Omni.TextDim, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SearchDepthRow(scope: String, onDepth: (Int) -> Unit) {
    val context = LocalContext.current
    var depth by remember {
        mutableStateOf(com.winwasm64.torrent.SearchSettings.depth(context, scope))
    }
    Surface(
        color = Omni.SurfaceHighest,
        shape = RoundedCornerShape(9.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Глубина поиска: $depth", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text(
                    when {
                        depth <= 1 -> "1 запрос · быстро, но меньше ссылок"
                        depth <= 3 -> "$depth запроса · с синонимами и ассоциациями"
                        else -> "$depth запросов · долго, но максимально полно"
                    },
                    color = Omni.TextDim, fontSize = 9.sp
                )
                // v4.58.0: живая индикация агента + кнопки «Выключить» и «Заменить»
                AiAgentRow()
            }
            Text("−", color = Color.White, fontSize = 18.sp, modifier = Modifier.clickable {
                val next = (depth - 1).coerceAtLeast(com.winwasm64.torrent.SearchSettings.MIN)
                depth = next
                com.winwasm64.torrent.SearchSettings.setDepth(context, next, scope)
                onDepth(next)
            })
            Text("+", color = Color.White, fontSize = 16.sp, modifier = Modifier.clickable {
                val next = (depth + 1).coerceAtMost(com.winwasm64.torrent.SearchSettings.MAX)
                depth = next
                com.winwasm64.torrent.SearchSettings.setDepth(context, next, scope)
                onDepth(next)
            })
        }
    }
}

@Composable
private fun DirectFilesSection(
    modifier: Modifier,
    initialQuery: String,
    // v4.57.0: обычные ссылки (Seasonvar и подобные) открываем ВНУТРИ приложения, а не наружу
    onOpenUrlInBrowser: (String) -> Unit = {}
) {
    val context = LocalContext.current
    // v4.52.0: состояние живёт в SearchCache — переключение вкладок ничего не сбрасывает
    var query by remember { mutableStateOf(com.winwasm64.torrent.SearchCache.directQuery.ifBlank { initialQuery }) }
    var buckets by remember { mutableStateOf<com.winwasm64.torrent.DirectSearch.Buckets?>(null) }
    var busy by remember { mutableStateOf(false) }
    var kindFilter by remember { mutableStateOf(com.winwasm64.torrent.SearchCache.directKind) }
    var formatFilter by remember { mutableStateOf(com.winwasm64.torrent.SearchCache.directFormat) }
    var sortMode by remember { mutableStateOf(com.winwasm64.torrent.SearchCache.directSort) }
    var page by remember { mutableStateOf(com.winwasm64.torrent.SearchCache.directPage) }
    var searchDepth by remember { mutableStateOf(com.winwasm64.torrent.SearchSettings.depth(context, "files")) }
    val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    /**
     * Один поиск — сразу все категории параллельно. Категория больше НЕ дописывается в запрос
     * («чип игра» больше не появится в названии) и при переключении фильтра повторного поиска нет.
     */
    fun run(nextSort: Int, nextPage: Int, append: Boolean) {
        if (query.isBlank()) {
            Toast.makeText(context, "Введите название", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        com.winwasm64.torrent.SearchCache.directQuery = query
        com.winwasm64.torrent.SearchCache.directSort = nextSort
        com.winwasm64.torrent.SearchCache.directPage = nextPage
        sortMode = nextSort
        page = nextPage
        val prev = if (append) buckets else null
        if (prev == null) buckets = com.winwasm64.torrent.DirectSearch.Buckets(emptyList(), emptyMap(), emptyMap())
        Thread({
            // v4.55.0: асинхронно — что нашлось, то сразу и показываем
            // v4.66.0: агент на моделях ИИ-чата; считает в фоне, поиск берёт только готовые варианты
            com.winwasm64.torrent.AiCurator.prefetch(context, query, 2)
            val aiExtra = com.winwasm64.torrent.AiCurator.cachedVariants(query)
            val res = try {
                com.winwasm64.torrent.DirectSearch.searchAll(
                    query, nextSort, nextPage, depth = searchDepth, aiVariants = aiExtra
                ) { chip, files ->
                    handler.post {
                        val cur = buckets ?: com.winwasm64.torrent.DirectSearch.Buckets(emptyList(), emptyMap(), emptyMap())
                        val mergedChip = if (append) {
                            ((cur.byChip[chip] ?: emptyList()) + files).distinctBy { it.downloadUrl }
                        } else files
                        val newChips = cur.byChip.toMutableMap().apply { put(chip, mergedChip) }
                        buckets = com.winwasm64.torrent.DirectSearch.Buckets(
                            all = newChips.values.flatten().distinctBy { it.downloadUrl },
                            byChip = newChips,
                            more = cur.more
                        )
                    }
                }
            } catch (_: Throwable) {
                com.winwasm64.torrent.DirectSearch.Buckets(emptyList(), emptyMap(), emptyMap())
            }
            // v4.60.0: склейку списков делаем ЗДЕСЬ, в фоновом потоке, — в главный отдаём готовое.
            // Раньше flatten+distinct считались на главном потоке, и на большой выдаче система
            // показывала «OmniBrowser & VPN Pro не отвечает».
            val finalBuckets = if (prev == null) res else {
                val merged = mutableMapOf<String, List<com.winwasm64.torrent.DirectFile>>()
                val chips = (prev.byChip.keys + res.byChip.keys).distinct()
                for (chip in chips) {
                    merged[chip] = ((prev.byChip[chip] ?: emptyList()) + (res.byChip[chip] ?: emptyList()))
                        .distinctBy { it.downloadUrl }
                }
                com.winwasm64.torrent.DirectSearch.Buckets(
                    all = merged.values.flatten().distinctBy { it.downloadUrl },
                    byChip = merged,
                    more = res.more
                )
            }
            handler.post {
                buckets = finalBuckets
                busy = false
            }
        }, "DirectAll").also { it.isDaemon = true; it.start() }
    }

    val kindChips = com.winwasm64.torrent.DirectSearch.CHIPS
    val sortChips = listOf("По релевантности", "Новые", "Старые", "Тяжёлые", "Лёгкие")
    val current = buckets
    val byKind = when {
        current == null -> emptyList()
        kindFilter == "Все" -> current.all
        else -> current.byChip[kindFilter] ?: emptyList()
    }
    val formats = byKind.map { it.format }.distinct().sorted()
    val visible = if (formatFilter.isBlank()) byKind else byKind.filter { it.format == formatFilter }
    val filesListState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(filesListState, busy, kindFilter, visible.size) {
        androidx.compose.runtime.snapshotFlow {
            filesListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        }.collect { lastIndex ->
            if (!busy && visible.isNotEmpty() && current != null &&
                current.hasMore(kindFilter) && lastIndex >= visible.size - 2
            ) {
                run(sortMode, page + 1, append = true)
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth().background(Omni.Bg).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(color = Omni.Surface, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Omni.Outline)) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // v4.57.0: «удали лишний текст сверху, а то листать места мало» — вместо абзаца одна строка
                Text("📦 Файлы — прямые ссылки", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CompactDarkTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            com.winwasm64.torrent.SearchCache.directQuery = it
                        },
                        placeholder = "Название: черепашки ниндзя, манга, doom…",
                        modifier = Modifier.weight(1f),
                        onGo = { run(sortMode, 0, false) }
                    )
                    Button(
                        onClick = { run(sortMode, 0, false) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer)
                    ) {
                        Text(if (busy) "⏳..." else "🔍 Найти", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
                SearchDepthRow("files") { searchDepth = it }
            }
        }

        // категории с количеством найденного — переключение мгновенное
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val allChip = "Все"
            val allCount = current?.all?.size ?: 0
            Surface(
                color = if (kindFilter == allChip) Omni.AccentContainer else Omni.SurfaceHighest,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.clickable {
                    kindFilter = allChip
                    formatFilter = ""
                    com.winwasm64.torrent.SearchCache.directKind = allChip
                    com.winwasm64.torrent.SearchCache.directFormat = ""
                }
            ) {
                Text(
                    "Все" + if (allCount > 0) " $allCount" + (if (current?.hasMore(allChip) == true) "+" else "") else "",
                    color = Color.White, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
            kindChips.forEach { chip ->
                val count = current?.count(chip) ?: 0
                val more = current?.hasMore(chip) == true
                Surface(
                    color = if (kindFilter == chip) Omni.AccentContainer else Omni.SurfaceHighest,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickable {
                        // никакого нового поиска — только фильтрация уже собранного
                        kindFilter = chip
                        formatFilter = ""
                        com.winwasm64.torrent.SearchCache.directKind = chip
                        com.winwasm64.torrent.SearchCache.directFormat = ""
                    }
                ) {
                    Text(
                        chip + if (current != null) " $count" + (if (more) "+" else "") else "",
                        color = if (kindFilter != chip && count == 0) Omni.TextDim else Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            sortChips.forEachIndexed { idx, label ->
                Surface(
                    color = if (sortMode == idx) Omni.AccentContainer else Omni.SurfaceHighest,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickable {
                        // сортировка уже собранного — тоже без похода в сеть
                        sortMode = idx
                        com.winwasm64.torrent.SearchCache.directSort = idx
                        buckets = buckets?.let { b ->
                            com.winwasm64.torrent.DirectSearch.Buckets(
                                all = sortFiles(b.all, idx),
                                byChip = b.byChip.mapValues { sortFiles(it.value, idx) },
                                more = b.more
                            )
                        }
                    }
                ) {
                    Text(label, color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                }
            }
        }
        if (formats.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("").plus(formats).forEach { fmt ->
                    val count = if (fmt.isBlank()) byKind.size else byKind.count { it.format == fmt }
                    Surface(
                        color = if (formatFilter == fmt) Omni.AccentContainer else Omni.SurfaceHighest,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable {
                            formatFilter = fmt
                            com.winwasm64.torrent.SearchCache.directFormat = fmt
                        }
                    ) {
                        Text(
                            (if (fmt.isBlank()) "Любой формат" else "$fmt") + " $count",
                            color = Color.White, fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }

        val biggestChip = current?.byChip?.maxByOrNull { it.value.size }
        Text(
            when {
                busy && current == null -> "Ищу сразу во всех категориях…"
                busy -> "Догружаю страницу ${page + 1}…"
                current == null -> "Введите название и нажмите «Найти»"
                visible.isEmpty() && formatFilter.isNotBlank() -> "Под формат $formatFilter в «$kindFilter» ничего нет"
                visible.isEmpty() && kindFilter != "Все" ->
                    "В категории «$kindFilter» пусто" +
                        (biggestChip?.let { " · больше всего в «${it.key}» (${it.value.size})" } ?: "")
                visible.isEmpty() -> "По «${query.trim()}» ничего не нашлось — попробуйте другое слово"
                else -> "Показано ${visible.size} · $kindFilter" +
                    (if (current.hasMore(kindFilter)) " · есть ещё" else "")
            },
            color = Omni.Warning, fontWeight = FontWeight.Bold, fontSize = 12.sp
        )
        if (current != null && visible.isEmpty() && kindFilter != "Все") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = {
                        kindFilter = "Все"
                        formatFilter = ""
                        com.winwasm64.torrent.SearchCache.directKind = "Все"
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer)
                ) { Text("Показать все (${current.all.size})", fontSize = 11.sp, color = Color.White) }
                biggestChip?.let { best ->
                    Button(
                        onClick = {
                            kindFilter = best.key
                            com.winwasm64.torrent.SearchCache.directKind = best.key
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Omni.SurfaceHighest)
                    ) { Text("${best.key} (${best.value.size})", fontSize = 11.sp, color = Color.White) }
                }
            }
        }

        LazyColumn(
            state = filesListState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(visible, key = { it.downloadUrl + "|" + it.fileName }) { f ->
                Surface(color = Omni.Surface, shape = RoundedCornerShape(10.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Omni.Outline)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        resultCover(f.poster, f.kind)
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(f.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2)
                            Text(
                                "${f.format} · ${f.sizeLabel} · ${f.source}" +
                                    (if (f.year > 0) " · ${f.year}" else "") +
                                    " · ${com.winwasm64.torrent.TorrentSearchAgent.kindEmoji(f.kind)}",
                                color = Omni.Accent, fontSize = 11.sp
                            )
                            Text(f.fileName, color = Omni.TextDim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // v4.62.0: «не куча воды, а описание и для чего подходит» — у моделей это главное
                            if (f.description.isNotBlank() && f.kind == "model") {
                                Text(
                                    f.description,
                                    color = Omni.TextDim,
                                    fontSize = 10.sp,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 3.dp)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                // v4.57.0: у обычной ссылки (сериал/страница) качать нечего
                                if (!f.linkOnly) {
                                    Button(
                                        onClick = { enqueueDirectDownload(context, f.downloadUrl, f.fileName) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                        modifier = Modifier.height(34.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer)
                                    ) {
                                        Text("⬇ Скачать", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                                Button(
                                    onClick = {
                                        // v4.57.0: страница открывается в этой же вкладке приложения
                                        try {
                                            onOpenUrlInBrowser(f.pageUrl)
                                        } catch (_: Throwable) {
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    modifier = Modifier.height(34.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Omni.SurfaceHighest)
                                ) {
                                    Text("🌐 Открыть", fontSize = 11.sp, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
            if (visible.isNotEmpty() && current?.hasMore(kindFilter) == true) {
                item {
                    Button(
                        onClick = { run(sortMode, page + 1, append = true) },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Omni.SurfaceHighest)
                    ) {
                        Text(if (busy) "Загружаю…" else "⬇ Показать ещё (страница ${page + 2})", fontSize = 12.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

/** Локальная сортировка уже найденного — без обращения к сети. */
private fun sortFiles(list: List<com.winwasm64.torrent.DirectFile>, mode: Int): List<com.winwasm64.torrent.DirectFile> {
    fun size(f: com.winwasm64.torrent.DirectFile): Long {
        val num = Regex("""([\d.,]+)""").find(f.sizeLabel)?.groupValues?.get(1)
            ?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        val mult = when {
            f.sizeLabel.contains("ГБ", true) || f.sizeLabel.contains("GB", true) -> 1024.0 * 1024 * 1024
            f.sizeLabel.contains("МБ", true) || f.sizeLabel.contains("MB", true) -> 1024.0 * 1024
            f.sizeLabel.contains("КБ", true) || f.sizeLabel.contains("KB", true) -> 1024.0
            else -> 1.0
        }
        return (num * mult).toLong()
    }
    return when (mode) {
        1 -> list.sortedByDescending { it.year }
        2 -> list.sortedBy { it.year }
        3 -> list.sortedByDescending { size(it) }
        4 -> list.sortedBy { size(it) }
        else -> list
    }
}

@Composable
private fun PhotoSection(modifier: Modifier, initialQuery: String) {
    val context = LocalContext.current
    var query by remember { mutableStateOf(com.winwasm64.torrent.SearchCache.photoQuery.ifBlank { initialQuery }) }
    var filter by remember { mutableStateOf(com.winwasm64.torrent.SearchCache.photoFilter) }
    var images by remember { mutableStateOf<List<com.winwasm64.torrent.GalleryImage>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(com.winwasm64.torrent.SearchCache.photoPage) }
    // страницы кончились — дальше не дёргаем сеть
    var noMore by remember { mutableStateOf(false) }
    // v4.54.0: глубина = сколько страниц галереи забирать за один поиск (1…8)
    var galleryDepth by remember { mutableStateOf(com.winwasm64.torrent.SearchSettings.depth(context, "photo")) }
    val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val filters = listOf("Фото", "Обложки игр", "Постеры фильмов", "Обложки книг", "Кадры из видео", "Альбомы музыки")

    fun runGallery(q: String, f: String, nextPage: Int, append: Boolean) {
        if (q.isBlank()) {
            Toast.makeText(context, "Введите название", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        if (!append) images = emptyList()
        com.winwasm64.torrent.SearchCache.photoQuery = q
        com.winwasm64.torrent.SearchCache.photoFilter = f
        com.winwasm64.torrent.SearchCache.photoPage = nextPage
        page = nextPage
        Thread({
            // забираем столько страниц, сколько задал человек (глубина поиска)
            val collected = mutableListOf<com.winwasm64.torrent.GalleryImage>()
            for (i in 0 until galleryDepth) {
                val pageRes = try {
                    com.winwasm64.torrent.ImageSearch.search(q, f, nextPage + i)
                } catch (_: Throwable) {
                    emptyList()
                }
                if (pageRes.isEmpty()) break
                collected.addAll(pageRes)
            }
            val res = collected.distinctBy { it.url }
            handler.post {
                if (append) {
                    val before = images.size
                    images = (images + res).distinctBy { it.url }
                    if (images.size == before) noMore = true
                } else {
                    images = res
                    noMore = false
                }
                busy = false
            }
        }, "Gallery").also { it.isDaemon = true; it.start() }
    }

    // v4.51.0: галерея тоже докручивается бесконечно
    val photoListState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(photoListState, busy, images.size) {
        androidx.compose.runtime.snapshotFlow {
            photoListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        }.collect { lastIndex ->
            if (!busy && !noMore && images.isNotEmpty() && lastIndex >= images.size / 2 - 2) {
                runGallery(query, filter, page + 1, true)
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth().background(Omni.Bg).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(color = Omni.Surface, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Omni.Outline)) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🖼 Фото и обложки", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                SearchDepthRow("photo") { galleryDepth = it }
                CompactDarkTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        com.winwasm64.torrent.SearchCache.photoQuery = it
                    },
                    placeholder = "Название: черепашки ниндзя…",
                    modifier = Modifier.fillMaxWidth(),
                    onGo = { runGallery(query, filter, 0, false) }
                )
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    filters.forEach { f ->
                        Surface(
                            color = if (filter == f) Omni.AccentContainer else Omni.SurfaceHighest,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.clickable {
                                filter = f
                                com.winwasm64.torrent.SearchCache.photoFilter = f
                                runGallery(query, f, 0, false)
                            }
                        ) {
                            Text(f, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { runGallery(query, filter, 0, false) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer)
                    ) {
                        Text(if (busy) "⏳…" else "🖼 Найти «$filter»", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Button(
                        onClick = {
                            if (query.isBlank()) {
                                Toast.makeText(context, "Введите название", Toast.LENGTH_SHORT).show()
                            } else {
                                context.startActivity(
                                    Intent(context, com.winwasm64.torrent.ImageSearchActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        .putExtra("query", query)
                                        .putExtra("filter", filter)
                                )
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Omni.SurfaceHighest)
                    ) {
                        Text("🖱 Веб-поиск", fontSize = 11.sp, color = Color.White)
                    }
                }
            }
        }

        Text(
            when {
                busy -> "Ищу картинки… (страница ${page + 1})"
                images.isEmpty() -> "Введите название и нажмите «Найти»"
                else -> "Картинок: ${images.size} · $filter"
            },
            color = Omni.Warning, fontWeight = FontWeight.Bold, fontSize = 12.sp
        )

        LazyColumn(
            state = photoListState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(images.chunked(2)) { pair ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { img ->
                        Column(modifier = Modifier.weight(1f)) {
                            var bmp by remember(img.url) { mutableStateOf<android.graphics.Bitmap?>(null) }
                            LaunchedEffect(img.url) {
                                if (bmp == null) {
                                    bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        // v4.56.0: с referer — Яндекс отдаёт исходник только «своим»
                                        com.winwasm64.ui.browser.CoverCache.load(
                                            img.url,
                                            referer = img.pageUrl.ifBlank { null }
                                        ) ?: com.winwasm64.ui.browser.CoverCache.load(img.url)
                                    }
                                }
                            }
                            val bitmap = bmp
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(150.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Omni.SurfaceHighest)
                                    .clickable {
                                        context.startActivity(
                                            Intent(context, com.winwasm64.torrent.ImagePreviewActivity::class.java)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                .putExtra("url", img.url)
                                                .putExtra("title", img.title)
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Text("🖼", fontSize = 24.sp)
                                }
                            }
                            Text(img.title.ifBlank { img.source }, color = Color.White, fontSize = 10.sp, maxLines = 2)
                            // v4.50.0: действия подписаны словами, а не мелкими эмодзи —
                            // раньше было непонятно, что нажимать и что именно произойдёт
                            val photoTitle = if (img.title.isBlank()) query else img.title.take(48)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf(
                                    "⬇ Скачать" to {
                                        enqueueDirectDownload(
                                            context,
                                            img.url,
                                            img.url.substringAfterLast('/').substringBefore('?').ifBlank { "image.jpg" }
                                        )
                                    },
                                    "🔎 Похожие" to {
                                        // ищем по самой картинке: берём название этой картинки,
                                        // а не общий запрос вкладки
                                        query = photoTitle
                                        com.winwasm64.torrent.SearchCache.photoQuery = photoTitle
                                        runGallery(photoTitle, "Фото", 0, false)
                                    },
                                    "🎬 Видео" to {
                                        // v4.76.0: старый экран поиска удалён — уводим на вкладку «Видео»
                                        com.winwasm64.torrent.SearchCache.videoQuery = photoTitle
                                        currentSection = 6
                                    }
                                ).forEach { (label, action) ->
                                    Button(
                                        onClick = action,
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                        modifier = Modifier.height(30.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Omni.SurfaceHighest)
                                    ) {
                                        Text(label, fontSize = 9.sp, color = Color.White, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                    if (pair.size == 1) Box(modifier = Modifier.weight(1f))
                }
            }
            if (images.isNotEmpty()) {
                item {
                    Button(
                        onClick = { runGallery(query, filter, page + 1, true) },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Omni.SurfaceHighest)
                    ) {
                        Text(if (busy) "Загружаю…" else "⬇ Показать ещё (страница ${page + 2})", fontSize = 12.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

/**
 * v4.50.0: «🎬 Видео» — как «Файлы»: список карточек с тремя действиями, плюс ПОДПИСКИ и поиск
 * внутри канала (Айвори, LightFox Manga и любые свои).
 */
/**
 * v4.71.0: «Видео» — самостоятельная вкладка с обычным видом видеоприложения:
 * обложка 16:9, длительность, автор, просмотры, дата, «Показать ещё».
 *
 * Здесь НЕТ ИИ: агент вызывается только из ИИ-ассистента, когда пользователь сам пишет запрос.
 */
@Composable
private fun VideoHomeSection(
    modifier: Modifier,
    initialQuery: String,
    onOpenUrl: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(com.winwasm64.torrent.SearchCache.videoQuery.ifBlank { initialQuery }) }
    var submitted by remember { mutableStateOf(com.winwasm64.torrent.SearchCache.videoQuery.ifBlank { initialQuery }) }
    var source by remember { mutableStateOf(com.winwasm64.torrent.VideoFeed.SRC_YOUTUBE) }
    var category by remember { mutableStateOf("Все") }
    var sort by remember { mutableStateOf(0) }
    var durFilter by remember { mutableStateOf(0) }          // 0 любая · 1 до 4 мин · 2 4–20 · 3 от 20
    var nameFilter by remember { mutableStateOf("") }         // фильтр по названию/каналу, как в «Файлах»
    var showNameFilter by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<com.winwasm64.torrent.VideoFeed.Clip>>(emptyList()) }
    var cont by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var elapsed by remember { mutableIntStateOf(0) }
    var scrapeUrl by remember { mutableStateOf<String?>(null) }
    var scrapeSource by remember { mutableStateOf("") }
    var scrapeRound by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf("") }

    fun humanError(t: Throwable): String = (t.message ?: "сеть").take(100)

    /** v4.76.0: карточка открывает ВСТРОЕННЫЙ плеер, а не сайт YouTube. */
    fun openClip(c: com.winwasm64.torrent.VideoFeed.Clip) {
        if (c.kind != com.winwasm64.torrent.VideoFeed.CLIP_VIDEO) {
            // плейлист/канал — открываем внутри приложения на отдельной вкладке браузера
            onOpenUrl(c.url)
            return
        }
        playing = "Открываю «${c.title.take(40)}»…"
        scope.launch {
            val play = withContext(Dispatchers.IO) {
                runCatching { com.winwasm64.torrent.VideoPlay.resolve(c.url) }.getOrNull()
            }
            playing = ""
            if (play == null) {
                val why = com.winwasm64.torrent.VideoPlay.lastError.ifBlank { "не удалось получить поток" }
                note = "Плеер: $why — открываю страницу внутри приложения"
                onOpenUrl(c.url)
                return@launch
            }
            if (com.winwasm64.torrent.VideoPlay.lastError.isNotBlank()) note = com.winwasm64.torrent.VideoPlay.lastError
            runCatching {
                context.startActivity(
                    Intent(context, com.winwasm64.ui.NativeVideoPlayerActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("url", play.url)
                        .putExtra("title", c.title)
                        .putExtra("thumbnail", c.thumb)
                        .putExtra("referer", play.referer)
                )
            }.onFailure { note = "Не смог открыть плеер: " + (it.message ?: "") }
        }
    }

    fun startSearch(text: String) {
        val q = text.trim()
        if (q.isBlank()) {
            note = "Введите название или вставьте ссылку"
            return
        }
        submitted = q
        com.winwasm64.torrent.SearchCache.videoQuery = q
        items = emptyList()
        scrapeRound = 0
        tick++
    }

    /** Поиск. VK/RuTube — встроенным браузером (там без входа иначе никак), YouTube — innertube. */
    LaunchedEffect(tick, source) {
        if (submitted.isBlank()) return@LaunchedEffect
        scrapeUrl = null
        elapsed = 0
        com.winwasm64.torrent.VideoFeed.cached(source, submitted)?.let {
            items = it.clips
            cont = it.continuation
            note = it.note.orEmpty()
            loading = false
            return@LaunchedEffect
        }
        loading = true
        note = ""
        if (source != com.winwasm64.torrent.VideoFeed.SRC_YOUTUBE) {
            scrapeSource = source
            scrapeRound = 0
            scrapeUrl = com.winwasm64.torrent.VideoFeed.scrapeUrl(source, submitted)
            return@LaunchedEffect
        }
        val page = withContext(Dispatchers.IO) {
            runCatching { com.winwasm64.torrent.VideoFeed.search(source, submitted, sort, null) }
                .getOrElse { com.winwasm64.torrent.VideoFeed.Page(note = "Не удалось: " + humanError(it)) }
        }
        items = page.clips
        cont = page.continuation
        note = page.note.orEmpty()
        loading = false
        if (page.clips.isEmpty()) {
            scrapeSource = source
            scrapeRound = 0
            scrapeUrl = com.winwasm64.torrent.VideoFeed.scrapeUrl(source, submitted)
        } else {
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(loading) {
        if (!loading) return@LaunchedEffect
        elapsed = 0
        while (loading && elapsed < 600) {
            delay(1000)
            elapsed++
        }
    }

    // встроенный браузер: терпеливо ждёт отрисовку, прокручивает выдачу и копит до 100 карточек
    val currentScrape = scrapeUrl
    if (currentScrape != null) {
        WebScraper(
            url = currentScrape,
            source = scrapeSource,
            query = submitted,
            round = scrapeRound,
            onProgress = { list ->
                if (list.size > items.size) {
                    items = list
                    loading = false
                }
            },
            onStatus = { msg -> note = msg },
            onFinished = { list ->
                if (list.isNotEmpty()) {
                    items = list
                    cont = null
                    note = if (list.size >= 100) "Показано ${list.size} — нажмите «Ещё», чтобы добрать" else ""
                } else if (note.isBlank()) {
                    note = "На $scrapeSource ничего не нашлось — проверьте VPN (щит) или уточните запрос"
                }
                loading = false
                scrapeUrl = null
            }
        )
    }

    val filtered = remember(items, category, sort, durFilter, nameFilter) {
        var base = when (category) {
            "🎬 Видео" -> items.filter { it.kind == com.winwasm64.torrent.VideoFeed.CLIP_VIDEO && !it.live }
            "🎞 Плейлисты" -> items.filter { it.kind == com.winwasm64.torrent.VideoFeed.CLIP_PLAYLIST }
            "📺 Каналы" -> items.filter { it.kind == com.winwasm64.torrent.VideoFeed.CLIP_CHANNEL }
            "🔴 Трансляции" -> items.filter { it.live }
            else -> items
        }
        base = when (durFilter) {
            1 -> base.filter { it.lengthSec in 1..240 }
            2 -> base.filter { it.lengthSec in 241..1200 }
            3 -> base.filter { it.lengthSec > 1200 }
            else -> base
        }
        val f = nameFilter.trim().lowercase()
        if (f.isNotBlank()) base = base.filter { it.title.lowercase().contains(f) || it.author.lowercase().contains(f) }
        when (sort) {
            1 -> base.sortedByDescending { it.days }                 // новые
            2 -> base.sortedByDescending { it.views }                // популярные
            3 -> base.sortedBy { it.days }                           // старые
            4 -> base.sortedWith(compareBy({ it.lengthSec == 0 }, { it.lengthSec }))          // длительность ↑
            5 -> base.sortedWith(compareByDescending<com.winwasm64.torrent.VideoFeed.Clip> { it.lengthSec })  // длительность ↓
            else -> base
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ---------- верхняя строка ----------
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(26.dp).clip(CircleShape).background(Omni.AccentContainer),
                contentAlignment = Alignment.Center
            ) { Text("▶", color = Color.White, fontSize = 12.sp) }
            Text(
                "Видео", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                modifier = Modifier.padding(start = 8.dp).weight(1f)
            )
            Text(
                "📋", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 8.dp).clickable {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
                    if (text.startsWith("http", true)) {
                        query = text
                        startSearch(text)
                    } else {
                        note = "В буфере нет ссылки — вставьте адрес ролика вручную"
                    }
                }
            )
            Text(
                "⟳", fontSize = 16.sp, color = Color.White,
                modifier = Modifier.padding(horizontal = 6.dp).clickable { tick++ }
            )
        }

        // ---------- поиск ----------
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                CompactDarkTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Поиск видео — название или ссылка",
                    modifier = Modifier.fillMaxWidth(),
                    onGo = { startSearch(query) }
                )
            }
            Button(
                onClick = { startSearch(query) },
                modifier = Modifier.padding(start = 6.dp).height(42.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer)
            ) { Text("Найти", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1) }
        }

        // ---------- площадка ----------
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                com.winwasm64.torrent.VideoFeed.SRC_YOUTUBE,
                com.winwasm64.torrent.VideoFeed.SRC_RUTUBE,
                com.winwasm64.torrent.VideoFeed.SRC_VK
            ).forEach { name ->
                val active = source == name
                Surface(
                    color = if (active) Omni.AccentContainer else Omni.SurfaceHighest,
                    shape = RoundedCornerShape(9.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (active) Omni.Accent else Omni.Outline),
                    modifier = Modifier.clickable { if (!active) source = name }
                ) {
                    Text(
                        when (name) {
                            com.winwasm64.torrent.VideoFeed.SRC_RUTUBE -> "R RuTube"
                            com.winwasm64.torrent.VideoFeed.SRC_VK -> "VK Видео"
                            else -> "▶ YouTube"
                        },
                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // ---------- категории со счётчиками ----------
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "Все" to items.size,
                "🎬 Видео" to items.count { it.kind == com.winwasm64.torrent.VideoFeed.CLIP_VIDEO && !it.live },
                "🎞 Плейлисты" to items.count { it.kind == com.winwasm64.torrent.VideoFeed.CLIP_PLAYLIST },
                "📺 Каналы" to items.count { it.kind == com.winwasm64.torrent.VideoFeed.CLIP_CHANNEL },
                "🔴 Трансляции" to items.count { it.live }
            ).forEach { (name, count) ->
                val active = category == name
                Surface(
                    color = if (active) Omni.AccentContainer else Omni.SurfaceHighest,
                    shape = RoundedCornerShape(9.dp),
                    modifier = Modifier.clickable { category = name }
                ) {
                    Text(
                        if (count > 0) "$name $count" else name,
                        color = if (active) Color.White else Omni.TextDim, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                    )
                }
            }
        }

        // ---------- сортировка (включая длительность от меньшей к большей) ----------
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "По релевантности", "Новые", "Популярные", "Старые", "Длительность ↑", "Длительность ↓"
            ).forEachIndexed { idx, label ->
                val active = sort == idx
                Surface(
                    color = if (active) Omni.SurfaceHighest else Color.Transparent,
                    shape = RoundedCornerShape(9.dp),
                    modifier = Modifier.clickable { sort = idx }
                ) {
                    Text(
                        label, color = if (active) Color.White else Omni.TextFaint, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                    )
                }
            }
        }

        // ---------- фильтр по длительности и по названию ----------
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("Любая длина", "до 4 мин", "4–20 мин", "от 20 мин").forEachIndexed { idx, label ->
                val active = durFilter == idx
                Surface(
                    color = if (active) Omni.AccentContainer else Omni.SurfaceHighest,
                    shape = RoundedCornerShape(9.dp),
                    modifier = Modifier.clickable { durFilter = idx }
                ) {
                    Text(
                        label, color = if (active) Color.White else Omni.TextDim, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                    )
                }
            }
            Surface(
                color = if (showNameFilter) Omni.AccentContainer else Omni.SurfaceHighest,
                shape = RoundedCornerShape(9.dp),
                modifier = Modifier.clickable { showNameFilter = !showNameFilter }
            ) {
                Text(
                    if (nameFilter.isBlank()) "🔎 фильтр" else "🔎 $nameFilter",
                    color = if (showNameFilter) Color.White else Omni.TextDim, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), maxLines = 1
                )
            }
        }
        if (showNameFilter) {
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)) {
                CompactDarkTextField(
                    value = nameFilter,
                    onValueChange = { nameFilter = it },
                    placeholder = "Оставить только: слово в названии или канале",
                    modifier = Modifier.fillMaxWidth(),
                    onGo = { showNameFilter = false }
                )
            }
        }

        if (note.isNotBlank()) {
            Text(
                note, color = Omni.Warning, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), maxLines = 3
            )
        }
        if (playing.isNotBlank()) {
            Text(
                playing, color = Omni.Accent, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp), maxLines = 2
            )
        }
        if (loading && items.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Omni.Accent, strokeWidth = 2.dp)
                Text(
                    "  Ищу на $source · ${elapsed} с" +
                        (if (source != com.winwasm64.torrent.VideoFeed.SRC_YOUTUBE) " · встроенный браузер" else ""),
                    color = Omni.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f)
                )
                Text(
                    "⏹ Отменить", color = Omni.Accent, fontSize = 11.sp,
                    modifier = Modifier.clickable { scrapeUrl = null; loading = false; note = "Поиск прерван" }
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 18.dp)
        ) {
            items(filtered) { clip ->
                VideoCard(clip = clip, onOpen = { openClip(clip) }, onLink = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("ссылка", clip.url))
                    Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
                })
            }
            item {
                val total = items.size
                if (total in 1 until 100 && (cont != null || source != com.winwasm64.torrent.VideoFeed.SRC_YOUTUBE)) {
                    Button(
                        onClick = {
                            if (loading) return@Button
                            if (source == com.winwasm64.torrent.VideoFeed.SRC_YOUTUBE) {
                                loading = true
                                scope.launch {
                                    val page = withContext(Dispatchers.IO) {
                                        runCatching {
                                            com.winwasm64.torrent.VideoFeed.search(source, submitted, sort, cont)
                                        }.getOrElse {
                                            com.winwasm64.torrent.VideoFeed.Page(note = "Не удалось: " + humanError(it))
                                        }
                                    }
                                    items = (items + page.clips).distinctBy { it.id }
                                    cont = page.continuation
                                    if (page.note != null) note = page.note.orEmpty()
                                    loading = false
                                }
                            } else {
                                // VK/RuTube: новый проход встроенного браузера — прокрутка глубже
                                loading = true
                                note = "Догружаю ещё…"
                                scrapeSource = source
                                scrapeRound++
                                scrapeUrl = com.winwasm64.torrent.VideoFeed.scrapeUrl(source, submitted)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Omni.SurfaceHighest),
                        contentPadding = PaddingValues(vertical = 0.dp)
                    ) {
                        Text(
                            if (loading) "Загружаю…" else "⬇ Показать ещё  ·  $total/100",
                            fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1
                        )
                    }
                } else if (filtered.isNotEmpty()) {
                    Text(
                        if (total >= 100) "Показано 100 из 100 · уточните запрос, чтобы увидеть другое"
                        else "Это всё, что нашлось · ${filtered.size}",
                        color = Omni.TextFaint, fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                    )
                }
            }
        }
    }
}

/**
 * v4.76.0: встроенный браузер для площадок, которые не отдают выдачу простым запросом (VK, RuTube).
 * Терпеливо ждёт отрисовку (до 25 с), прокручивает выдачу и копит карточки до 100 штук,
 * отдавая результат по мере поступления — как в обычном клиенте.
 */
@Composable
private fun WebScraper(
    url: String,
    source: String,
    query: String,
    round: Int,
    onProgress: (List<com.winwasm64.torrent.VideoFeed.Clip>) -> Unit,
    onStatus: (String) -> Unit,
    onFinished: (List<com.winwasm64.torrent.VideoFeed.Clip>) -> Unit
) {
    val context = LocalContext.current
    var web by remember(url, round) { mutableStateOf<android.webkit.WebView?>(null) }
    val collected = remember(url, round) { LinkedHashMap<String, com.winwasm64.torrent.VideoFeed.Clip>() }

    AndroidView(
        modifier = Modifier.size(1.dp),
        factory = { ctx ->
            android.webkit.WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView?, u: String?) {
                        view?.evaluateJavascript("document.body.style.zoom='1';", null)
                    }
                }
                loadUrl(url)
                web = this
            }
        }
    )

    LaunchedEffect(url, round) {
        val js = com.winwasm64.torrent.VideoFeed.scrapeJs(source)
        var pass = 0
        var altUsed = false
        val maxPass = 10 + round * 6
        while (pass < maxPass) {
            delay(1300)
            val w = web ?: continue
            // v4.76.0: если мобильная выдача VK пуста — пробуем полный сайт (в нём тот же поиск)
            if (pass == 5 && collected.isEmpty() && !altUsed) {
                altUsed = true
                val alt = when {
                    url.contains("m.vk.com") -> url.replace("m.vk.com", "vk.com")
                    url.contains("rutube.ru/search") -> url.replace("rutube.ru/search/?query=", "rutube.ru/search/?query=")
                    else -> url
                }
                if (alt != url) {
                    onStatus("Открываю полную версию $source…")
                    w.loadUrl(alt)
                    delay(2500)
                }
            }
            val raw = evalJs(w, js)
            if (!raw.isNullOrBlank() && raw != "\"[]\"") {
                val list = runCatching {
                    com.winwasm64.torrent.VideoFeed.parseScraped(source, raw, query)
                }.getOrDefault(emptyList())
                var added = false
                for (c in list) if (!collected.containsKey(c.url)) { collected[c.url] = c; added = true }
                if (added) onProgress(collected.values.toList())
                if (collected.size >= 100) break
            }
            // прокручиваем — выдача VK/RuTube доезжает по мере скролла
            w.evaluateJavascript("window.scrollBy(0, Math.round(window.innerHeight*0.95));", null)
            pass++
            if (pass == 7 && collected.isEmpty()) {
                val txt = evalJs(w, "(document.body?document.body.innerText:'').slice(0,300)") ?: ""
                onStatus(
                    if (txt.contains("Войти") || txt.contains("Вход")) "$source просит вход — включите VPN (щит) и повторите"
                    else "$source: пока ничего не отдал, продолжаю прокрутку…"
                )
            }
        }
        onFinished(collected.values.toList())
    }
}

/** evaluateJavascript как suspend-вызов (должен вызываться из главного потока). */
private suspend fun evalJs(web: android.webkit.WebView, js: String): String? =
    suspendCancellableCoroutine { c ->
        try {
            web.evaluateJavascript(js) { r -> if (c.isActive) c.resume(r) }
        } catch (_: Throwable) {
            if (c.isActive) c.resume(null)
        }
    }

/** Карточка видео: обложка 16:9, длительность, автор, просмотры, дата. */
@Composable
private fun VideoCard(
    clip: com.winwasm64.torrent.VideoFeed.Clip,
    onOpen: () -> Unit,
    onLink: () -> Unit
) {
    var menu by remember(clip.id) { mutableStateOf(false) }
    var bmp by remember(clip.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(clip.id) {
        if (clip.thumb.isNotBlank()) {
            bmp = withContext(Dispatchers.IO) {
                runCatching { com.winwasm64.ui.browser.CoverCache.load(clip.thumb, maxSide = 640) }.getOrNull()
            }
        }
    }
    // цвет аватара — из названия канала, чтобы у каждого канала был свой
    val avatarColors = listOf(0xFF2D7FF9L, 0xFFE0443EL, 0xFF12A150L, 0xFFB4570AL, 0xFF8A4FE0L, 0xFF0E8F9BL)
    val avatarColor = Color(avatarColors[kotlin.math.abs(clip.author.hashCode()) % avatarColors.size])

    Column(modifier = Modifier.fillMaxWidth().clickable { onOpen() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .background(Omni.SurfaceHighest)
        ) {
            val bitmap = bmp
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (clip.kind == "playlist") "🎞" else if (clip.kind == "channel") "📺" else "▶", fontSize = 26.sp, color = Omni.TextFaint)
                }
            }
            if (clip.lengthText.isNotBlank()) {
                Surface(
                    color = Color(0xCC000000),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                ) {
                    Text(clip.lengthText, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            if (clip.live) {
                Surface(
                    color = Color(0xFFD7263D),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                ) {
                    Text("LIVE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            if (clip.kind != "video") {
                Surface(
                    color = Color(0xCC000000),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                ) {
                    Text(
                        if (clip.kind == "playlist") "плейлист" else "канал",
                        color = Color.White, fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Box(
                modifier = Modifier.size(34.dp).clip(CircleShape).background(avatarColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    (clip.author.firstOrNull() ?: 'О').uppercase(),
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    clip.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                // v4.76.0: видно площадку, канал, просмотры и дату — как в эталонном клиенте
                val platformLabel = when (clip.source) {
                    com.winwasm64.torrent.VideoFeed.SRC_VK -> "VK Видео"
                    com.winwasm64.torrent.VideoFeed.SRC_RUTUBE -> "RuTube"
                    else -> "YouTube"
                }
                val meta = listOf(
                    clip.author.ifBlank { platformLabel },
                    platformLabel,
                    clip.viewsText,
                    clip.publishedText,
                    if (clip.lengthSec > 0) clip.lengthText else ""
                ).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(meta, color = Omni.TextDim, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(
                "⋮", color = Omni.TextDim, fontSize = 18.sp,
                modifier = Modifier.padding(start = 6.dp).clickable { menu = !menu }
            )
        }
        if (menu) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "▶ Смотреть" to onOpen,
                    "📋 Ссылка" to onLink
                ).forEach { (label, action) ->
                    Surface(
                        color = Omni.SurfaceHighest,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable { action() }
                    ) {
                        Text(label, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
        }
    }
}

private fun torrentLastQueryFallback(): String = com.winwasm64.torrent.SearchCache.videoQuery.trim()
