package com.winwasm64.ui.browser

import android.net.Uri
import com.winwasm64.vpn.BrowserDirectRepeater
import com.winwasm64.vpn.VpnLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Locale

/**
 * v4.3.0: «Скачать видео» из плеера любого сайта.
 *
 *  • [SNIFF_SCRIPT] выполняется до скриптов страницы: перехватывает fetch/XHR/ресурсы и запоминает адреса медиа
 *    (mp4/webm/m3u8/googlevideo…), сообщает приложению, что на странице есть видео (кнопка появляется);
 *  • `window.__omniListMedia()` отдаёт JSON: адрес страницы, элементы <video>/<audio>, форматы YouTube
 *    (из ответа плеера) и всё, что «подслушано» в сети;
 *  • [buildOptions] превращает это в список вариантов «качество / только аудио» для шторки;
 *  • сами загрузки выполняет [MediaDownloadService].
 *
 * Все сетевые запросы идут через локальный прокси браузера (127.0.0.1:8877 → Browsec), поэтому
 * работают там же, где работает сама страница.
 */
object MediaGrabber {
    const val TAG = "Загрузка"

    /** Встраивается до скриптов страницы (addDocumentStartJavaScript). Без символа `$` — это Kotlin raw string. */
    val SNIFF_SCRIPT: String = """
(function(){
  if (window.__omniGrab) return;
  var G = window.__omniGrab = { urls: {}, order: [], reported: false, seenEl: false, frames: {} };
  var isTop = true;
  try { isTop = (window.top === window); } catch (e) { isTop = false; }
  var pushToTop = function(){
    try {
      if (isTop || !window.__omniListMedia) return;
      window.top.postMessage({ __omniMediaFrame: window.__omniListMedia(), __omniFrameKey: location.href.slice(0, 200) }, '*');
    } catch (e) {}
  };
  if (isTop) {
    try {
      window.addEventListener('message', function(ev){
        try {
          var d = ev.data;
          if (d && typeof d === 'object' && typeof d.__omniMediaFrame === 'string') {
            G.frames[String(d.__omniFrameKey || 'frame')] = d.__omniMediaFrame;
            report();
          }
        } catch (e) {}
      }, false);
    } catch (e) {}
  }
  var report = function(){
    try {
      if (G.reported) return;
      G.reported = true;
      if (window.OmniMedia && OmniMedia.hasMedia) { OmniMedia.hasMedia(true); }
      if (!isTop) { var k = 0; var t = setInterval(function(){ pushToTop(); if (++k > 12) clearInterval(t); }, 2500); setTimeout(pushToTop, 300); }
    } catch (e) {}
  };
  var extRe = /\.(m3u8|mp4|webm|m4a|m4v|mp3|aac|ogg|oga|mov|flac|wav|opus|mkv)(\?|#|$)/i;
  var segRe = /\.(ts|m4s|aac|cmfv|cmfa)(\?|#|$)/i;
  var note = function(u, ct, cl){
    try {
      if (!u) return;
      u = String(u);
      if (u.indexOf('http') !== 0) return;
      var noHash = u.split('#')[0];
      var path = noHash.split('?')[0];
      var isYt = path.indexOf('/videoplayback') >= 0;
      ct = ct || '';
      var isMediaCt = /^(video|audio)\//i.test(ct) || /mpegurl/i.test(ct);
      if (!isYt && !extRe.test(path) && !isMediaCt) return;
      if (!isYt && segRe.test(path) && !/mpegurl/i.test(ct)) return;
      if (/^image\//i.test(ct)) return;
      if (isYt) {
        if (noHash.indexOf('itag=') < 0) return;
        if (noHash.indexOf('sabr=1') >= 0 && noHash.indexOf('itag=') < 0) return;
        noHash = noHash.replace(/[&?](range|rn|rbuf|ump|srfvp|alr)=[^&]*/g, function(m){ return m.charAt(0) === '?' ? '?' : ''; });
        noHash = noHash.replace(/\?&/, '?').replace(/[?&]$/, '');
      }
      var e = G.urls[noHash];
      if (!e) {
        e = G.urls[noHash] = { url: noHash, ct: ct, size: 0, t: Date.now() };
        G.order.push(noHash);
        if (G.order.length > 200) { delete G.urls[G.order.shift()]; }
        report();
      }
      if (ct && !e.ct) e.ct = ct;
      if (cl && cl > e.size) e.size = cl;
    } catch (x) {}
  };
  try {
    var of = window.fetch;
    if (typeof of === 'function') {
      window.fetch = function(input, init){
        var u = '';
        try { u = (typeof input === 'string') ? input : (input && input.url) || ''; } catch (e) {}
        var p = of.apply(this, arguments);
        try {
          p.then(function(res){
            try {
              var ct = res.headers.get('content-type') || '';
              var cl = parseInt(res.headers.get('content-length') || '0', 10) || 0;
              note(res.url || u, ct, cl);
            } catch (e) {}
          }, function(){});
        } catch (e) {}
        return p;
      };
    }
  } catch (e) {}
  try {
    var XO = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(m, u){
      try {
        this.__omniUrl = u;
        this.addEventListener('readystatechange', function(){
          try {
            if (this.readyState >= 2) {
              var ct = this.getResponseHeader('content-type') || '';
              var cl = parseInt(this.getResponseHeader('content-length') || '0', 10) || 0;
              note(this.responseURL || this.__omniUrl, ct, cl);
            }
          } catch (e) {}
        });
      } catch (e) {}
      return XO.apply(this, arguments);
    };
  } catch (e) {}
  try {
    if (window.PerformanceObserver) {
      var po = new PerformanceObserver(function(list){
        try {
          var es = list.getEntries();
          for (var i = 0; i < es.length; i++) {
            var en = es[i];
            if (en.initiatorType === 'video' || en.initiatorType === 'audio' || en.initiatorType === 'fetch' || en.initiatorType === 'xmlhttprequest' || en.initiatorType === 'other') {
              note(en.name, '', en.decodedBodySize || en.transferSize || 0);
            }
          }
        } catch (e) {}
      });
      po.observe({ entryTypes: ['resource'] });
    }
  } catch (e) {}
  var checkEls = function(){
    try {
      if (G.reported) return;
      var vs = document.querySelectorAll('video, audio');
      for (var i = 0; i < vs.length; i++) {
        var v = vs[i];
        if (v.currentSrc || v.src || v.readyState > 0 || v.querySelector('source')) { G.seenEl = true; report(); return; }
      }
    } catch (e) {}
  };
  try {
    ['loadedmetadata', 'loadeddata', 'play', 'playing', 'durationchange'].forEach(function(ev){
      document.addEventListener(ev, function(e){ try { if (e.target && (e.target.tagName === 'VIDEO' || e.target.tagName === 'AUDIO')) report(); } catch (x) {} }, true);
    });
  } catch (e) {}
  try { var n = 0; var tm = setInterval(function(){ checkEls(); if (G.reported || ++n > 60) clearInterval(tm); }, 2000); } catch (e) {}
  window.__omniListMedia = function(){
    var out = { page: location.href, title: document.title || '', els: [], yt: null, sniffed: [] };
    try {
      var vs = document.querySelectorAll('video, audio');
      for (var i = 0; i < vs.length; i++) {
        var v = vs[i];
        var srcs = [];
        var s = v.currentSrc || v.src || '';
        if (s) srcs.push({ src: s, type: '' });
        var ss = v.querySelectorAll('source');
        for (var j = 0; j < ss.length; j++) srcs.push({ src: ss[j].src || ss[j].getAttribute('src') || '', type: ss[j].type || '' });
        var poster = '';
        try { poster = v.getAttribute('poster') || ''; } catch (e) {}
        out.els.push({ tag: v.tagName.toLowerCase(), w: v.videoWidth || 0, h: v.videoHeight || 0, dur: isFinite(v.duration) ? v.duration : 0, srcs: srcs, poster: poster });
      }
    } catch (e) {}
    try {
      var hn = location.hostname || '';
      if (/(^|\.)youtube(-nocookie)?\.com$/.test(hn) || hn === 'youtu.be') {
        var pr = null;
        try { var mp = document.getElementById('movie_player'); if (mp && mp.getPlayerResponse) pr = mp.getPlayerResponse(); } catch (e) {}
        if (!pr || !pr.streamingData) { try { if (window.ytInitialPlayerResponse && window.ytInitialPlayerResponse.streamingData) pr = window.ytInitialPlayerResponse; } catch (e) {} }
        var vid = '';
        try { vid = (pr && pr.videoDetails && pr.videoDetails.videoId) || ''; } catch (e) {}
        if (!vid) { try { vid = new URL(location.href).searchParams.get('v') || ''; } catch (e) {} }
        if (!vid) { var m = location.pathname.match(/\/(shorts|embed|live|v)\/([A-Za-z0-9_-]{6,})/); if (m) vid = m[2]; }
        if (!vid && hn === 'youtu.be') { var m2 = location.pathname.match(/^\/([A-Za-z0-9_-]{6,})/); if (m2) vid = m2[1]; }
        var fm = [];
        var push = function(muxed){ return function(f){
          try {
            fm.push({ itag: f.itag || 0, url: f.url || '', cipher: !!(f.signatureCipher || f.cipher), mime: f.mimeType || '', q: f.qualityLabel || f.quality || '', br: f.bitrate || 0, size: parseInt(f.contentLength || '0', 10) || 0, fps: f.fps || 0, h: f.height || 0, w: f.width || 0, muxed: muxed, lang: (f.audioTrack && f.audioTrack.displayName) || '', dflt: !!(f.audioTrack && f.audioTrack.audioIsDefault) });
          } catch (e) {}
        }; };
        if (pr && pr.streamingData) {
          (pr.streamingData.formats || []).forEach(push(true));
          (pr.streamingData.adaptiveFormats || []).forEach(push(false));
        }
        out.yt = {
          id: vid,
          title: (pr && pr.videoDetails && pr.videoDetails.title) || '',
          author: (pr && pr.videoDetails && pr.videoDetails.author) || '',
          dur: parseInt((pr && pr.videoDetails && pr.videoDetails.lengthSeconds) || '0', 10) || 0,
          live: !!(pr && pr.videoDetails && pr.videoDetails.isLive),
          formats: fm,
          hls: (pr && pr.streamingData && pr.streamingData.hlsManifestUrl) || ''
        };
      }
    } catch (e) {}
    try { for (var k in G.urls) { if (G.urls.hasOwnProperty(k)) out.sniffed.push(G.urls[k]); } } catch (e) {}
    try {
      for (var fk in G.frames) {
        if (!G.frames.hasOwnProperty(fk)) continue;
        var fr = JSON.parse(G.frames[fk]);
        if (fr.els) for (var a = 0; a < fr.els.length; a++) out.els.push(fr.els[a]);
        if (fr.sniffed) for (var b = 0; b < fr.sniffed.length; b++) out.sniffed.push(fr.sniffed[b]);
        if (fr.yt && !out.yt) { out.yt = fr.yt; if (!out.title && fr.yt.title) out.title = fr.yt.title; }
      }
    } catch (e) {}
    try { return JSON.stringify(out); } catch (e) { return '{}'; }
  };
})();
"""

