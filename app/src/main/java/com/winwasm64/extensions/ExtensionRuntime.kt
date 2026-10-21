package com.winwasm64.extensions

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class ExtTab(
    val id: Int,
    val index: Int,
    val url: String,
    val title: String,
    val active: Boolean,
    val incognito: Boolean,
    val loading: Boolean
)

/** То, что браузер предоставляет расширениям (вызывается в UI-потоке). */
interface ExtensionTabHost {
    fun tabs(): List<ExtTab>
    fun createTab(url: String, active: Boolean): ExtTab?
    fun updateTab(tabId: Int, url: String?, active: Boolean?): ExtTab?
    fun removeTab(tabId: Int)
    fun reloadTab(tabId: Int)
    fun webViewOf(tabId: Int): WebView?
    fun openPopup(extId: String)
    fun closePopup(extId: String)
    fun toast(text: String)
}

/** Получатель сообщений: фрейм с контент-скриптами или страница расширения. */
interface ExtSink {
    val sinkKey: String
    fun send(json: String)
}

class ExtEndpoint(
    val key: String,
    val webView: WebView,
    var kind: String,
    var ext: String,
    val tabId: Int,
    val top: Boolean,
    var frameId: Int
) : ExtSink {
    var url: String = ""
    var proxy: JavaScriptReplyProxy? = null
    var host: ExtPageHost? = null
    val exts = HashSet<String>()
    override val sinkKey: String get() = host?.sinkKey ?: key
    override fun send(json: String) = sendDirect(json)
    fun sendDirect(json: String) {
        try {
            proxy?.postMessage(json)
        } catch (_: Exception) {
        }
    }
}

/** Скрытая или видимая страница расширения: фон (bg), popup, offscreen/настройки (page). */
class ExtPageHost(val serial: Int, val extId: String, val kind: String, val webView: WebView) : ExtSink {
    override val sinkKey: String = "host:$serial"
    var endpoint: ExtEndpoint? = null
    var ready = false
    private val queue = ArrayList<String>()
    override fun send(json: String) {
        val ep = endpoint
        if (ready && ep != null) ep.sendDirect(json) else if (queue.size < 5000) queue.add(json)
    }

    fun markReady() {
        ready = true
        val ep = endpoint ?: return
        for (q in queue) ep.sendDirect(q)
        queue.clear()
    }
}

object ExtensionRuntime {
    const val OBJ_NAME = "__omniExtPort"
    const val HOST_SUFFIX = ".omni-ext.invalid"
    private const val GENERATED_BG = "_omni_generated_background_page.html"
    private const val NO_RECEIVER = "Could not establish connection. Receiving end does not exist."
    private const val TAG = "OmniExt"

    private val random = SecureRandom()
    private val token = randomHex(24)
    private val run = randomHex(6)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(3)
    private val disk = Executors.newSingleThreadExecutor()

    private lateinit var app: Context
    private var coreTemplate = ""
    var initialized = false
        private set
    var messagingSupported = false
        private set
    var documentStartSupported = false
        private set

    @Volatile
    private var tabScripts: List<String> = emptyList()
    private val tabViews = WeakHashMap<WebView, Int>()
    private val scriptHandles = WeakHashMap<WebView, MutableList<ScriptHandler>>()
    private val tabMainUrl = ConcurrentHashMap<Int, String>()
    private val tabAllowAll = ConcurrentHashMap<Int, Int>()
    private val endpoints = HashMap<String, ExtEndpoint>()
    private val hosts = HashMap<String, ExtPageHost>()
    private val hostsByView = WeakHashMap<WebView, ExtPageHost>()
    private val backgrounds = HashMap<String, ExtPageHost>()
    private val offscreens = HashMap<String, ExtPageHost>()
    private val pendings = HashMap<Int, Pair<ExtEndpoint, Int>>()
    private val pendingCounts = HashMap<Int, IntArray>()
    private val execs = HashMap<Int, Pair<ExtEndpoint, Int>>()
    private val ports = HashMap<String, PortRec>()
    private val nonces = ConcurrentHashMap<String, Long>()
    private val frameSeq = HashMap<Int, Int>()
    private var ridSeq = 0
    private var xidSeq = 0
    private var hostSeq = 0
    private var scriptsGeneration = 0
    private var dnrGeneration = 0

    @Volatile
    private var dnrIndex: DnrIndex? = null
    val blockedRequests = AtomicInteger(0)

    var tabHost: ExtensionTabHost? = null

    /** Счётчики состояния для Compose (UI перерисовывается при изменениях). */
    var stateVersion by mutableIntStateOf(0)
        private set
    var dnrRuleCount by mutableIntStateOf(0)
        private set
    val busy = mutableStateMapOf<String, String>()

    private class PortRec(val ext: String, val opener: String, val receivers: MutableSet<String>)

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    fun baseUrl(extId: String) = "https://$extId$HOST_SUFFIX/"

    // =====================================================================
    // Инициализация
    // =====================================================================

