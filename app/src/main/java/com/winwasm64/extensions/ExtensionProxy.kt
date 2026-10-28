package com.winwasm64.extensions

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.winwasm64.vpn.BrowserDirectRepeater
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * chrome.proxy для Android WebView.
 *
 * WebView не умеет ни PAC-скрипты, ни выдачу пароля прокси расширению, поэтому:
 *  - WebView направляется (ProxyController) в локальный прокси 127.0.0.1:<порт>;
 *  - для каждого соединения маршрут вычисляется PAC-скриптом расширения (в отдельном скрытом WebView)
 *    или правилами fixed_servers;
 *  - соединение идёт через сервер расширения (HTTP, HTTPS/TLS или SOCKS5), а при ответе 407
 *    логин/пароль запрашиваются у расширения (webRequest.onAuthRequired);
 *  - «DIRECT» идёт через встроенный обход DPI браузера, если он включён, иначе напрямую.
 */
object ExtensionProxy {
    private const val TAG = "OmniExtProxy"

    class Upstream(val type: String, val host: String, val port: Int) {
        val key: String get() = "$host:$port"
        override fun toString(): String = if (type == "DIRECT") "DIRECT" else "$type $host:$port"
    }

    class FixedRules(val single: Upstream?, val http: Upstream?, val https: Upstream?, val fallback: Upstream?, val bypass: List<String>)

    class Control(val extId: String, val value: JSONObject, val pacText: String?, val fixed: FixedRules?)

    private val DIRECT = Upstream("DIRECT", "", 0)

    @Volatile
    var control: Control? = null
        private set

    /** Имя расширения, которое сейчас управляет прокси (для интерфейса). */
    var ownerName by mutableStateOf<String?>(null)
        private set

    val isActive: Boolean get() = control != null

    @Volatile
    var port: Int = 0
        private set