    /** Один вариант в шторке «Скачать». */
    class Option(
        val title: String,
        val subtitle: String,
        val isAudio: Boolean,
        val job: DownloadJob
    )

    /** Что скачивать: передаётся сервису загрузок как JSON. */
    class DownloadJob(
        /** single — один файл; merge — видео+аудио склеить (mp4); hls — плейлист m3u8; audio_extract — вытащить звук из mp4. */
        val mode: String,
        val title: String,
        val fileName: String,
        val mime: String,
        val urls: List<String>,
        val headers: Map<String, String>,
        val expectedBytes: Long = 0L,
        val isAudio: Boolean = false
    ) {
        fun toJson(): String = JSONObject().apply {
            put("mode", mode); put("title", title); put("fileName", fileName); put("mime", mime)
            put("urls", JSONArray(urls)); put("headers", JSONObject(headers as Map<*, *>))
            put("expectedBytes", expectedBytes); put("isAudio", isAudio)
        }.toString()

        companion object {
            fun fromJson(s: String): DownloadJob? = try {
                val o = JSONObject(s)
                val urls = ArrayList<String>()
                val ua = o.optJSONArray("urls") ?: JSONArray()
                for (i in 0 until ua.length()) urls.add(ua.getString(i))
                val headers = LinkedHashMap<String, String>()
                val ho = o.optJSONObject("headers")
                if (ho != null) for (k in ho.keys()) headers[k] = ho.getString(k)
                DownloadJob(
                    o.getString("mode"), o.optString("title"), o.getString("fileName"), o.optString("mime", "application/octet-stream"),
                    urls, headers, o.optLong("expectedBytes", 0L), o.optBoolean("isAudio", false)
                )
            } catch (_: Exception) { null }
        }
    }