    fun init(context: Context) {
        if (initialized) return
        app = context.applicationContext
        ExtensionStore.init(app)
        ExtensionProxy.init(app)
        ExtensionProxy.authRequester = { owner, details, cb -> requestProxyAuth(owner, details, cb) }
        ExtensionProxy.errorReporter = { owner, error, details ->
            val payload = eventJson(owner, "proxy.onProxyError", JSONArray().put(JSONObject().put("fatal", false).put("error", error).put("details", details)))
            for (sink in pageSinksOf(owner, null)) sink.send(payload)
        }
        ExtensionProxy.changeListener = { broadcastProxyChange() }
        messagingSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        documentStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        coreTemplate = try {
            app.assets.open("omni_ext_core.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }.replace("__OMNI_OBJ__", OBJ_NAME).replace("__OMNI_TOKEN__", token).replace("__OMNI_RUN__", run)
        initialized = true
        rebuildScripts(delayMs = 0)
        rebuildDnr(delayMs = 300)
        main.postDelayed({ syncBackgrounds() }, 800)
    }

    private fun rebuildScripts(delayMs: Long = 250) {
        val gen = ++scriptsGeneration
        main.postDelayed({
            if (gen != scriptsGeneration) return@postDelayed
            io.execute {
                val scripts = try {
                    buildTabScripts()
                } catch (e: Exception) {
                    Log.w(TAG, "buildTabScripts", e)
                    emptyList()
                }
                main.post {
                    if (gen != scriptsGeneration) return@post
                    tabScripts = scripts
                    for (wv in tabViews.keys.toList()) installScripts(wv)
                    stateVersion++
                }
            }
        }, delayMs)
    }

    private fun rebuildDnr(delayMs: Long = 400) {
        val gen = ++dnrGeneration
        main.postDelayed({
            if (gen != dnrGeneration) return@postDelayed
            io.execute {
                val idx = try {
                    buildDnrIndex()
                } catch (e: Throwable) {
                    Log.w(TAG, "buildDnrIndex", e)
                    DnrIndex()
                }
                main.post {
                    if (gen != dnrGeneration) return@post
                    dnrIndex = idx
                    dnrRuleCount = idx.size
                    stateVersion++
                }
            }
        }, delayMs)
    }

    private fun syncBackgrounds() {
        val enabled = ExtensionStore.enabledExtensions().filter { it.manifest.hasBackground }.associateBy { it.id }
        for (id in backgrounds.keys.toList()) {
            if (id !in enabled) backgrounds.remove(id)?.let { releaseHost(it) }
        }
        for ((id, ext) in enabled) {
            if (backgrounds.containsKey(id)) continue
            val path = ext.manifest.backgroundPage ?: GENERATED_BG
            createHost(app, id, "bg", path)?.let { backgrounds[id] = it }
        }
        for (id in offscreens.keys.toList()) {
            if (!ExtensionStore.isEnabled(id)) offscreens.remove(id)?.let { releaseHost(it) }
        }
    }

    /** Полная перезагрузка после установки/удаления/включения. */
    fun refreshAll() {
        ExtensionStore.reload()
        rebuildScripts(delayMs = 0)
        rebuildDnr(delayMs = 100)
        syncBackgrounds()
        stateVersion++
    }

    // =====================================================================
    // Установка и управление
    // =====================================================================

    /** Установка настоящего пакета из Chrome Web Store по ссылке или ID. Результат: null = успех, иначе текст ошибки. */
    fun installFromStore(input: String, onResult: (String?, InstalledExtension?) -> Unit) {
        val id = ChromeWebStore.extractId(input)
        if (id == null) {
            onResult("Не найден ID расширения. Вставьте ссылку вида chromewebstore.google.com/detail/…/<32 буквы> или сам ID.", null)
            return
        }
        if (busy.containsKey(id)) return
        busy[id] = "Загрузка пакета с серверов Google…"
        io.execute {
            val result: Pair<String?, InstalledExtension?> = try {
                val bytes = ExtensionStore.downloadCrx(id)
                main.post { busy[id] = "Распаковка и проверка (${bytes.size / 1024} КБ)…" }
                null to ExtensionStore.installBytes(bytes, id, "store")
            } catch (e: Exception) {
                (e.message ?: e.javaClass.simpleName) to null
            }
            main.post {
                busy.remove(id)
                if (result.second != null) refreshAll()
                onResult(result.first, result.second)
            }
        }
    }

    /** Установка из локального файла .crx / .zip. */
    fun installFromBytes(bytes: ByteArray, onResult: (String?, InstalledExtension?) -> Unit) {
        val key = "file_${System.nanoTime()}"
        busy[key] = "Установка из файла…"
        io.execute {
            val result: Pair<String?, InstalledExtension?> = try {
                null to ExtensionStore.installBytes(bytes, null, "file")
            } catch (e: Exception) {
                (e.message ?: e.javaClass.simpleName) to null
            }
            main.post {
                busy.remove(key)
                if (result.second != null) refreshAll()
                onResult(result.first, result.second)
            }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        if (!enabled) ExtensionProxy.release(id)
        ExtensionStore.setEnabled(id, enabled)
        if (!enabled) dropExtensionEndpoints(id)
        refreshAll()
    }

    fun uninstall(id: String) {
        ExtensionProxy.release(id)
        backgrounds.remove(id)?.let { releaseHost(it) }
        offscreens.remove(id)?.let { releaseHost(it) }
        dropExtensionEndpoints(id)
        ExtensionStore.uninstall(id)
        refreshAll()
    }

    private fun reloadExtension(id: String) {
        backgrounds.remove(id)?.let { releaseHost(it) }
        refreshAll()
    }

    fun badgeText(extId: String): String {
        val active = tabHost?.tabs()?.firstOrNull { it.active }?.id
        return ExtensionStore.getActionValue(extId, "badgeText", active) ?: ""
    }

    fun popupPath(extId: String): String? {
        val active = tabHost?.tabs()?.firstOrNull { it.active }?.id
        val override = ExtensionStore.getActionValue(extId, "popup", active)
        if (override != null) return override.trimStart('/').ifBlank { null }
        return ExtensionStore.get(extId)?.manifest?.popupPath
    }

    /** Нажатие на расширение: если есть popup — UI покажет его, иначе событие action.onClicked. */
    fun actionClicked(extId: String): Boolean {
        if (popupPath(extId) != null) return true
        val tab = tabHost?.tabs()?.firstOrNull { it.active }
        val args = JSONArray().put(if (tab != null) tabJson(tab) else JSONObject())
        val payload = eventJson(extId, "action.onClicked", args)
        val mv2 = (ExtensionStore.get(extId)?.manifest?.manifestVersion ?: 3) < 3
        for (s in pageSinksOf(extId, null)) {
            s.send(payload)
            if (mv2) s.send(eventJson(extId, "browserAction.onClicked", args))
        }
        return false
    }

    // =====================================================================
    // Вкладки
    // =====================================================================

    fun attachTab(wv: WebView, tabId: Int) {
        if (!initialized) return
        tabViews[wv] = tabId
        if (messagingSupported) {
            try {
                WebViewCompat.addWebMessageListener(wv, OBJ_NAME, setOf("*"), listener)
            } catch (e: Exception) {
                Log.w(TAG, "addWebMessageListener", e)
            }
        }
        installScripts(wv)
    }

    fun detachTab(wv: WebView, tabId: Int) {
        tabViews.remove(wv)
        scriptHandles.remove(wv)
        tabMainUrl.remove(tabId)
        tabAllowAll.remove(tabId)
        for (ep in endpoints.values.filter { it.tabId == tabId }.toList()) dropEndpoint(ep.key)
        broadcastEvent("tabs.onRemoved", JSONArray().put(tabId).put(JSONObject().put("windowId", 1).put("isWindowClosing", false)))
    }

    private fun installScripts(wv: WebView) {
        scriptHandles.remove(wv)?.forEach {
            try {
                it.remove()
            } catch (_: Exception) {
            }
        }
        if (!messagingSupported || !documentStartSupported) return
        val scripts = tabScripts
        if (scripts.isEmpty()) return
        val list = ArrayList<ScriptHandler>()
        for (s in scripts) {
            try {
                list.add(WebViewCompat.addDocumentStartJavaScript(wv, s, setOf("*")))
            } catch (e: Exception) {
                Log.w(TAG, "addDocumentStartJavaScript", e)
            }
        }
        scriptHandles[wv] = list
    }

    /** Для старых WebView без DOCUMENT_START_SCRIPT: внедрение после загрузки (только главный фрейм). */
    fun injectFallback(wv: WebView) {
        if (!initialized || !messagingSupported || documentStartSupported) return
        val scripts = tabScripts
        if (scripts.isEmpty()) return
        wv.evaluateJavascript(scripts.joinToString("\n;\n"), null)
    }

    fun onTabActivated(tabId: Int) {
        if (!initialized) return
        broadcastEvent("tabs.onActivated", JSONArray().put(JSONObject().put("tabId", tabId).put("windowId", 1)))
        stateVersion++
    }

    fun onTabCreated(tab: ExtTab) {
        if (!initialized) return
        broadcastEvent("tabs.onCreated", JSONArray().put(tabJson(tab)))
    }

    /** status: "loading" / "complete" / null (смена заголовка). */
    fun onTabUpdated(tab: ExtTab, status: String?, urlChanged: Boolean, titleChanged: Boolean) {
        if (!initialized) return
        if (tab.url.isNotBlank()) tabMainUrl[tab.id] = tab.url
        val change = JSONObject()
        if (status != null) change.put("status", status)
        if (urlChanged) change.put("url", tab.url)
        if (titleChanged) change.put("title", tab.title)
        if (change.length() == 0) return
        broadcastEvent("tabs.onUpdated", JSONArray().put(tab.id).put(change).put(tabJson(tab)))
        val nav = JSONObject().put("tabId", tab.id).put("frameId", 0).put("parentFrameId", -1).put("url", tab.url)
            .put("timeStamp", System.currentTimeMillis().toDouble()).put("processId", -1).put("frameType", "outermost_frame")
            .put("documentLifecycle", "active").put("transitionType", "link").put("transitionQualifiers", JSONArray())
        when (status) {
            "loading" -> if (urlChanged) {
                broadcastEvent("webNavigation.onBeforeNavigate", JSONArray().put(nav))
                broadcastEvent("webNavigation.onCommitted", JSONArray().put(nav))
            }
            "complete" -> {
                broadcastEvent("webNavigation.onDOMContentLoaded", JSONArray().put(nav))
                broadcastEvent("webNavigation.onCompleted", JSONArray().put(nav))
            }
        }
    }

    fun onTabHistoryStateUpdated(tab: ExtTab) {
        if (!initialized) return
        tabMainUrl[tab.id] = tab.url
        val nav = JSONObject().put("tabId", tab.id).put("frameId", 0).put("parentFrameId", -1).put("url", tab.url)
            .put("timeStamp", System.currentTimeMillis().toDouble()).put("transitionType", "link").put("transitionQualifiers", JSONArray())
        broadcastEvent("webNavigation.onHistoryStateUpdated", JSONArray().put(nav))
    }

    // =====================================================================
    // Перехват запросов вкладок: ресурсы расширений + declarativeNetRequest
    // =====================================================================

    fun interceptTabRequest(tabId: Int, request: WebResourceRequest): WebResourceResponse? {
        if (!initialized) return null
        val url = request.url ?: return null
        val host = url.host?.lowercase() ?: return null
        if (request.isForMainFrame) tabMainUrl[tabId] = url.toString()
        if (host.endsWith(HOST_SUFFIX)) {
            val extId = host.removeSuffix(HOST_SUFFIX)
            val mainHost = tabMainUrl[tabId]?.let { Uri.parse(it).host?.lowercase() }
            val insideExtPage = request.isForMainFrame || mainHost == host
            return serveExtension(extId, url, if (insideExtPage) "page" else null, restrictToWar = !insideExtPage)
        }
        val idx = dnrIndex ?: return null
        if (idx.size == 0) return null
        val scheme = url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https" && scheme != "ws" && scheme != "wss") return null
        val full = url.toString()
        val initiator = if (request.isForMainFrame) null else tabMainUrl[tabId]?.let { Uri.parse(it).host?.lowercase() }
        val type = DnrEngine.guessResourceType(request.isForMainFrame, request.requestHeaders?.get("Accept"), url.path)
        val rule = idx.bestMatch(DnrRequest(full, full.lowercase(), host, (request.method ?: "GET").uppercase(), type, initiator))
        if (request.isForMainFrame) {
            if (rule != null && rule.action == "allowAllRequests") tabAllowAll[tabId] = rule.priority else tabAllowAll.remove(tabId)
        }
        if (rule == null) return null
        return when (rule.action) {
            "block" -> {
                val allowPriority = tabAllowAll[tabId]
                if (allowPriority != null && rule.priority <= allowPriority) {
                    null
                } else {
                    blockedRequests.incrementAndGet()
                    if (request.isForMainFrame) blockedPage(rule.extId, full) else blockedResponse()
                }
            }
            "redirect" -> {
                val path = rule.redirectExtensionPath ?: return null
                blockedRequests.incrementAndGet()
                serveExtension(rule.extId, Uri.parse(baseUrl(rule.extId) + path.trimStart('/')), null, restrictToWar = false)
            }
            else -> null
        }
    }

    private fun interceptHostRequest(host: ExtPageHost, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url ?: return null
        val h = url.host?.lowercase() ?: return null
        if (!h.endsWith(HOST_SUFFIX)) return null
        val extId = h.removeSuffix(HOST_SUFFIX)
        if (extId != host.extId) return textResponse(403, "Forbidden", "text/plain", "")
        return serveExtension(extId, url, if (request.isForMainFrame) host.kind else "page", restrictToWar = false)
    }

    private fun serveExtension(extId: String, url: Uri, apiKind: String?, restrictToWar: Boolean): WebResourceResponse {
        val ext = ExtensionStore.get(extId)
        if (ext == null || !ext.enabled) return textResponse(404, "Not Found", "text/plain", "")
        val path = (url.encodedPath ?: "/").trimStart('/')
        if (path == "__omni_api__.js") {
            val nonce = url.getQueryParameter("n") ?: ""
            val kind = url.getQueryParameter("ctx") ?: "page"
            val exp = nonces.remove(nonce)
            if (exp == null || exp < System.currentTimeMillis() || kind !in setOf("bg", "popup", "page")) {
                return textResponse(403, "Forbidden", "text/javascript", "")
            }
            return textResponse(200, "OK", "text/javascript", apiScript(ext, kind))
        }
        if (path == GENERATED_BG) {
            if (apiKind != "bg") return textResponse(403, "Forbidden", "text/plain", "")
            return textResponse(200, "OK", "text/html", injectApi(generatedBackgroundHtml(ext), ext, "bg"))
        }
        if (restrictToWar && !isWebAccessible(ext, path)) return textResponse(403, "Forbidden", "text/plain", "")
        val file = ExtensionStore.resolveFile(ext, path.ifEmpty { "index.html" }) ?: return textResponse(404, "Not Found", "text/plain", "")
        val mime = mimeOf(file.name)
        if (mime == "text/html" && apiKind != null) {
            val html = ExtensionStore.readText(ext, path) ?: ""
            return textResponse(200, "OK", "text/html", injectApi(html, ext, apiKind))
        }
        val textual = mime.startsWith("text/") || mime == "application/json" || mime == "image/svg+xml"
        return WebResourceResponse(mime, if (textual) "UTF-8" else null, 200, "OK", baseHeaders(), FileInputStream(file))
    }

    private fun isWebAccessible(ext: InstalledExtension, path: String): Boolean {
        for (p in ext.manifest.webAccessibleResources) {
            if (p == path || p == "*") return true
            if (p.contains('*')) {
                try {
                    if (Regex(MatchPattern.globToRegexSource(p)).matches(path)) return true
                } catch (_: Exception) {
                }
            }
        }
        return false
    }

    private fun baseHeaders(): Map<String, String> = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Cache-Control" to "no-cache"
    )

    private fun textResponse(code: Int, reason: String, mime: String, body: String): WebResourceResponse =
        WebResourceResponse(mime, "UTF-8", code, reason, baseHeaders(), ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))

    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", 403, "Blocked by extension", baseHeaders(), ByteArrayInputStream(ByteArray(0)))

    private fun blockedPage(extId: String, url: String): WebResourceResponse {
        val name = ExtensionStore.get(extId)?.manifest?.name ?: extId
        val esc = url.replace("&", "&amp;").replace("<", "&lt;")
        val html = "<!doctype html><meta name=viewport content='width=device-width'><body style='background:#0E1116;color:#E6E9EF;" +
            "font-family:sans-serif;padding:32px'><h3>Страница заблокирована расширением</h3><p style='color:#9AA3B2'>" +
            name.replace("<", "&lt;") + "</p><p style='word-break:break-all;color:#6B7482;font-size:12px'>" + esc + "</p></body>"
        return textResponse(200, "OK", "text/html", html)
    }

    private fun mimeOf(name: String): String {
        val n = name.lowercase()
        return when (n.substringAfterLast('.', "")) {
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "html", "htm" -> "text/html"
            "json", "map" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "wasm" -> "application/wasm"
            "txt" -> "text/plain"
            "xml" -> "application/xml"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            else -> "application/octet-stream"
        }
    }

    private fun injectApi(html: String, ext: InstalledExtension, kind: String): String {
        val nonce = randomHex(12)
        nonces[nonce] = System.currentTimeMillis() + 120_000
        if (nonces.size > 500) {
            val now = System.currentTimeMillis()
            nonces.entries.removeAll { it.value < now }
        }
        val tag = "<script src=\"/__omni_api__.js?ctx=$kind&n=$nonce\"></script>"
        val lower = html.lowercase()
        for (marker in listOf("<head", "<html", "<!doctype")) {
            val at = lower.indexOf(marker)
            if (at >= 0) {
                val close = lower.indexOf('>', at)
                if (close > 0) return html.substring(0, close + 1) + tag + html.substring(close + 1)
            }
        }
        return tag + html
    }

    private fun apiScript(ext: InstalledExtension, kind: String): String =
        coreTemplate + "\n;(function(){var c=window.__omniCore;if(!c)return;c.page(" + metaJson(ext) + "," +
            JSONObject.quote(kind) + ");try{delete window.__omniCore;}catch(e){}})();"

    private fun generatedBackgroundHtml(ext: InstalledExtension): String {
        val sb = StringBuilder("<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body>")
        val sw = ext.manifest.serviceWorker
        if (sw != null) {
            sb.append("<script ").append(if (ext.manifest.serviceWorkerIsModule) "type=\"module\" " else "")
                .append("src=\"/").append(sw).append("\"></script>")
        } else {
            for (s in ext.manifest.backgroundScripts) sb.append("<script src=\"/").append(s).append("\"></script>")
        }
        return sb.append("</body></html>").toString()
    }

    // =====================================================================
    // Сборка скриптов для вкладок
    // =====================================================================

    private fun metaJson(ext: InstalledExtension): JSONObject = JSONObject()
        .put("id", ext.id)
        .put("name", ext.manifest.name)
        .put("base", baseUrl(ext.id))
        .put("manifest", JSONObject(ext.manifest.rawJson))
        .put("messages", JSONObject(ext.manifest.messagesJson))
        .put("uiLocale", ext.manifest.uiLocale)
        .put("optionsUrl", ext.manifest.optionsPath?.let { baseUrl(ext.id) + it } ?: "")

    private fun buildTabScripts(): List<String> {
        val exts = ExtensionStore.enabledExtensions()
        if (exts.isEmpty() || coreTemplate.isEmpty()) return emptyList()
        val out = ArrayList<String>(exts.size + 2)
        out.add(coreTemplate)
        for (ext in exts) out.add(buildExtensionScript(ext))
        out.add(
            "(function(){var c=window.__omniCore;if(!c)return;try{c.start();}catch(e){}" +
                "if(/\\.omni-ext\\.invalid$/i.test(location.hostname))return;try{delete window.__omniCore;}catch(e){}})();"
        )
        return out
    }

    private fun registeredEntries(ext: InstalledExtension): List<ContentScriptEntry> {
        val arr = ExtensionStore.registeredScripts(ext.id)
        val out = ArrayList<ContentScriptEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val matches = ManifestParser.stringList(o.optJSONArray("matches"))
            if (matches.isEmpty()) continue
            out.add(
                ContentScriptEntry(
                    id = "reg_" + o.optString("id", "$i"),
                    matches = matches,
                    excludeMatches = ManifestParser.stringList(o.optJSONArray("excludeMatches")),
                    js = ManifestParser.stringList(o.optJSONArray("js")).map { it.trimStart('/') },
                    css = ManifestParser.stringList(o.optJSONArray("css")).map { it.trimStart('/') },
                    runAt = o.optString("runAt", "document_idle").ifBlank { "document_idle" },
                    allFrames = o.optBoolean("allFrames", false),
                    matchAboutBlank = o.optBoolean("matchOriginAsFallback", false),
                    world = o.optString("world", "ISOLATED")
                )
            )
        }
        return out
    }

