package com.winwasm64.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.winwasm64.vpn.VpnLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v4.3.7: решатель `n`/`sig` YouTube кодом самого плеера — тот же подход, что у yt-dlp (`yt-dlp/ejs`, Unlicense).
 *
 * Почему: параметр `n` в адресах из ответа плеера — «задача», которую плеер решает своим JS и подставляет ответ;
 * без правильного ответа googlevideo отдаёт 403 (журнал 19:09: n из запроса плеера ≠ n в адресе ответа —
 * мы подставляли чужой ответ). Скопировать решение из SABR-запроса нельзя — у него другая задача.
 *
 * Как: скрытый WebView с пустой страницей (база https://www.youtube.com/, чтобы код плеера видел привычный
 * `location`), в него один раз загружаются `yt.solver.lib.min.js` (meriyah + astring) и `yt.solver.core.js` из
 * assets/ytsolver, затем для каждого запроса `window.__omniSolve(id, key, ns, sigs)` читает код плеера через
 * `OmniSolver.player(id)`, решает задачи и возвращает JSON через `OmniSolver.done(id, json)`. Разобранный плеер
 * кэшируется на странице (повторные решения — десятки мс).
 */
@SuppressLint("SetJavaScriptEnabled")
object JsSolver {
    private const val TAG = MediaGrabber.TAG
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var wv: WebView? = null
    @Volatile private var ready = false
    @Volatile private var loadError: String? = null
    private val whenReady = ArrayList<() -> Unit>()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val preparedKeys = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private class Pending(val playerSrc: String?) {
        val latch = CountDownLatch(1)
        @Volatile var result: String? = null
    }

    /** Разобранный плеер с таким ключом уже лежит в скрытой странице — исходник не нужен. */
    fun hasPlayer(key: String): Boolean = preparedKeys.contains(key) && wv != null

    internal object Bridge {
        @JavascriptInterface
        fun player(id: String): String = pending[id]?.playerSrc ?: ""

        @JavascriptInterface
        fun done(id: String, json: String) {
            pending.remove(id)?.let { it.result = json; it.latch.countDown() }
        }

        @JavascriptInterface
        fun log(msg: String) { VpnLog.i(TAG, "решатель n: $msg") }
    }

    private const val SOLVE_FN = """
window.__omniPre = {};
window.__omniSolve = function(id, key, ns, sigs){
  try {
    var inp;
    if (window.__omniPre[key]) inp = { type: 'preprocessed', preprocessed_player: window.__omniPre[key] };
    else {
      var src = OmniSolver.player(id);
      if (!src) throw 'нет кода плеера';
      inp = { type: 'player', player: src, output_preprocessed: true };
    }
    inp.requests = [{ type: 'n', challenges: ns }, { type: 'sig', challenges: sigs }];
    var out = window.__omniJsc(inp);
    if (out && out.preprocessed_player) { window.__omniPre = {}; window.__omniPre[key] = out.preprocessed_player; delete out.preprocessed_player; }
    OmniSolver.done(id, JSON.stringify(out));
  } catch (e) {
    OmniSolver.done(id, JSON.stringify({ type: 'error', error: String(e && e.stack || e) }));
  }
};
"""

    private fun ensure(ctx: Context) {
        if (wv != null) return
        val app = ctx.applicationContext
        val w = WebView(app)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = false
        w.settings.blockNetworkLoads = true
        w.addJavascriptInterface(Bridge, "OmniSolver")
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
            override fun onPageFinished(view: WebView?, url: String?) {
                if (ready) return
                val lib = readAsset(app, "ytsolver/yt.solver.lib.min.js")
                val core = readAsset(app, "ytsolver/yt.solver.core.js")
                if (lib == null || core == null) { loadError = "нет файлов решателя в assets"; flushReady(); return }
                val boot = "(function(){ try { " + lib + "\nvar meriyah = lib.meriyah, astring = lib.astring;\n" + core +
                    "\nwindow.__omniJsc = jsc;\n" + SOLVE_FN + "\nreturn 'ok'; } catch (e) { return 'err:' + e; } })()"
                w.evaluateJavascript(boot) { r ->
                    if (r != null && r.contains("ok")) { ready = true; VpnLog.i(TAG, "решатель n загружен (${(lib.length + core.length) / 1024} КБ)") }
                    else loadError = "не загрузился: ${r ?: "null"}"
                    flushReady()
                }
            }
        }
        wv = w
        w.loadDataWithBaseURL("https://www.youtube.com/watch?v=omni", "<!doctype html><html><head><meta charset=\"utf-8\"></head><body></body></html>", "text/html", "utf-8", null)
    }

    private fun flushReady() {
        val list = ArrayList(whenReady); whenReady.clear()
        for (r in list) r()
    }

    private fun readAsset(ctx: Context, name: String): String? = try {
        ctx.assets.open(name).use { String(it.readBytes(), Charsets.UTF_8) }
    } catch (_: Exception) { null }

    /**
     * Решает задачи [ns] (параметр n) и [sigs] (подпись) кодом плеера [playerSrc] (null — использовать уже разобранный
     * плеер с ключом [key]). Возвращает JSON решателя `{type:'result', responses:[{type,data|error},{…}]}` или null по таймауту.
     */
    fun solve(ctx: Context, key: String, playerSrc: String?, ns: List<String>, sigs: List<String>, timeoutMs: Long): JSONObject? {
        val id = UUID.randomUUID().toString()
        val p = Pending(playerSrc)
        pending[id] = p
        val run = {
            val w = wv
            if (w == null || !ready) {
                Bridge.done(id, JSONObject().put("type", "error").put("error", loadError ?: "решатель не готов").toString())
            } else {
                val js = "window.__omniSolve(" + JSONObject.quote(id) + "," + JSONObject.quote(key) + "," + JSONArray(ns) + "," + JSONArray(sigs) + ")"
                try { w.evaluateJavascript(js, null) } catch (e: Exception) { Bridge.done(id, JSONObject().put("type", "error").put("error", e.toString()).toString()) }
            }
        }
        main.post {
            try { ensure(ctx) } catch (e: Exception) { loadError = e.toString(); ready = false }
            if (ready || loadError != null) run() else whenReady.add(run)
        }
        if (!p.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            pending.remove(id)
            return null
        }
        val res = try { JSONObject(p.result ?: "{}") } catch (_: Exception) { JSONObject() }
        if (res.optString("type") == "result") { if (playerSrc != null) preparedKeys.clear(); preparedKeys.add(key) }
        return res
    }
}