    // ------------------------------------------------------------------ HTTP через локальный прокси браузера

    const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    fun proxy(): Proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", BrowserDirectRepeater.ensureServerRunning()))

    fun open(url: String, headers: Map<String, String>, method: String = "GET", readTimeoutMs: Int = 25_000): HttpURLConnection {
        val c = URL(url).openConnection(proxy()) as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = readTimeoutMs
        c.instanceFollowRedirects = true
        c.requestMethod = method
        var hasUa = false
        for ((k, v) in headers) {
            if (v.isBlank()) continue
            if (k.equals("User-Agent", true)) hasUa = true
            c.setRequestProperty(k, v)
        }
        if (!hasUa) c.setRequestProperty("User-Agent", DEFAULT_UA)
        c.setRequestProperty("Accept-Encoding", "identity")
        c.setRequestProperty("Accept", "*/*")
        return c
    }

    fun getText(url: String, headers: Map<String, String>, maxBytes: Int = 4 * 1024 * 1024): String {
        val c = open(url, headers)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return readAll(c.inputStream, maxBytes).toString(Charsets.UTF_8)
        } finally { c.disconnect() }
    }

    fun postJson(url: String, headers: Map<String, String>, body: String): String {
        val c = open(url, headers, "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return readAll(c.inputStream, 8 * 1024 * 1024).toString(Charsets.UTF_8)
        } finally { c.disconnect() }
    }

    fun readAll(ins: InputStream, maxBytes: Int): ByteArray {
        val bo = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        ins.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                bo.write(buf, 0, n)
                if (bo.size() > maxBytes) throw java.io.IOException("слишком большой ответ")
            }
        }
        return bo.toByteArray()
    }

    /** HEAD (или GET с Range 0-0) — размер и тип файла. */
    class Probe(val size: Long, val contentType: String, val acceptRanges: Boolean)

    fun probe(url: String, headers: Map<String, String>): Probe? = try {
        val c = open(url, headers, "GET", 10_000)
        c.setRequestProperty("Range", "bytes=0-0")
        try {
            val code = c.responseCode
            if (code !in 200..299) null else {
                val ct = c.getHeaderField("Content-Type") ?: ""
                val cr = c.getHeaderField("Content-Range")
                val size = when {
                    cr != null && cr.contains('/') -> cr.substringAfterLast('/').trim().toLongOrNull() ?: -1L
                    code == 200 -> c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                    else -> -1L
                }
                Probe(size, ct, code == 206)
            }
        } finally { c.disconnect() }
    } catch (_: Exception) { null }

    // ------------------------------------------------------------------ форматирование

    fun fmtSize(b: Long): String = when {
        b <= 0 -> ""
        b >= 1_073_741_824L -> String.format(Locale.US, "%.2f ГБ", b / 1_073_741_824.0)
        b >= 1_048_576L -> String.format(Locale.US, "%.1f МБ", b / 1_048_576.0)
        else -> "${b / 1024} КБ"
    }

    fun fmtDur(sec: Long): String = when {
        sec <= 0 -> ""
        sec >= 3600 -> String.format(Locale.US, "%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
        else -> String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
    }

    fun safeName(raw: String, fallback: String = "video"): String {
        var s = raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trim('.')
        if (s.length > 80) s = s.substring(0, 80).trim()
        return if (s.isBlank()) fallback else s
    }

    private fun cleanTitle(t: String): String = t
        .replace(Regex("\\s*[-–|]\\s*YouTube\\s*$"), "")
        .replace(Regex("^\\(\\d+\\)\\s*"), "")
        .trim()

    // ------------------------------------------------------------------ YouTube

    private class YtFormat(
        val itag: Int, val url: String, val mime: String, val q: String, val br: Int, val size: Long,
        val fps: Int, val h: Int, val muxed: Boolean, val lang: String, val dflt: Boolean
    ) {
        val isVideo get() = mime.startsWith("video/")
        val isAudio get() = mime.startsWith("audio/")
        val container get() = mime.substringAfter('/').substringBefore(';').trim()
        val codec get() = mime.substringAfter("codecs=\"", "").substringBefore('"')
    }

    private fun heightOfItag(itag: Int): Int = when (itag) {
        160, 278, 394 -> 144; 133, 242, 395 -> 240; 134, 243, 396, 18 -> 360; 135, 244, 397 -> 480
        136, 247, 398, 22, 298, 302 -> 720; 137, 248, 399, 299, 303 -> 1080; 264, 271, 400, 308 -> 1440; 266, 313, 401, 315 -> 2160
        else -> 0
    }

    private fun itagMime(itag: Int): String = when (itag) {
        18, 22, 37, 59, 78 -> "video/mp4"
        133, 134, 135, 136, 137, 160, 264, 266, 298, 299, 394, 395, 396, 397, 398, 399, 400, 401 -> "video/mp4"
        242, 243, 244, 247, 248, 271, 278, 302, 303, 308, 313, 315 -> "video/webm"
        139, 140, 141, 256, 258 -> "audio/mp4"
        249, 250, 251 -> "audio/webm"
        else -> ""
    }

    private fun parseYtFormats(yt: JSONObject): MutableList<YtFormat> {
        val out = ArrayList<YtFormat>()
        val arr = yt.optJSONArray("formats") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            if (f.optBoolean("cipher")) continue
            val url = f.optString("url")
            if (url.isBlank()) continue
            out.add(
                YtFormat(
                    f.optInt("itag"), url, f.optString("mime"), f.optString("q"), f.optInt("br"), f.optLong("size"),
                    f.optInt("fps"), f.optInt("h"), f.optBoolean("muxed"), f.optString("lang"), f.optBoolean("dflt")
                )
            )
        }
        return out
    }

    /** Форматы из «подслушанных» адресов googlevideo (когда плеер не отдал список). */
    private fun sniffedYtFormats(sniffed: JSONArray): List<YtFormat> {
        val out = ArrayList<YtFormat>()
        val seen = HashSet<Int>()
        for (i in 0 until sniffed.length()) {
            val s = sniffed.optJSONObject(i) ?: continue
            val url = s.optString("url")
            if (!url.contains("/videoplayback")) continue
            val u = try { Uri.parse(url) } catch (_: Exception) { continue }
            val itag = u.getQueryParameter("itag")?.toIntOrNull() ?: continue
            if (!seen.add(itag)) continue
            val mime = u.getQueryParameter("mime")?.let { Uri.decode(it) } ?: itagMime(itag)
            if (mime.isBlank()) continue
            val clen = u.getQueryParameter("clen")?.toLongOrNull() ?: 0L
            val dur = u.getQueryParameter("dur")?.toDoubleOrNull() ?: 0.0
            val br = if (clen > 0 && dur > 0) (clen * 8 / dur).toInt() else 0
            val h = heightOfItag(itag)
            val muxed = itag in setOf(18, 22, 37, 59, 78)
            out.add(YtFormat(itag, url, mime, if (h > 0) "${h}p" else "", br, clen, 0, h, muxed, "", true))
        }
        return out
    }

    /** Запасной путь: InnerTube-клиенты без шифрования подписи (ANDROID_VR, IOS). */
    private fun innerTubeFormats(videoId: String, headers: Map<String, String>): Pair<List<YtFormat>, String>? {
        data class Client(val name: String, val version: String, val ua: String, val id: Int, val extra: JSONObject)
        val clients = listOf(
            Client(
                "ANDROID_VR", "1.60.19",
                "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip", 28,
                JSONObject().put("androidSdkVersion", 32).put("osName", "Android").put("osVersion", "12L").put("deviceMake", "Oculus").put("deviceModel", "Quest 3")
            ),
            Client(
                "IOS", "20.10.4",
                "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)", 5,
                JSONObject().put("deviceMake", "Apple").put("deviceModel", "iPhone16,2").put("osName", "iPhone").put("osVersion", "18.3.2.22D82")
            )
        )
        for (cl in clients) {
            try {
                val clientObj = JSONObject().put("clientName", cl.name).put("clientVersion", cl.version).put("hl", "en").put("gl", "US")
                for (k in cl.extra.keys()) clientObj.put(k, cl.extra.get(k))
                val body = JSONObject()
                    .put("context", JSONObject().put("client", clientObj))
                    .put("videoId", videoId)
                    .put("contentCheckOk", true)
                    .put("racyCheckOk", true)
                    .toString()
                val h = LinkedHashMap<String, String>()
                h["User-Agent"] = cl.ua
                h["X-YouTube-Client-Name"] = cl.id.toString()
                h["X-YouTube-Client-Version"] = cl.version
                h["Origin"] = "https://www.youtube.com"
                val resp = postJson("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", h, body)
                val o = JSONObject(resp)
                val status = o.optJSONObject("playabilityStatus")?.optString("status") ?: ""
                val sd = o.optJSONObject("streamingData")
                if (sd == null || status != "OK") {
                    VpnLog.w(TAG, "YouTube ${cl.name}: ${status.ifBlank { "нет streamingData" }} ${o.optJSONObject("playabilityStatus")?.optString("reason") ?: ""}".trim())
                    continue
                }
                val list = ArrayList<YtFormat>()
                fun take(arr: JSONArray?, muxed: Boolean) {
                    if (arr == null) return
                    for (i in 0 until arr.length()) {
                        val f = arr.optJSONObject(i) ?: continue
                        val url = f.optString("url")
                        if (url.isBlank()) continue
                        val at = f.optJSONObject("audioTrack")
                        list.add(
                            YtFormat(
                                f.optInt("itag"), url, f.optString("mimeType"), f.optString("qualityLabel", f.optString("quality")),
                                f.optInt("bitrate"), f.optString("contentLength", "0").toLongOrNull() ?: 0L, f.optInt("fps"), f.optInt("height"),
                                muxed, at?.optString("displayName") ?: "", at?.optBoolean("audioIsDefault") ?: true
                            )
                        )
                    }
                }
                take(sd.optJSONArray("formats"), true)
                take(sd.optJSONArray("adaptiveFormats"), false)
                if (list.isNotEmpty()) {
                    val title = o.optJSONObject("videoDetails")?.optString("title") ?: ""
                    VpnLog.i(TAG, "YouTube: форматы получены через клиент ${cl.name} (${list.size})")
                    return Pair(list, title)
                }
            } catch (e: Exception) {
                VpnLog.w(TAG, "YouTube ${cl.name}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return null
    }

    private fun ytOptions(yt: JSONObject, sniffed: JSONArray, baseHeaders: Map<String, String>, pageTitle: String): List<Option> {
        val videoId = yt.optString("id")
        var title = yt.optString("title").ifBlank { cleanTitle(pageTitle) }
        val durSec = yt.optLong("dur")
        val formats = parseYtFormats(yt)
        // добавляем подслушанные itag, которых нет в списке плеера
        val known = formats.map { it.itag }.toHashSet()
        for (f in sniffedYtFormats(sniffed)) if (known.add(f.itag)) formats.add(f)

        val haveVideo = formats.any { it.isVideo && it.h >= 360 }
        val haveAudio = formats.any { it.isAudio }
        if ((!haveVideo || !haveAudio) && videoId.isNotBlank() && !yt.optBoolean("live")) {
            val fb = innerTubeFormats(videoId, baseHeaders)
            if (fb != null) {
                for (f in fb.first) if (known.add(f.itag)) formats.add(f)
                if (title.isBlank()) title = fb.second
            }
        }
        if (title.isBlank()) title = "YouTube $videoId"
        if (formats.isEmpty()) {
            val hls = yt.optString("hls")
            if (hls.isNotBlank()) return hlsOptions(hls, baseHeaders, title, durSec)
            return emptyList()
        }

        val name = safeName(title)
        // заголовки для googlevideo: UA должен совпадать с тем, кем получен адрес (web-адреса привязаны к UA/IP не строго, но Referer не нужен)
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = baseHeaders["User-Agent"] ?: DEFAULT_UA
        headers["Origin"] = "https://www.youtube.com"
        headers["Referer"] = "https://www.youtube.com/"

        val audioMp4 = formats.filter { it.isAudio && it.container == "mp4" && (it.dflt || it.lang.isBlank()) }.maxByOrNull { it.br }
            ?: formats.filter { it.isAudio && it.container == "mp4" }.maxByOrNull { it.br }
        val audioWebm = formats.filter { it.isAudio && it.container == "webm" && (it.dflt || it.lang.isBlank()) }.maxByOrNull { it.br }
            ?: formats.filter { it.isAudio && it.container == "webm" }.maxByOrNull { it.br }

        val out = ArrayList<Option>()
        // --- видео: по одному варианту на высоту; mp4 (avc1/av01) предпочтительнее — склеивается в mp4
        val videoOnly = formats.filter { it.isVideo && !it.muxed && it.h > 0 }
        val muxed = formats.filter { it.isVideo && it.muxed && it.h > 0 }
        val heights = (videoOnly.map { it.h } + muxed.map { it.h }).toSortedSet().reversed()
        for (h in heights) {
            val mp4v = videoOnly.filter { it.h == h && it.container == "mp4" }.sortedWith(compareBy<YtFormat> { if (it.codec.startsWith("avc1")) 0 else 1 }.thenByDescending { it.fps }.thenByDescending { it.br }).firstOrNull()
            val webmv = videoOnly.filter { it.h == h && it.container == "webm" }.maxByOrNull { it.br }
            val mux = muxed.filter { it.h == h }.maxByOrNull { it.br }
            when {
                mp4v != null && audioMp4 != null -> {
                    val size = if (mp4v.size > 0 && audioMp4.size > 0) mp4v.size + audioMp4.size else 0L
                    val fpsTxt = if (mp4v.fps > 30) "${mp4v.fps} к/с · " else ""
                    out.add(
                        Option(
                            "Видео ${h}p · MP4", "${fpsTxt}${mp4v.codec.substringBefore('.')} + AAC ${audioMp4.br / 1000} кбит/с · ${fmtSize(size).ifBlank { "размер неизвестен" }}", false,
                            DownloadJob("merge", title, "$name [${h}p].mp4", "video/mp4", listOf(mp4v.url, audioMp4.url), headers, size)
                        )
                    )
                }
                mux != null -> out.add(
                    Option(
                        "Видео ${h}p · MP4 со звуком", "один файл, без склейки · ${fmtSize(mux.size).ifBlank { "размер неизвестен" }}", false,
                        DownloadJob("single", title, "$name [${h}p].mp4", "video/mp4", listOf(mux.url), headers, mux.size)
                    )
                )
                webmv != null && audioWebm != null -> {
                    val size = if (webmv.size > 0 && audioWebm.size > 0) webmv.size + audioWebm.size else 0L
                    out.add(
                        Option(
                            "Видео ${h}p · WebM", "VP9/AV1 + Opus · ${fmtSize(size).ifBlank { "размер неизвестен" }}", false,
                            DownloadJob("merge", title, "$name [${h}p].webm", "video/webm", listOf(webmv.url, audioWebm.url), headers, size)
                        )
                    )
                }
                mp4v != null -> out.add(
                    Option(
                        "Видео ${h}p · MP4 без звука", "аудиодорожка недоступна · ${fmtSize(mp4v.size)}", false,
                        DownloadJob("single", title, "$name [${h}p, без звука].mp4", "video/mp4", listOf(mp4v.url), headers, mp4v.size)
                    )
                )
            }
            if (out.size >= 7) break
        }
        // --- только аудио (музыка)
        if (audioMp4 != null) {
            out.add(
                Option(
                    "Только аудио · M4A (музыка)", "AAC ${audioMp4.br / 1000} кбит/с · ${fmtSize(audioMp4.size).ifBlank { "размер неизвестен" }}" + (if (durSec > 0) " · ${fmtDur(durSec)}" else ""), true,
                    DownloadJob("single", title, "$name.m4a", "audio/mp4", listOf(audioMp4.url), headers, audioMp4.size, isAudio = true)
                )
            )
        }
        if (audioWebm != null) {
            out.add(
                Option(
                    "Только аудио · Opus (webm)", "Opus ${audioWebm.br / 1000} кбит/с · ${fmtSize(audioWebm.size).ifBlank { "размер неизвестен" }}", true,
                    DownloadJob("single", title, "$name.opus.webm", "audio/webm", listOf(audioWebm.url), headers, audioWebm.size, isAudio = true)
                )
            )
        }
        // другие языки озвучки
        val otherLangs = formats.filter { it.isAudio && it.container == "mp4" && it.lang.isNotBlank() && !it.dflt }
            .groupBy { it.lang }.mapNotNull { (_, l) -> l.maxByOrNull { it.br } }.take(4)
        for (a in otherLangs) {
            out.add(
                Option(
                    "Аудио · ${a.lang}", "AAC ${a.br / 1000} кбит/с · ${fmtSize(a.size)}", true,
                    DownloadJob("single", title, "$name [${safeName(a.lang)}].m4a", "audio/mp4", listOf(a.url), headers, a.size, isAudio = true)
                )
            )
        }
        if (out.isEmpty()) {
            val hls = yt.optString("hls")
            if (hls.isNotBlank()) return hlsOptions(hls, baseHeaders, title, durSec)
        }
        return out
    }

    // ------------------------------------------------------------------ HLS

    class HlsVariant(val url: String, val bandwidth: Long, val resolution: String, val codecs: String, val audioOnly: Boolean)

    fun resolveUrl(base: String, ref: String): String = try {
        if (ref.startsWith("http://") || ref.startsWith("https://")) ref else URL(URL(base), ref).toString()
    } catch (_: Exception) { ref }

    private fun attrs(line: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        val re = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")
        for (r in re.findAll(line)) m[r.groupValues[1]] = r.groupValues[2].trim('"')
        return m
    }

    fun parseMaster(text: String, base: String): List<HlsVariant> {
        val out = ArrayList<HlsVariant>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val l = lines[i].trim()
            if (l.startsWith("#EXT-X-STREAM-INF:")) {
                val a = attrs(l.substringAfter(':'))
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                if (j < lines.size) {
                    val codecs = a["CODECS"] ?: ""
                    val audioOnly = codecs.isNotBlank() && !codecs.contains("avc") && !codecs.contains("hvc") && !codecs.contains("hev") && !codecs.contains("av01") && !codecs.contains("vp0")
                    out.add(HlsVariant(resolveUrl(base, lines[j].trim()), a["BANDWIDTH"]?.toLongOrNull() ?: 0L, a["RESOLUTION"] ?: "", codecs, audioOnly))
                    i = j
                }
            } else if (l.startsWith("#EXT-X-MEDIA:") && l.contains("TYPE=AUDIO")) {
                val a = attrs(l.substringAfter(':'))
                val uri = a["URI"]
                if (!uri.isNullOrBlank()) out.add(HlsVariant(resolveUrl(base, uri), 0L, "", "audio", true))
            }
            i++
        }
        return out
    }

    private fun hlsOptions(masterUrl: String, headers: Map<String, String>, title: String, durSec: Long): List<Option> {
        val text = try { getText(masterUrl, headers, 2 * 1024 * 1024) } catch (e: Exception) {
            VpnLog.w(TAG, "HLS: не удалось получить плейлист — ${e.message}")
            return emptyList()
        }
        if (!text.contains("#EXTM3U")) return emptyList()
        val name = safeName(title)
        val out = ArrayList<Option>()
        val hasInit = text.contains("#EXT-X-MAP")
        val ext = if (hasInit) "mp4" else "ts"
        if (text.contains("#EXT-X-STREAM-INF")) {
            val variants = parseMaster(text, masterUrl)
            val seenRes = HashSet<String>()
            for (v in variants.filter { !it.audioOnly }.sortedByDescending { it.bandwidth }) {
                val h = v.resolution.substringAfter('x', "").toIntOrNull() ?: 0
                val key = if (h > 0) "${h}p" else "${v.bandwidth}"
                if (!seenRes.add(key)) continue
                val est = if (durSec > 0 && v.bandwidth > 0) durSec * v.bandwidth / 8 else 0L
                out.add(
                    Option(
                        "Видео ${if (h > 0) "${h}p" else "${v.bandwidth / 1000} кбит/с"} · HLS", "${v.codecs.ifBlank { "поток m3u8" }}${if (est > 0) " · ~${fmtSize(est)}" else ""}", false,
                        DownloadJob("hls", title, "$name [${key}].$ext", if (ext == "ts") "video/mp2t" else "video/mp4", listOf(v.url), headers, est)
                    )
                )
                if (out.size >= 6) break
            }
            val audio = variants.filter { it.audioOnly }.maxByOrNull { it.bandwidth }
            if (audio != null) {
                out.add(
                    Option(
                        "Только аудио · HLS", "аудиодорожка потока (${if (audio.codecs == "audio") "AAC" else audio.codecs})", true,
                        DownloadJob("hls", title, "$name.aac", "audio/aac", listOf(audio.url), headers, 0L, isAudio = true)
                    )
                )
            }
        } else if (text.contains("#EXTINF")) {
            out.add(
                Option(
                    "Видео · HLS-поток", "сегменты m3u8 будут собраны в один файл", false,
                    DownloadJob("hls", title, "$name.$ext", if (ext == "ts") "video/mp2t" else "video/mp4", listOf(masterUrl), headers, 0L)
                )
            )
        }
        if (out.isNotEmpty() && !out.any { it.isAudio }) {
            out.add(
                Option(
                    "Только аудио из потока · M4A", "скачать поток и извлечь звук", true,
                    DownloadJob("hls_audio", title, "$name.m4a", "audio/mp4", listOf(out.last { !it.isAudio }.job.urls[0]), headers, 0L, isAudio = true)
                )
            )
        }
        return out
    }

    // ------------------------------------------------------------------ общий конструктор

    /**
     * Собирает варианты загрузки. Выполнять в фоновом потоке (ходит в сеть: HLS-плейлисты, размер файлов, InnerTube).
     * @param json ответ `__omniListMedia()`
     */
    fun buildOptions(json: String, pageUrl: String, userAgent: String?, cookie: String?): List<Option> {
        val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
        val page = o.optString("page").ifBlank { pageUrl }
        val pageTitle = o.optString("title")
        val sniffed = o.optJSONArray("sniffed") ?: JSONArray()
        val els = o.optJSONArray("els") ?: JSONArray()
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = userAgent?.ifBlank { null } ?: DEFAULT_UA
        headers["Referer"] = page
        if (!cookie.isNullOrBlank()) headers["Cookie"] = cookie

        val yt = o.optJSONObject("yt")
        if (yt != null) {
            val list = try { ytOptions(yt, sniffed, headers, pageTitle) } catch (e: Exception) {
                VpnLog.w(TAG, "YouTube: ${e.message ?: e.javaClass.simpleName}"); emptyList()
            }
            if (list.isNotEmpty()) return list
        }

        val title = cleanTitle(pageTitle).ifBlank { Uri.parse(page).host ?: "video" }
        val name = safeName(title)
        val out = ArrayList<Option>()
        val seen = HashSet<String>()
        var durSec = 0L

        // 1) источники <video>/<audio>
        val direct = ArrayList<Triple<String, String, Boolean>>() // url, type, isAudioEl
        for (i in 0 until els.length()) {
            val e = els.optJSONObject(i) ?: continue
            if (e.optDouble("dur") > durSec) durSec = e.optDouble("dur").toLong()
            val isAudioEl = e.optString("tag") == "audio"
            val srcs = e.optJSONArray("srcs") ?: continue
            for (j in 0 until srcs.length()) {
                val s = srcs.optJSONObject(j) ?: continue
                val u = s.optString("src")
                if (!u.startsWith("http")) continue
                if (seen.add(u)) direct.add(Triple(u, s.optString("type"), isAudioEl))
            }
        }
        // 2) подслушанные адреса
        val sniffedList = ArrayList<Triple<String, String, Long>>()
        for (i in 0 until sniffed.length()) {
            val s = sniffed.optJSONObject(i) ?: continue
            val u = s.optString("url")
            if (!u.startsWith("http") || !seen.add(u)) continue
            sniffedList.add(Triple(u, s.optString("ct"), s.optLong("size")))
        }

        fun extOf(u: String): String {
            val p = Uri.parse(u).path ?: ""
            val e = p.substringAfterLast('.', "").lowercase(Locale.US)
            return if (e.length in 2..4 && e.all { it.isLetterOrDigit() }) e else ""
        }

        // HLS сначала (для них есть выбор качества)
        val hlsUrls = (direct.map { it.first } + sniffedList.map { it.first }).filter { extOf(it) == "m3u8" || it.contains(".m3u8") }
        for (h in hlsUrls.take(2)) {
            out.addAll(hlsOptions(h, headers, title, durSec))
            if (out.isNotEmpty()) break
        }

        // прямые файлы
        val fileCandidates = ArrayList<Pair<String, String>>() // url, hint type
        for ((u, t, _) in direct) if (!hlsUrls.contains(u) && extOf(u) != "mpd") fileCandidates.add(u to t)
        for ((u, ct, _) in sniffedList) if (!hlsUrls.contains(u) && extOf(u) != "mpd" && !u.contains("/videoplayback")) fileCandidates.add(u to ct)

        var fileCount = 0
        for ((u, hint) in fileCandidates) {
            if (fileCount >= 6) break
            val pr = probe(u, headers)
            val ct = (pr?.contentType ?: hint).lowercase(Locale.US)
            var ext = extOf(u)
            val isAudio = ct.startsWith("audio/") || ext in setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav")
            if (ext.isBlank() || ext == "php" || ext == "html") ext = when {
                ct.contains("mp4") -> if (isAudio) "m4a" else "mp4"
                ct.contains("webm") -> "webm"
                ct.contains("mpeg") && isAudio -> "mp3"
                ct.contains("ogg") -> "ogg"
                ct.contains("quicktime") -> "mov"
                ct.contains("x-matroska") -> "mkv"
                ct.startsWith("video/") || ct.startsWith("audio/") -> "bin"
                else -> ""
            }
            if (ext.isBlank()) continue
            if (pr != null && pr.size in 1..(200 * 1024L) && !isAudio) continue // явно не видео (превью/заглушка)
            val mime = if (ct.startsWith("video/") || ct.startsWith("audio/")) ct.substringBefore(';').trim() else if (isAudio) "audio/$ext" else "video/$ext"
            val size = pr?.size ?: -1L
            val host = Uri.parse(u).host ?: ""
            val label = if (isAudio) "Аудио · ${ext.uppercase(Locale.US)}" else "Видео · ${ext.uppercase(Locale.US)}" + (if (fileCount > 0) " (${fileCount + 1})" else "")
            out.add(
                Option(
                    label, "$host · ${if (size > 0) fmtSize(size) else "размер неизвестен"}" + (if (durSec > 0 && fileCount == 0) " · ${fmtDur(durSec)}" else ""), isAudio,
                    DownloadJob("single", title, if (fileCount == 0) "$name.$ext" else "$name (${fileCount + 1}).$ext", mime, listOf(u), headers, if (size > 0) size else 0L, isAudio)
                )
            )
            if (!isAudio && (ext == "mp4" || ext == "m4v" || ext == "mov" || ext == "webm")) {
                out.add(
                    Option(
                        "Только аудио из видео · ${if (ext == "webm") "WebM" else "M4A"}", "скачать и извлечь звуковую дорожку", true,
                        DownloadJob("audio_extract", title, if (ext == "webm") "$name.audio.webm" else "$name.m4a", if (ext == "webm") "audio/webm" else "audio/mp4", listOf(u), headers, if (size > 0) size else 0L, isAudio = true)
                    )
                )
            }
            fileCount++
        }
        return out
    }
}