    private fun buildExtensionScript(ext: InstalledExtension): String {
        val sb = StringBuilder()
        sb.append("(function(c){if(!c||/\\.omni-ext\\.invalid$/i.test(location.hostname))return;c.register(")
            .append(metaJson(ext).toString()).append(",[")
        var first = true
        for (e in ext.manifest.contentScripts + registeredEntries(ext)) {
            if (e.js.isEmpty() && e.css.isEmpty()) continue
            val js = entryJs(ext, e) ?: continue
            if (!first) sb.append(',')
            first = false
            sb.append(js)
        }
        sb.append("]);})(window.__omniCore);")
        return sb.toString()
    }

    private enum class PatternKind { ALL, SUFFIX, EXACT, REGEX }

    private fun classifyPattern(p: String): Pair<PatternKind, String> {
        if (p == "<all_urls>" || p == "*://*/*") return PatternKind.ALL to ""
        if (p.startsWith("*://") && p.endsWith("/*")) {
            val host = p.substring(4, p.length - 2).lowercase()
            if (host.contains('/') || host.contains(':') || host.isEmpty()) return PatternKind.REGEX to ""
            if (host == "*") return PatternKind.ALL to ""
            if (host.startsWith("*.")) {
                val h = host.substring(2)
                if (h.isNotEmpty() && !h.contains('*')) return PatternKind.SUFFIX to h
            } else if (!host.contains('*')) {
                return PatternKind.EXACT to host
            }
        }
        return PatternKind.REGEX to ""
    }

    private fun entryJs(ext: InstalledExtension, e: ContentScriptEntry): String? {
        val o = JSONObject()
        o.put("id", e.id).put("ra", e.runAt).put("af", e.allFrames).put("mab", e.matchAboutBlank)
        val hs = JSONObject()
        val hx = JSONObject()
        val regexes = JSONArray()
        var all = false
        for (p in e.matches) {
            val (kind, host) = classifyPattern(p)
            when (kind) {
                PatternKind.ALL -> all = true
                PatternKind.SUFFIX -> hs.put(host, 1)
                PatternKind.EXACT -> hx.put(host, 1)
                PatternKind.REGEX -> MatchPattern.toRegexSource(p)?.let { regexes.put(it) }
            }
        }
        if (!all && hs.length() == 0 && hx.length() == 0 && regexes.length() == 0) return null
        if (all) o.put("all", true)
        if (hs.length() > 0) o.put("hs", hs)
        if (hx.length() > 0) o.put("hx", hx)
        if (regexes.length() > 0) o.put("m", regexes)
        if (e.excludeMatches.isNotEmpty()) o.put("x", JSONArray(e.excludeMatches.mapNotNull { MatchPattern.toRegexSource(it) }))
        if (e.includeGlobs.isNotEmpty()) o.put("ig", JSONArray(e.includeGlobs.map { MatchPattern.globToRegexSource(it) }))
        if (e.excludeGlobs.isNotEmpty()) o.put("eg", JSONArray(e.excludeGlobs.map { MatchPattern.globToRegexSource(it) }))
        if (e.css.isNotEmpty()) {
            val css = e.css.mapNotNull { path -> ExtensionStore.readText(ext, path)?.let { rewriteCss(ext, path, it) } }.joinToString("\n")
            if (css.isNotBlank()) o.put("css", css)
        }
        val json = o.toString()
        if (e.js.isEmpty()) return json
        val code = StringBuilder()
        for (f in e.js) {
            val t = ExtensionStore.readText(ext, f) ?: continue
            code.append(t).append("\n;\n")
        }
        if (code.isEmpty()) return json
        return json.substring(0, json.length - 1) + ",\"js\":function(chrome,browser){\n" + code + "\n}}"
    }

