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
  var G = window.__omniGrab = { urls: {}, order: [], reported: false, seenEl: false, frames: {}, pot: '', n: '' };
  var b64url = function(arr){
    var s = '';
    for (var i = 0; i < arr.length; i++) s += String.fromCharCode(arr[i]);
    return btoa(s).replace(/\+/g, '-').replace(/\//g, '_');
  };
  var pbFields = function(b){
    var out = []; var i = 0;
    var vi = function(){ var r = 0, sh = 0, x; do { x = b[i++]; r += (x & 127) * Math.pow(2, sh); sh += 7; } while (x & 128 && i < b.length); return r; };
    while (i < b.length) {
      var tag = vi(); var fn = Math.floor(tag / 8), wt = tag & 7;
      if (wt === 0) vi();
      else if (wt === 1) i += 8;
      else if (wt === 2) { var len = vi(); if (len < 0 || i + len > b.length) break; out.push({ f: fn, v: b.subarray(i, i + len) }); i += len; }
      else if (wt === 5) i += 4;
      else break;
    }
    return out;
  };
  var looksLikePot = function(v){
    try {
      if (v.length < 50 || v.length > 600 || v[0] !== 0x32) return false;
      var i = 1, len = 0, sh = 0, x;
      do { x = v[i++]; len += (x & 127) * Math.pow(2, sh); sh += 7; } while (x & 128 && i < 6);
      return len === v.length - i;
    } catch (e) { return false; }
  };
  var findPot = function(b){
    try {
      var top = pbFields(b);
      for (var i = 0; i < top.length; i++) {
        if (top[i].f !== 19) continue;
        var sc = pbFields(top[i].v);
        for (var j = 0; j < sc.length; j++) if (sc[j].f === 2 && sc[j].v.length >= 40) return b64url(sc[j].v);
      }
      var scan = function(fields, depth){
        for (var k = 0; k < fields.length; k++) {
          if (looksLikePot(fields[k].v)) return b64url(fields[k].v);
          if (depth < 3 && fields[k].v.length > 60) { var r = scan(pbFields(fields[k].v), depth + 1); if (r) return r; }
        }
        return '';
      };
      return scan(top, 0);
    } catch (e) {}
    return '';
  };
  var diag = { n: 0 };
  var trace = function(msg){ try { if (window.OmniMedia && OmniMedia.trace) OmniMedia.trace(msg); } catch (e) {} };
  var describeBody = function(body){
    try {
      if (!body) return 'нет тела';
      if (body instanceof ArrayBuffer) return 'ArrayBuffer ' + body.byteLength;
      if (body.buffer instanceof ArrayBuffer) return 'TypedArray ' + body.byteLength;
      if (typeof Blob !== 'undefined' && body instanceof Blob) return 'Blob ' + body.size;
      if (typeof body === 'string') return 'string ' + body.length;
      if (typeof ReadableStream !== 'undefined' && body instanceof ReadableStream) return 'ReadableStream';
      return Object.prototype.toString.call(body);
    } catch (e) { return '?'; }
  };
  var toBytes = function(body, cb){
    try {
      if (body instanceof ArrayBuffer) { cb(new Uint8Array(body)); return; }
      if (body && body.buffer instanceof ArrayBuffer) { cb(new Uint8Array(body.buffer, body.byteOffset || 0, body.byteLength)); return; }
      if (typeof Blob !== 'undefined' && body instanceof Blob && body.size < 65536) {
        var fr = new FileReader();
        fr.onload = function(){ try { cb(new Uint8Array(fr.result)); } catch (e) {} };
        fr.readAsArrayBuffer(body);
      }
    } catch (e) {}
  };
  var gotBytes = function(u, arr){
    try {
      if (G.pot || !arr || arr.length < 32 || arr.length > 65536) return;
      var pot = findPot(arr);
      if (!pot) {
        if (diag.n < 2) {
          diag.n++;
          var tf = pbFields(arr).map(function(f){ return f.f + ':' + f.v.length; }).join(' ');
          trace('SABR-тело ' + arr.length + ' байт, PO-token не найден; поля: ' + tf.slice(0, 300));
        }
        return;
      }
      G.pot = pot;
      var mn = u.match(/[?&]n=([^&#]+)/);
      G.n = mn ? decodeURIComponent(mn[1]) : '';
      report();
      trace('PO-token плеера перехвачен из SABR-запроса (' + pot.length + ' зн.' + (G.n ? ', n есть' : ', без n') + ')');
    } catch (e) {}
  };
  var noteBody = function(u, body, how){
    try {
      if (G.pot || !u) return;
      u = String(u);
      if (u.indexOf('/videoplayback') < 0) return;
      if (diag.n < 1 && !G.pot) {
        diag.n++;
        trace('запрос плеера: ' + how + ', ' + describeBody(body) + ', itag=' + (u.indexOf('itag=') >= 0) + ', pot в адресе=' + (u.indexOf('pot=') >= 0) + ', sabr=' + (u.indexOf('sabr=1') >= 0));
      }
      if (!body) return;
      toBytes(body, function(arr){ gotBytes(u, arr); });
    } catch (e) {}
  };
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
        if (noHash.indexOf('itag=') < 0 && noHash.indexOf('pot=') < 0) return;
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
        try {
          if (u && String(u).indexOf('/videoplayback') >= 0 && !G.pot) {
            if (init && init.body) noteBody(u, init.body, 'fetch+init');
            else if (input && typeof input === 'object' && input.clone && input.method && input.method.toUpperCase() === 'POST') {
              noteBody(u, null, 'fetch+Request');
              try { input.clone().arrayBuffer().then(function(buf){ gotBytes(String(u), new Uint8Array(buf)); }, function(){}); } catch (e) {}
            } else noteBody(u, null, 'fetch GET');
          }
        } catch (e) {}
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
    var XS = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.send = function(body){
      try { noteBody(this.__omniUrl, body, 'XHR'); } catch (e) {}
      return XS.apply(this, arguments);
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
  // v4.3.6: загрузка файла самой страницей (тот же fetch, что у плеера) → байты в сервис через OmniMedia.dlChunk
  var dlActive = {};
  window.__omniDlCancel = function(id){ dlActive[id] = false; };
  window.__omniDl = function(id, url, total){
    dlActive[id] = true;
    var CH = 1048576;
    var pos = 0;
    var toB64 = function(buf){
      var u8 = new Uint8Array(buf); var s = ''; var step = 0x8000;
      for (var i = 0; i < u8.length; i += step) s += String.fromCharCode.apply(null, u8.subarray(i, i + step));
      return btoa(s);
    };
    var fail = function(msg){ dlActive[id] = false; try { OmniMedia.dlError(id, String(msg)); } catch (e) {} };
    var finish = function(){ dlActive[id] = false; try { OmniMedia.dlDone(id, pos); } catch (e) {} };
    var step = function(){
      if (!dlActive[id]) { fail('cancelled'); return; }
      var end = pos + CH - 1;
      if (total > 0 && end >= total) end = total - 1;
      var want = end - pos + 1;
      var sep = url.indexOf('?') >= 0 ? '&' : '?';
      var u = url.replace(/([?&])range=[^&]*/, function(m, p){ return p; }) + sep + 'range=' + pos + '-' + end;
      fetch(u, { method: 'GET', credentials: 'omit', cache: 'no-store', redirect: 'follow' }).then(function(res){
        if (res.status !== 200 && res.status !== 206) { fail('HTTP ' + res.status); return null; }
        return res.arrayBuffer();
      }).then(function(buf){
        if (!buf) return;
        if (buf.byteLength === 0) { finish(); return; }
        OmniMedia.dlChunk(id, toB64(buf));
        pos += buf.byteLength;
        if (total > 0 ? pos >= total : buf.byteLength < want) { finish(); return; }
        step();
      }).catch(function(e){ fail(e && e.message ? e.message : e); });
    };
    step();
  };
  window.__omniListMedia = function(){
    var out = { page: location.href, title: document.title || '', els: [], yt: null, sniffed: [], pot: G.pot || '', n: G.n || '' };
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
        if (fr.pot && !out.pot) { out.pot = fr.pot; out.n = fr.n || ''; }
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
        val urls: MutableList<String>,
        val headers: Map<String, String>,
        val expectedBytes: Long = 0L,
        val isAudio: Boolean = false,
        /** YouTube: id ролика и itag каждой ссылки — чтобы при 403 получить свежие адреса через новый сервер. */
        val ytId: String = "",
        val itags: List<Int> = emptyList(),
        /** Закреплённый сервер Browsec (host:port): все соединения загрузки идут через него. Пусто — обычный прокси браузера. */
        @Volatile var server: String = "",
        /** v4.3.6: сначала пробовать скачать силами самой страницы (fetch в WebView вкладки [tabId]). */
        var inPage: Boolean = false,
        var tabId: String = ""
    ) {
        fun toJson(): String = JSONObject().apply {
            put("mode", mode); put("title", title); put("fileName", fileName); put("mime", mime)
            put("urls", JSONArray(urls)); put("headers", JSONObject(headers as Map<*, *>))
            put("expectedBytes", expectedBytes); put("isAudio", isAudio)
            put("ytId", ytId); put("itags", JSONArray(itags)); put("server", server)
            put("inPage", inPage); put("tabId", tabId)
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
                val itags = ArrayList<Int>()
                val ia = o.optJSONArray("itags") ?: JSONArray()
                for (i in 0 until ia.length()) itags.add(ia.getInt(i))
                DownloadJob(
                    o.getString("mode"), o.optString("title"), o.getString("fileName"), o.optString("mime", "application/octet-stream"),
                    urls, headers, o.optLong("expectedBytes", 0L), o.optBoolean("isAudio", false),
                    o.optString("ytId"), itags, o.optString("server"),
                    o.optBoolean("inPage", false), o.optString("tabId")
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

    // ------------------------------------------------------------------ подбор сервера по IP в ссылке

    private val egressCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    /** Внешний IP сервера Browsec [serverKey] (кэш 30 мин). */
    fun egressIp(serverKey: String): String? {
        val c = egressCache[serverKey]
        if (c != null && System.currentTimeMillis() - c.second < 1_800_000L) return c.first.ifBlank { null }
        val ip = try {
            TunnelHttp(serverKey).getText("https://api.ipify.org?format=text", emptyMap(), 4096).trim()
        } catch (_: Exception) {
            try { TunnelHttp(serverKey).getText("https://checkip.amazonaws.com/", emptyMap(), 4096).trim() } catch (_: Exception) { "" }
        }
        val ok = ip.length in 7..45 && ip.all { it.isDigit() || it == '.' || it == ':' || it.isLetter() && it.lowercaseChar() in 'a'..'f' }
        egressCache[serverKey] = Pair(if (ok) ip else "", System.currentTimeMillis())
        return if (ok) ip else null
    }

    /**
     * Ссылки googlevideo привязаны к IP (`ip=` в адресе) того сервера, через который страница получила ответ плеера.
     * Ищем сервер Browsec с таким внешним IP, чтобы качать строго через него. null — не нашли (страница шла напрямую
     * или у сервера несколько внешних адресов).
     */
    fun findServerForIp(ip: String, maxServers: Int = 8, waitMs: Long = 12_000L): String? {
        if (ip.isBlank()) return null
        val keys = try { com.winwasm64.extensions.ExtensionProxy.tunnelServerKeysRanked() } catch (_: Exception) { emptyList() }.take(maxServers)
        if (keys.isEmpty()) return null
        egressCache.entries.firstOrNull { it.value.first == ip && System.currentTimeMillis() - it.value.second < 1_800_000L }?.let { return it.key }
        val result = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val latch = java.util.concurrent.CountDownLatch(keys.size)
        for (k in keys) {
            Thread({
                try { if (egressIp(k) == ip) result.compareAndSet(null, k) } catch (_: Exception) {} finally { latch.countDown() }
            }, "omni-egress").apply { isDaemon = true; start() }
        }
        val deadline = System.currentTimeMillis() + waitMs
        while (result.get() == null && latch.count > 0 && System.currentTimeMillis() < deadline) {
            latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        var found = result.get()
        var stale = false
        if (found == null) {
            // v4.3.6: проверка не успела (пик, медленный сервер) — доверяем устаревшему значению из кэша
            egressCache.entries.firstOrNull { it.key in keys && it.value.first == ip }?.let { found = it.key; stale = true }
        }
        val known = egressCache.filter { it.key in keys }.map { "${it.key.substringBefore('.')}=${it.value.first.ifBlank { "?" }}" }
        if (found != null) VpnLog.i(TAG, "ссылка привязана к IP $ip — это сервер Browsec $found${if (stale) " (по старым данным, свежая проверка не успела)" else ""}, качаем строго через него")
        else VpnLog.w(TAG, "ссылка привязана к IP $ip, но ни у одного сервера Browsec нет такого адреса (${known.joinToString(" ")}) — качаем через прокси браузера")
        return found
    }

    /** Подсказка для шторки (почему вариантов может не быть / что сделать). */
    @Volatile var lastNotice: String = ""

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

    private fun YtFormat.copyUrl(u: String) = YtFormat(itag, u, mime, q, br, size, fps, h, muxed, lang, dflt)

    /** PO-token и уже расшифрованный параметр n из любого запроса плеера к googlevideo (одинаковы для всех форматов ролика). */
    private fun sniffedPotAndN(root: JSONObject, sniffed: JSONArray): Pair<String, String?>? {
        val pot0 = root.optString("pot")
        if (pot0.isNotBlank()) return Pair(pot0, root.optString("n").ifBlank { null })
        var best: Pair<String, String?>? = null
        for (i in 0 until sniffed.length()) {
            val s = sniffed.optJSONObject(i) ?: continue
            val url = s.optString("url")
            if (!url.contains("/videoplayback")) continue
            val u = try { Uri.parse(url) } catch (_: Exception) { continue }
            val pot = u.getQueryParameter("pot") ?: continue
            if (pot.isBlank()) continue
            val n = u.getQueryParameter("n")
            if (best == null || (best.second == null && n != null)) best = Pair(pot, n)
            if (best.second != null) break
        }
        return best
    }

    private fun patchYtUrl(url: String, pot: String, n: String?): String {
        var u = url
        if (n != null) {
            u = u.replace(Regex("([?&])n=[^&]*")) { m -> m.groupValues[1] + "n=" + Uri.encode(n) }
            if (!Regex("[?&]n=").containsMatchIn(u)) u += (if (u.contains('?')) "&" else "?") + "n=" + Uri.encode(n)
        }
        if (!Regex("[?&]pot=").containsMatchIn(u)) u += (if (u.contains('?')) "&" else "?") + "pot=" + Uri.encode(pot)
        return u
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
    /** Клиентский UA, которым получены адреса InnerTube (тем же UA их и качаем). */
    @Volatile private var lastInnerTubeUa: String = DEFAULT_UA

    private class InnerTubeResult(val formats: List<YtFormat>, val title: String, val hls: String, val client: String)

    private fun innerTubeFormats(videoId: String, http: TunnelHttp, only: Set<String>? = null): InnerTubeResult? {
        data class Client(val name: String, val version: String, val ua: String, val id: Int, val extra: JSONObject)
        val clients = listOf(
            // yt-dlp (10.2026): visionos — клиент по умолчанию без PO-token и без шифрования подписей
            Client(
                "VISIONOS", "1.02",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15", 101,
                JSONObject().put("deviceMake", "Apple").put("deviceModel", "RealityDevice17,1").put("osName", "visionOS").put("osVersion", "26.5.23O471")
            ),
            Client(
                "ANDROID_VR", "1.60.19",
                "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip", 28,
                JSONObject().put("androidSdkVersion", 32).put("osName", "Android").put("osVersion", "12L").put("deviceMake", "Oculus").put("deviceModel", "Quest 3")
            ),
            Client(
                "IOS", "21.26.4",
                "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)", 5,
                JSONObject().put("deviceMake", "Apple").put("deviceModel", "iPhone16,2").put("osName", "iPhone").put("osVersion", "18.3.2.22D82")
            )
        )
        for (cl in clients) {
            if (only != null && cl.name !in only) continue
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
                val resp = http.postJson("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", h, body)
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
                val hls = sd.optString("hlsManifestUrl")
                if (list.isNotEmpty() || hls.isNotBlank()) {
                    val title = o.optJSONObject("videoDetails")?.optString("title") ?: ""
                    lastInnerTubeUa = cl.ua
                    VpnLog.i(TAG, "YouTube: ответ клиента ${cl.name}: форматов ${list.size}${if (hls.isNotBlank()) ", есть HLS" else ""}, сервер ${http.serverKey ?: "?"}")
                    return InnerTubeResult(list, title, hls, cl.name)
                }
            } catch (e: Exception) {
                VpnLog.w(TAG, "YouTube ${cl.name}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return null
    }

    /** Свежие адреса для тех же itag через новый закреплённый сервер (после 403 / падения сервера). */
    fun ytRefresh(videoId: String, itags: List<Int>): Pair<List<String>, String>? {
        val http = TunnelHttp()
        val fb = innerTubeFormats(videoId, http, setOf("VISIONOS", "ANDROID_VR")) ?: return null
        val server = http.serverKey ?: return null
        val urls = ArrayList<String>()
        for (t in itags) {
            val f = fb.formats.firstOrNull { it.itag == t } ?: run {
                VpnLog.w(TAG, "YouTube: itag $t больше не отдаётся"); return null
            }
            urls.add(f.url)
        }
        return Pair(urls, server)
    }

    private fun ytOptions(root: JSONObject, yt: JSONObject, sniffed: JSONArray, baseHeaders: Map<String, String>, pageTitle: String): List<Option> {
        val videoId = yt.optString("id")
        var title = yt.optString("title").ifBlank { cleanTitle(pageTitle) }
        val durSec = yt.optLong("dur")
        val formats = parseYtFormats(yt)
        // добавляем подслушанные itag, которых нет в списке плеера
        val known = formats.map { it.itag }.toHashSet()
        for (f in sniffedYtFormats(sniffed)) if (known.add(f.itag)) formats.add(f)

        // v4.3.2: три пути, по порядку надёжности (см. журнал 12:21 / 14:05):
        //  A) адреса из ответа плеера страницы + PO-token (pot) и расшифрованный параметр n, подсмотренные в запросах
        //     самого плеера — качаем через обычный прокси браузера, как и сама страница;
        //  B) InnerTube ANDROID_VR через закреплённый сервер (без pot), если плеер ещё ничего не запрашивал;
        //  C) InnerTube IOS → HLS-манифест (HLS iOS не требует pot) через закреплённый сервер.
        var server = ""
        var itUa = ""
        var webMode = false
        val potN = sniffedPotAndN(root, sniffed)
        if (formats.isNotEmpty() && potN != null) {
            val (pot, nVal) = potN
            // v4.3.6: диагностика — одинаков ли n в SABR-адресе плеера и в адресах ответа плеера (если одинаков, плеер
            // его не преобразовывал, и 403 объясняется не им)
            try {
                val nPage = Uri.parse(formats[0].url).getQueryParameter("n") ?: ""
                VpnLog.i(TAG, "YouTube: n из запроса плеера=${nVal ?: "—"}, n в адресе ответа плеера=${nPage.ifBlank { "—" }}" +
                    (if (nVal != null && nPage.isNotBlank()) (if (nVal == nPage) " (совпадают)" else " (разные — плеер преобразует n)") else ""))
            } catch (_: Exception) {}
            val patched = formats.map { f -> f.copyUrl(patchYtUrl(f.url, pot, nVal)) }
            formats.clear(); formats.addAll(patched)
            webMode = true
            VpnLog.i(TAG, "YouTube: адреса плеера страницы + PO-token/n из запросов плеера (${formats.size} форматов${if (potN.second == null) ", без n" else ""})")
            val boundIp = try { Uri.parse(formats[0].url).getQueryParameter("ip") ?: "" } catch (_: Exception) { "" }
            server = findServerForIp(boundIp) ?: ""
            if (server.isBlank()) lastNotice = "Ссылки плеера привязаны к IP $boundIp, подходящий сервер Browsec не найден — загрузка пойдёт через прокси браузера и может получить 403."
        } else if (videoId.isNotBlank() && !yt.optBoolean("live")) {
            if (formats.isNotEmpty()) {
                VpnLog.w(TAG, "YouTube: PO-token плеера не перехвачен — пробуем InnerTube")
                lastNotice = "PO-token плеера ещё не перехвачен: запустите видео и подождите 15–20 секунд (плеер получает токен не сразу), затем откройте шторку снова."
            }
            val http = TunnelHttp()
            val vr = innerTubeFormats(videoId, http, setOf("VISIONOS", "ANDROID_VR"))
            if (vr != null && vr.formats.isNotEmpty()) {
                formats.clear(); known.clear()
                for (f in vr.formats) if (known.add(f.itag)) formats.add(f)
                if (title.isBlank()) title = vr.title
                server = http.serverKey ?: ""
                itUa = lastInnerTubeUa
            } else {
                val ios = innerTubeFormats(videoId, http, setOf("IOS"))
                if (ios != null && ios.hls.isNotBlank()) {
                    if (title.isBlank()) title = ios.title
                    val h = LinkedHashMap<String, String>()
                    h["User-Agent"] = lastInnerTubeUa
                    val srv = http.serverKey ?: ""
                    val list = hlsOptions(ios.hls, h, title.ifBlank { "YouTube $videoId" }, durSec, http, videoId, srv)
                    if (list.isNotEmpty()) {
                        VpnLog.i(TAG, "YouTube: используем HLS клиента IOS (${list.size} вариантов), сервер $srv")
                        return list
                    }
                }
                if (formats.isNotEmpty()) {
                    VpnLog.w(TAG, "YouTube: InnerTube не помог — адреса страницы без pot (возможен 403; запустите видео и повторите)")
                    webMode = true
                }
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
        if (server.isNotBlank() && !webMode) {
            headers["User-Agent"] = itUa
        } else {
            headers["User-Agent"] = baseHeaders["User-Agent"] ?: DEFAULT_UA
            headers["Origin"] = "https://www.youtube.com"
            headers["Referer"] = "https://www.youtube.com/"
        }
        fun job(mode: String, file: String, mime: String, fs: List<YtFormat>, size: Long, audio: Boolean = false) =
            DownloadJob(mode, title, file, mime, fs.map { it.url }.toMutableList(), headers, size, audio, videoId, fs.map { it.itag }, server, inPage = webMode)

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
                            job("merge", "$name [${h}p].mp4", "video/mp4", listOf(mp4v, audioMp4), size)
                        )
                    )
                }
                mux != null -> out.add(
                    Option(
                        "Видео ${h}p · MP4 со звуком", "один файл, без склейки · ${fmtSize(mux.size).ifBlank { "размер неизвестен" }}", false,
                        job("single", "$name [${h}p].mp4", "video/mp4", listOf(mux), mux.size)
                    )
                )
                webmv != null && audioWebm != null -> {
                    val size = if (webmv.size > 0 && audioWebm.size > 0) webmv.size + audioWebm.size else 0L
                    out.add(
                        Option(
                            "Видео ${h}p · WebM", "VP9/AV1 + Opus · ${fmtSize(size).ifBlank { "размер неизвестен" }}", false,
                            job("merge", "$name [${h}p].webm", "video/webm", listOf(webmv, audioWebm), size)
                        )
                    )
                }
                mp4v != null -> out.add(
                    Option(
                        "Видео ${h}p · MP4 без звука", "аудиодорожка недоступна · ${fmtSize(mp4v.size)}", false,
                        job("single", "$name [${h}p, без звука].mp4", "video/mp4", listOf(mp4v), mp4v.size)
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
                    job("single", "$name.m4a", "audio/mp4", listOf(audioMp4), audioMp4.size, audio = true)
                )
            )
        }
        if (audioWebm != null) {
            out.add(
                Option(
                    "Только аудио · Opus (webm)", "Opus ${audioWebm.br / 1000} кбит/с · ${fmtSize(audioWebm.size).ifBlank { "размер неизвестен" }}", true,
                    job("single", "$name.opus.webm", "audio/webm", listOf(audioWebm), audioWebm.size, audio = true)
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
                    job("single", "$name [${safeName(a.lang)}].m4a", "audio/mp4", listOf(a), a.size, audio = true)
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

    private fun hlsOptions(
        masterUrl: String, headers: Map<String, String>, title: String, durSec: Long,
        http: TunnelHttp? = null, ytId: String = "", server: String = ""
    ): List<Option> {
        val text = try { if (http != null) http.getText(masterUrl, headers, 2 * 1024 * 1024) else getText(masterUrl, headers, 2 * 1024 * 1024) } catch (e: Exception) {
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
                        DownloadJob("hls", title, "$name [${key}].$ext", if (ext == "ts") "video/mp2t" else "video/mp4", mutableListOf(v.url), headers, est, ytId = ytId, server = server)
                    )
                )
                if (out.size >= 6) break
            }
            val audio = variants.filter { it.audioOnly }.maxByOrNull { it.bandwidth }
            if (audio != null) {
                out.add(
                    Option(
                        "Только аудио · HLS", "аудиодорожка потока (${if (audio.codecs == "audio") "AAC" else audio.codecs})", true,
                        DownloadJob("hls", title, "$name.aac", "audio/aac", mutableListOf(audio.url), headers, 0L, isAudio = true, ytId = ytId, server = server)
                    )
                )
            }
        } else if (text.contains("#EXTINF")) {
            out.add(
                Option(
                    "Видео · HLS-поток", "сегменты m3u8 будут собраны в один файл", false,
                    DownloadJob("hls", title, "$name.$ext", if (ext == "ts") "video/mp2t" else "video/mp4", mutableListOf(masterUrl), headers, 0L, ytId = ytId, server = server)
                )
            )
        }
        if (out.isNotEmpty() && !out.any { it.isAudio }) {
            out.add(
                Option(
                    "Только аудио из потока · M4A", "скачать поток и извлечь звук", true,
                    DownloadJob("hls_audio", title, "$name.m4a", "audio/mp4", mutableListOf(out.last { !it.isAudio }.job.urls[0]), headers, 0L, isAudio = true, ytId = ytId, server = server)
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
        lastNotice = ""
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
            val list = try { ytOptions(o, yt, sniffed, headers, pageTitle) } catch (e: Exception) {
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
                    DownloadJob("single", title, if (fileCount == 0) "$name.$ext" else "$name (${fileCount + 1}).$ext", mime, mutableListOf(u), headers, if (size > 0) size else 0L, isAudio)
                )
            )
            if (!isAudio && (ext == "mp4" || ext == "m4v" || ext == "mov" || ext == "webm")) {
                out.add(
                    Option(
                        "Только аудио из видео · ${if (ext == "webm") "WebM" else "M4A"}", "скачать и извлечь звуковую дорожку", true,
                        DownloadJob("audio_extract", title, if (ext == "webm") "$name.audio.webm" else "$name.m4a", if (ext == "webm") "audio/webm" else "audio/mp4", mutableListOf(u), headers, if (size > 0) size else 0L, isAudio = true)
                    )
                )
            }
            fileCount++
        }
        return out
    }
}