    var authRequester: ((owner: String, details: JSONObject, cb: (Pair<String, String>?) -> Unit) -> Unit)? = null
    var errorReporter: ((owner: String, error: String, details: String) -> Unit)? = null
    var changeListener: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "omni-extproxy").apply {
            isDaemon = true
            setUncaughtExceptionHandler { _, e -> Log.w(TAG, "фоновая ошибка", e) } // v4.2.8: ошибка в потоке не должна ронять приложение
        }
    }
    private var server: ServerSocket? = null
    private var appliedKey = ""
    private lateinit var appContext: Context

    private var pacView: WebView? = null
    private var pacPageLoaded = false
    private var pendingPac: String? = null
    @Volatile
    private var pacReady = false
    private var pacGeneration = 0
    private val pacCache = ConcurrentHashMap<String, Pair<List<Upstream>, Long>>()

    private val creds = ConcurrentHashMap<String, Pair<String, String>>()
    private val authInFlight = ConcurrentHashMap<String, CountDownLatch>()
    private val lastErrorAt = ConcurrentHashMap<String, Long>()
    private val requestSeq = AtomicInteger(0)

    fun init(context: Context) {
        appContext = context.applicationContext
        DirectSites.init(context)
        loadPersisted()
        registerNetworkWatch()
    }

    // =====================================================================
    // v4.2.11: смена сети (Wi-Fi выключили/включили, переход на мобильную) — раньше все серверы Browsec помечались
    // «не отвечают» на 3–5 мин из-за ENETUNREACH/ECONNABORTED нашей же сети, и после возврата Wi-Fi браузер ещё долго
    // шёл мимо туннеля. Теперь: сбои «на нашей стороне» серверы не штрафуют, а при появлении сети все пометки снимаются
    // и серверы перемеряются сразу.
    // =====================================================================
    @Volatile private var noNetLoggedAt = 0L
    @Volatile private var lastNetId: String? = null
    @Volatile private var netLostAt = 0L
    @Volatile private var netValidated = true
    private var netWatchRegistered = false

    private fun describeNet(cm: android.net.ConnectivityManager, n: android.net.Network?): String {
        val caps = try { n?.let { cm.getNetworkCapabilities(it) } } catch (_: Exception) { null } ?: return "неизвестная сеть"
        return when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная сеть"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "сеть"
        }
    }

    private fun registerNetworkWatch() {
        if (netWatchRegistered) return
        netWatchRegistered = true
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            lastNetId = cm.activeNetwork?.toString()
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                private fun isVpn(network: android.net.Network): Boolean = try {
                    cm.getNetworkCapabilities(network)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
                } catch (_: Exception) { false }
                override fun onAvailable(network: android.net.Network) {
                    if (isVpn(network)) return // v4.2.12: наш собственный VPN приложений — не смена сети
                    val id = network.toString()
                    val prev = lastNetId
                    lastNetId = id
                    if (prev == id) return
                    val lostAgo = if (netLostAt > 0L) SystemClock.elapsedRealtime() - netLostAt else -1L
                    onNetworkChanged("появилась сеть: ${describeNet(cm, network)}" + (if (prev == null) "" else " (вместо прежней)") + (if (lostAgo in 0..600_000L) ", без сети были ${lostAgo / 1000} с" else ""))
                }
                override fun onLost(network: android.net.Network) {
                    if (network.toString() != lastNetId) return
                    if (isVpn(network)) return
                    netLostAt = SystemClock.elapsedRealtime()
                    lastNetId = null
                    com.winwasm64.vpn.VpnLog.w("Сеть", "сеть потеряна (${describeNet(cm, network)}) — серверы Browsec не штрафуем, ждём новую сеть")
                }
                override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                    if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return
                    val v = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (v && !netValidated) onNetworkChanged("интернет в сети снова доступен (${describeNet(cm, network)})")
                    netValidated = v
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "network watch", e)
        }
    }

    /** Есть ли сейчас сеть с интернетом (по данным системы). */
    fun networkUp(): Boolean = try {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val n = cm.activeNetwork
        n != null && (cm.getNetworkCapabilities(n)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
    } catch (_: Exception) { true }

    /** Ошибка вызвана нашей сетью (Wi-Fi выключен/переключается, нет маршрута, DNS не отвечает), а не сервером Browsec. */
    private fun localNetFault(e: Throwable): Boolean {
        val m = e.message ?: ""
        val local = e is java.net.UnknownHostException || m.contains("ENETUNREACH") || m.contains("Network is unreachable") ||
            m.contains("ECONNABORTED") || m.contains("Software caused connection abort") || m.contains("EHOSTUNREACH") ||
            m.contains("No route to host") || m.contains("EADDRNOTAVAIL") || !networkUp()
        if (local) {
            val now = SystemClock.elapsedRealtime()
            if (now - noNetLoggedAt > 30_000L) {
                noNetLoggedAt = now
                com.winwasm64.vpn.VpnLog.w("Сеть", "сбой на нашей стороне (${(m.ifEmpty { e.javaClass.simpleName }).take(90)}) — серверы Browsec не помечаем недоступными; когда сеть вернётся, проверим их заново")
            }
        }
        return local
    }

    /** Снимает все пометки «сервер не отвечает» и запускает новый замер. */
    fun onNetworkChanged(reason: String) {
        val had = downUntil.size + tunnelDownUntil.size + (if (ownerDown()) 1 else 0)
        downUntil.clear()
        ownerDownUntil = 0L
        ownerFails.clear()
        tunnelDownUntil.clear()
        tunnelPing.clear()
        tunnelConnectMs.entries.removeIf { it.value >= 9_000L }
        tunnelDestMs.entries.removeIf { it.value >= 9_000L }
        appTunnelFailLoggedAt.clear()
        netLostAt = 0L
        com.winwasm64.vpn.VpnLog.ok("Сеть", "$reason — снимаем пометки «не отвечает» ($had) и перемеряем серверы Browsec")
        tunnelPingAt = SystemClock.elapsedRealtime()
        pool.execute {
            try { pingTunnelServers() } catch (_: Exception) {}
            if (control != null && pacReady) try { prewarmRoute() } catch (_: Exception) {}
        }
    }

    // v4.1.1: учётные данные Browsec и список его серверов переживают отключение прокси расширения и перезапуск —
    // туннель приложений и DoH через туннель работают независимо от того, управляет ли расширение прокси браузера
    private const val PREFS = "omni_ext_proxy_v41"

    @Volatile
    private var sharedCreds: Pair<String, String>? = null

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadPersisted() {
        try {
            val p = prefs()
            val u = p.getString("shared_user", null)
            val pw = p.getString("shared_pass", null)
            if (!u.isNullOrEmpty() && pw != null) sharedCreds = u to pw
            val list = p.getString("tunnel_servers", null)
            if (!list.isNullOrBlank()) {
                val arr = JSONArray(list)
                val found = ArrayList<Upstream>()
                for (i in 0 until arr.length()) {
                    val hp = splitHostPort(arr.optString(i), 443) ?: continue
                    found.add(Upstream("HTTPS", hp.first, hp.second))
                }
                if (found.isNotEmpty()) tunnelServers = found
            }
        } catch (_: Exception) {
        }
    }

    private fun rememberSharedCreds(c: Pair<String, String>) {
        sharedCreds = c
        try {
            prefs().edit().putString("shared_user", c.first).putString("shared_pass", c.second).apply()
        } catch (_: Exception) {
        }
    }

    private fun persistTunnelServers(list: List<Upstream>) {
        try {
            val arr = JSONArray()
            for (u in list.take(40)) arr.put(u.key)
            prefs().edit().putString("tunnel_servers", arr.toString()).apply()
        } catch (_: Exception) {
        }
    }

    // =====================================================================
    // chrome.proxy.settings
    // =====================================================================

    fun settingFor(extId: String): JSONObject {
        val c = control
        val level = when {
            c == null -> "controllable_by_this_extension"
            c.extId == extId -> "controlled_by_this_extension"
            else -> "controlled_by_other_extensions"
        }
        val value = c?.value?.let { JSONObject(it.toString()) } ?: JSONObject().put("mode", "system")
        return JSONObject().put("value", value).put("levelOfControl", level).put("incognitoSpecific", false)
    }

    /** Вызывается на главном потоке. onDone(null) — успех, иначе текст ошибки для runtime.lastError. */
    fun set(extId: String, extName: String, value: JSONObject, onDone: (String?) -> Unit) {
        when (val mode = value.optString("mode", "system")) {
            "direct", "system", "auto_detect" -> {
                release(extId)
                onDone(null)
            }
            "fixed_servers" -> {
                val rules = value.optJSONObject("rules")
                val fixed = rules?.let { parseFixed(it) }
                if (fixed == null) {
                    onDone("Для fixed_servers нужен хотя бы один прокси-сервер в rules")
                } else {
                    activate(Control(extId, value, null, fixed), extName)
                    onDone(null)
                }
            }
            "pac_script" -> {
                val pac = value.optJSONObject("pacScript")
                val data = pac?.optString("data", "")?.takeIf { it.isNotBlank() }
                val url = pac?.optString("url", "")?.takeIf { it.isNotBlank() }
                when {
                    data != null -> {
                        activate(Control(extId, value, data, null), extName)
                        onDone(null)
                    }
                    url != null -> pool.execute {
                        val text = fetchPac(url)
                        main.post {
                            if (text == null) {
                                onDone("Не удалось загрузить PAC-скрипт: $url")
                            } else {
                                activate(Control(extId, value, text, null), extName)
                                onDone(null)
                            }
                        }
                    }
                    else -> onDone("Нужен pacScript.data или pacScript.url")
                }
            }
            else -> onDone("Неизвестный режим прокси: $mode")
        }
    }

    /** Снять управление прокси (если extId == null — у любого расширения). */
    fun release(extId: String?) {
        val c = control ?: return
        downUntil.clear()
        ownerDownUntil = 0L
        ownerFails.clear()
        if (extId != null && c.extId != extId) return
        control = null
        ownerName = null
        pacCache.clear()
        // creds не очищаем: туннель приложений продолжает работать через серверы Browsec
        pacReady = false
        BrowserDirectRepeater.externalProxyOverride = null
        BrowserDirectRepeater.resetAppliedProxyKey()
        appliedKey = ""
        try {
            BrowserDirectRepeater.ensureDirectBrowserMode(null)
        } catch (e: Exception) {
            Log.w(TAG, "restore", e)
        }
        changeListener?.invoke()
    }

    /** Прокси для HTTP-запросов самих расширений (как в Chrome — через выбранный расширением прокси). */
    fun javaProxy(): Proxy? {
        if (control == null || port <= 0) return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getLoopbackAddress(), port))
    }

    private fun activate(c: Control, name: String) {
        val previous = control
        if (previous == null || previous.extId != c.extId) creds.clear()
        control = c
        ownerName = name
        pacCache.clear()
        if (c.pacText != null) loadPac(c.pacText) else pacReady = true
        refreshAutoTunnel(c.pacText)
        ensureServer()
        BrowserDirectRepeater.externalProxyOverride = { onReady -> applyOverride(onReady) }
        applyOverride(null)
        changeListener?.invoke()
    }

    /** Порт, на который направлен WebView: единый слушатель репитера (он же обслуживает режим без расширения). */
    fun webViewPort(): Int {
        val rp = try { BrowserDirectRepeater.ensureServerRunning() } catch (_: Exception) { -1 }
        return if (rp > 0) rp else port
    }

    private fun applyOverride(onReady: (() -> Unit)?) {
        // v4.1.3: WebView всегда ходит в один и тот же локальный порт (репитер, 8877) — и с расширением, и без него.
        // Репитер передаёт CONNECT сюда, пока прокси расширения активен. Смена режима больше не меняет настройку
        // прокси WebView и не рвёт его соединения.
        val p = webViewPort()
        if (p <= 0 || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            onReady?.invoke()
            return
        }
        val key = "EXT:$p"
        if (appliedKey == key) {
            onReady?.invoke()
            return
        }
        try {
            val cfg = ProxyConfig.Builder()
                .addProxyRule("127.0.0.1:$p")
                .addBypassRule("localhost")
                .addBypassRule("127.0.0.1")
                .build()
            appliedKey = key
            ProxyController.getInstance().setProxyOverride(cfg, { r -> r.run() }, { onReady?.invoke() })
        } catch (e: Exception) {
            Log.w(TAG, "setProxyOverride", e)
            onReady?.invoke()
        }
    }

    // =====================================================================
    // PAC
    // =====================================================================

    private val PAC_HELPERS = """
function isPlainHostName(h){return String(h).indexOf('.')<0;}
function dnsDomainIs(h,d){h=String(h);d=String(d);return h.length>=d.length&&h.substring(h.length-d.length)===d;}
function localHostOrDomainIs(h,hd){return h===hd||String(hd).lastIndexOf(h+'.',0)===0;}
function dnsResolve(h){try{var r=OmniPac.resolve(String(h));return r?r:null;}catch(e){return null;}}
function dnsResolveEx(h){var r=dnsResolve(h);return r?r:'';}
function isResolvable(h){return dnsResolve(h)!==null;}
function isResolvableEx(h){return isResolvable(h);}
function myIpAddress(){try{return OmniPac.myIp()||'127.0.0.1';}catch(e){return '127.0.0.1';}}
function myIpAddressEx(){return myIpAddress();}
function __a(ip){var b=String(ip).split('.');return(((b[0]&255)<<24)>>>0)+((b[1]&255)<<16)+((b[2]&255)<<8)+(b[3]&255);}
function isInNet(ip,pat,mask){var re=/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/;if(!re.test(ip)){ip=dnsResolve(ip);if(ip===null)return false;}return((__a(ip)&__a(mask))>>>0)===((__a(pat)&__a(mask))>>>0);}
function isInNetEx(ip,prefix){var p=String(prefix).split('/');if(p.length!==2||String(ip).indexOf(':')>=0||p[0].indexOf(':')>=0)return false;var bits=+p[1];var mask=bits<=0?0:((0xFFFFFFFF<<(32-bits))>>>0);var re=/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/;if(!re.test(ip)){ip=dnsResolve(ip);if(ip===null)return false;}return((__a(ip)&mask)>>>0)===((__a(p[0])&mask)>>>0);}
function dnsDomainLevels(h){return String(h).split('.').length-1;}
function shExpMatch(s,p){var r='';p=String(p);for(var i=0;i<p.length;i++){var ch=p.charAt(i);if(ch==='*')r+='.*';else if(ch==='?')r+='.';else if('.+^{}()|[]\\/'.indexOf(ch)>=0||ch.charCodeAt(0)===36)r+='\\'+ch;else r+=ch;}return new RegExp('^'+r+'$').test(String(s));}
function weekdayRange(){return true;}
function dateRange(){return true;}
function timeRange(){return true;}
function sortIpAddressList(l){return l;}
function getClientVersion(){return '1.0';}
function alert(){}
"""

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadPac(text: String) {
        pacReady = false
        pacGeneration++
        pendingPac = text
        val view = pacView ?: WebView(appContext).also { w ->
            w.settings.javaScriptEnabled = true
            w.addJavascriptInterface(PacBridge, "OmniPac")
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, url: String?) {
                    pacPageLoaded = true
                    runPendingPac()
                }
            }
            pacView = w
            w.loadDataWithBaseURL("https://pac.omni-ext.invalid/", "<html><body></body></html>", "text/html", "utf-8", null)
        }
        if (pacPageLoaded && view === pacView) runPendingPac()
    }

    private fun runPendingPac() {
        val text = pendingPac ?: return
        val view = pacView ?: return
        pendingPac = null
        val gen = pacGeneration
        val body = text + "\n;return typeof FindProxyForURL==='function'?FindProxyForURL:null;"
        val script = PAC_HELPERS + "\n(function(){try{window.__omniPac=(new Function(" + JSONObject.quote(body) +
            "))();return window.__omniPac?'ok':'no FindProxyForURL';}catch(e){return 'error: '+e;}})()"
        view.evaluateJavascript(script) { r ->
            if (gen != pacGeneration) return@evaluateJavascript
            pacReady = r != null && r.contains("ok")
            if (!pacReady) Log.w(TAG, "PAC не загружен: $r")
            else pool.execute { prewarmRoute() } // v4.2.2: мёртвые серверы маршрута отмечаем до первой страницы
        }
    }

    /**
     * Параллельно проверяет первые серверы PAC-маршрута (CONNECT www.google.com:443) и отмечает неотвечающие «вниз»,
     * чтобы первая страница после включения не ждала их таймауты (чёрный плеер YouTube при первом открытии).
     */
    private fun prewarmRoute() {
        val route = try { pacLookup("https://www.youtube.com/", "www.youtube.com") } catch (_: Exception) { null } ?: return
        val ups = route.filter { it.type != "DIRECT" }.take(4)
        val latch = CountDownLatch(ups.size)
        for (up in ups) {
            pool.execute {
                try {
                    val t0 = SystemClock.elapsedRealtime()
                    val r = if (up.type == "SOCKS5") viaSocks5(up, "www.google.com", 443) else viaHttpProxy(up, "www.google.com", 443, "https://www.google.com/")
                    closeQuietly(r.first)
                    noteUpstreamOk(up, "проверка маршрута")
                    com.winwasm64.vpn.VpnLog.i("Браузер", "сервер расширения ${up.key} отвечает за ${SystemClock.elapsedRealtime() - t0} мс")
                } catch (e: Exception) {
                    markDown(up, e)
                    if (!localNetFault(e)) com.winwasm64.vpn.VpnLog.w("Браузер", "сервер расширения ${up.key} не отвечает (${e.message ?: e.javaClass.simpleName}) — пропускаем его сразу")
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await(12, TimeUnit.SECONDS)
        // v4.2.7: сразу же измеряем резервные серверы автотуннеля, чтобы к первому видео знать самые быстрые
        if (tunnelServers.isEmpty()) refreshAutoTunnel(control?.pacText)
        val now = SystemClock.elapsedRealtime()
        if (now - tunnelPingAt > 600_000L) {
            tunnelPingAt = now
            pingTunnelServers()
        }
    }

    object PacBridge {
        @JavascriptInterface
        fun resolve(host: String): String = try {
            InetAddress.getByName(host).hostAddress ?: ""
        } catch (_: Exception) {
            ""
        }

        @JavascriptInterface
        fun myIp(): String = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address }?.hostAddress ?: "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    private fun fetchPac(url: String): String? = try {
        if (url.startsWith("data:", true)) {
            val comma = url.indexOf(',')
            val meta = url.substring(5, comma)
            val payload = url.substring(comma + 1)
            if (meta.endsWith(";base64", true)) String(Base64.decode(payload, Base64.DEFAULT)) else java.net.URLDecoder.decode(payload, "UTF-8")
        } else {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "fetchPac", e)
        null
    }

    fun parsePacResult(s: String): List<Upstream> {
        val out = ArrayList<Upstream>()
        for (part in s.split(';')) {
            val t = part.trim()
            if (t.isEmpty()) continue
            val sp = t.split(Regex("\\s+"), limit = 2)
            val kind = sp[0].uppercase()
            if (kind == "DIRECT") {
                out.add(DIRECT)
                continue
            }
            if (sp.size < 2) continue
            val type = when (kind) {
                "PROXY", "HTTP" -> "HTTP"
                "HTTPS" -> "HTTPS"
                "SOCKS", "SOCKS5", "SOCKS4" -> "SOCKS5"
                else -> null
            } ?: continue
            val hp = splitHostPort(sp[1].trim(), if (type == "HTTPS") 443 else if (type == "SOCKS5") 1080 else 80) ?: continue
            out.add(Upstream(type, hp.first, hp.second))
        }
        if (out.isEmpty()) out.add(DIRECT)
        return out
    }

    private fun pacLookup(url: String, host: String): List<Upstream>? {
        val c = control ?: return null
        c.fixed?.let { return fixedRoute(it, url, host) }
        val now = SystemClock.elapsedRealtime()
        pacCache[host]?.let { if (now - it.second < 30_000) return it.first }
        var waited = 0
        while (!pacReady && waited < 5000 && control === c) {
            Thread.sleep(50)
            waited += 50
        }
        if (!pacReady) return null
        val latch = CountDownLatch(1)
        var answer: String? = null
        main.post {
            val v = pacView
            if (v == null) {
                latch.countDown()
            } else {
                v.evaluateJavascript(
                    "(function(){try{return String(window.__omniPac(" + JSONObject.quote(url) + "," + JSONObject.quote(host) + "));}catch(e){return 'DIRECT';}})()"
                ) { r ->
                    answer = try {
                        JSONArray("[$r]").optString(0, null)
                    } catch (_: Exception) {
                        null
                    }
                    latch.countDown()
                }
            }
        }
        if (!latch.await(4, TimeUnit.SECONDS)) return null
        val list = parsePacResult(answer ?: return null)
        if (pacCache.size > 2000) pacCache.clear()
        pacCache[host] = list to now
        return list
    }

    // =====================================================================
    // fixed_servers
    // =====================================================================

    private fun parseServer(o: JSONObject?): Upstream? {
        if (o == null) return null
        val host = o.optString("host", "").trim()
        if (host.isEmpty()) return null
        val type = when (o.optString("scheme", "http").lowercase()) {
            "https", "quic" -> "HTTPS"
            "socks4", "socks5", "socks" -> "SOCKS5"
            else -> "HTTP"
        }
        val port = if (o.has("port")) o.optInt("port") else when (type) {
            "HTTPS" -> 443
            "SOCKS5" -> 1080
            else -> 80
        }
        return Upstream(type, host.trim('[', ']'), port)
    }

    private fun parseFixed(r: JSONObject): FixedRules? {
        val bypass = ArrayList<String>()
        r.optJSONArray("bypassList")?.let { arr -> for (i in 0 until arr.length()) bypass.add(arr.optString(i)) }
        val f = FixedRules(
            parseServer(r.optJSONObject("singleProxy")),
            parseServer(r.optJSONObject("proxyForHttp")),
            parseServer(r.optJSONObject("proxyForHttps")),
            parseServer(r.optJSONObject("fallbackProxy")),
            bypass
        )
        if (f.single == null && f.http == null && f.https == null && f.fallback == null) return null
        return f
    }

    private fun fixedRoute(f: FixedRules, url: String, host: String): List<Upstream> {
        if (bypassed(f.bypass, host)) return listOf(DIRECT)
        val secure = url.startsWith("https:") || url.startsWith("wss:")
        val up = f.single ?: (if (secure) f.https else f.http) ?: f.fallback
        return listOf(up ?: DIRECT)
    }

    private fun ipv4ToLong(ip: String): Long? {
        val p = ip.split('.')
        if (p.size != 4) return null
        var v = 0L
        for (x in p) {
            val n = x.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }

    private fun bypassed(list: List<String>, host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        for (raw in list) {
            var p = raw.trim().lowercase()
            if (p.isEmpty()) continue
            if (p == "<local>") {
                if (!h.contains('.') && !h.contains(':')) return true
                continue
            }
            if (p == "<-loopback>") continue
            p = p.substringAfter("://")
            if (p.contains('/')) {
                val net = ipv4ToLong(p.substringBefore('/'))
                val bits = p.substringAfter('/').toIntOrNull()
                val hv = ipv4ToLong(h)
                if (net != null && bits != null && hv != null) {
                    val mask = if (bits <= 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
                    if ((hv and mask) == (net and mask)) return true
                }
                continue
            }
            if (!p.startsWith("[") && p.count { it == ':' } == 1) p = p.substringBefore(':')
            if (p.contains('*')) {
                val re = Regex("^" + Regex.escape(p).replace("*", "\\E.*\\Q") + "$")
                if (re.matches(h)) return true
            } else if (p.startsWith(".")) {
                if (h.endsWith(p) || h == p.substring(1)) return true
            } else if (h == p) {
                return true
            }
        }
        return false
    }

    // =====================================================================
    // Локальный прокси-сервер
    // =====================================================================

    private fun ensureServer() {
        if (server?.isClosed == false) return
        try {
            // v4.1.1: порт стабилен на всё время жизни процесса — перезапуск слушателя не меняет настройку WebView,
            // иначе соединения, идущие на старый порт, получали отказ (= ERR_PROXY_CONNECTION_FAILED)
            var ss: ServerSocket? = null
            if (port > 0) {
                try {
                    val t = ServerSocket()
                    t.reuseAddress = true
                    t.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 512)
                    ss = t
                } catch (_: Exception) {
                }
            }
            if (ss == null) {
                val t = ServerSocket()
                t.reuseAddress = true
                t.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 512)
                ss = t
            }
            server = ss
            port = ss.localPort
            val t = Thread({ acceptLoop(ss) }, "omni-extproxy-accept").apply { isDaemon = true }
            acceptor = t
            t.start()
            startWatchdog()
        } catch (e: Exception) {
            Log.e(TAG, "не удалось запустить локальный прокси", e)
            com.winwasm64.vpn.VpnLog.e("Браузер", "локальный прокси расширения не запустился: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    @Volatile
    private var lastAcceptErrorLog = 0L

    private fun acceptLoop(ss: ServerSocket) {
        var errors = 0
        while (!ss.isClosed) {
            val s = try {
                ss.accept()
            } catch (e: Throwable) {
                if (ss.isClosed) break
                // Временная ошибка (например, кончились дескрипторы при Shorts) — НЕ выходим, иначе прокси «умирает»
                errors++
                val now = SystemClock.elapsedRealtime()
                if (now - lastAcceptErrorLog > 30_000L) {
                    lastAcceptErrorLog = now
                    com.winwasm64.vpn.VpnLog.w("Браузер", "локальный прокси: accept() не удался (${e.message ?: e.javaClass.simpleName}) — повторяю")
                }
                try { Thread.sleep(if (errors > 50) 200L else 10L) } catch (_: InterruptedException) {}
                continue
            }
            errors = 0
            try {
                pool.execute { handle(s) }
            } catch (e: Throwable) {
                // Не хватило потоков/памяти — обрабатываем без пула, но слушатель не умирает
                try { Thread({ handle(s) }, "omni-extproxy-handle").apply { isDaemon = true }.start() } catch (_: Throwable) { closeQuietly(s) }
            }
        }
    }

    private fun probe(p: Int): Boolean = probeReason(p) == null

    /** null — порт принимает соединения; иначе причина отказа (для журнала). */
    private fun probeReason(p: Int): String? {
        if (p <= 0) return "порт не задан"
        return try {
            Socket().use {
                it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), p), 3_000)
                null
            }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    @Volatile
    private var acceptor: Thread? = null

    /** Слушатель прокси расширения жив: сокет открыт и поток accept() работает. */
    private fun listenerAlive(): Boolean = server?.isClosed == false && acceptor?.isAlive == true

    /**
     * Проверяет локальный прокси расширения и перезапускает слушатель (на том же порту), только если он
     * действительно не принимает соединения; затем заново применяет настройку WebView.
     */
    fun repairNow(onReady: (() -> Unit)? = null) {
        if (control == null) {
            main.post { onReady?.invoke() }
            return
        }
        // v4.2.0: слушатель перезапускаем только если он действительно мёртв (сокет закрыт или поток accept() умер).
        // Неудачная пробная проверка при живом слушателе — лишь запись в журнал: перезапуск рвал бы все соединения браузера.
        if (!listenerAlive()) {
            com.winwasm64.vpn.VpnLog.w("Браузер", "слушатель прокси расширения (порт $port) остановился — перезапуск")
            try { server?.close() } catch (_: Exception) {}
            server = null
            ensureServer()
        } else {
            probeReason(port)?.let { com.winwasm64.vpn.VpnLog.w("Браузер", "проверка порта $port прокси расширения не прошла ($it), слушатель жив — не трогаем") }
        }
        val rp = BrowserDirectRepeater.ensureServerRunning()
        if (!BrowserDirectRepeater.listenerAlive()) {
            com.winwasm64.vpn.VpnLog.w("Браузер", "слушатель репитера (порт $rp) остановился — перезапуск")
            try { BrowserDirectRepeater.restartListener() } catch (_: Exception) {}
        } else {
            probeReason(rp)?.let { com.winwasm64.vpn.VpnLog.w("Браузер", "проверка порта $rp репитера не прошла ($it), слушатель жив — не трогаем") }
        }
        appliedKey = ""
        main.post { applyOverride(onReady) }
    }

    /** Строка диагностики для журнала VPN: порт, жив ли слушатель, сколько открыто дескрипторов и потоков. */
    fun diagnose(): String {
        val fds = try { java.io.File("/proc/self/fd").list()?.size ?: -1 } catch (_: Exception) { -1 }
        val alive = server?.isClosed == false
        val reach = probe(port)
        val wp = webViewPort()
        val wReach = probe(wp)
        return "WebView → порт $wp (приём ${if (wReach) "ок" else "НЕТ"}, ключ $appliedKey); прокси расширения порт $port, слушатель ${if (alive) "открыт" else "закрыт"}, приём ${if (reach) "ок" else "НЕТ"}; дескрипторов $fds, потоков ${Thread.activeCount()}"
    }

    @Volatile
    private var probeFails = 0

    /** true — прокси жив (или не используется); false — пришлось перезапустить. */
    fun ensureHealthy(): Boolean {
        if (control == null) return true
        if (listenerAlive() && BrowserDirectRepeater.listenerAlive()) {
            probeFails = 0
            return true
        }
        if (++probeFails < 2) return true
        probeFails = 0
        repairNow(null)
        return false
    }

    @Volatile
    private var watchdogStarted = false

    private fun startWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        Thread({
            while (true) {
                try { Thread.sleep(5000) } catch (_: InterruptedException) {}
                try { if (control != null) ensureHealthy() } catch (_: Exception) {}
            }
        }, "omni-extproxy-watchdog").apply { isDaemon = true }.start()
    }

    private fun closeQuietly(s: Socket?) {
        try {
            s?.close()
        } catch (_: Exception) {
        }
    }

    private fun readHead(inp: InputStream, limit: Int = 65536): ByteArray? {
        val out = ByteArrayOutputStream(1024)
        var state = 0
        while (out.size() < limit) {
            val b = inp.read()
            if (b < 0) return if (out.size() == 0) null else out.toByteArray()
            out.write(b)
            state = when {
                b == '\r'.code -> if (state == 2) 3 else 1
                b == '\n'.code -> if (state == 3) 4 else if (state == 1) 2 else 0
                else -> 0
            }
            if (state == 4) return out.toByteArray()
        }
        return null
    }

    private fun splitHostPort(s: String, def: Int): Pair<String, Int>? {
        val t = s.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("[")) {
            val end = t.indexOf(']')
            if (end < 0) return null
            val host = t.substring(1, end)
            val port = t.substring(end + 1).removePrefix(":").toIntOrNull() ?: def
            return host to port
        }
        val colon = t.lastIndexOf(':')
        if (colon > 0 && t.indexOf(':') == colon) return t.substring(0, colon) to (t.substring(colon + 1).toIntOrNull() ?: def)
        return t to def
    }

    private fun respond(client: Socket, code: Int, text: String) {
        try {
            val body = text.toByteArray(Charsets.UTF_8)
            client.getOutputStream().write(
                ("HTTP/1.1 $code $text\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8) + body
            )
            client.getOutputStream().flush()
        } catch (_: Exception) {
        }
        closeQuietly(client)
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 30_000
            client.tcpNoDelay = true
            val inp = BufferedInputStream(client.getInputStream())
            val head = readHead(inp)
            if (head == null) {
                closeQuietly(client)
                return
            }
            handleRequest(client, inp, head)
        } catch (e: Exception) {
            closeQuietly(client)
        }
    }

    /** Заголовок CONNECT/GET, который ставит сам прокси расширения, когда идёт через репитер напрямую (защита от петли). */
    const val DIRECT_MARK = "X-Omni-Direct"

    /**
     * Соединение, принятое слушателем репитера (v4.1.3): заголовки уже прочитаны, тело/туннель — в [inp].
     * Возвращается после закрытия соединения.
     */
    fun handleAccepted(client: Socket, inp: InputStream, head: ByteArray) {
        try {
            try { client.soTimeout = 30_000 } catch (_: Exception) {}
            handleRequest(client, inp, head)
        } catch (_: Exception) {
            closeQuietly(client)
        }
    }

    private fun handleRequest(client: Socket, inp: InputStream, head: ByteArray) {
        try {
            val text = String(head, Charsets.ISO_8859_1)
            val first = text.substringBefore("\r\n").split(' ')
            if (first.size < 3) {
                respond(client, 400, "Bad Request")
                return
            }
            val method = first[0].uppercase()
            if (method == "CONNECT") {
                val hp = splitHostPort(first[1], 443)
                if (hp == null) {
                    respond(client, 400, "Bad Request")
                    return
                }
                val url = (if (hp.second == 80) "http://" else "https://") + hp.first + "/"
                // v4.10.1: сайты «напрямую» (GeForce NOW и список пользователя) — мимо PAC и закреплённых серверов
                val forcedDirect = DirectSites.isDirect(hp.first, hp.second)
                if (forcedDirect) DirectSites.note(hp.first, hp.second)
                val route = if (forcedDirect) listOf(DIRECT) else (pacLookup(url, hp.first) ?: listOf(DIRECT))
                // v4.1.1: ступенчатая гонка вместо последовательного перебора — ответ на CONNECT всегда укладывается в ~8 с
                // v4.3.8: закреплённый за хостом сервер (загрузка видео, привязанного к IP) имеет приоритет
                val up = (if (forcedDirect) null else pinnedConnect(hp.first, hp.second)) ?: raceConnect(route, hp.first, hp.second, url)
                if (up == null) {
                    com.winwasm64.vpn.VpnLog.e("Браузер", "не удалось открыть ${hp.first}:${hp.second}")
                    respond(client, 502, "Proxy Error")
                    return
                }
                try {
                    val out = client.getOutputStream()
                    out.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                } catch (e: Exception) {
                    closeQuietly(up.first)
                    throw e
                }
                pipe(client, inp, up.first, up.second)
            } else {
                val u = try {
                    URL(first[1])
                } catch (_: Exception) {
                    respond(client, 400, "Bad Request")
                    return
                }
                val plainPort = if (u.port > 0) u.port else 80
                val route = if (DirectSites.isDirect(u.host, plainPort)) { DirectSites.note(u.host, plainPort); listOf(DIRECT) } else (pacLookup(first[1], u.host) ?: listOf(DIRECT))
                forwardHttp(client, inp, text, u, route)
            }
        } catch (e: Exception) {
            closeQuietly(client)
        }
    }

    private class RaceResult(val sock: Pair<Socket, InputStream>?, val kind: Int, val up: Upstream?)

    // v4.10.2: «сервер сессии» браузера. Chrome с PAC Browsec держит все соединения на одном прокси (один внешний IP),
    // а наша гонка выбирала победителя на каждое соединение — внешний IP скакал между lt94/lv115/…, и сервисы, которые
    // сверяют IP сессии (GeForce NOW → 0x00000157, вход Google, банки), ломались. Теперь последний выигравший сервер
    // стартует первым с форой 1,2 с; остальные подключаются к гонке, только если он не ответил.
    @Volatile private var stickyUp: Upstream? = null
    @Volatile private var stickyAt: Long = 0L
    private fun setSticky(up: Upstream) {
        // v4.10.4: только серверы общего списка (tunnelServers). Прокси, который PAC даёт отдельному сайту (browsec.com → gr21,
        // требует логин/пароль), «сервером сессии» становиться не должен — иначе все остальные сайты получают 407.
        if (up.type == "DIRECT" || (tunnelServers.isNotEmpty() && tunnelServers.none { it.key == up.key })) return
        val prev = stickyUp
        stickyUp = up
        stickyAt = SystemClock.elapsedRealtime()
        if (prev?.key != up.key) com.winwasm64.vpn.VpnLog.i("Браузер", "сервер сессии браузера: ${up.key.substringBefore('.')} — все соединения через один выход, пока он отвечает")
    }

    /**
     * Ступенчатая гонка маршрутов (v4.1.1). WebView ждёт ответ на CONNECT около 10 с, поэтому:
     *  t=0      — до двух серверов расширения из PAC параллельно (пропуская «упавшие»);
     *  t=2.5 с  — (или сразу, когда все они отвалились/пропущены) два лучших сервера автотуннеля Browsec;
     *  t=5 с    — (или сразу) встроенный обход DPI;
     *  t=8.5 с  — общий предел.
     * Первый успешный маршрут побеждает, остальные закрываются по завершении. Выбранный расширением сервер
     * получает фору, но зависший сервер больше не блокирует страницу.
     */
    private fun raceConnect(route: List<Upstream>, host: String, port: Int, url: String): Pair<Socket, InputStream>? {
        val proxies = route.filter { it.type != "DIRECT" }
        val hasDirectInRoute = route.any { it.type == "DIRECT" }
        if (proxies.isEmpty()) {
            return try { direct(host, port, url) } catch (_: Exception) { null }
        }
        val results = LinkedBlockingQueue<RaceResult>()
        val won = AtomicBoolean(false)
        val pending = AtomicInteger(0)
        fun launch(kind: Int, up: Upstream?, block: () -> Pair<Socket, InputStream>) {
            pending.incrementAndGet()
            pool.execute {
                var r: Pair<Socket, InputStream>? = null
                try {
                    r = block()
                } catch (e: Exception) {
                    if (kind == 0 && up != null) {
                        markDown(up, e)
                        reportError(up, e)
                    } else if (kind == 1 && up != null && e !is ProxyRefusedException && localNetFault(e)) {
                        tunnelDownUntil.putIfAbsent(up.key, SystemClock.elapsedRealtime() + 8_000L) // v4.2.11: наша сеть, не сервер
                    } else if (kind == 1 && up != null) {
                        if (e !is ProxyRefusedException || e.code == 407) tunnelDownUntil[up.key] = SystemClock.elapsedRealtime() + 180_000L
                        else if (e.code in 500..599) tunnelDownUntil[up.key] = SystemClock.elapsedRealtime() + 60_000L
                        if (SystemClock.elapsedRealtime() - (appTunnelFailLoggedAt[up.key] ?: 0L) > 30_000L) {
                            appTunnelFailLoggedAt[up.key] = SystemClock.elapsedRealtime()
                            com.winwasm64.vpn.VpnLog.w("Браузер", "автотуннель: ${up.key} не отвечает (${e.message ?: e.javaClass.simpleName})")
                        }
                    }
                } finally {
                    results.add(RaceResult(r, kind, up)) // сначала результат, потом счётчик — чтобы pending==0 означало «всё в очереди»
                    pending.decrementAndGet()
                    if (r != null && won.get()) closeQuietly(r.first) // гонка уже выиграна/завершена — лишний сокет закрываем
                }
            }
        }

        val t0 = SystemClock.elapsedRealtime()
        val deadline = t0 + 9_500L
        // v4.10.2: ступень «сервер сессии» — прошлый победитель один, с форой
        val sticky = stickyUp?.takeIf { s -> t0 - stickyAt < 20 * 60_000L && !isDown(s) && (tunnelDownUntil[s.key] ?: 0L) <= t0 }
        val stickyKind = if (sticky != null && proxies.any { it.key == sticky.key }) 0 else 1
        if (sticky != null) launch(stickyKind, sticky) { if (sticky.type == "SOCKS5") viaSocks5(sticky, host, port) else viaHttpProxy(sticky, host, port, url) }
        // ступень 0: серверы расширения
        val live = proxies.filter { !isDown(it) && it.key != sticky?.key }.take(2)
        var liveStarted = sticky == null
        if (liveStarted) for (p in live) launch(0, p) { if (p.type == "SOCKS5") viaSocks5(p, host, port) else viaHttpProxy(p, host, port, url) }
        val shift = if (sticky != null) 1_200L else 0L
        val liveAt = t0 + shift
        val stage1At = if (live.isEmpty() && sticky == null) t0 else t0 + 1_500L + shift
        var stage2At = if (live.isEmpty() && sticky == null) t0 + 3_000L else t0 + 5_000L + shift
        var autoStarted = false
        var directStarted = false
        // v4.2.5: если DNS провайдера подменяет адреса (все заблокированные сайты «резолвятся» в один IP фильтра), прямой путь
        // соединяется мгновенно и выигрывал гонку — а дальше страница ломалась. Для таких сайтов прямой путь не используем вовсе.
        val poisonedF = pool.submit<Boolean> { try { dnsSuspect(host) } catch (_: Exception) { false } }
        fun poisonedNow(): Boolean = poisonedF.isDone && (try { poisonedF.get() } catch (_: Exception) { false })
        while (true) {
            val poisoned = poisonedNow()
            val now = SystemClock.elapsedRealtime()
            if (!liveStarted && (now >= liveAt || pending.get() == 0)) {
                liveStarted = true
                for (p in live) launch(0, p) { if (p.type == "SOCKS5") viaSocks5(p, host, port) else viaHttpProxy(p, host, port, url) }
            }
            if (!autoStarted && liveStarted && (now >= stage1At || pending.get() == 0)) {
                autoStarted = true
                for (p in autoTunnelCandidates(3, host)) launch(1, p) { viaHttpProxy(p, host, port, url) }
                if (pending.get() == 0) stage2At = now
            }
            if (!directStarted && autoStarted && !poisoned && (now >= stage2At || pending.get() == 0)) {
                directStarted = true
                launch(2, null) { direct(host, port, url) }
            }
            if (now >= deadline || (pending.get() == 0 && results.isEmpty() && (directStarted || poisoned))) {
                won.set(true)
                drainAndClose(results)
                return null
            }
            val nextTick = minOf(deadline, if (!liveStarted) liveAt else if (!autoStarted) stage1At else if (!directStarted) stage2At else deadline)
            val r = results.poll(maxOf(20L, nextTick - now), TimeUnit.MILLISECONDS) ?: continue
            if (r.sock == null) {
                if (sticky != null && r.up?.key == sticky.key) { stickyUp = null } // v4.10.4: сервер сессии не ответил — забываем его
                continue
            }
            val sock = r.sock
            won.set(true)
            drainAndClose(results)
            when (r.kind) {
                0 -> { noteUpstreamOk(r.up!!, host); setSticky(r.up) }
                1 -> { noteAutoTunnel(r.up!!, host); setSticky(r.up) }
                else -> if (!hasDirectInRoute) noteFallback(host)
            }
            return sock
        }
    }

    private fun drainAndClose(q: LinkedBlockingQueue<RaceResult>) {
        while (true) {
            val x = q.poll() ?: return
            x.sock?.let { closeQuietly(it.first) }
        }
    }

    private fun autoTunnelCandidates(n: Int, host: String? = null): List<Upstream> {
        if (tunnelServers.isEmpty()) refreshAutoTunnel(control?.pacText)
        val now = SystemClock.elapsedRealtime()
        if (now - tunnelPingAt > 600_000L) {
            tunnelPingAt = now
            pool.execute { pingTunnelServers() }
        }
        // v4.2.5: те же измеренные серверы, что и для приложений (CONNECT-задержка/скорость), а не просто TCP-пинг
        // страну здесь не учитываем: это резерв, когда серверы страны из PAC уже не ответили — нужен самый быстрый
        return rankTunnelServers(null, now, host?.let { destKey(it) }, useCountry = false).take(n)
    }

    // ---- v4.2.5: подмена DNS провайдером ----
    private val poisonIps: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>()).apply { add("194.221.250.50") }
    private val ipSites = ConcurrentHashMap<String, MutableSet<String>>()
    private val poisonLogged = ConcurrentHashMap<String, Boolean>()

    /** Похоже ли, что системный DNS отдаёт для [host] адрес фильтра: известный IP подмены или один IP у ≥3 разных сайтов из PAC-маршрута. */
    private fun dnsSuspect(host: String): Boolean {
        if (host.all { it.isDigit() || it == '.' }) return poisonIps.contains(host)
        val ips = try { InetAddress.getAllByName(host).mapNotNull { it.hostAddress } } catch (_: Exception) { return false }
        val site = destKey(host)
        var suspect = false
        for (ip in ips) {
            if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("127.") || ip.contains(':')) continue
            if (poisonIps.contains(ip)) { suspect = true; continue }
            val set = ipSites.getOrPut(ip) { java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>()) }
            set.add(site)
            if (set.size >= 3) {
                poisonIps.add(ip)
                suspect = true
            }
        }
        if (suspect && poisonLogged.putIfAbsent(site, true) == null) {
            com.winwasm64.vpn.VpnLog.w("Браузер", "DNS провайдера подменяет адрес $host → ${ips.joinToString()} (фильтр); прямой путь к нему отключён — только через Browsec")
        }
        return suspect
    }

    private fun noteAutoTunnel(up: Upstream, host: String) {
        val t = SystemClock.elapsedRealtime()
        lastAutoTunnel = up.key
        lastAutoTunnelAt = t
        if (t - (tunnelLoggedAt[host] ?: 0L) > 60_000L) {
            tunnelLoggedAt[host] = t
            com.winwasm64.vpn.VpnLog.ok("Браузер", "$host → автотуннель через ${describe(up)}")
        }
    }

    private fun direct(host: String, port: Int, url: String): Pair<Socket, InputStream> {
        if (BrowserDirectRepeater.enabled) {
            val rp = BrowserDirectRepeater.ensureServerRunning()
            if (rp > 0) return viaHttpProxy(Upstream("HTTP", "127.0.0.1", rp), host, port, url, direct = true)
        }
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 15_000)
        s.tcpNoDelay = true
        return s to s.getInputStream()
    }

    private fun openToProxy(up: Upstream, readTimeoutMs: Int = 5_000): Socket {
        val raw = Socket()
        raw.connect(InetSocketAddress(up.host, up.port), 4_000)
        // v4.1.1: WebView ждёт ответ на CONNECT ~10 с — зависший сервер должен отвалиться раньше; локальному репитеру даём больше
        raw.soTimeout = if (up.host == "127.0.0.1") 15_000 else readTimeoutMs
        raw.tcpNoDelay = true
        if (up.type != "HTTPS") return raw
        try {
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, up.host, up.port, true) as SSLSocket
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(up.host, ssl.session)) {
                closeQuietly(ssl)
                throw SSLPeerUnverifiedException("Сертификат прокси не соответствует ${up.host}")
            }
            return ssl
        } catch (e: Exception) {
            closeQuietly(raw) // v4.1.1: зависшее TLS-рукопожатие раньше оставляло дескриптор открытым
            throw e
        }
    }

    private fun statusCode(head: ByteArray): Int {
        val line = String(head, Charsets.ISO_8859_1).substringBefore("\r\n")
        return line.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun realmOf(head: ByteArray): String {
        val text = String(head, Charsets.ISO_8859_1)
        val m = Regex("(?im)^proxy-authenticate:.*realm=\"([^\"]*)\"").find(text)
        return m?.groupValues?.getOrNull(1) ?: ""
    }

    private fun basic(c: Pair<String, String>): String =
        Base64.encodeToString("${c.first}:${c.second}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun viaHttpProxy(up: Upstream, host: String, port: Int, url: String, direct: Boolean = false, readTimeoutMs: Int = 5_000, allowWarm: Boolean = false): Pair<Socket, InputStream> {
        var attempt = 0
        var warmTried = false
        while (true) {
            val used = creds[up.key]
            // v4.4.1: заранее открытое TLS-соединение к серверу (тёплый пул) — экономит TCP+TLS-рукопожатие (~0,3–1 с) на каждом CONNECT
            val warm = if (allowWarm && !warmTried) takeWarm(up) else null
            warmTried = true
            val fromWarm = warm != null
            val s = warm ?: openToProxy(up, readTimeoutMs)
            if (fromWarm) try { s.soTimeout = readTimeoutMs } catch (_: Exception) {}
            try {
            val sb = StringBuilder()
                .append("CONNECT ").append(host).append(':').append(port).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append(':').append(port).append("\r\n")
                .append("User-Agent: ").append(ExtensionStore.DESKTOP_UA).append("\r\n")
                .append("Proxy-Connection: keep-alive\r\n")
            if (used != null) sb.append("Proxy-Authorization: Basic ").append(basic(used)).append("\r\n")
            if (direct) sb.append(DIRECT_MARK).append(": 1\r\n")
            sb.append("\r\n")
            val out = s.getOutputStream()
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val inp = BufferedInputStream(s.getInputStream())
            val head = try { readHead(inp) } catch (e: IOException) {
                closeQuietly(s)
                if (fromWarm) { warmDead.incrementAndGet(); continue } // тёплое соединение умерло — пробуем свежее
                throw e
            }
            if (head == null) {
                closeQuietly(s)
                if (fromWarm) { warmDead.incrementAndGet(); continue }
                throw IOException("Прокси ${up.key} закрыл соединение")
            }
            val code = statusCode(head)
            if (code == 200) {
                s.soTimeout = 0
                if (allowWarm) scheduleWarm(up)
                return s to inp
            }
            closeQuietly(s)
            if (code == 407 && attempt == 0) {
                attempt++
                credentialsFor(up, realmOf(head), url, used) ?: throw IOException("Прокси ${up.key} требует логин и пароль")
                continue
            }
            throw ProxyRefusedException(up, code)
            } catch (e: Exception) {
                // v4.1.1: главная утечка 4.0.x–4.1.0 — при «Read timed out» сокет к прокси не закрывался; десятки таких за
                // минуту YouTube исчерпывали дескрипторы, accept() локального прокси падал, WebView получал
                // ERR_PROXY_CONNECTION_FAILED
                closeQuietly(s)
                throw e
            }
        }
    }

    /** Прокси ответил, но отказал (403/405 на порт и т.п.) — сервер жив, «вниз» его не отправляем. */
    class ProxyRefusedException(up: Upstream, val code: Int) : IOException("Прокси ${up.key} ответил HTTP $code")

    /** Один запрос пароля на прокси одновременно: остальные соединения ждут его результата. */
    private fun credentialsFor(up: Upstream, realm: String, url: String, failed: Pair<String, String>?): Pair<String, String>? {
        val current = creds[up.key]
        if (current != null && current != failed) return current
        // Browsec использует одни учётные данные на все свои серверы — пробуем известные, не дёргая расширение
        val shared = sharedCreds
        if (shared != null && shared != failed && shared != current) {
            creds[up.key] = shared
            return shared
        }
        val mine = CountDownLatch(1)
        val other = authInFlight.putIfAbsent(up.key, mine)
        if (other != null) {
            other.await(25, TimeUnit.SECONDS)
            return creds[up.key]
        }
        try {
            val c = askCredentials(up, realm, url)
            if (c != null) {
                creds[up.key] = c
                rememberSharedCreds(c)
            }
            return c
        } finally {
            authInFlight.remove(up.key)
            mine.countDown()
        }
    }

    private fun askCredentials(up: Upstream, realm: String, url: String): Pair<String, String>? {
        val c = control ?: return null
        val requester = authRequester ?: return null
        val details = JSONObject()
            .put("requestId", "omni-proxy-" + requestSeq.incrementAndGet())
            .put("url", url)
            .put("method", "GET")
            .put("frameId", 0)
            .put("parentFrameId", -1)
            .put("tabId", -1)
            .put("type", "main_frame")
            .put("timeStamp", System.currentTimeMillis().toDouble())
            .put("isProxy", true)
            .put("scheme", "basic")
            .put("realm", realm)
            .put("challenger", JSONObject().put("host", up.host).put("port", up.port))
            .put("statusCode", 407)
            .put("statusLine", "HTTP/1.1 407 Proxy Authentication Required")
        val latch = CountDownLatch(1)
        var result: Pair<String, String>? = null
        main.post {
            requester(c.extId, details) { r ->
                result = r
                latch.countDown()
            }
        }
        latch.await(20, TimeUnit.SECONDS)
        return result
    }

    private fun viaSocks5(up: Upstream, host: String, port: Int): Pair<Socket, InputStream> {
        val s = Socket()
        try {
        s.connect(InetSocketAddress(up.host, up.port), 4_000)
        s.soTimeout = 5_000
        val out = s.getOutputStream()
        val inp = s.getInputStream()
        out.write(byteArrayOf(5, 1, 0))
        out.flush()
        val hello = readExactly(inp, 2)
        if (hello[0] != 5.toByte() || hello[1] != 0.toByte()) {
            closeQuietly(s)
            throw IOException("SOCKS5 ${up.key}: метод аутентификации не принят")
        }
        val hb = host.toByteArray(Charsets.UTF_8)
        val req = ByteArrayOutputStream()
        req.write(byteArrayOf(5, 1, 0, 3, hb.size.toByte()))
        req.write(hb)
        req.write((port shr 8) and 0xFF)
        req.write(port and 0xFF)
        out.write(req.toByteArray())
        out.flush()
        val rep = readExactly(inp, 4)
        if (rep[1] != 0.toByte()) {
            closeQuietly(s)
            throw IOException("SOCKS5 ${up.key}: отказ ${rep[1]}")
        }
        val addrLen = when (rep[3].toInt()) {
            1 -> 4
            4 -> 16
            3 -> inp.read()
            else -> 0
        }
        readExactly(inp, addrLen + 2)
        s.soTimeout = 0
        return s to inp
        } catch (e: Exception) {
            closeQuietly(s)
            throw e
        }
    }

    private fun readExactly(inp: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(b, off, n - off)
            if (r < 0) throw IOException("Соединение закрыто")
            off += r
        }
        return b
    }

    private fun rewriteHead(text: String, originForm: Boolean, u: URL, auth: Pair<String, String>?, direct: Boolean = false): ByteArray {
        val lines = text.split("\r\n")
        val first = lines[0].split(' ')
        val target = if (originForm) u.file.ifEmpty { "/" } else first[1]
        val sb = StringBuilder().append(first[0]).append(' ').append(target).append(' ').append(first.getOrElse(2) { "HTTP/1.1" }).append("\r\n")
        for (i in 1 until lines.size) {
            val l = lines[i]
            if (l.isEmpty()) continue
            val name = l.substringBefore(':').trim().lowercase()
            if (name == "proxy-connection" || name == "proxy-authorization" || name == "connection" || name == "keep-alive" || name == DIRECT_MARK.lowercase()) continue
            sb.append(l).append("\r\n")
        }
        if (auth != null) sb.append("Proxy-Authorization: Basic ").append(basic(auth)).append("\r\n")
        if (direct) sb.append(DIRECT_MARK).append(": 1\r\n")
        sb.append("Connection: close\r\n\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun forwardHttp(client: Socket, cin: InputStream, head: String, u: URL, route: List<Upstream>) {
        val host = u.host
        val port = if (u.port > 0) u.port else 80
        for (up in route) {
            if (up.type != "DIRECT" && isDown(up)) continue
            var opened: Socket? = null
            try {
                val target: Socket
                val tin: InputStream
                val payload: ByteArray
                when (up.type) {
                    "DIRECT" -> {
                        if (BrowserDirectRepeater.enabled && BrowserDirectRepeater.ensureServerRunning() > 0) {
                            target = Socket()
                            target.connect(InetSocketAddress("127.0.0.1", BrowserDirectRepeater.activePort), 10_000)
                            payload = rewriteHead(head, false, u, null, direct = true)
                        } else {
                            target = Socket()
                            target.connect(InetSocketAddress(host, port), 15_000)
                            payload = rewriteHead(head, true, u, null)
                        }
                        tin = target.getInputStream()
                    }
                    "SOCKS5" -> {
                        val t = viaSocks5(up, host, port)
                        target = t.first
                        tin = t.second
                        payload = rewriteHead(head, true, u, null)
                    }
                    else -> {
                        target = openToProxy(up)
                        tin = target.getInputStream()
                        payload = rewriteHead(head, false, u, creds[up.key])
                    }
                }
                opened = target
                target.tcpNoDelay = true
                target.getOutputStream().write(payload)
                target.getOutputStream().flush()
                if (up.type != "DIRECT") noteUpstreamOk(up, host)
                pipe(client, cin, target, tin)
                return
            } catch (e: Exception) {
                closeQuietly(opened)
                if (up.type != "DIRECT") {
                    markDown(up, e)
                    reportError(up, e)
                }
            }
        }
        if (route.any { it.type != "DIRECT" }) {
            noteFallback(host)
            forwardHttp(client, cin, head, u, listOf(DIRECT))
            return
        }
        com.winwasm64.vpn.VpnLog.e("Браузер", "не удалось открыть $host:$port")
        respond(client, 502, "Proxy Error")
    }

    private fun pipe(a: Socket, aIn: InputStream, b: Socket, bIn: InputStream) {
        try {
            a.soTimeout = 300_000
            b.soTimeout = 300_000
        } catch (_: Exception) {
        }
        // v4.2.8: getOutputStream() на уже закрытом сокете бросал SocketException вне try — ронял весь процесс
        pool.execute {
            try { copy(bIn, a.getOutputStream()) } catch (_: Exception) {}
            closeQuietly(a)
            closeQuietly(b)
        }
        try { copy(aIn, b.getOutputStream()) } catch (_: Exception) {}
        closeQuietly(a)
        closeQuietly(b)
    }

    private fun copy(i: InputStream, o: OutputStream) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
                o.flush()
            }
        } catch (_: Exception) {
        }
    }

    @Volatile
    private var lastFallbackToast = 0L

    private val fallbackLoggedAt = ConcurrentHashMap<String, Long>()

    private fun noteFallback(host: String) {
        val owner = ownerName ?: "расширения"
        val now = SystemClock.elapsedRealtime()
        if (now - (fallbackLoggedAt[host] ?: 0L) > 60_000L) {
            fallbackLoggedAt[host] = now
            com.winwasm64.vpn.VpnLog.i("Браузер", "$host открыт через встроенный обход DPI (прокси «$owner» не отвечает)")
        }
        if (now - lastFallbackToast > 60_000L) {
            lastFallbackToast = now
            main.post {
                try {
                    android.widget.Toast.makeText(appContext, "Прокси «$owner» не отвечает — сайты открываются через встроенный обход DPI", android.widget.Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                }
            }
        }
    }

    // v4.0.4: «предохранитель» — не ответивший сервер прокси расширения пропускаем 3 мин и дальше, пока фоновая проверка не пройдёт
    private val downUntil = ConcurrentHashMap<String, Long>()
    private val probing = ConcurrentHashMap<String, Boolean>()

    private fun isDown(up: Upstream): Boolean {
        if (ownerDown()) {
            maybeProbeOwner(up)
            return true
        }
        val until = downUntil[up.key] ?: return false
        if (SystemClock.elapsedRealtime() < until) return true
        if (probing.putIfAbsent(up.key, true) == null) {
            pool.execute {
                val ok = try {
                    probeUpstream(up)
                } catch (_: Exception) {
                    false
                }
                if (ok) {
                    downUntil.remove(up.key)
                    com.winwasm64.vpn.VpnLog.ok("Браузер", "прокси расширения ${up.key} снова отвечает")
                } else {
                    downUntil[up.key] = SystemClock.elapsedRealtime() + 180_000L
                }
                probing.remove(up.key)
            }
        }
        return true
    }

    // v4.0.5: предохранитель на всё расширение — Browsec из РФ перебирает серверы, и каждый новый ждали бы по 8 с
    private val ownerFails = java.util.concurrent.ConcurrentLinkedDeque<Long>()
    @Volatile
    private var ownerDownUntil = 0L
    @Volatile
    private var ownerProbeAt = 0L

    private fun ownerDown(): Boolean = SystemClock.elapsedRealtime() < ownerDownUntil

    private fun maybeProbeOwner(up: Upstream) {
        val now = SystemClock.elapsedRealtime()
        if (now - ownerProbeAt < 60_000L) return
        ownerProbeAt = now
        pool.execute {
            val ok = try {
                probeUpstream(up)
            } catch (_: Exception) {
                false
            }
            if (ok) {
                ownerDownUntil = 0L
                ownerFails.clear()
                downUntil.remove(up.key)
                com.winwasm64.vpn.VpnLog.ok("Браузер", "прокси расширения «${ownerName ?: "?"}» снова отвечает (${up.key})")
            }
        }
    }

    // v4.0.6: удачные соединения через прокси расширения — сбрасывают предохранитель и видны в журнале
    @Volatile
    private var lastUpstreamOkAt = 0L
    @Volatile
    var lastWorkingUpstream: String? = null
        private set
    private val okLoggedAt = ConcurrentHashMap<String, Long>()

    private fun noteUpstreamOk(up: Upstream, host: String) {
        val now = SystemClock.elapsedRealtime()
        lastUpstreamOkAt = now
        lastWorkingUpstream = up.key
        downUntil.remove(up.key)
        if (ownerDown()) {
            ownerDownUntil = 0L
            ownerFails.clear()
        }
        if (now - (okLoggedAt[up.key] ?: 0L) > 300_000L) {
            okLoggedAt[up.key] = now
            com.winwasm64.vpn.VpnLog.ok("Браузер", "$host → через прокси «${ownerName ?: "расширения"}» ${up.key}")
        }
    }

    private fun markDown(up: Upstream, e: Exception) {
        if (e is ProxyRefusedException && e.code in 500..599) {
            // v4.2.7: 502/503/504 — сервер перегружен; минуту его не трогаем (раньше считался «живым» и выбирался снова и снова)
            val now = SystemClock.elapsedRealtime()
            if (downUntil.put(up.key, now + 60_000L) == null) {
                com.winwasm64.vpn.VpnLog.w("Браузер", "прокси расширения ${up.key} перегружен (HTTP ${e.code}) — минуту идём через другие серверы Browsec")
            }
            return
        }
        if (e is ProxyRefusedException && e.code != 407) return // сервер отвечает — просто отказал этому запросу
        if (localNetFault(e)) { downUntil.putIfAbsent(up.key, SystemClock.elapsedRealtime() + 8_000L); return } // v4.2.11
        val now = SystemClock.elapsedRealtime()
        val reason = e.message ?: e.javaClass.simpleName
        if (downUntil.put(up.key, now + 180_000L) == null) {
            com.winwasm64.vpn.VpnLog.w("Браузер", "прокси расширения ${up.key} не отвечает ($reason) — 3 мин идём через другие серверы Browsec")
        }
        ownerFails.addLast(now)
        while (ownerFails.isNotEmpty() && now - (ownerFails.peekFirst() ?: now) > 180_000L) ownerFails.pollFirst()
        // v4.0.6: «всё расширение не работает» — только если через него ничего не прошло 2 мин (служебные серверы Browsec не в счёт)
        if (ownerFails.size >= 2 && !ownerDown() && now - lastUpstreamOkAt > 120_000L) {
            ownerDownUntil = now + 300_000L
            ownerProbeAt = now
            com.winwasm64.vpn.VpnLog.w("Браузер", "прокси расширения «${ownerName ?: "?"}» не работает (серверы не отвечают) — 5 мин все сайты сразу идут через встроенный обход DPI, без ожидания")
        }
    }

    /** Есть ли сейчас «упавший» сервер прокси расширения (для карточки статуса). */
    fun isExtensionProxyDown(): Boolean = control != null &&
        (ownerDown() || (downUntil.isNotEmpty() && SystemClock.elapsedRealtime() - lastUpstreamOkAt > 120_000L))

    private fun probeUpstream(up: Upstream): Boolean {
        val p = if (up.type == "SOCKS5") viaSocks5(up, "www.gstatic.com", 443) else viaHttpProxy(up, "www.gstatic.com", 443, "https://www.gstatic.com/")
        closeQuietly(p.first)
        return true
    }

    // v4.0.7: автотуннель — бесплатные серверы Browsec (LV/LT/DE…) из PAC расширения; если выбранный расширением сервер
    // (например, Греция) не отвечает, сайт идёт через самый быстрый рабочий из них, а не через обход DPI
    private val FREE_BROWSEC = setOf("lv", "lt", "de", "nl", "uk", "sg", "us")
    private val FALLBACK_TUNNEL = listOf(
        "lv193.staticvaultcdn.org:443", "lv197.rapidstaticserve.cc:443", "lv117.staticvaultcdn.org:443",
        "lt40.staticvaultcdn.org:443", "lt36.staticvaultcdn.org:443", "lt44.staticvaultcdn.xyz:443",
        "de57.rapidstaticserve.cc:443", "de62.staticvaultcdn.xyz:443", "de67.staticvaultcdn.org:443"
    )
    @Volatile
    private var tunnelServers: List<Upstream> = emptyList()
    private val tunnelPing = ConcurrentHashMap<String, Long>()
    private val tunnelDownUntil = ConcurrentHashMap<String, Long>()
    private val tunnelLoggedAt = ConcurrentHashMap<String, Long>()
    @Volatile
    private var tunnelPingAt = 0L
    @Volatile
    var lastAutoTunnel: String? = null
        private set
    @Volatile
    private var lastAutoTunnelAt = 0L

    /** Был ли автотуннель за последние 2 минуты (для карточки статуса). */
    fun autoTunnelRecent(): Boolean = SystemClock.elapsedRealtime() - lastAutoTunnelAt < 120_000L

    private fun refreshAutoTunnel(pac: String?) {
        val found = ArrayList<Upstream>()
        try {
            if (pac != null) {
                val marker = "const countries="
                val i = pac.indexOf(marker)
                val j = if (i >= 0) pac.indexOf(";const globalReturn", i) else -1
                if (i >= 0 && j > i) {
                    val o = JSONObject(pac.substring(i + marker.length, j))
                    val keys = o.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k.lowercase() !in FREE_BROWSEC) continue
                        val v = o.opt(k)
                        val txt = when (v) {
                            is String -> v
                            is org.json.JSONArray -> (0 until v.length()).joinToString(";") { idx -> v.optString(idx) }
                            else -> ""
                        }
                        for (part in txt.split(';')) {
                            val t = part.trim().split(Regex("\\s+"))
                            if (t.size != 2) continue
                            val https = t[0].equals("HTTPS", true)
                            if (!https && !t[0].equals("PROXY", true)) continue
                            val hp = splitHostPort(t[1], if (https) 443 else 80) ?: continue
                            found.add(Upstream(if (https) "HTTPS" else "HTTP", hp.first, hp.second))
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        if (found.isEmpty()) {
            for (srv in FALLBACK_TUNNEL) splitHostPort(srv, 443)?.let { found.add(Upstream("HTTPS", it.first, it.second)) }
        }
        tunnelServers = found.distinctBy { it.key }
        persistTunnelServers(tunnelServers)
        tunnelPingAt = SystemClock.elapsedRealtime()
        pool.execute { pingTunnelServers() }
    }

    /** Страны серверов Browsec, доступных для туннеля приложений, с лучшим пингом (мс); Long.MAX_VALUE — не отвечает. */
    fun tunnelCountries(): Map<String, Long> {
        val m = LinkedHashMap<String, Long>()
        for (up in tunnelServers) {
            val cc = up.host.takeWhile { it.isLetter() }.take(2).lowercase()
            if (cc.length < 2) continue
            val ping = tunnelPing[up.key] ?: continue
            val p = if (ping == Long.MAX_VALUE) ping else (tunnelConnectMs[up.key] ?: ping)
            val cur = m[cc]
            if (cur == null || p < cur) m[cc] = p
        }
        return m
    }

    private fun logTunnelCountries() {
        val m = tunnelCountries()
        if (m.isEmpty()) return
        val ok = m.filter { it.value != Long.MAX_VALUE }.entries.sortedBy { it.value }.joinToString(", ") { "${it.key.uppercase()} ${it.value} мс" }
        val bad = m.filter { it.value == Long.MAX_VALUE }.keys.joinToString(", ") { it.uppercase() }
        com.winwasm64.vpn.VpnLog.i("Прил", "страны Browsec для приложений: " + ok.ifEmpty { "нет отвечающих" } + (if (bad.isNotEmpty()) " · не отвечают: $bad" else ""))
    }

    private fun pingTunnelServers() {
        // v4.2.7: параллельно (раньше по очереди — до 70 с, и всё это время выбор шёл вслепую)
        val list = tunnelServers.take(32)
        val latch = CountDownLatch(list.size)
        for (up in list) {
            pool.execute {
                val sock = Socket()
                try {
                    val t0 = SystemClock.elapsedRealtime()
                    sock.connect(InetSocketAddress(up.host, up.port), 3000)
                    tunnelPing[up.key] = SystemClock.elapsedRealtime() - t0
                } catch (e: Exception) {
                    if (!localNetFault(e)) tunnelPing[up.key] = Long.MAX_VALUE // v4.2.11: без сети пинг не «провал сервера»
                } finally {
                    closeQuietly(sock)
                    latch.countDown()
                }
            }
        }
        latch.await(4, TimeUnit.SECONDS)
        tunnelMeasuredAt = SystemClock.elapsedRealtime()
        measureTunnelServers()
        logTunnelCountries()
    }

    // v4.1.0: туннель Browsec для приложений из списка VPN (настоящая смена IP, как у браузера с расширением)

    /** Есть ли чем туннелировать приложения: расширение с прокси включено или уже известны его учётные данные. */
    fun appTunnelAvailable(): Boolean = control != null || sharedCreds != null || creds.isNotEmpty()

    private val appTunnelLoggedAt = ConcurrentHashMap<String, Long>()

    /**
     * CONNECT host:port через рабочий сервер Browsec; предпочитаем серверы страны профиля приложения ([preferCountry], например "LT"),
     * затем — по пингу. Возвращает (сокет, поток чтения, имя сервера) или null, если ни один сервер не ответил.
     */
    // v4.2.15: адресат, до которого не достучались ≥2 разных сервера за 2 мин, 3 минуты не пробуем вовсе — отказ сразу,
    // чтобы Telegram не ждал 12 с на каждом мёртвом резервном адресе, а шёл на следующий DC
    private val deadDestServers = ConcurrentHashMap<String, MutableSet<String>>()
    private val deadDestAt = ConcurrentHashMap<String, Long>()
    private val deadDestUntil = ConcurrentHashMap<String, Long>()

    private fun noteDeadDest(host: String, port: Int, server: String) {
        val k = "$host:$port"
        val now = SystemClock.elapsedRealtime()
        if (now - (deadDestAt[k] ?: 0L) > 120_000L) deadDestServers.remove(k)
        deadDestAt[k] = now
        val set = deadDestServers.getOrPut(k) { java.util.Collections.synchronizedSet(HashSet()) }
        set.add(server)
        if (set.size >= 2 && (deadDestUntil[k] ?: 0L) < now) {
            deadDestUntil[k] = now + 180_000L
            com.winwasm64.vpn.VpnLog.w("Прил", "адресат $k недоступен через ${set.size} сервера Browsec — 3 мин отказываем сразу, приложение пойдёт на другой адрес")
        }
    }

    /** Адресат недавно признан недоступным через туннель (см. [noteDeadDest]). */
    fun isDeadDest(host: String, port: Int): Boolean = (deadDestUntil["$host:$port"] ?: 0L) > SystemClock.elapsedRealtime()

    fun noteDestAlive(host: String, port: Int) { val k = "$host:$port"; deadDestUntil.remove(k); deadDestServers.remove(k) }

    // ===================== v4.4.3: Psiphon как ещё один «сервер» пула =====================
    @Volatile private var psiphonUp: Upstream? = null

    /** Локальный HTTP-прокси Psiphon, если он подключён (ключ 127.0.0.1:8921). */
    fun psiphonUpstream(): Upstream? {
        val pb = com.winwasm64.vpn.PsiphonBackup
        if (!pb.ready()) return null
        val cur = psiphonUp
        if (cur != null && cur.port == pb.httpPort) return cur
        return Upstream("HTTP", "127.0.0.1", pb.httpPort).also { psiphonUp = it }
    }

    fun isPsiphonKey(key: String): Boolean = key.startsWith("127.0.0.1:") && key == psiphonUp?.key

    /** Короткое имя сервера для журнала: lv115 / Psiphon. */
    fun shortName(key: String): String = if (isPsiphonKey(key)) "Psiphon" else key.substringBefore('.')
    fun describe(up: Upstream): String = if (isPsiphonKey(up.key)) "Psiphon (${up.key})" else "Browsec ${up.key}"

    private fun upstreamByKey(key: String): Upstream? =
        tunnelServers.firstOrNull { it.key == key } ?: psiphonUpstream()?.takeIf { it.key == key }

    /** Подмешиваем Psiphon к ранжированному списку по режиму. */
    private fun withPsiphon(list: List<Upstream>, forceFirst: Boolean = false): List<Upstream> {
        val ps = psiphonUpstream() ?: return list
        val now = SystemClock.elapsedRealtime()
        if ((tunnelDownUntil[ps.key] ?: 0L) > now) return list
        val mode = com.winwasm64.vpn.PsiphonBackup.mode
        if (mode == com.winwasm64.vpn.PsiphonBackup.MODE_OFF) return list
        val rest = list.filter { it.key != ps.key }
        val first = mode == com.winwasm64.vpn.PsiphonBackup.MODE_ALWAYS || forceFirst || rest.isEmpty() || poolOverloaded()
        return if (first) listOf(ps) + rest else rest + ps
    }

    fun onPsiphonConnected() {
        psiphonUpstream()?.let { tunnelDownUntil.remove(it.key); tunnelConnectMs.remove(it.key) }
    }

    private fun startPsiphonIfNeeded(why: String) {
        val ctx = (if (::appContext.isInitialized) appContext else null) ?: com.winwasm64.vpn.BrowsecVpnService.appContext ?: return
        try { com.winwasm64.vpn.PsiphonBackup.ensureStarted(ctx, why) } catch (_: Throwable) {}
    }

    // ===================== v4.4.1: тёплый пул TLS-соединений к серверу =====================
    private class WarmSock(val sock: Socket, val at: Long)
    private val warmPool = ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<WarmSock>>()
    private val warmFilling = ConcurrentHashMap<String, AtomicBoolean>()
    private val warmDead = AtomicInteger()
    private val warmUsed = AtomicInteger()
    private const val WARM_MAX_AGE_MS = 25_000L
    private const val WARM_PER_SERVER = 2

    private fun takeWarm(up: Upstream): Socket? {
        val q = warmPool[up.key] ?: return null
        while (true) {
            val w = q.poll() ?: return null
            if (SystemClock.elapsedRealtime() - w.at > WARM_MAX_AGE_MS || w.sock.isClosed) { closeQuietly(w.sock); continue }
            warmUsed.incrementAndGet()
            return w.sock
        }
    }

    /** Подогреваем до [WARM_PER_SERVER] соединений к серверу, за которым закреплено приложение (пока VPN приложений работает). */
    private fun scheduleWarm(up: Upstream) {
        if (!com.winwasm64.vpn.BrowsecVpnService.isRunning || com.winwasm64.vpn.BrowsecVpnService.isPaused) return
        if (appAffinity.values.none { it.first == up.key }) return
        val flag = warmFilling.getOrPut(up.key) { AtomicBoolean(false) }
        if (!flag.compareAndSet(false, true)) return
        pool.execute {
            try {
                val q = warmPool.getOrPut(up.key) { java.util.concurrent.ConcurrentLinkedQueue() }
                // выбрасываем устаревшие
                val it = q.iterator()
                while (it.hasNext()) { val w = it.next(); if (SystemClock.elapsedRealtime() - w.at > WARM_MAX_AGE_MS || w.sock.isClosed) { it.remove(); closeQuietly(w.sock) } }
                var guard = 0
                while (q.size < WARM_PER_SERVER && guard++ < WARM_PER_SERVER && (tunnelDownUntil[up.key] ?: 0L) < SystemClock.elapsedRealtime()) {
                    try { q.add(WarmSock(openToProxy(up, 9_000), SystemClock.elapsedRealtime())) } catch (_: Exception) { break }
                }
            } finally { flag.set(false) }
        }
    }

    fun warmStats(): String = "тёплых использовано ${warmUsed.get()}, умерло ${warmDead.get()}"

    // ===================== v4.4.1: привязка приложения к серверу =====================
    // Адреса googlevideo (YouTube), CDN Telegram и т.п. привязаны к IP, с которого приложение получило ответ API:
    // если следующее соединение уйдёт через другой сервер Browsec — 403/обрыв и «буферизация» каждые несколько секунд.
    // Поэтому все соединения одного приложения идут через один сервер, пока он отвечает.
    private val appAffinity = ConcurrentHashMap<String, Pair<String, Long>>()  // пакет → (сервер, срок)
    private const val AFFINITY_TTL_MS = 20 * 60_000L

    fun affinityServer(pkg: String?): String? {
        if (pkg == null) return null
        val a = appAffinity[pkg] ?: return null
        val now = SystemClock.elapsedRealtime()
        if (a.second < now) { appAffinity.remove(pkg); return null }
        if ((tunnelDownUntil[a.first] ?: 0L) > now) {
            // v4.4.7: в час пик все серверы по очереди уходят в «паузу», и приложение прыгало lv115 → de83 → lv115 по 4 раза в секунду,
            // каждый раз переоткрывая соединения к видео, привязанным к IP. Если весь пул в паузе или сервер сменили <15 с назад —
            // держимся за текущий: медленный, но живой сервер лучше вечного переключения.
            val switchedAgo = now - (affinitySwitchedAt[pkg] ?: 0L)
            val poolDead = tunnelServers.isNotEmpty() && tunnelServers.all { (tunnelDownUntil[it.key] ?: 0L) > now || isPsiphonKey(it.key) }
            if (poolDead || switchedAgo < 15_000L) return a.first
            return null
        }
        return a.first
    }
    private val affinitySwitchedAt = ConcurrentHashMap<String, Long>()
    private val affinityHoldLoggedAt = ConcurrentHashMap<String, Long>()

    private fun setAffinity(pkg: String?, serverKey: String, reason: String? = null) {
        if (pkg == null) return
        val now = SystemClock.elapsedRealtime()
        val cur = appAffinity[pkg]
        if (cur != null && cur.first != serverKey && cur.second > now && now - (affinitySwitchedAt[pkg] ?: 0L) < 15_000L) {
            // v4.4.8: не чаще одной смены сервера за 15 с на приложение — параллельные соединения в час пик
            // приносили разных «победителей» и закрепление прыгало de83 → lv115 → de83 за одну секунду
            if (now - (affinityHoldLoggedAt[pkg] ?: 0L) > 60_000L) {
                affinityHoldLoggedAt[pkg] = now
                com.winwasm64.vpn.VpnLog.i("Прил", "${com.winwasm64.vpn.VpnLog.label(pkg)}: держим сервер ${shortName(cur.first)} (сменили <15 с назад), хотя ответил ${shortName(serverKey)}")
            }
            return
        }
        val prev = appAffinity.put(pkg, Pair(serverKey, now + AFFINITY_TTL_MS))
        if (prev?.first != serverKey) {
            affinitySwitchedAt[pkg] = now
            val who = com.winwasm64.vpn.VpnLog.label(pkg)
            com.winwasm64.vpn.VpnLog.i("Прил", if (prev == null || (prev.second < SystemClock.elapsedRealtime())) "$who: все соединения приложения закреплены за сервером ${shortName(serverKey)} (адреса видео/CDN привязаны к IP)"
                else "$who: сервер сменён ${shortName(prev.first)} → ${shortName(serverKey)} (${reason ?: "прежний не ответил"}) — приложение переоткроет соединения")
        }
    }

    fun clearAffinity(pkg: String?) { if (pkg != null) appAffinity.remove(pkg) }

    // ===================== v4.4.1: индикатор перегрузки пула =====================
    @Volatile private var overloadLoggedAt = 0L
    /** Сводка состояния пула: (серверов отвечает, всего измерено, лучший CONNECT мс). */
    fun poolHealth(): Triple<Int, Int, Long> {
        val now = SystemClock.elapsedRealtime()
        val measured = tunnelServers.filter { tunnelConnectMs.containsKey(it.key) || (tunnelDownUntil[it.key] ?: 0L) > now }
        val alive = measured.filter { (tunnelDownUntil[it.key] ?: 0L) < now && (tunnelConnectMs[it.key] ?: 9_000L) < 3_000L }
        val best = alive.minOfOrNull { tunnelConnectMs[it.key] ?: 9_000L } ?: (measured.minOfOrNull { tunnelConnectMs[it.key] ?: 9_000L } ?: 0L)
        return Triple(alive.size, measured.size, best)
    }

    /** true, если пул явно перегружен (час пик): отвечает меньше половины серверов или лучший CONNECT > 2,5 с. */
    fun poolOverloaded(): Boolean {
        val (alive, total, best) = poolHealth()
        if (total < 3) return false
        return alive * 2 < total || best > 2_500L
    }

    private fun maybeLogOverload() {
        val now = SystemClock.elapsedRealtime()
        if (now - overloadLoggedAt < 300_000L) return
        if (!poolOverloaded()) return
        overloadLoggedAt = now
        startPsiphonIfNeeded("серверы Browsec перегружены")
        val (alive, total, best) = poolHealth()
        com.winwasm64.vpn.VpnLog.w("Прил", "серверы Browsec перегружены: отвечают $alive из $total, лучший CONNECT $best мс — час пик бесплатного пула; видео может буферизоваться, это не ошибка приложения")
    }

    fun openAppTunnel(host: String, port: Int, preferCountry: String?, stickyKey: String? = null): Triple<Socket, InputStream, String>? {
        if (tunnelServers.isEmpty()) refreshAutoTunnel(control?.pacText)
        if (isDeadDest(host, port)) return null
        val now = SystemClock.elapsedRealtime()
        if (now - tunnelPingAt > 600_000L) {
            tunnelPingAt = now
            pool.execute { pingTunnelServers() }
        }
        val goodMeasured = tunnelConnectMs.count { it.value < 3_000L && (tunnelDownUntil[it.key] ?: 0L) < now }
        if (now - tunnelMeasuredAt > (if (goodMeasured < 3) 90_000L else 300_000L) && tunnelPing.isNotEmpty()) {
            tunnelMeasuredAt = now
            pool.execute { measureTunnelServers() }
        }
        val dest = destKey(host)
        // v4.2.12: четыре кандидата и хедж через 2,5 с (было 3 и 4 с) — Telegram сам рвёт соединение через ~8–10 с,
        // и при перегруженных серверах Browsec в час пик мы не успевали ответить ему вовремя
        var list = rankTunnelServers(preferCountry, now, dest).take(4)
        psiphonUpstream()?.let { ps -> if (list.none { it.key == ps.key }) { val full = rankTunnelServers(preferCountry, now, dest); if (full.any { it.key == ps.key }) list = list.take(3) + ps } }
        // v4.4.1: закреплённый за приложением сервер — первым кандидатом (хедж через 4 с вместо 2,5)
        // v4.4.2: пока сервер для приложения не выбран, выбирает его ПЕРВОЕ соединение, остальные параллельные ждут результата —
        // иначе 5 одновременных соединений YouTube выбирали 5 разных победителей и «закрепление» прыгало каждую секунду
        var stickyUp: Upstream? = affinityServer(stickyKey)?.let { k -> upstreamByKey(k) }
        var leaderLatch: CountDownLatch? = null
        if (stickyKey != null && stickyUp == null) {
            val mine = CountDownLatch(1)
            val other = affinityPending.putIfAbsent(stickyKey, mine)
            if (other != null) {
                try { other.await(8, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
                stickyUp = affinityServer(stickyKey)?.let { k -> upstreamByKey(k) }
            } else leaderLatch = mine
        }
        val stickyUpF: Upstream? = stickyUp
        try {
        if (stickyUpF != null) list = (listOf(stickyUpF) + list.filter { it.key != stickyUpF.key }).take(4)
        if (list.isEmpty()) return null
        maybeLogOverload()

        // v4.2.5: «хеджирование» — если сервер не ответил на CONNECT за 4 с, параллельно пробуем следующий; побеждает первый ответ.
        // Сервер запоминает задержку до конкретного адресата (Telegram DC и т.п.), и в следующий раз к нему идёт лучший для него сервер.
        class Res(val up: Upstream, val sock: Pair<Socket, InputStream>?, val err: Exception?)
        val results = LinkedBlockingQueue<Res>()
        val won = AtomicBoolean(false)
        var next = 0
        var pendingN = 0
        fun launch(up: Upstream) {
            pendingN++
            val inFlight = tunnelInFlight.getOrPut(up.key) { AtomicInteger() }
            inFlight.incrementAndGet()
            pool.execute {
                var r: Pair<Socket, InputStream>? = null
                var err: Exception? = null
                val t0 = SystemClock.elapsedRealtime()
                try {
                    r = viaHttpProxy(up, host, port, (if (port == 80) "http://" else "https://") + host + "/", readTimeoutMs = 9_000, allowWarm = stickyUpF != null && up.key == stickyUpF.key)
                    val ms = SystemClock.elapsedRealtime() - t0
                    noteConnectMs(up.key, ms)
                    noteDestMs(up.key, dest, ms)
                    noteDestAlive(host, port)
                    tunnelLastOk[up.key] = SystemClock.elapsedRealtime()
                } catch (e: Exception) {
                    err = e
                    handleAppTunnelError(up, e, host, port, dest)
                } finally {
                    inFlight.decrementAndGet()
                    if (r != null && won.get()) { closeQuietly(r.first); r = null }
                    results.add(Res(up, r, err))
                }
            }
        }
        launch(list[next++])
        val t0 = SystemClock.elapsedRealtime()
        val deadline = t0 + 12_000L
        var hedgeAt = t0 + (if (stickyUpF != null) 4_000L else 2_500L)
        var backup: Res? = null
        var stickyDone = false
        var stickyErr: String? = null
        fun accept(r: Res, switchReason: String?): Triple<Socket, InputStream, String> {
            won.set(true)
            while (true) { val late = results.poll() ?: break; late.sock?.let { closeQuietly(it.first) } }
            backup?.let { if (it !== r) it.sock?.let { s -> closeQuietly(s.first) } }
            backup = null
            val up = r.up
            lastAutoTunnel = up.key
            lastAutoTunnelAt = SystemClock.elapsedRealtime()
            if (stickyUpF == null || up.key == stickyUpF.key || switchReason != null) setAffinity(stickyKey, up.key, switchReason)
            val t = lastAutoTunnelAt
            if (t - (appTunnelLoggedAt[up.key] ?: 0L) > 120_000L) {
                appTunnelLoggedAt[up.key] = t
                com.winwasm64.vpn.VpnLog.ok("Прил", "приложения идут через ${describe(up)} (CONNECT ${tunnelConnectMs[up.key] ?: 0} мс)")
            }
            if (isPsiphonKey(up.key)) com.winwasm64.vpn.PsiphonBackup.lastUsedAt = SystemClock.elapsedRealtime()
            return Triple(r.sock!!.first, CountingInputStream(r.sock.second, up.key), up.key)
        }
        while (pendingN > 0) {
            val now2 = SystemClock.elapsedRealtime()
            if (now2 >= deadline) break
            if (backup != null && stickyUpF != null && !stickyDone && now2 - t0 >= STICKY_WAIT_MS) {
                stickyDone = true
                return accept(backup!!, "закреплённый ${shortName(stickyUpF.key)} не ответил за ${STICKY_WAIT_MS / 1000} с")
            }
            val r = results.poll(maxOf(20L, minOf(hedgeAt, deadline) - now2), TimeUnit.MILLISECONDS)
            if (r == null) {
                if (SystemClock.elapsedRealtime() >= hedgeAt) {
                    if (next < list.size) launch(list[next++])
                    hedgeAt = SystemClock.elapsedRealtime() + 2_500L
                }
                continue
            }
            pendingN--
            if (r.sock != null) {
                if (stickyUpF != null && r.up.key != stickyUpF.key && !stickyDone && SystemClock.elapsedRealtime() - t0 < STICKY_WAIT_MS) {
                    // закреплённый сервер ещё отвечает — чужой успех держим в запасе, адреса видео привязаны к IP закреплённого
                    if (backup == null) backup = r else closeQuietly(r.sock.first)
                    continue
                }
                if (stickyUpF != null && r.up.key == stickyUpF.key) stickyDone = true
                return accept(r, if (stickyUpF != null && r.up.key != stickyUpF.key) "закреплённый ${shortName(stickyUpF.key)} не ответил за ${(SystemClock.elapsedRealtime() - t0) / 1000} с" else null)
            } else {
                val e = r.err
                if (e is ProxyRefusedException && e.code != 407 && e.code !in 500..599) { won.set(true); backup?.sock?.let { closeQuietly(it.first) }; return null } // адресат/порт запрещён — идём напрямую
                if (stickyUpF != null && r.up.key == stickyUpF.key) {
                    stickyDone = true
                    stickyErr = e?.message ?: e?.javaClass?.simpleName ?: "ошибка"
                    val b = backup
                    if (b != null) return accept(b, "закреплённый ${shortName(stickyUpF.key)} не ответил: ${stickyErr.take(60)}")
                }
                if (next < list.size && pendingN == 0) { launch(list[next++]); hedgeAt = SystemClock.elapsedRealtime() + 2_500L }
            }
        }
        val b = backup
        if (b != null) return accept(b, "закреплённый ${stickyUpF?.key?.let { shortName(it) }} не ответил: ${stickyErr?.take(60) ?: "таймаут"}")
        won.set(true)
        return null
        } finally {
            if (leaderLatch != null) { affinityPending.remove(stickyKey!!, leaderLatch); leaderLatch.countDown() }
        }
    }

    private const val STICKY_WAIT_MS = 9_000L
    private val affinityPending = ConcurrentHashMap<String, CountDownLatch>()

    /** v4.3.5: все известные серверы автотуннеля, лучшие первыми (для подбора сервера по IP в ссылке googlevideo). */
    fun tunnelServerKeysRanked(): List<String> {
        if (tunnelServers.isEmpty()) refreshAutoTunnel(control?.pacText)
        val now = SystemClock.elapsedRealtime()
        val ranked = rankTunnelServers(null, now, null, useCountry = false).map { it.key }
        val rest = tunnelServers.map { it.key }.filter { it !in ranked }
        return ranked + rest
    }

    /**
     * v4.3.1: туннель к [host]:[port] через КОНКРЕТНЫЙ сервер Browsec [serverKey] (host:port).
     * Нужен загрузчику видео: адреса googlevideo привязаны к IP, с которого получен ответ плеера, — все соединения
     * одной загрузки должны идти через один и тот же сервер. Возвращает null, если сервер неизвестен/не ответил.
     */
    /** v4.3.8: хост → (сервер Browsec, срок): соединения браузера к этому хосту идут строго через указанный сервер. */
    private val hostPins = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    private val hostPinFailLoggedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun pinHost(host: String, serverKey: String, ttlMs: Long) {
        hostPins[host.lowercase()] = Pair(serverKey, SystemClock.elapsedRealtime() + ttlMs)
    }

    fun unpinHost(host: String) { hostPins.remove(host.lowercase()) }

    private fun pinnedConnect(host: String, port: Int): Pair<Socket, InputStream>? {
        val pin = hostPins[host.lowercase()] ?: return null
        val now = SystemClock.elapsedRealtime()
        if (pin.second < now) { hostPins.remove(host.lowercase()); return null }
        if ((tunnelDownUntil[pin.first] ?: 0L) > now) return null
        val r = openPinnedTunnel(host, port, pin.first)
        if (r == null && now - (hostPinFailLoggedAt[host] ?: 0L) > 30_000L) {
            hostPinFailLoggedAt[host] = now
            com.winwasm64.vpn.VpnLog.w("Браузер", "закреплённый сервер ${pin.first.substringBefore('.')} не открыл $host — обычный маршрут")
        }
        return r
    }

    fun openPinnedTunnel(host: String, port: Int, serverKey: String): Pair<Socket, InputStream>? {
        if (tunnelServers.isEmpty()) refreshAutoTunnel(control?.pacText)
        val up = upstreamByKey(serverKey) ?: return null
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val r = viaHttpProxy(up, host, port, (if (port == 80) "http://" else "https://") + host + "/", readTimeoutMs = 12_000)
            noteConnectMs(up.key, SystemClock.elapsedRealtime() - t0)
            tunnelLastOk[up.key] = SystemClock.elapsedRealtime()
            r
        } catch (e: Exception) {
            handleAppTunnelError(up, e, host, port, destKey(host))
            null
        }
    }

    private fun handleAppTunnelError(up: Upstream, e: Exception, host: String, port: Int, dest: String) {
        val t = SystemClock.elapsedRealtime()
        if (e is ProxyRefusedException) {
            if (e.code == 407) {
                tunnelDownUntil[up.key] = t + 60_000L
                com.winwasm64.vpn.VpnLog.w("Прил", "сервер Browsec ${up.key} не принял учётные данные — включите расширение Browsec в браузере, чтобы обновить их")
            } else if (e.code in 500..599) {
                tunnelDownUntil[up.key] = t + 60_000L
                if (t - (appTunnelFailLoggedAt[up.key] ?: 0L) > 60_000L) {
                    appTunnelFailLoggedAt[up.key] = t
                    com.winwasm64.vpn.VpnLog.w("Прил", "сервер Browsec ${up.key} перегружен (HTTP ${e.code}) — минуту пропускаем")
                }
            }
            return
        }
        if (localNetFault(e)) { tunnelDownUntil.putIfAbsent(up.key, t + 8_000L); return } // v4.2.11
        val readTimeout = e is java.net.SocketTimeoutException && (e.message ?: "").contains("Read timed out")
        if (readTimeout && tunnelServerAlive(up)) {
            // сервер жив (CONNECT к Google проходит) — это адресат не ответил ему вовремя; запоминаем, что к этому адресату через него медленно
            // v4.2.15: штрафуем только пару «сервер → адресат»; общий рейтинг сервера не трогаем (раньше мёртвый адрес
            // Telegram 91.105.192.100 за несколько попыток «утапливал» лучший lv115, и приложения уходили на медленный de83)
            noteDestMs(up.key, dest, 9_000L)
            noteDeadDest(host, port, up.key)
            if (t - (appTunnelFailLoggedAt[up.key] ?: 0L) > 60_000L) {
                appTunnelFailLoggedAt[up.key] = t
                com.winwasm64.vpn.VpnLog.w("Прил", "сервер Browsec ${up.key} не достучался до $host:$port за 9 с (сам сервер отвечает) — к этому адресату пойдём через другой")
            }
        } else {
            tunnelDownUntil[up.key] = t + 180_000L
            if (t - (appTunnelFailLoggedAt[up.key] ?: 0L) > 60_000L) {
                appTunnelFailLoggedAt[up.key] = t
                com.winwasm64.vpn.VpnLog.w("Прил", "сервер ${describe(up)} не отвечает (${e.message ?: e.javaClass.simpleName}) — ${if (isPsiphonKey(up.key)) "1 мин" else "3 мин"} пропускаем")
            }
        }
    }

    // ---- v4.2.5: память «сервер → адресат»: Telegram DC, Google и т.п. доступны с разных серверов Browsec по-разному ----
    private val tunnelDestMs = ConcurrentHashMap<String, Long>()

    private fun destKey(host: String): String {
        val isIp = host.isNotEmpty() && host.all { it.isDigit() || it == '.' }
        if (isIp) return host.split('.').take(3).joinToString(".")
        val parts = host.lowercase().split('.')
        return if (parts.size >= 3 && parts[parts.size - 2].length <= 3) parts.takeLast(3).joinToString(".") else parts.takeLast(2).joinToString(".")
    }

    private fun noteDestMs(server: String, dest: String, ms: Long) {
        val k = "$server|$dest"
        val prev = tunnelDestMs[k]
        tunnelDestMs[k] = if (prev == null) ms else (prev * 0.5 + ms * 0.5).toLong()
    }

    private val tunnelProbeAt = ConcurrentHashMap<String, Long>()

    /** Жив ли сервер: недавний успех через него или быстрый CONNECT www.google.com:443 (не чаще раза в 30 с на сервер). */
    private fun tunnelServerAlive(up: Upstream): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - (tunnelLastOk[up.key] ?: 0L) < 30_000L) return true
        val lastProbe = tunnelProbeAt[up.key] ?: 0L
        if (now - lastProbe < 30_000L) return now - (tunnelLastOk[up.key] ?: 0L) < 60_000L
        tunnelProbeAt[up.key] = now
        return try {
            val t0 = SystemClock.elapsedRealtime()
            val r = viaHttpProxy(up, "www.google.com", 443, "https://www.google.com/")
            closeQuietly(r.first)
            noteConnectMs(up.key, SystemClock.elapsedRealtime() - t0)
            tunnelLastOk[up.key] = SystemClock.elapsedRealtime()
            true
        } catch (e: ProxyRefusedException) {
            e.code != 407
        } catch (_: Exception) {
            false
        }
    }

    private val tunnelInFlight = ConcurrentHashMap<String, AtomicInteger>()
    private val tunnelLastOk = ConcurrentHashMap<String, Long>()
    private val appTunnelFailLoggedAt = ConcurrentHashMap<String, Long>()

    // ---- v4.2.2: качество серверов Browsec для приложений — реальная задержка CONNECT и фактическая скорость ----
    private val tunnelConnectMs = ConcurrentHashMap<String, Long>()   // EWMA времени установления CONNECT
    private val tunnelRateKb = ConcurrentHashMap<String, Double>()    // EWMA скорости приёма в активные секунды, КБ/с
    @Volatile private var tunnelMeasuredAt = 0L

    private fun noteConnectMs(key: String, ms: Long) {
        val prev = tunnelConnectMs[key]
        tunnelConnectMs[key] = if (prev == null) ms else (prev * 0.6 + ms * 0.4).toLong()
    }

    private fun noteRate(key: String, kbPerSec: Double) {
        if (kbPerSec < 8.0) return // пустые секунды/keep-alive не считаем
        val prev = tunnelRateKb[key]
        tunnelRateKb[key] = if (prev == null) kbPerSec else prev * 0.7 + kbPerSec * 0.3
    }

    /** Оценка с учётом адресата: если известна задержка именно до него через этот сервер — она важнее общей. */
    private fun scoreFor(dest: String?, up: Upstream): Long {
        val base = scoreOf(up)
        val d = if (dest != null) tunnelDestMs["${up.key}|$dest"] else null
        return if (d == null) base else maxOf(d, 50L) + (base - (tunnelConnectMs[up.key] ?: 0L)).coerceAtLeast(0L)
    }

    /** Чем меньше — тем лучше: задержка CONNECT плюс штраф за медленную отдачу (ниже 400 КБ/с). */
    private fun scoreOf(up: Upstream): Long {
        // неизмеренный сервер — хуже любого измеренного рабочего (в час пик половина серверов отвечает 503)
        val connect = tunnelConnectMs[up.key] ?: ((tunnelPing[up.key] ?: 1_500L).coerceAtMost(5_000L) * 3 + 1_500)
        val rate = tunnelRateKb[up.key]
        val penalty = if (rate == null) 0L else ((400.0 - rate).coerceAtLeast(0.0) * 5).toLong()
        return connect + penalty
    }

    /**
     * Порядок серверов: лучший по измеренному качеству; страна профиля приложения или страна расширения — только если её
     * лучший сервер не хуже общего лучшего более чем в полтора раза («быстрее» важнее, чем «первый попавшийся»).
     */
    @Volatile private var lastResortLoggedAt = 0L

    private fun rankTunnelServers(preferCountry: String?, now: Long, dest: String? = null, useCountry: Boolean = true): List<Upstream> {
        val pref = if (useCountry) preferCountry?.lowercase()?.take(2) else null
        val extPref = if (useCountry) extensionCountry() else null
        var alive = tunnelServers
            .filter { (tunnelDownUntil[it.key] ?: 0L) < now && tunnelPing[it.key] != Long.MAX_VALUE }
            .filter { (tunnelInFlight[it.key]?.get() ?: 0) < 4 || now - (tunnelLastOk[it.key] ?: 0L) < 60_000L }
        if (alive.isEmpty() && tunnelServers.isNotEmpty()) {
            // v4.2.17: в час пик весь пул за несколько секунд получает 503/таймауты и уходит в паузу на 60–180 с —
            // раньше приложения на это время оставались вообще без серверов (1881 отказ AyuGram за 45 с).
            // Запасной вариант: берём серверы, чья пауза кончается раньше всех (503 = живой, но перегруженный), и пробуем всё равно.
            alive = tunnelServers
                .filter { tunnelPing[it.key] != Long.MAX_VALUE || tunnelConnectMs.containsKey(it.key) }
                .sortedBy { (tunnelDownUntil[it.key] ?: 0L) }
                .take(6)
            if (alive.isEmpty()) alive = tunnelServers.take(6)
            if (now - lastResortLoggedAt > 60_000L) {
                lastResortLoggedAt = now
                com.winwasm64.vpn.VpnLog.w("Прил", "все серверы Browsec в паузе после 503/таймаутов — пробуем ${alive.take(4).joinToString(", ") { it.host.substringBefore('.') }} всё равно, чтобы приложения не остались без туннеля")
            }
        }
        if (alive.isEmpty()) return withPsiphon(alive)
        val byScore = alive.sortedBy { scoreFor(dest, it) }
        val best = scoreFor(dest, byScore.first())
        val limit = (best * 1.5).toLong() + 200
        fun cc(u: Upstream) = u.host.takeWhile { it.isLetter() }.take(2).lowercase()
        var head = byScore.filter { (pref != null && cc(it) == pref) || (extPref != null && cc(it) == extPref) }
            .filter { scoreFor(dest, it) <= limit }
        if (head.isEmpty()) {
            // держимся текущего сервера, пока другой не лучше его хотя бы на 30 % (иначе прыгаем между lt9/lt40/de73 без пользы)
            val cur = alive.firstOrNull { it.key == lastAutoTunnel }
            if (cur != null && scoreFor(dest, cur) <= (best * 1.3).toLong() + 100) head = listOf(cur)
        }
        return withPsiphon((head + byScore).distinctBy { it.key })
    }

    /** Активный замер: CONNECT www.google.com:443 через 10 лучших по TCP-пингу серверов, параллельно. */
    private val measuring = AtomicBoolean(false)

    private fun measureTunnelServers() {
        if (!measuring.compareAndSet(false, true)) return
        try { measureTunnelServersInner() } finally { measuring.set(false) }
    }

    private fun measureTunnelServersInner() {
        val now = SystemClock.elapsedRealtime()
        val cands = tunnelServers
            .filter { (tunnelDownUntil[it.key] ?: 0L) < now && tunnelPing[it.key] != Long.MAX_VALUE }
            .sortedBy { tunnelPing[it.key] ?: 5_000L }
            .take(16)
        if (cands.isEmpty()) return
        val latch = CountDownLatch(cands.size)
        for (up in cands) {
            pool.execute {
                try {
                    val t0 = SystemClock.elapsedRealtime()
                    val r = viaHttpProxy(up, "www.google.com", 443, "https://www.google.com/")
                    closeQuietly(r.first)
                    noteConnectMs(up.key, SystemClock.elapsedRealtime() - t0)
                } catch (e: ProxyRefusedException) {
                    if (e.code == 407 || e.code in 500..599) {
                        tunnelDownUntil[up.key] = SystemClock.elapsedRealtime() + 60_000L
                        tunnelConnectMs[up.key] = 9_000L
                    }
                } catch (e: Exception) {
                    if (localNetFault(e)) {
                        tunnelDownUntil.putIfAbsent(up.key, SystemClock.elapsedRealtime() + 8_000L)
                    } else {
                        tunnelDownUntil[up.key] = SystemClock.elapsedRealtime() + 180_000L
                        tunnelConnectMs[up.key] = 9_000L
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await(15, TimeUnit.SECONDS)
        val ranked = rankTunnelServers(null, SystemClock.elapsedRealtime()).take(5)
        if (ranked.isNotEmpty()) {
            com.winwasm64.vpn.VpnLog.i("Прил", "лучшие серверы Browsec: " + ranked.joinToString(", ") { u ->
                val rate = tunnelRateKb[u.key]
                u.key.substringBefore('.') + " " + (tunnelConnectMs[u.key]?.let { "$it мс" } ?: "не измерен") + (if (rate != null) " · %.0f КБ/с".format(java.util.Locale.US, rate) else "")
            })
        }
    }

    /** Считает фактическую скорость приёма через сервер (по секундам с данными). */
    private class CountingInputStream(private val inner: InputStream, private val key: String) : InputStream() {
        private var sec = 0L
        private var bucket = 0L
        private fun count(n: Int) {
            if (n <= 0) return
            val s = SystemClock.elapsedRealtime() / 1000
            if (s != sec) {
                if (sec != 0L && bucket > 0) noteRate(key, bucket / 1024.0)
                sec = s
                bucket = 0
            }
            bucket += n
        }
        override fun read(): Int { val b = inner.read(); if (b >= 0) count(1); return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int { val n = inner.read(b, off, len); count(n); return n }
        override fun available(): Int = inner.available()
        override fun close() = inner.close()
    }

    @Volatile private var extCountryCache: Pair<String?, Long> = null to 0L

    /** Страна, выбранная пользователем в расширении Browsec (по первому серверу его PAC-маршрута); null — неизвестно. */
    private fun extensionCountry(): String? {
        val now = SystemClock.elapsedRealtime()
        val c = extCountryCache
        if (now - c.second < 60_000L) return c.first
        val cc = try {
            if (control == null) null else pacLookup("https://www.youtube.com/", "www.youtube.com")
                ?.firstOrNull { it.type != "DIRECT" }?.host?.takeWhile { ch -> ch.isLetter() }?.take(2)?.lowercase()?.takeIf { it.length == 2 }
        } catch (_: Exception) { null }
        extCountryCache = cc to now
        return cc
    }

    // v4.1.1: DNS приложений через туннель Browsec (DoH к dns.google внутри CONNECT) — ответы провайдера отравлены
    private val tunnelDnsCache = ConcurrentHashMap<String, Pair<String, Long>>()

    fun resolveViaTunnel(name: String, ecsSubnet: String?): String? {
        val now = SystemClock.elapsedRealtime()
        tunnelDnsCache[name]?.let { if (it.second > now) return it.first else tunnelDnsCache.remove(name) }
        if (!appTunnelAvailable()) return null
        val t = openAppTunnel("dns.google", 443, null) ?: return null
        var ssl: SSLSocket? = null
        try {
            t.first.soTimeout = 5_000
            ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(t.first, "dns.google", 443, true) as SSLSocket
            ssl.startHandshake()
            val path = "/resolve?name=" + java.net.URLEncoder.encode(name, "UTF-8") + "&type=A" +
                (if (!ecsSubnet.isNullOrBlank()) "&edns_client_subnet=" + java.net.URLEncoder.encode(ecsSubnet, "UTF-8") else "")
            ssl.outputStream.write(("GET $path HTTP/1.1\r\nHost: dns.google\r\nAccept: application/dns-json\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
            ssl.outputStream.flush()
            val all = ssl.inputStream.readBytes()
            val text = String(all, Charsets.UTF_8)
            val body = text.substringAfter("\r\n\r\n", "")
            val json = JSONObject(body.substring(body.indexOf('{').coerceAtLeast(0)))
            val answers = json.optJSONArray("Answer") ?: return null
            var ttl = 60L
            for (i in 0 until answers.length()) {
                val a = answers.getJSONObject(i)
                if (a.optInt("type") == 1) {
                    val ip = a.optString("data").trim()
                    if (ip.count { it == '.' } == 3) {
                        ttl = a.optLong("TTL", 60L).coerceIn(30L, 600L)
                        tunnelDnsCache[name] = ip to (now + ttl * 1000L)
                        return ip
                    }
                }
            }
            return null
        } catch (_: Exception) {
            return null
        } finally {
            closeQuietly(ssl)
            closeQuietly(t.first)
        }
    }

    private fun reportError(up: Upstream, e: Exception) {
        val owner = control?.extId ?: return
        val now = SystemClock.elapsedRealtime()
        val last = lastErrorAt[up.key] ?: 0L
        if (now - last < 10_000) return
        lastErrorAt[up.key] = now
        Log.w(TAG, "прокси ${up.key}: ${e.message}")
        // v4.0.3: без красной плашки — сайт и так откроется через встроенный обход DPI; причина — в журнале VPN
    }
}