    private val cssUrlRegex = Regex("url\\(\\s*(['\"]?)([^'\")]+)\\1\\s*\\)")

    private fun rewriteCss(ext: InstalledExtension, cssPath: String, css: String): String {
        val base = baseUrl(ext.id)
        val dir = cssPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val replaced = css
            .replace("chrome-extension://__MSG_@@extension_id__/", base)
            .replace("chrome-extension://${ext.id}/", base)
            .replace("__MSG_@@extension_id__", ext.id)
        return cssUrlRegex.replace(replaced) { m ->
            val q = m.groupValues[1]
            val u = m.groupValues[2].trim()
            val external = u.startsWith("data:") || u.startsWith("http:") || u.startsWith("https:") || u.startsWith("#") ||
                u.startsWith("blob:") || u.startsWith("//") || u.startsWith("chrome-extension:")
            if (external) {
                m.value
            } else {
                val abs = if (u.startsWith("/")) base + u.trimStart('/') else base + normalizePath(dir + u)
                "url($q$abs$q)"
            }
        }
    }

    private fun normalizePath(p: String): String {
        val out = ArrayList<String>()
        for (seg in p.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(seg)
            }
        }
        return out.joinToString("/")
    }

    private fun buildDnrIndex(): DnrIndex {
        val idx = DnrIndex()
        for (ext in ExtensionStore.enabledExtensions()) {
            val enabled = ExtensionStore.enabledRulesets(ext)
            val disabled = ExtensionStore.disabledStaticRules(ext.id)
            for (rs in ext.manifest.dnrRulesets) {
                if (rs.id !in enabled) continue
                val text = ExtensionStore.readText(ext, rs.path) ?: continue
                val dis = disabled.optJSONArray(rs.id)?.let { arr -> (0 until arr.length()).map { arr.optInt(it) }.toSet() } ?: emptySet()
                try {
                    DnrEngine.parseRuleset(ext.id, text, dis).first.forEach { idx.add(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "ruleset ${rs.id} of ${ext.id}", e)
                }
            }
            for (arr in listOf(ExtensionStore.dynamicRules(ext.id), ExtensionStore.sessionRules(ext.id))) {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { o -> DnrEngine.parseRule(ext.id, o)?.let { idx.add(it) } }
                }
            }
        }
        return idx
    }

    // =====================================================================
    // Страницы расширения в отдельных WebView
    // =====================================================================

    @SuppressLint("SetJavaScriptEnabled")
    fun createHost(context: Context, extId: String, kind: String, path: String): ExtPageHost? {
        if (!initialized || !messagingSupported) return null
        val ext = ExtensionStore.get(extId) ?: return null
        val wv = WebView(context)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.allowFileAccess = false
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.setSupportMultipleWindows(false)
        val host = ExtPageHost(++hostSeq, extId, kind, wv)
        hosts[host.sinkKey] = host
        hostsByView[wv] = host
        try {
            WebViewCompat.addWebMessageListener(wv, OBJ_NAME, setOf("https://$extId$HOST_SUFFIX"), listener)
        } catch (e: Exception) {
            Log.w(TAG, "host listener", e)
        }
        val ownHost = "$extId$HOST_SUFFIX"
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                interceptHostRequest(host, request)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host?.lowercase() == ownHost) return false
                val s = u.toString()
                if (kind != "bg" && (s.startsWith("http://") || s.startsWith("https://"))) {
                    tabHost?.createTab(s, true)
                    if (kind == "popup") tabHost?.closePopup(extId)
                }
                return true
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(TAG, "[${ext.manifest.name}/$kind] ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})")
                return true
            }
        }
        wv.loadUrl(baseUrl(extId) + path.trimStart('/'))
        return host
    }

    fun createPopupHost(context: Context, extId: String): ExtPageHost? {
        val path = popupPath(extId) ?: return null
        return createHost(context, extId, "popup", path)
    }

    fun createOptionsHost(context: Context, extId: String): ExtPageHost? {
        val path = ExtensionStore.get(extId)?.manifest?.optionsPath ?: return null
        return createHost(context, extId, "page", path)
    }

    fun releaseHost(host: ExtPageHost) {
        hosts.remove(host.sinkKey)
        hostsByView.remove(host.webView)
        host.endpoint?.let { dropEndpoint(it.key) }
        try {
            host.webView.stopLoading()
            host.webView.destroy()
        } catch (_: Exception) {
        }
    }

    // =====================================================================
    // Сообщения от JS
    // =====================================================================

    private val listener = WebViewCompat.WebMessageListener { view, message, sourceOrigin, isMainFrame, replyProxy ->
        onWebMessage(view, message.data, sourceOrigin, isMainFrame, replyProxy)
    }

    private fun onWebMessage(view: WebView, data: String?, origin: Uri, isMainFrame: Boolean, proxy: JavaScriptReplyProxy) {
        if (data.isNullOrEmpty()) return
        val m = try {
            JSONObject(data)
        } catch (_: Exception) {
            return
        }
        if (m.optString("t") != token) return
        val fk = m.optString("fk")
        if (fk.isEmpty()) return
        val kind = m.optString("kind", "cs")
        val ext = m.optString("ext")
        val host = hostsByView[view]
        val tabId = tabViews[view] ?: -1
        if (kind != "cs") {
            if (ext.isEmpty() || origin.host?.lowercase() != ext + HOST_SUFFIX) return
            if (host != null && host.extId != ext) return
        } else if (host != null) {
            return
        }
        var ep = endpoints[fk]
        if (ep == null) {
            val top = m.optBoolean("top", isMainFrame)
            val frameId = if (top || tabId < 0) 0 else (frameSeq[tabId] ?: 0) + 1
            if (!top && tabId >= 0) frameSeq[tabId] = frameId
            ep = ExtEndpoint(fk, view, kind, if (kind == "cs") "" else ext, tabId, top, frameId)
            endpoints[fk] = ep
            if (host != null) {
                ep.host = host
                host.endpoint = ep
            }
        }
        if (ep.kind != kind) {
            ep.kind = kind
            if (kind != "cs") ep.ext = ext
        }
        ep.proxy = proxy
        ep.url = m.optString("url", ep.url)
        val op = m.optString("op")
        val cid = m.optInt("cid", 0)
        val a = m.optJSONObject("a") ?: JSONObject()
        if (ext.isNotEmpty() && !ExtensionStore.isEnabled(ext)) {
            if (cid > 0) replyError(ep, cid, "Расширение отключено")
            return
        }
        try {
            handle(ep, ext, op, cid, a)
        } catch (e: Exception) {
            Log.w(TAG, "op $op", e)
            if (cid > 0) replyError(ep, cid, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun replyOk(ep: ExtEndpoint, cid: Int, result: Any?) {
        if (cid <= 0) return
        ep.sendDirect(JSONObject().put("op", "reply").put("cid", cid).put("ok", true).put("r", result ?: JSONObject.NULL).toString())
    }

    private fun replyError(ep: ExtEndpoint, cid: Int, message: String) {
        if (cid <= 0) return
        ep.sendDirect(JSONObject().put("op", "reply").put("cid", cid).put("ok", false).put("e", message).toString())
    }

    private fun eventJson(ext: String, name: String, args: JSONArray): String =
        JSONObject().put("op", "event").put("ext", ext).put("name", name).put("args", args).toString()

    private fun broadcastEvent(name: String, args: JSONArray) {
        for (ext in ExtensionStore.enabledExtensions()) {
            val payload = eventJson(ext.id, name, args)
            for (s in pageSinksOf(ext.id, null)) s.send(payload)
        }
    }

    /** Страницы расширения (фон, popup, offscreen, страницы во вкладках), кроме отправителя. */
    private fun pageSinksOf(ext: String, exclude: ExtEndpoint?): List<ExtSink> {
        val out = ArrayList<ExtSink>()
        for (h in hosts.values) {
            if (h.extId == ext && exclude?.host !== h) out.add(h)
        }
        for (ep in endpoints.values) {
            if (ep.host == null && ep.kind != "cs" && ep.ext == ext && ep !== exclude) out.add(ep)
        }
        return out
    }

    private fun csSinks(ext: String, tabId: Int, frameId: Int?): List<ExtSink> =
        endpoints.values.filter { it.kind == "cs" && it.tabId == tabId && ext in it.exts && (frameId == null || it.frameId == frameId) }

    private fun sinkOf(key: String): ExtSink? = hosts[key] ?: endpoints[key]

    private fun activeTabId(): Int = tabHost?.tabs()?.firstOrNull { it.active }?.id ?: -1

    private fun originOf(url: String): String? = try {
        val u = Uri.parse(url)
        if (u.scheme != null && u.host != null) "${u.scheme}://${u.host}" + (if (u.port > 0) ":${u.port}" else "") else null
    } catch (_: Exception) {
        null
    }

    // =====================================================================
    // Прокси расширений: пароль (webRequest.onAuthRequired), события, browsingData
    // =====================================================================

    private val authWaiters = HashMap<Int, (JSONObject?) -> Unit>()
    private var authSeq = 0

    /** Спросить логин/пароль прокси у расширения-владельца (и у других с webRequestAuthProvider). Главный поток. */
    private fun requestProxyAuth(owner: String, details: JSONObject, cb: (Pair<String, String>?) -> Unit) {
        val ids = ArrayList<String>()
        ids.add(owner)
        for (e in ExtensionStore.enabledExtensions()) {
            if (e.id != owner && e.manifest.permissions.contains("webRequestAuthProvider")) ids.add(e.id)
        }
        val targets = ids.flatMap { id -> pageSinksOf(id, null).map { id to it } }
        if (targets.isEmpty()) {
            cb(null)
            return
        }
        val rid = ++authSeq
        var left = targets.size
        var finished = false
        authWaiters[rid] = { res ->
            if (!finished) {
                if (res != null && res.has("u")) {
                    finished = true
                    authWaiters.remove(rid)
                    cb(res.optString("u") to res.optString("p"))
                } else if (--left <= 0) {
                    finished = true
                    authWaiters.remove(rid)
                    cb(null)
                }
            }
        }
        for ((id, sink) in targets) {
            sink.send(JSONObject().put("op", "auth").put("ext", id).put("rid", rid).put("details", details).toString())
        }
        main.postDelayed({
            if (!finished) {
                finished = true
                authWaiters.remove(rid)
                cb(null)
            }
        }, 15_000)
    }

    private fun broadcastProxyChange() {
        for (e in ExtensionStore.enabledExtensions()) {
            val payload = eventJson(e.id, "proxy.settings.onChange", JSONArray().put(ExtensionProxy.settingFor(e.id)))
            for (sink in pageSinksOf(e.id, null)) sink.send(payload)
        }
        stateVersion++
    }

    private fun removeBrowsingData(a: JSONObject) {
        val types = a.optJSONObject("types") ?: return
        val originsArr = a.optJSONObject("options")?.optJSONArray("origins")
        val origins = ArrayList<String>()
        if (originsArr != null) for (i in 0 until originsArr.length()) {
            val o = originsArr.optString(i)
            if (o.startsWith("http")) origins.add(o)
        }
        try {
            if (types.optBoolean("cookies")) {
                val cm = CookieManager.getInstance()
                if (origins.isEmpty()) {
                    cm.removeAllCookies(null)
                } else {
                    for (o in origins) {
                        cm.getCookie(o)?.split(';')?.forEach { c ->
                            val name = c.substringBefore('=').trim()
                            if (name.isNotEmpty()) cm.setCookie(o, "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
                        }
                    }
                }
                cm.flush()
            }
            if (types.optBoolean("localStorage") || types.optBoolean("indexedDB") || types.optBoolean("webSQL") || types.optBoolean("fileSystems")) {
                val ws = WebStorage.getInstance()
                if (origins.isEmpty()) ws.deleteAllData() else for (o in origins) ws.deleteOrigin(o)
            }
            if ((types.optBoolean("cache") || types.optBoolean("appcache")) && origins.isEmpty()) {
                val host = tabHost
                val first = host?.tabs()?.firstOrNull()
                if (host != null && first != null) host.webViewOf(first.id)?.clearCache(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "browsingData.remove", e)
        }
    }

    fun tabJson(t: ExtTab): JSONObject = JSONObject()
        .put("id", t.id).put("index", t.index).put("windowId", 1).put("openerTabId", JSONObject.NULL)
        .put("url", t.url).put("title", t.title).put("active", t.active).put("highlighted", t.active)
        .put("selected", t.active).put("pinned", false).put("audible", false).put("discarded", false)
        .put("autoDiscardable", true).put("frozen", false).put("incognito", t.incognito).put("groupId", -1)
        .put("status", if (t.loading) "loading" else "complete")
        .put("mutedInfo", JSONObject().put("muted", false)).put("lastAccessed", System.currentTimeMillis().toDouble())

    private fun senderJson(ep: ExtEndpoint, ext: String): JSONObject {
        val s = JSONObject().put("id", ext).put("url", ep.url)
        originOf(ep.url)?.let { s.put("origin", it) }
        if (ep.tabId >= 0) {
            tabHost?.tabs()?.firstOrNull { it.id == ep.tabId }?.let { s.put("tab", tabJson(it)) }
            s.put("frameId", ep.frameId)
            s.put("documentId", ep.key)
            s.put("documentLifecycle", "active")
        }
        return s
    }

    private fun handle(ep: ExtEndpoint, ext: String, op: String, cid: Int, a: JSONObject) {
        when (op) {
            "hello" -> ep.exts.add(ext)
            "frame" -> registerFrame(ep)
            "ready" -> onReady(ep, ext)
            "sendMessage" -> routeMessage(ep, ext, cid, a.opt("msg"), pageSinksOf(ext, ep))
            "tabs.sendMessage" -> {
                val tabId = a.optInt("tabId", -1)
                val frameId = if (a.isNull("frameId") || !a.has("frameId")) null else a.optInt("frameId")
                routeMessage(ep, ext, cid, a.opt("msg"), csSinks(ext, tabId, frameId))
            }
            "respond" -> onRespond(a)
            "connect" -> openPort(ep, ext, a)
            "portPost" -> portPost(ep, a)
            "portDisconnect" -> portDisconnect(ep, a)
            "portRefuse" -> portRefuse(ep, a)
            "storage.get" -> replyOk(ep, cid, storageGet(ext, a))
            "storage.set" -> {
                storageSet(ext, a)
                replyOk(ep, cid, null)
            }
            "storage.remove" -> {
                storageRemove(ext, a.optString("area", "local"), a.optJSONArray("keys"))
                replyOk(ep, cid, null)
            }
            "storage.clear" -> {
                storageRemove(ext, a.optString("area", "local"), null)
                replyOk(ep, cid, null)
            }
            "storage.bytes" -> {
                val got = storageGet(ext, a)
                replyOk(ep, cid, got.toString().toByteArray().size)
            }
            "storage.keys" -> {
                val obj = ExtensionStore.storageArea(ext, a.optString("area", "local"))
                val keys = synchronized(obj) { obj.keys().asSequence().toList() }
                replyOk(ep, cid, JSONArray(keys))
            }
            "tabs.query" -> replyOk(ep, cid, tabsQuery(a))
            "tabs.get" -> {
                val t = tabHost?.tabs()?.firstOrNull { it.id == a.optInt("tabId", -1) }
                if (t == null) replyError(ep, cid, "No tab with id: ${a.optInt("tabId", -1)}.") else replyOk(ep, cid, tabJson(t))
            }
            "tabs.getCurrent" -> {
                val t = if (ep.tabId >= 0) tabHost?.tabs()?.firstOrNull { it.id == ep.tabId } else null
                replyOk(ep, cid, t?.let { tabJson(it) })
            }
            "tabs.create" -> {
                val url = resolveExtUrl(ext, a.optString("url", ""))
                val t = tabHost?.createTab(url.ifBlank { "https://www.google.com" }, a.optBoolean("active", true))
                if (t == null) replyError(ep, cid, "Не удалось открыть вкладку") else replyOk(ep, cid, tabJson(t))
            }
            "tabs.update" -> {
                val tabId = if (a.isNull("tabId") || !a.has("tabId")) activeTabId() else a.optInt("tabId")
                val props = a.optJSONObject("props") ?: JSONObject()
                val url = if (props.has("url")) resolveExtUrl(ext, props.optString("url")) else null
                val active = if (props.has("active")) props.optBoolean("active") else if (props.has("highlighted")) props.optBoolean("highlighted") else null
                val t = tabHost?.updateTab(tabId, url, active)
                if (t == null) replyError(ep, cid, "No tab with id: $tabId.") else replyOk(ep, cid, tabJson(t))
            }
            "tabs.remove" -> {
                val ids = a.optJSONArray("tabIds") ?: JSONArray()
                for (i in 0 until ids.length()) tabHost?.removeTab(ids.optInt(i))
                replyOk(ep, cid, null)
            }
            "tabs.reload" -> {
                val tabId = if (a.isNull("tabId") || !a.has("tabId")) activeTabId() else a.optInt("tabId")
                tabHost?.reloadTab(tabId)
                replyOk(ep, cid, null)
            }
            "tabs.duplicate" -> {
                val src = tabHost?.tabs()?.firstOrNull { it.id == a.optInt("tabId", -1) }
                val t = src?.let { tabHost?.createTab(it.url, true) }
                replyOk(ep, cid, t?.let { tabJson(it) })
            }
            "windows.get" -> {
                val w = JSONObject().put("id", 1).put("focused", true).put("top", 0).put("left", 0)
                    .put("type", "normal").put("state", "normal").put("incognito", false).put("alwaysOnTop", false)
                if (a.optBoolean("populate")) w.put("tabs", JSONArray(tabHost?.tabs().orEmpty().map { tabJson(it) }))
                replyOk(ep, cid, w)
            }
            "scripting.executeScript" -> executeScript(ep, ext, cid, a)
            "scripting.insertCSS" -> insertCss(ep, ext, cid, a, remove = false)
            "scripting.removeCSS" -> insertCss(ep, ext, cid, a, remove = true)
            "scripting.register", "scripting.update" -> {
                registerScripts(ext, a.optJSONArray("scripts") ?: JSONArray(), update = op == "scripting.update")
                replyOk(ep, cid, null)
            }
            "scripting.unregister" -> {
                val ids = a.optJSONArray("ids")?.let { ManifestParser.stringList(it).toSet() }
                val arr = ExtensionStore.registeredScripts(ext)
                val out = JSONArray()
                if (ids != null) {
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { if (it.optString("id") !in ids) out.put(it) }
                }
                ExtensionStore.saveRegisteredScripts(ext, out)
                rebuildScripts()
                replyOk(ep, cid, null)
            }
            "scripting.getRegistered" -> {
                val ids = a.optJSONArray("ids")?.let { ManifestParser.stringList(it).toSet() }
                val arr = ExtensionStore.registeredScripts(ext)
                val out = JSONArray()
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { if (ids == null || it.optString("id") in ids) out.put(it) }
                replyOk(ep, cid, out)
            }
            "execResult" -> {
                val p = execs.remove(a.optInt("xid", -1)) ?: return
                if (a.has("error")) {
                    replyError(p.first, p.second, a.optString("error"))
                } else {
                    replyOk(p.first, p.second, JSONArray().put(JSONObject().put("frameId", 0).put("documentId", "omni").put("result", a.opt("result") ?: JSONObject.NULL)))
                }
            }
            "action.set" -> {
                val tabId = if (a.isNull("tabId") || !a.has("tabId")) null else a.optInt("tabId")
                ExtensionStore.setActionValue(ext, a.optString("k"), tabId, a.optString("v", ""))
                stateVersion++
                replyOk(ep, cid, null)
            }
            "action.get" -> {
                val tabId = if (a.isNull("tabId") || !a.has("tabId")) null else a.optInt("tabId")
                val key = a.optString("k")
                val v = ExtensionStore.getActionValue(ext, key, tabId)
                    ?: when (key) {
                        "title" -> ExtensionStore.get(ext)?.manifest?.actionTitle ?: ""
                        "popup" -> ExtensionStore.get(ext)?.manifest?.popupPath?.let { baseUrl(ext) + it } ?: ""
                        else -> ""
                    }
                replyOk(ep, cid, v)
            }
            "action.openPopup" -> {
                tabHost?.openPopup(ext)
                replyOk(ep, cid, null)
            }
            "dnr.update" -> {
                val session = a.optString("scope") == "session"
                val current = if (session) ExtensionStore.sessionRules(ext) else ExtensionStore.dynamicRules(ext)
                val remove = HashSet<Int>()
                a.optJSONArray("remove")?.let { r -> for (i in 0 until r.length()) remove.add(r.optInt(i)) }
                val add = a.optJSONArray("add") ?: JSONArray()
                for (i in 0 until add.length()) add.optJSONObject(i)?.let { remove.add(it.optInt("id")) }
                val out = JSONArray()
                for (i in 0 until current.length()) current.optJSONObject(i)?.let { if (it.optInt("id") !in remove) out.put(it) }
                for (i in 0 until add.length()) add.optJSONObject(i)?.let { out.put(it) }
                if (session) ExtensionStore.setSessionRules(ext, out) else ExtensionStore.saveDynamicRules(ext, out)
                rebuildDnr()
                replyOk(ep, cid, null)
            }
            "dnr.get" -> {
                val current = if (a.optString("scope") == "session") ExtensionStore.sessionRules(ext) else ExtensionStore.dynamicRules(ext)
                val ids = a.optJSONArray("ids")?.let { r -> (0 until r.length()).map { r.optInt(it) }.toSet() }
                val out = JSONArray()
                for (i in 0 until current.length()) current.optJSONObject(i)?.let { if (ids == null || it.optInt("id") in ids) out.put(it) }
                replyOk(ep, cid, out)
            }
            "dnr.rulesets" -> {
                val e = ExtensionStore.get(ext) ?: return replyError(ep, cid, "Нет расширения")
                val set = ExtensionStore.enabledRulesets(e).toMutableSet()
                a.optJSONArray("disable")?.let { set.removeAll(ManifestParser.stringList(it).toSet()) }
                val known = e.manifest.dnrRulesets.map { it.id }.toSet()
                a.optJSONArray("enable")?.let { en -> set.addAll(ManifestParser.stringList(en).filter { it in known }) }
                ExtensionStore.saveEnabledRulesets(ext, set)
                rebuildDnr()
                replyOk(ep, cid, null)
            }
            "dnr.getRulesets" -> {
                val e = ExtensionStore.get(ext)
                replyOk(ep, cid, JSONArray(e?.let { ExtensionStore.enabledRulesets(it).toList() } ?: emptyList<String>()))
            }
            "dnr.static" -> {
                val rs = a.optString("rulesetId")
                val obj = ExtensionStore.disabledStaticRules(ext)
                val set = obj.optJSONArray(rs)?.let { arr -> (0 until arr.length()).map { arr.optInt(it) }.toMutableSet() } ?: mutableSetOf()
                a.optJSONArray("disable")?.let { d -> for (i in 0 until d.length()) set.add(d.optInt(i)) }
                a.optJSONArray("enable")?.let { d -> for (i in 0 until d.length()) set.remove(d.optInt(i)) }
                obj.put(rs, JSONArray(set.toList()))
                ExtensionStore.saveDisabledStaticRules(ext, obj)
                rebuildDnr()
                replyOk(ep, cid, null)
            }
            "dnr.getDisabled" -> {
                val arr = ExtensionStore.disabledStaticRules(ext).optJSONArray(a.optString("rulesetId")) ?: JSONArray()
                replyOk(ep, cid, arr)
            }
            "notify" -> {
                val text = listOf(a.optString("title"), a.optString("message")).filter { it.isNotBlank() }.joinToString(": ")
                if (text.isNotBlank()) Toast.makeText(app, text, Toast.LENGTH_LONG).show()
                replyOk(ep, cid, null)
            }
            "cookies.get" -> replyOk(ep, cid, cookiesFor(a).firstOrNull { it.optString("name") == a.optString("name") })
            "cookies.getAll" -> {
                val name = if (a.has("name")) a.optString("name") else null
                replyOk(ep, cid, JSONArray(cookiesFor(a).filter { name == null || it.optString("name") == name }))
            }
            "cookies.set" -> {
                val url = a.optString("url")
                val sb = StringBuilder(a.optString("name")).append('=').append(a.optString("value"))
                if (a.has("path")) sb.append("; path=").append(a.optString("path"))
                if (a.has("domain")) sb.append("; domain=").append(a.optString("domain"))
                if (a.optBoolean("secure")) sb.append("; secure")
                if (a.has("expirationDate")) {
                    val ms = (a.optDouble("expirationDate") * 1000).toLong()
                    sb.append("; expires=").append(java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }.format(java.util.Date(ms)))
                }
                CookieManager.getInstance().setCookie(url, sb.toString())
                CookieManager.getInstance().flush()
                replyOk(ep, cid, cookieJson(url, a.optString("name"), a.optString("value")))
            }
            "cookies.remove" -> {
                val url = a.optString("url")
                CookieManager.getInstance().setCookie(url, a.optString("name") + "=; expires=Thu, 01 Jan 1970 00:00:00 GMT")
                CookieManager.getInstance().flush()
                replyOk(ep, cid, JSONObject().put("url", url).put("name", a.optString("name")).put("storeId", "0"))
            }
            "downloads.download" -> replyOk(ep, cid, startDownload(a))
            "http" -> httpRequest(ep, cid, a)
            "openOptionsPage" -> {
                val path = ExtensionStore.get(ext)?.manifest?.optionsPath
                if (path == null) {
                    replyError(ep, cid, "У расширения нет страницы настроек")
                } else {
                    tabHost?.createTab(baseUrl(ext) + path, true)
                    replyOk(ep, cid, null)
                }
            }
            "closePopup" -> tabHost?.closePopup(ext)
            "offscreen.create" -> {
                if (!offscreens.containsKey(ext)) {
                    val path = a.optString("url", "").removePrefix(baseUrl(ext)).trimStart('/')
                    createHost(app, ext, "page", path)?.let { offscreens[ext] = it }
                }
                replyOk(ep, cid, null)
            }
            "offscreen.close" -> {
                offscreens.remove(ext)?.let { releaseHost(it) }
                replyOk(ep, cid, null)
            }
            "offscreen.has" -> replyOk(ep, cid, offscreens.containsKey(ext))
            "webNav.getAllFrames", "webNav.getFrame" -> {
                val tabId = a.optInt("tabId", -1)
                val frames = endpoints.values.filter { it.tabId == tabId && it.kind == "cs" }.distinctBy { it.frameId }
                val list = JSONArray()
                if (frames.none { it.frameId == 0 }) {
                    tabHost?.tabs()?.firstOrNull { it.id == tabId }?.let {
                        list.put(JSONObject().put("frameId", 0).put("parentFrameId", -1).put("url", it.url).put("errorOccurred", false))
                    }
                }
                for (f in frames) {
                    list.put(JSONObject().put("frameId", f.frameId).put("parentFrameId", if (f.frameId == 0) -1 else 0).put("url", f.url).put("errorOccurred", false))
                }
                if (op == "webNav.getFrame") {
                    val want = a.optInt("frameId", 0)
                    var found: JSONObject? = null
                    for (i in 0 until list.length()) if (list.getJSONObject(i).optInt("frameId") == want) found = list.getJSONObject(i)
                    replyOk(ep, cid, found)
                } else {
                    replyOk(ep, cid, list)
                }
            }
            "reload" -> reloadExtension(ext)
            "proxy.get" -> replyOk(ep, cid, ExtensionProxy.settingFor(ext))
            "proxy.set" -> if (ep.kind == "cs") replyError(ep, cid, "chrome.proxy недоступен в контент-скриптах") else {
                val value = a.optJSONObject("value") ?: JSONObject()
                val name = ExtensionStore.get(ext)?.manifest?.name ?: ext
                ExtensionProxy.set(ext, name, value) { err -> if (err == null) replyOk(ep, cid, null) else replyError(ep, cid, err) }
            }
            "proxy.clear" -> if (ep.kind == "cs") replyError(ep, cid, "chrome.proxy недоступен в контент-скриптах") else {
                ExtensionProxy.release(ext)
                replyOk(ep, cid, null)
            }
            "authResult" -> if (ep.kind != "cs") authWaiters[a.optInt("rid")]?.invoke(a.optJSONObject("creds"))
            "browsingData.remove" -> if (ep.kind == "cs") replyError(ep, cid, "chrome.browsingData недоступен в контент-скриптах") else {
                removeBrowsingData(a)
                replyOk(ep, cid, null)
            }
            else -> if (cid > 0) replyError(ep, cid, "Метод $op не поддерживается")
        }
    }

    private fun registerFrame(ep: ExtEndpoint) {
        if (ep.tabId < 0 || !ep.top) return
        frameSeq[ep.tabId] = 0
        for (old in endpoints.values.filter { it.tabId == ep.tabId && it.key != ep.key }.toList()) dropEndpoint(old.key)
    }

    private fun onReady(ep: ExtEndpoint, ext: String) {
        val host = ep.host
        if (host == null) {
            if (ep.tabId >= 0 && ep.top) registerFrame(ep)
            return
        }
        host.markReady()
        if (host.kind == "bg") {
            val ev = ExtensionStore.takePendingInstallEvent(ext)
            if (ev != null) {
                val details = JSONObject().put("reason", ev.first)
                ev.second?.let { details.put("previousVersion", it) }
                host.send(eventJson(ext, "runtime.onInstalled", JSONArray().put(details)))
            } else {
                host.send(eventJson(ext, "runtime.onStartup", JSONArray()))
            }
        }
    }

    private fun dropEndpoint(key: String) {
        val ep = endpoints.remove(key) ?: return
        val sk = ep.sinkKey
        for ((pid, rec) in ports.entries.toList()) {
            if (rec.opener == sk) {
                val disc = JSONObject().put("op", "portDisc").put("pid", pid).toString()
                rec.receivers.forEach { sinkOf(it)?.send(disc) }
                ports.remove(pid)
            } else if (rec.receivers.remove(sk) && rec.receivers.isEmpty()) {
                sinkOf(rec.opener)?.send(JSONObject().put("op", "portDisc").put("pid", pid).toString())
                ports.remove(pid)
            }
        }
    }

    private fun dropExtensionEndpoints(ext: String) {
        for ((pid, rec) in ports.entries.toList()) if (rec.ext == ext) ports.remove(pid)
        for (ep in endpoints.values) ep.exts.remove(ext)
    }

    private fun routeMessage(ep: ExtEndpoint, ext: String, cid: Int, msg: Any?, targets: List<ExtSink>) {
        if (targets.isEmpty()) {
            replyError(ep, cid, NO_RECEIVER)
            return
        }
        val rid = ++ridSeq
        pendings[rid] = ep to cid
        pendingCounts[rid] = intArrayOf(targets.size, 0)
        val payload = JSONObject().put("op", "msg").put("ext", ext).put("rid", rid)
            .put("msg", msg ?: JSONObject.NULL).put("sender", senderJson(ep, ext)).toString()
        for (t in targets) t.send(payload)
        main.postDelayed({
            pendingCounts.remove(rid)
            pendings.remove(rid)?.let { (e, c) -> replyOk(e, c, JSONObject()) }
        }, 5 * 60_000L)
    }

    private fun onRespond(a: JSONObject) {
        val rid = a.optInt("rid", -1)
        val p = pendings[rid] ?: return
        if (a.optBoolean("none")) {
            val counts = pendingCounts[rid] ?: intArrayOf(1, 0)
            counts[0]--
            if (a.optBoolean("had")) counts[1] = 1
            if (counts[0] <= 0) {
                pendings.remove(rid)
                pendingCounts.remove(rid)
                if (counts[1] == 1) replyOk(p.first, p.second, JSONObject()) else replyError(p.first, p.second, NO_RECEIVER)
            }
            return
        }
        pendings.remove(rid)
        pendingCounts.remove(rid)
        if (a.has("err")) {
            replyError(p.first, p.second, a.optString("err"))
            return
        }
        val r = JSONObject()
        if (!a.optBoolean("undef")) r.put("v", a.opt("resp") ?: JSONObject.NULL)
        replyOk(p.first, p.second, r)
    }

    private fun openPort(ep: ExtEndpoint, ext: String, a: JSONObject) {
        val pid = a.optString("pid")
        if (pid.isEmpty()) return
        val targets = if (a.optString("target") == "tab") {
            val frameId = if (a.isNull("frameId") || !a.has("frameId")) null else a.optInt("frameId")
            csSinks(ext, a.optInt("tabId", -1), frameId)
        } else {
            pageSinksOf(ext, ep)
        }
        if (targets.isEmpty()) {
            ep.sendDirect(JSONObject().put("op", "portDisc").put("pid", pid).put("e", NO_RECEIVER).toString())
            return
        }
        ports[pid] = PortRec(ext, ep.sinkKey, targets.map { it.sinkKey }.toMutableSet())
        val payload = JSONObject().put("op", "connect").put("ext", ext).put("pid", pid)
            .put("name", a.optString("name", "")).put("sender", senderJson(ep, ext)).toString()
        for (t in targets) t.send(payload)
    }

    private fun portPost(ep: ExtEndpoint, a: JSONObject) {
        val pid = a.optString("pid")
        val rec = ports[pid] ?: return
        val payload = JSONObject().put("op", "portMsg").put("pid", pid).put("msg", a.opt("msg") ?: JSONObject.NULL).toString()
        if (ep.sinkKey == rec.opener) {
            for (r in rec.receivers) sinkOf(r)?.send(payload)
        } else {
            sinkOf(rec.opener)?.send(payload)
        }
    }

    private fun portDisconnect(ep: ExtEndpoint, a: JSONObject) {
        val pid = a.optString("pid")
        val rec = ports[pid] ?: return
        val disc = JSONObject().put("op", "portDisc").put("pid", pid).toString()
        if (ep.sinkKey == rec.opener) {
            for (r in rec.receivers) sinkOf(r)?.send(disc)
            ports.remove(pid)
        } else {
            rec.receivers.remove(ep.sinkKey)
            if (rec.receivers.isEmpty()) {
                sinkOf(rec.opener)?.send(disc)
                ports.remove(pid)
            }
        }
    }

    private fun portRefuse(ep: ExtEndpoint, a: JSONObject) {
        val pid = a.optString("pid")
        val rec = ports[pid] ?: return
        rec.receivers.remove(ep.sinkKey)
        if (rec.receivers.isEmpty()) {
            sinkOf(rec.opener)?.send(JSONObject().put("op", "portDisc").put("pid", pid).put("e", NO_RECEIVER).toString())
            ports.remove(pid)
        }
    }

    // ---------------- chrome.storage ----------------

    private fun storageGet(ext: String, a: JSONObject): JSONObject {
        val obj = ExtensionStore.storageArea(ext, a.optString("area", "local"))
        val keys = a.opt("keys")
        val out = JSONObject()
        synchronized(obj) {
            when (keys) {
                null, JSONObject.NULL -> obj.keys().forEach { k -> out.put(k, obj.opt(k)) }
                is String -> if (obj.has(keys)) out.put(keys, obj.opt(keys))
                is JSONArray -> for (i in 0 until keys.length()) {
                    val k = keys.optString(i)
                    if (obj.has(k)) out.put(k, obj.opt(k))
                }
                is JSONObject -> keys.keys().forEach { k -> out.put(k, if (obj.has(k)) obj.opt(k) else keys.opt(k)) }
            }
        }
        return out
    }

    private fun storageSet(ext: String, a: JSONObject) {
        val area = a.optString("area", "local")
        val items = a.optJSONObject("items") ?: return
        val obj = ExtensionStore.storageArea(ext, area)
        val changes = JSONObject()
        synchronized(obj) {
            items.keys().forEach { k ->
                val nv = items.opt(k)
                val ov = obj.opt(k)
                if (ov != null && nv != null && ov.javaClass == nv.javaClass && ov.toString() == nv.toString()) return@forEach
                obj.put(k, nv)
                val ch = JSONObject()
                if (ov != null) ch.put("oldValue", ov)
                ch.put("newValue", nv ?: JSONObject.NULL)
                changes.put(k, ch)
            }
        }
        if (changes.length() > 0) {
            disk.execute { ExtensionStore.persistStorage(ext, area) }
            broadcastStorage(ext, area, changes)
        }
    }

    private fun storageRemove(ext: String, area: String, keys: JSONArray?) {
        val obj = ExtensionStore.storageArea(ext, area)
        val changes = JSONObject()
        synchronized(obj) {
            val list = if (keys == null) obj.keys().asSequence().toList() else ManifestParser.stringList(keys)
            for (k in list) {
                if (!obj.has(k)) continue
                changes.put(k, JSONObject().put("oldValue", obj.opt(k)))
                obj.remove(k)
            }
        }
        if (changes.length() > 0) {
            disk.execute { ExtensionStore.persistStorage(ext, area) }
            broadcastStorage(ext, area, changes)
        }
    }

    private fun broadcastStorage(ext: String, area: String, changes: JSONObject) {
        val payload = JSONObject().put("op", "storage").put("ext", ext).put("area", area).put("changes", changes).toString()
        for (ep in endpoints.values) if (ep.kind == "cs" && ext in ep.exts) ep.sendDirect(payload)
        for (s in pageSinksOf(ext, null)) s.send(payload)
    }

    // ---------------- Вкладки ----------------

    private fun tabsQuery(q: JSONObject): JSONArray {
        val all = tabHost?.tabs().orEmpty()
        val urlPatterns: List<String>? = when (val u = q.opt("url")) {
            is String -> listOf(u)
            is JSONArray -> ManifestParser.stringList(u)
            else -> null
        }
        val out = JSONArray()
        for (t in all) {
            if (q.has("active") && !q.isNull("active") && q.optBoolean("active") != t.active) continue
            if (q.has("highlighted") && !q.isNull("highlighted") && q.optBoolean("highlighted") != t.active) continue
            if (q.has("pinned") && q.optBoolean("pinned")) continue
            if (q.has("audible") && q.optBoolean("audible")) continue
            if (q.has("index") && q.optInt("index") != t.index) continue
            if (q.has("status") && q.optString("status") != (if (t.loading) "loading" else "complete")) continue
            if (q.has("incognito") && q.optBoolean("incognito") != t.incognito) continue
            if (urlPatterns != null && !MatchPattern.matchesAny(urlPatterns, t.url)) continue
            if (q.has("title")) {
                val glob = q.optString("title")
                if (!Regex(MatchPattern.globToRegexSource(glob)).matches(t.title)) continue
            }
            out.put(tabJson(t))
        }
        return out
    }

    private fun resolveExtUrl(ext: String, raw: String): String {
        val u = raw.trim()
        if (u.isEmpty()) return ""
        if (u.startsWith("chrome-extension://")) {
            val rest = u.removePrefix("chrome-extension://")
            val id = rest.substringBefore('/')
            return baseUrl(id) + rest.substringAfter('/', "")
        }
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(u)) return u
        return baseUrl(ext) + u.trimStart('/')
    }

    // ---------------- scripting ----------------

    private fun targetTab(a: JSONObject): Int {
        val target = a.optJSONObject("target") ?: JSONObject()
        return if (!target.has("tabId") || target.isNull("tabId")) activeTabId() else target.optInt("tabId")
    }

    private fun filesSource(ext: InstalledExtension, files: JSONArray?): String? {
        if (files == null || files.length() == 0) return null
        val sb = StringBuilder()
        for (f in ManifestParser.stringList(files)) {
            val t = ExtensionStore.readText(ext, f.trimStart('/')) ?: return null
            sb.append(t).append("\n;\n")
        }
        return sb.toString()
    }

    private fun executeScript(ep: ExtEndpoint, ext: String, cid: Int, a: JSONObject) {
        val e = ExtensionStore.get(ext) ?: return replyError(ep, cid, "Нет расширения")
        val tabId = targetTab(a)
        val wv = tabHost?.webViewOf(tabId) ?: return replyError(ep, cid, "No tab with id: $tabId.")
        val func = if (a.isNull("func")) "" else a.optString("func", "")
        val code = if (a.isNull("code")) "" else a.optString("code", "")
        val body = when {
            func.isNotBlank() -> "return (" + func + ").apply(null, " + (a.optJSONArray("args") ?: JSONArray()).toString() + ");"
            code.isNotBlank() -> code
            else -> filesSource(e, a.optJSONArray("files")) ?: return replyError(ep, cid, "Файл скрипта не найден в расширении")
        }
        runInTab(ep, cid, wv, ext, "function(chrome,browser){\n$body\n}", "null,null,false")
    }

    private fun insertCss(ep: ExtEndpoint, ext: String, cid: Int, a: JSONObject, remove: Boolean) {
        val e = ExtensionStore.get(ext) ?: return replyError(ep, cid, "Нет расширения")
        val tabId = targetTab(a)
        val wv = tabHost?.webViewOf(tabId) ?: return replyError(ep, cid, "No tab with id: $tabId.")
        val inline = if (a.isNull("css")) "" else a.optString("css", "")
        val css = inline.ifBlank {
            val files = ManifestParser.stringList(a.optJSONArray("files"))
            files.mapNotNull { f -> ExtensionStore.readText(e, f.trimStart('/'))?.let { rewriteCss(e, f.trimStart('/'), it) } }.joinToString("\n")
        }
        if (css.isBlank()) return replyOk(ep, cid, JSONArray())
        val key = "ins:" + ext + ":" + css.hashCode()
        runInTab(ep, cid, wv, ext, "null", JSONObject.quote(css) + "," + JSONObject.quote(key) + "," + remove)
    }

    private fun runInTab(ep: ExtEndpoint, cid: Int, wv: WebView, ext: String, fnLiteral: String, cssArgs: String) {
        val xid = ++xidSeq
        execs[xid] = ep to cid
        val js = "(function(){var X=window['__omniExec_$run'];if(!X)return 'omni:missing';X('$token','$ext',$xid,$fnLiteral,$cssArgs);return 'omni:ok';})()"
        wv.evaluateJavascript(js) { r ->
            if (r != null && r.contains("omni:missing")) {
                execs.remove(xid)?.let { replyError(it.first, it.second, "Нет доступа к странице: обновите вкладку после включения расширения") }
            }
        }
        main.postDelayed({ execs.remove(xid)?.let { replyOk(it.first, it.second, JSONArray()) } }, 15_000L)
    }

    private fun registerScripts(ext: String, incoming: JSONArray, update: Boolean) {
        val existing = ExtensionStore.registeredScripts(ext)
        val map = LinkedHashMap<String, JSONObject>()
        for (i in 0 until existing.length()) existing.optJSONObject(i)?.let { map[it.optString("id")] = it }
        for (i in 0 until incoming.length()) {
            val s = incoming.optJSONObject(i) ?: continue
            val id = s.optString("id")
            if (id.isEmpty()) continue
            val prev = map[id]
            if (update && prev != null) {
                s.keys().forEach { k -> prev.put(k, s.opt(k)) }
            } else {
                map[id] = s
            }
        }
        ExtensionStore.saveRegisteredScripts(ext, JSONArray(map.values.toList()))
        rebuildScripts()
    }

    // ---------------- cookies / downloads / http ----------------

    private fun cookieJson(url: String, name: String, value: String): JSONObject {
        val host = Uri.parse(url).host ?: ""
        return JSONObject().put("name", name).put("value", value).put("domain", host).put("hostOnly", true).put("path", "/")
            .put("secure", url.startsWith("https")).put("httpOnly", false).put("sameSite", "unspecified")
            .put("session", true).put("storeId", "0")
    }

    private fun cookiesFor(a: JSONObject): List<JSONObject> {
        val url = when {
            a.has("url") -> a.optString("url")
            a.has("domain") -> "https://" + a.optString("domain").trimStart('.') + "/"
            else -> return emptyList()
        }
        val raw = CookieManager.getInstance().getCookie(url) ?: return emptyList()
        return raw.split(';').mapNotNull { part ->
            val p = part.trim()
            if (p.isEmpty()) return@mapNotNull null
            val eq = p.indexOf('=')
            if (eq <= 0) null else cookieJson(url, p.substring(0, eq), p.substring(eq + 1))
        }
    }

    private fun startDownload(a: JSONObject): Int {
        return try {
            val url = a.optString("url")
            val name = a.optString("filename", "").substringAfterLast('/').ifBlank { URLUtil.guessFileName(url, null, null) }
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req).toInt()
        } catch (_: Exception) {
            -1
        }
    }

    private val forbiddenRequestHeaders = setOf("accept-encoding", "host", "content-length", "connection", "cookie", "origin", "referer")

    private fun httpRequest(ep: ExtEndpoint, cid: Int, a: JSONObject) {
        val url = a.optString("url")
        var method = a.optString("method", "GET").uppercase()
        val headers = a.optJSONObject("headers")
        var body: ByteArray? = if (a.isNull("body")) null else a.optString("body", "").takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.NO_WRAP) }
        io.execute {
            var error: String? = null
            var result: JSONObject? = null
            try {
                var current = URL(url)
                var redirected = false
                var hops = 0
                lateinit var conn: HttpURLConnection
                while (true) {
                    conn = (ExtensionProxy.javaProxy()?.let { current.openConnection(it) } ?: current.openConnection()) as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.requestMethod = method
                    conn.connectTimeout = 20_000
                    conn.readTimeout = 60_000
                    var hasUa = false
                    headers?.keys()?.forEach { k ->
                        val lk = k.lowercase()
                        if (lk == "user-agent") hasUa = true
                        if (lk !in forbiddenRequestHeaders) conn.setRequestProperty(k, headers.optString(k))
                    }
                    if (!hasUa) conn.setRequestProperty("User-Agent", ExtensionStore.DESKTOP_UA)
                    CookieManager.getInstance().getCookie(current.toString())?.let { conn.setRequestProperty("Cookie", it) }
                    val payload = body
                    if (payload != null && method != "GET" && method != "HEAD") {
                        conn.doOutput = true
                        conn.outputStream.use { it.write(payload) }
                    }
                    val code = conn.responseCode
                    if (code in 301..308 && code != 304 && hops < 10) {
                        val loc = conn.getHeaderField("Location")
                        if (loc != null) {
                            current = URL(current, loc)
                            redirected = true
                            hops++
                            conn.disconnect()
                            if (code == 303 || ((code == 301 || code == 302) && method == "POST")) {
                                method = "GET"
                                body = null
                            }
                            continue
                        }
                    }
                    break
                }
                val code = conn.responseCode
                val stream = try {
                    if (code >= 400) conn.errorStream else conn.inputStream
                } catch (_: Exception) {
                    null
                }
                val bytes = stream?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(32 * 1024)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > 50 * 1024 * 1024) break
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                } ?: ByteArray(0)
                val hdrs = JSONObject()
                for ((k, v) in conn.headerFields) {
                    if (k == null) continue
                    val lk = k.lowercase()
                    if (lk == "set-cookie" || lk == "set-cookie2") {
                        v.forEach { CookieManager.getInstance().setCookie(current.toString(), it) }
                        continue
                    }
                    if (lk == "content-encoding" || lk == "content-length" || lk == "transfer-encoding") continue
                    hdrs.put(lk, v.joinToString(", "))
                }
                result = JSONObject().put("status", code).put("statusText", conn.responseMessage ?: "")
                    .put("headers", hdrs).put("body", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    .put("url", current.toString()).put("redirected", redirected)
                conn.disconnect()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            main.post {
                val r = result
                if (r != null) replyOk(ep, cid, r) else replyError(ep, cid, "Failed to fetch: $error")
            }
        }
    }
}
