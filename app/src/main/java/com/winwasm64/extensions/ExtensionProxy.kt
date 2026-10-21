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
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "omni-extproxy").apply { isDaemon = true } }
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
        if (extId != null && c.extId != extId) return
        control = null
        ownerName = null
        pacCache.clear()
        creds.clear()
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
        ensureServer()
        BrowserDirectRepeater.externalProxyOverride = { onReady -> applyOverride(onReady) }
        applyOverride(null)
        changeListener?.invoke()
    }

    private fun applyOverride(onReady: (() -> Unit)?) {
        val p = port
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
            val cfg = ProxyConfig.Builder().addProxyRule("127.0.0.1:$p").build()
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
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 128)
            server = ss
            port = ss.localPort
            pool.execute { acceptLoop(ss) }
            startWatchdog()
        } catch (e: Exception) {
            Log.e(TAG, "не удалось запустить локальный прокси", e)
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        var errors = 0
        while (!ss.isClosed) {
            val s = try {
                ss.accept()
            } catch (_: Exception) {
                if (ss.isClosed) break
                // Временная ошибка (например, кончились дескрипторы при Shorts) — НЕ выходим, иначе прокси «умирает»
                errors++
                try { Thread.sleep(if (errors > 50) 200L else 10L) } catch (_: InterruptedException) {}
                continue
            }
            errors = 0
            pool.execute { handle(s) }
        }
    }

    private fun probe(p: Int): Boolean {
        if (p <= 0) return false
        return try {
            Socket().use {
                it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), p), 500)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Перезапускает локальный прокси расширения на новом порту и заново подключает к нему WebView. */
    fun repairNow(onReady: (() -> Unit)? = null) {
        if (control == null) {
            main.post { onReady?.invoke() }
            return
        }
        try { server?.close() } catch (_: Exception) {}
        server = null
        ensureServer()
        appliedKey = ""
        main.post { applyOverride(onReady) }
    }

    /** true — прокси жив (или не используется); false — пришлось перезапустить. */
    fun ensureHealthy(): Boolean {
        if (control == null) return true
        if (probe(port)) return true
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
                val route = pacLookup(url, hp.first) ?: listOf(DIRECT)
                val up = openTunnel(route, hp.first, hp.second, url)
                if (up == null) {
                    respond(client, 502, "Proxy Error")
                    return
                }
                val out = client.getOutputStream()
                out.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                out.flush()
                pipe(client, inp, up.first, up.second)
            } else {
                val u = try {
                    URL(first[1])
                } catch (_: Exception) {
                    respond(client, 400, "Bad Request")
                    return
                }
                val route = pacLookup(first[1], u.host) ?: listOf(DIRECT)
                forwardHttp(client, inp, text, u, route)
            }
        } catch (e: Exception) {
            closeQuietly(client)
        }
    }

    private fun openTunnel(route: List<Upstream>, host: String, port: Int, url: String): Pair<Socket, InputStream>? {
        for (up in route) {
            try {
                return when (up.type) {
                    "DIRECT" -> direct(host, port, url)
                    "SOCKS5" -> viaSocks5(up, host, port)
                    else -> viaHttpProxy(up, host, port, url)
                }
            } catch (e: Exception) {
                if (up.type != "DIRECT") reportError(up, e)
            }
        }
        return null
    }

    private fun direct(host: String, port: Int, url: String): Pair<Socket, InputStream> {
        if (BrowserDirectRepeater.enabled) {
            val rp = BrowserDirectRepeater.ensureServerRunning()
            if (rp > 0) return viaHttpProxy(Upstream("HTTP", "127.0.0.1", rp), host, port, url)
        }
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 15_000)
        s.tcpNoDelay = true
        return s to s.getInputStream()
    }

    private fun openToProxy(up: Upstream): Socket {
        val raw = Socket()
        raw.connect(InetSocketAddress(up.host, up.port), 15_000)
        raw.soTimeout = 25_000
        raw.tcpNoDelay = true
        if (up.type != "HTTPS") return raw
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, up.host, up.port, true) as SSLSocket
        ssl.startHandshake()
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(up.host, ssl.session)) {
            closeQuietly(ssl)
            throw SSLPeerUnverifiedException("Сертификат прокси не соответствует ${up.host}")
        }
        return ssl
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

    private fun viaHttpProxy(up: Upstream, host: String, port: Int, url: String): Pair<Socket, InputStream> {
        var attempt = 0
        while (true) {
            val used = creds[up.key]
            val s = openToProxy(up)
            val sb = StringBuilder()
                .append("CONNECT ").append(host).append(':').append(port).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append(':').append(port).append("\r\n")
                .append("User-Agent: ").append(ExtensionStore.DESKTOP_UA).append("\r\n")
                .append("Proxy-Connection: keep-alive\r\n")
            if (used != null) sb.append("Proxy-Authorization: Basic ").append(basic(used)).append("\r\n")
            sb.append("\r\n")
            val out = s.getOutputStream()
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val inp = BufferedInputStream(s.getInputStream())
            val head = readHead(inp)
            if (head == null) {
                closeQuietly(s)
                throw IOException("Прокси ${up.key} закрыл соединение")
            }
            val code = statusCode(head)
            if (code == 200) {
                s.soTimeout = 0
                return s to inp
            }
            closeQuietly(s)
            if (code == 407 && attempt == 0) {
                attempt++
                credentialsFor(up, realmOf(head), url, used) ?: throw IOException("Прокси ${up.key} требует логин и пароль")
                continue
            }
            throw IOException("Прокси ${up.key} ответил HTTP $code")
        }
    }

    /** Один запрос пароля на прокси одновременно: остальные соединения ждут его результата. */
    private fun credentialsFor(up: Upstream, realm: String, url: String, failed: Pair<String, String>?): Pair<String, String>? {
        val current = creds[up.key]
        if (current != null && current != failed) return current
        val mine = CountDownLatch(1)
        val other = authInFlight.putIfAbsent(up.key, mine)
        if (other != null) {
            other.await(25, TimeUnit.SECONDS)
            return creds[up.key]
        }
        try {
            val c = askCredentials(up, realm, url)
            if (c != null) creds[up.key] = c
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
        s.connect(InetSocketAddress(up.host, up.port), 15_000)
        s.soTimeout = 25_000
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

    private fun rewriteHead(text: String, originForm: Boolean, u: URL, auth: Pair<String, String>?): ByteArray {
        val lines = text.split("\r\n")
        val first = lines[0].split(' ')
        val target = if (originForm) u.file.ifEmpty { "/" } else first[1]
        val sb = StringBuilder().append(first[0]).append(' ').append(target).append(' ').append(first.getOrElse(2) { "HTTP/1.1" }).append("\r\n")
        for (i in 1 until lines.size) {
            val l = lines[i]
            if (l.isEmpty()) continue
            val name = l.substringBefore(':').trim().lowercase()
            if (name == "proxy-connection" || name == "proxy-authorization" || name == "connection" || name == "keep-alive") continue
            sb.append(l).append("\r\n")
        }
        if (auth != null) sb.append("Proxy-Authorization: Basic ").append(basic(auth)).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun forwardHttp(client: Socket, cin: InputStream, head: String, u: URL, route: List<Upstream>) {
        val host = u.host
        val port = if (u.port > 0) u.port else 80
        for (up in route) {
            try {
                val target: Socket
                val tin: InputStream
                val payload: ByteArray
                when (up.type) {
                    "DIRECT" -> {
                        if (BrowserDirectRepeater.enabled && BrowserDirectRepeater.ensureServerRunning() > 0) {
                            target = Socket()
                            target.connect(InetSocketAddress("127.0.0.1", BrowserDirectRepeater.activePort), 10_000)
                            payload = rewriteHead(head, false, u, null)
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
                target.tcpNoDelay = true
                target.getOutputStream().write(payload)
                target.getOutputStream().flush()
                pipe(client, cin, target, tin)
                return
            } catch (e: Exception) {
                if (up.type != "DIRECT") reportError(up, e)
            }
        }
        respond(client, 502, "Proxy Error")
    }

    private fun pipe(a: Socket, aIn: InputStream, b: Socket, bIn: InputStream) {
        try {
            a.soTimeout = 300_000
            b.soTimeout = 300_000
        } catch (_: Exception) {
        }
        pool.execute {
            copy(bIn, a.getOutputStream())
            closeQuietly(a)
            closeQuietly(b)
        }
        copy(aIn, b.getOutputStream())
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

    private fun reportError(up: Upstream, e: Exception) {
        val owner = control?.extId ?: return
        val now = SystemClock.elapsedRealtime()
        val last = lastErrorAt[up.key] ?: 0L
        if (now - last < 10_000) return
        lastErrorAt[up.key] = now
        Log.w(TAG, "прокси ${up.key}: ${e.message}")
        main.post { errorReporter?.invoke(owner, "net::ERR_PROXY_CONNECTION_FAILED", "${up.key}: ${e.message ?: e.javaClass.simpleName}") }
    }
}
