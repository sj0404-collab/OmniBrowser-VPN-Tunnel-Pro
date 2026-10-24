package com.winwasm64.ui.browser

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebView
import com.winwasm64.vpn.VpnLog
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v4.3.6: загрузка файла **самой страницей** (fetch внутри WebView) с передачей байтов в сервис по мосту `OmniMedia`.
 *
 * Зачем: адреса плеера YouTube (googlevideo) с pot/n, которые страница качает без проблем, при запросе из нашего
 * кода получают HTTP 403 даже через тот же сервер Browsec (журнал 18:03–18:05). Запрос из страницы идёт с теми же
 * заголовками, куками, UA и тем же сетевым маршрутом, что и у плеера — это самый честный способ повторить его.
 *
 * Поток: сервис → [start] → `window.__omniDl(id, url, total)` в WebView → страница читает файл кусками по 1 МБ
 * (`&range=a-b`, как сам плеер) → `OmniMedia.dlChunk(id, base64)` → байты пишутся в поток сервиса →
 * `OmniMedia.dlDone`/`dlError`.
 */
object InPageDownloads {
    private const val TAG = MediaGrabber.TAG
    private val webViews = ConcurrentHashMap<String, WeakReference<WebView>>()
    private val transfers = ConcurrentHashMap<String, Transfer>()
    private val main = Handler(Looper.getMainLooper())

    class Transfer internal constructor(val id: String, val tabId: String, private val out: OutputStream, private val onProgress: (Long) -> Unit) {
        val latch = CountDownLatch(1)
        @Volatile var error: String? = null
        @Volatile var bytes: Long = 0L
        @Volatile var lastActivity: Long = System.currentTimeMillis()
        @Volatile var finished = false

        internal fun chunk(b64: String) {
            if (finished) return
            try {
                val b = Base64.decode(b64, Base64.DEFAULT)
                out.write(b)
                bytes += b.size
                lastActivity = System.currentTimeMillis()
                onProgress(bytes)
            } catch (e: Exception) {
                fail(e.message ?: e.javaClass.simpleName)
            }
        }

        internal fun done() {
            if (finished) return
            finished = true
            try { out.flush() } catch (_: Exception) {}
            latch.countDown()
            transfers.remove(id)
        }

        internal fun fail(msg: String) {
            if (finished) return
            finished = true
            error = msg
            latch.countDown()
            transfers.remove(id)
        }
    }

    /** Контекст приложения (для кэша и скрытого WebView решателя) — запоминается при регистрации вкладки. */
    @Volatile var appContext: android.content.Context? = null

    /** Шторка загрузок запоминает WebView вкладки, чтобы сервис мог попросить страницу скачать файл. */
    fun register(tabId: String, wv: WebView) {
        webViews[tabId] = WeakReference(wv)
        appContext = wv.context.applicationContext
    }

    // ---- v4.3.7: текст по адресу силами страницы (например, код плеера YouTube — он уже в кэше браузера)
    private class TextWait { val latch = CountDownLatch(1); @Volatile var text: String? = null; @Volatile var error: String = "" }
    private val texts = ConcurrentHashMap<String, TextWait>()

    fun fetchText(tabId: String, url: String, timeoutMs: Long): String? {
        val wv = webViews[tabId]?.get() ?: return null
        val id = UUID.randomUUID().toString()
        val w = TextWait()
        texts[id] = w
        main.post {
            try {
                wv.evaluateJavascript("(function(){ try { if (!window.__omniFetchText) return 'no'; window.__omniFetchText(" + org.json.JSONObject.quote(id) + "," + org.json.JSONObject.quote(url) + "); return 'ok'; } catch (e) { return 'err:' + e; } })()") { r ->
                    if (r == null || !r.contains("ok")) { w.error = "страница не приняла запрос (${r ?: "null"})"; w.latch.countDown() }
                }
            } catch (e: Exception) { w.error = e.toString(); w.latch.countDown() }
        }
        val ok = w.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        texts.remove(id)
        if (!ok) { VpnLog.w(TAG, "страница не вернула ${url.take(80)} за ${timeoutMs / 1000} с"); return null }
        if (w.text == null) VpnLog.w(TAG, "страница не смогла получить ${url.take(80)}: ${w.error}")
        return w.text
    }

    fun onText(id: String, text: String, error: String) {
        val w = texts[id] ?: return
        if (error.isBlank() && text.isNotEmpty()) w.text = text else w.error = error.ifBlank { "пусто" }
        w.latch.countDown()
    }

    fun available(tabId: String): Boolean = tabId.isNotBlank() && webViews[tabId]?.get() != null

    /**
     * Запускает загрузку [url] страницей вкладки [tabId]; байты пишутся в [out]. Возвращает null, если вкладки уже нет.
     * @param total ожидаемый размер (0 — неизвестен)
     */
    fun start(tabId: String, url: String, total: Long, out: OutputStream, onProgress: (Long) -> Unit): Transfer? {
        val wv = webViews[tabId]?.get() ?: return null
        val t = Transfer(UUID.randomUUID().toString(), tabId, out, onProgress)
        transfers[t.id] = t
        val js = "(function(){ try { if (!window.__omniDl) return 'no'; window.__omniDl(" +
            org.json.JSONObject.quote(t.id) + "," + org.json.JSONObject.quote(url) + "," + total + "); return 'ok'; } catch (e) { return 'err:' + e; } })()"
        main.post {
            try {
                wv.evaluateJavascript(js) { r ->
                    if (r == null || !r.contains("ok")) t.fail("страница не приняла задание (${r ?: "null"})")
                }
            } catch (e: Exception) {
                t.fail(e.message ?: e.javaClass.simpleName)
            }
        }
        return t
    }

    fun cancel(t: Transfer) {
        t.fail("cancelled")
        val wv = webViews[t.tabId]?.get() ?: return
        main.post { try { wv.evaluateJavascript("try { window.__omniDlCancel && window.__omniDlCancel(" + org.json.JSONObject.quote(t.id) + "); } catch (e) {}", null) } catch (_: Exception) {} }
    }

    /** Ждёт завершения не дольше [idleTimeoutMs] без прогресса; [shouldCancel] опрашивается каждые 300 мс. */
    fun await(t: Transfer, idleTimeoutMs: Long, shouldCancel: () -> Boolean): Boolean {
        while (!t.latch.await(300, TimeUnit.MILLISECONDS)) {
            if (shouldCancel()) { cancel(t); return false }
            if (System.currentTimeMillis() - t.lastActivity > idleTimeoutMs) {
                cancel(t)
                t.error = "страница не отвечает ${idleTimeoutMs / 1000} с"
                return false
            }
        }
        return t.error == null
    }

    // ---- вызовы из моста OmniMedia (поток JavaBridge)
    fun onChunk(id: String, b64: String) { transfers[id]?.chunk(b64) }
    fun onDone(id: String, bytes: Long) {
        val t = transfers[id] ?: return
        if (bytes > 0 && bytes != t.bytes) VpnLog.w(TAG, "страница сообщила $bytes байт, получено ${t.bytes}")
        t.done()
    }
    fun onError(id: String, msg: String) { transfers[id]?.fail(msg) }
}
