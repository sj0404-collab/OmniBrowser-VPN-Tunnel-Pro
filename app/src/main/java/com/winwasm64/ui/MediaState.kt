package com.winwasm64.ui

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** v4.2.0: состояние воспроизведения для «картинки в картинке» и фонового видео. */
object MediaState {
    /** Окно приложения сейчас в режиме «картинка в картинке». */
    var inPip by mutableStateOf(false)

    /** В активной вкладке играет видео/аудио. */
    var activeMediaPlaying by mutableStateOf(false)

    @Volatile
    var activeTabId: String? = null

    /** v4.2.3: обработчик команд кнопок окна PiP (play/pause/back/fwd); ставится экраном браузера. */
    @Volatile
    var controlHandler: ((String) -> Unit)? = null

    /** Когда пользователь последний раз нажимал кнопку в окне PiP (автовозобновление в это время не вмешивается). */
    @Volatile
    var lastUserControlAt = 0L

    fun control(cmd: String) {
        val h = controlHandler ?: return
        lastUserControlAt = android.os.SystemClock.elapsedRealtime()
        Handler(Looper.getMainLooper()).post { h(cmd) }
    }

    /** Управление видео на странице из окна PiP: пуск/пауза/перемотка ±10 с — для самого крупного или играющего элемента. */
    fun mediaControlScript(cmd: String): String = """
(function(cmd){
  try {
    var vs = Array.prototype.slice.call(document.querySelectorAll('video,audio'));
    if (!vs.length) return;
    var v = null;
    for (var i = 0; i < vs.length; i++) { if (!vs[i].paused && !vs[i].ended) { v = vs[i]; break; } }
    if (!v) { vs.sort(function(a, b){ return (b.clientWidth * b.clientHeight) - (a.clientWidth * a.clientHeight); }); v = vs[0]; }
    var play = function(){ try { var p = v.play(); if (p && p.catch) p.catch(function(){}); } catch (e) {} };
    var want = null;
    if (cmd === 'play') { play(); want = 'play'; }
    else if (cmd === 'pause') { window.__omniUserPauseAt = Date.now(); v.pause(); want = 'pause'; }
    else if (cmd === 'toggle') { if (v.paused) { play(); want = 'play'; } else { window.__omniUserPauseAt = Date.now(); v.pause(); want = 'pause'; } }
    else if (cmd === 'back') v.currentTime = Math.max(0, v.currentTime - 10);
    else if (cmd === 'fwd') { var d = isFinite(v.duration) ? v.duration : 1e12; v.currentTime = Math.min(d, v.currentTime + 10); }
    // сайт может «откатить» команду своим состоянием плеера — повторяем её дважды
    if (want) { [400, 1200].forEach(function(ms){ setTimeout(function(){ try { if (want === 'play' && v.paused && !v.ended) play(); else if (want === 'pause' && !v.paused) v.pause(); } catch (e) {} }, ms); }); }
  } catch (e) {}
})('$cmd');
"""

    private val mediaHosts = listOf(
        "youtube.com", "youtu.be", "youtube-nocookie.com", "twitch.tv", "rutube.ru", "vk.com", "vkvideo.ru", "ok.ru",
        "dzen.ru", "kinopoisk.ru", "soundcloud.com", "music.yandex", "vimeo.com", "dailymotion.com", "kick.com", "tiktok.com"
    )

    /** Сайт с видео/музыкой: его вкладку не скрываем от движка при переключении, чтобы воспроизведение не прерывалось. */
    fun isMediaSite(url: String?): Boolean {
        val host = try { android.net.Uri.parse(url ?: return false).host?.lowercase() ?: return false } catch (_: Exception) { return false }
        return mediaHosts.any { host == it || host.endsWith(".$it") || host.startsWith(it) }
    }

    /** Скрипт, который выполняется до скриптов страницы: учёт воспроизведения + страница не узнаёт, что скрыта. */
    const val DOCUMENT_START_SCRIPT = """
(function(){
  if (window.__omniMediaHooked) return; window.__omniMediaHooked = true;
  try {
    var last = null;
    var report = function(){
      try {
        var vs = document.querySelectorAll('video,audio'); var p = false;
        for (var i = 0; i < vs.length; i++) { var v = vs[i]; if (!v.paused && !v.ended) { p = true; break; } }
        if (p !== last) { last = p; if (window.OmniMedia) OmniMedia.state(p); }
      } catch (e) {}
    };
    ['play','playing','pause','ended','emptied','abort'].forEach(function(ev){ document.addEventListener(ev, function(){ setTimeout(report, 80); }, true); });
    setInterval(report, 3000);
    // v4.2.13: сколько видео реально играло и сколько трафика съела страница (ресурсы через PerformanceObserver;
    // для googlevideo, где размер скрыт политикой Timing-Allow-Origin, берём Content-Length из fetch/XHR)
    var S = window.__omniStats = { playedMs: 0, firstPlayAt: 0, lastPlayAt: 0, bytes: 0, media: 0, n: 0, lastCt: -1 };
    var clByUrl = {};
    var isMedia = function(u){ return u.indexOf('googlevideo.com') >= 0 || u.indexOf('videoplayback') >= 0 || u.indexOf('.m3u8') >= 0 || u.indexOf('.ts?') >= 0 || u.indexOf('.mp4') >= 0 || u.indexOf('.m4s') >= 0 || u.indexOf('.webm') >= 0; };
    var addBytes = function(u, b){ if (!(b > 0)) return; S.bytes += b; S.n++; if (isMedia(u)) S.media += b; };
    try {
      var po = new PerformanceObserver(function(list){
        try {
          var es = list.getEntries();
          for (var i = 0; i < es.length; i++) {
            var e = es[i]; var b = e.transferSize || e.encodedBodySize || 0;
            if (!b) { b = clByUrl[e.name] || 0; }
            delete clByUrl[e.name];
            addBytes(e.name, b);
          }
        } catch (x) {}
      });
      po.observe({ type: 'resource', buffered: true });
    } catch (e) {}
    try {
      var of = window.fetch;
      if (of) window.fetch = function(input, init){
        var u = (typeof input === 'string') ? input : (input && input.url) || '';
        return of.apply(this, arguments).then(function(res){
          try { var cl = parseInt(res.headers.get('content-length') || '0', 10); if (cl > 0 && u) clByUrl[u] = cl; } catch (x) {}
          return res;
        });
      };
    } catch (e) {}
    try {
      var oo = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = function(m, u){
        try { var self = this; self.__omniUrl = String(u); self.addEventListener('loadend', function(){ try { var cl = parseInt(self.getResponseHeader('content-length') || '0', 10); if (cl > 0) clByUrl[self.__omniUrl] = cl; } catch (x) {} }); } catch (x) {}
        return oo.apply(this, arguments);
      };
    } catch (e) {}
    document.addEventListener('timeupdate', function(e){
      try {
        var v = e.target; if (!v || (v.tagName !== 'VIDEO' && v.tagName !== 'AUDIO') || v.paused) return;
        var ct = v.currentTime; var d = ct - S.lastCt;
        if (S.lastCt >= 0 && d > 0 && d < 2) S.playedMs += d * 1000;
        S.lastCt = ct;
        if (!S.firstPlayAt) S.firstPlayAt = Date.now();
        S.lastPlayAt = Date.now();
      } catch (x) {}
    }, true);
    document.addEventListener('seeking', function(){ S.lastCt = -1; }, true);
    window.__omniStatsJson = function(){
      return JSON.stringify({ played: Math.round(S.playedMs), wall: S.firstPlayAt ? (Date.now() - S.firstPlayAt) : 0, bytes: Math.round(S.bytes), media: Math.round(S.media), n: S.n });
    };
    var statsAt = function(){ return { t: Date.now(), played: S.playedMs, bytes: S.bytes, media: S.media }; };
    var lastStop = statsAt();
    document.addEventListener('pause', function(e){
      try {
        var v = e.target; if (!v || (v.tagName !== 'VIDEO' && v.tagName !== 'AUDIO')) return;
        setTimeout(function(){
          try {
            var now = statsAt(); var seg = { played: now.played - lastStop.played, bytes: now.bytes - lastStop.bytes, media: now.media - lastStop.media, wall: now.t - lastStop.t };
            lastStop = now;
            if (seg.played < 1000) return;
            if (window.OmniMedia && OmniMedia.stats) OmniMedia.stats(JSON.stringify({ kind: 'stop', seg: seg, total: { played: S.playedMs, wall: S.firstPlayAt ? (Date.now() - S.firstPlayAt) : 0, bytes: S.bytes, media: S.media } }));
          } catch (x) {}
        }, 400);
      } catch (x) {}
    }, true);
    document.addEventListener('playing', function(){ try { if (lastStop.played < S.playedMs - 1000) return; lastStop = statsAt(); } catch (x) {} }, true);
    var pipMark = null;
    window.__omniPipMark = function(){ pipMark = statsAt(); };
    window.__omniPipReport = function(){
      try {
        if (!pipMark) return; var now = statsAt();
        if (window.OmniMedia && OmniMedia.stats) OmniMedia.stats(JSON.stringify({ kind: 'pip', seg: { played: now.played - pipMark.played, bytes: now.bytes - pipMark.bytes, media: now.media - pipMark.media, wall: now.t - pipMark.t }, total: { played: S.playedMs, wall: S.firstPlayAt ? (Date.now() - S.firstPlayAt) : 0, bytes: S.bytes, media: S.media } }));
        pipMark = null;
      } catch (x) {}
    };
    // каждые 5 минут непрерывной игры — промежуточная сводка
    var lastPeriodic = statsAt();
    setInterval(function(){
      try {
        if (Date.now() - S.lastPlayAt > 4000) { lastPeriodic = statsAt(); return; }
        var now = statsAt(); if (now.t - lastPeriodic.t < 300000) return;
        if (window.OmniMedia && OmniMedia.stats) OmniMedia.stats(JSON.stringify({ kind: 'period', seg: { played: now.played - lastPeriodic.played, bytes: now.bytes - lastPeriodic.bytes, media: now.media - lastPeriodic.media, wall: now.t - lastPeriodic.t }, total: { played: S.playedMs, wall: Date.now() - S.firstPlayAt, bytes: S.bytes, media: S.media } }));
        lastPeriodic = now;
      } catch (x) {}
    }, 10000);
    // v4.2.10: причина пауз в PiP — сам плеер YouTube: в его конфиге есть флаги html5_picture_in_picture_blocking_*
    // (onresize/ontimeupdate/standard_api/document_fullscreen) — плеер распознаёт маленькое окно PiP и ставит паузу
    // на каждом timeupdate. Как в Brave: переписываем флаги в ytcfg до создания плеера.
    var hn = location.hostname || '';
    if (hn === 'youtube.com' || hn.slice(-12) === '.youtube.com' || hn.slice(-21) === '.youtube-nocookie.com') {
      var flagRe = new RegExp('html5_picture_in_picture_(blocking|logging)_([a-z_]+)=true', 'g');
      var fixFlags = function(){
        try {
          var cfg = window.ytcfg; if (!cfg || !cfg.get) return;
          var confs = cfg.get('WEB_PLAYER_CONTEXT_CONFIGS'); if (!confs) return;
          var n = 0;
          for (var k in confs) {
            var c = confs[k]; if (!c || typeof c.serializedExperimentFlags !== 'string') continue;
            var f = c.serializedExperimentFlags.replace(flagRe, function(m, a, b){ return 'html5_picture_in_picture_' + a + '_' + b + '=false'; });
            if (f !== c.serializedExperimentFlags) { c.serializedExperimentFlags = f; n++; }
          }
          if (n && !window.__omniPipFlagsFixed) { window.__omniPipFlagsFixed = true; if (window.OmniMedia && OmniMedia.trace) OmniMedia.trace('YouTube: отключены флаги блокировки PiP в конфиге плеера (' + n + ')'); }
        } catch (e) {}
      };
      var hookSet = function(){
        try {
          var cfg = window.ytcfg; if (!cfg || !cfg.set || cfg.__omniHooked) return;
          cfg.__omniHooked = true; var os = cfg.set;
          cfg.set = function(){ var r = os.apply(this, arguments); fixFlags(); return r; };
        } catch (e) {}
      };
      fixFlags(); hookSet();
      document.addEventListener('load', function(e){ try { if (e.target && e.target.tagName === 'SCRIPT') { hookSet(); fixFlags(); } } catch (x) {} }, true);
      var t0 = Date.now(); var iv = setInterval(function(){ hookSet(); fixFlags(); if (Date.now() - t0 > 20000) clearInterval(iv); }, 250);
      document.addEventListener('DOMContentLoaded', function(){ hookSet(); fixFlags(); });
      window.__omniFixYtFlags = fixFlags;
    }
    // v4.2.10: с января 2026 YouTube останавливает видео у не-Premium, если страница «не видна» (проверяет
    // document.visibilityState, а не только событие). Для вкладки, которую смотрит пользователь (активная / PiP),
    // страница всегда «видима»: как в Brave и mozilla/video-bg-play.
    try {
      var held = function(){ return !!(window.__omniInPip || window.__omniHoldFocus); };
      var vsDesc = Object.getOwnPropertyDescriptor(Document.prototype, 'visibilityState');
      var hdDesc = Object.getOwnPropertyDescriptor(Document.prototype, 'hidden');
      if (vsDesc && vsDesc.get) Object.defineProperty(document, 'visibilityState', { configurable: true, get: function(){ return held() ? 'visible' : vsDesc.get.call(document); } });
      if (hdDesc && hdDesc.get) Object.defineProperty(document, 'hidden', { configurable: true, get: function(){ return held() ? false : hdDesc.get.call(document); } });
      var origHasFocus = document.hasFocus;
      document.hasFocus = function(){ return held() ? true : origHasFocus.call(document); };
      var swallow = function(e){ if (held()) { e.stopImmediatePropagation(); if (e.type === 'visibilitychange' && window.OmniMedia && OmniMedia.trace) OmniMedia.trace('событие visibilitychange скрыто от страницы (вкладка удерживается)'); } };
      window.addEventListener('visibilitychange', swallow, true);
      document.addEventListener('visibilitychange', swallow, true);
      window.addEventListener('pagehide', swallow, true);
      window.addEventListener('blur', function(e){ if (held() && e.target === window) e.stopImmediatePropagation(); }, true);
    } catch (e) {}
    // v4.2.9: причина пауз найдена — Chromium при потере аудиофокуса (второй плеер в другой вкладке / другое приложение)
    // шлёт странице действие mediaSession «pause», YouTube вызывает pauseVideo(); две вкладки качают фокус друг у друга
    // раз в секунду. Для вкладки, которую смотрит пользователь (активная или в PiP), такое «системное» pause игнорируем.
    try {
      var ms = navigator.mediaSession;
      if (ms && ms.setActionHandler) {
        var origSet = ms.setActionHandler.bind(ms);
        ms.setActionHandler = function(action, handler){
          if (action === 'pause' && typeof handler === 'function') {
            var wrapped = function(){
              window.__omniLastMsPause = Date.now();
              if (window.__omniInPip || window.__omniHoldFocus) {
                var n = (window.__omniMsIgnored || 0) + 1; window.__omniMsIgnored = n;
                if ((n <= 3 || n % 20 === 0) && window.OmniMedia && OmniMedia.trace) OmniMedia.trace('системная команда «пауза» (аудиофокус отобрал другой плеер) проигнорирована, №' + n);
                return;
              }
              return handler.apply(this, arguments);
            };
            return origSet(action, wrapped);
          }
          return origSet(action, handler);
        };
      }
    } catch (e) {}
    // кто ставит паузу — запоминаем стек вызова pause() (если паузу поставил движок, стека не будет)
    try {
      var origPause = HTMLMediaElement.prototype.pause;
      HTMLMediaElement.prototype.pause = function(){
        try { window.__omniLastPause = { t: Date.now(), stack: (new Error().stack || '').split('\n').slice(1, 9).map(function(l){ l = l.trim(); return l.indexOf('at ') === 0 ? l.slice(3) : l; }).join(' | ') }; } catch (e) {}
        return origPause.apply(this, arguments);
      };
    } catch (e) {}
    var lastPauseLog = 0;
    document.addEventListener('pause', function(e){
      try {
        var v = e.target; if (!v || v.tagName !== 'VIDEO' || v.ended) return;
        var user = Date.now() - (window.__omniUserPauseAt || 0) < 5000;
        var lp = window.__omniLastPause; var viaJs = lp && Date.now() - lp.t < 300;
        var viaMs = Date.now() - (window.__omniLastMsPause || 0) < 300;
        var who = user ? 'кнопка пользователя' : viaMs ? 'системная команда mediaSession (аудиофокус)' : viaJs ? ('скрипт сайта: ' + lp.stack) : 'движок/система (не через JS)';
        if (Date.now() - lastPauseLog > 1500 && window.OmniMedia && OmniMedia.trace) { lastPauseLog = Date.now(); OmniMedia.trace('пауза' + (window.__omniInPip ? ' в PiP' : '') + ' @' + Math.round(v.currentTime) + 'с — ' + who); }
        if (user || !window.__omniInPip) return;
        var n = window.__omniResumeCount || 0;
        if (n >= 30) return;
        window.__omniResumeCount = n + 1;
        setTimeout(function(){ try { if (window.__omniInPip && v.paused && !v.ended) { var p = v.play(); if (p && p.catch) p.catch(function(){}); } } catch (x) {} }, 250);
      } catch (x) {}
    }, true);
    var swallow = function(e){ try { e.stopImmediatePropagation(); } catch (x) {} };
    document.addEventListener('visibilitychange', swallow, true);
    document.addEventListener('webkitvisibilitychange', swallow, true);
    window.addEventListener('pagehide', swallow, true);
    var vis = function(name, val){ try { Object.defineProperty(document, name, { get: function(){ return val; }, configurable: true }); } catch (e) {} };
    vis('hidden', false); vis('webkitHidden', false); vis('visibilityState', 'visible'); vis('webkitVisibilityState', 'visible');
    try { if (document.hasFocus) document.hasFocus = function(){ return true; }; } catch (e) {}
  } catch (e) {}
})();
"""

    /** Вкладка, которую смотрит пользователь: системные «pause» по аудиофокусу игнорируются. */
    fun holdFocusScript(on: Boolean): String = "(function(){ window.__omniHoldFocus = $on; })();"

    /** Вызывается, когда во вкладке [tabId] началось воспроизведение — остальные вкладки надо поставить на паузу явно. */
    @Volatile
    var onTabStartedPlaying: ((String) -> Unit)? = null

    /** Диагностика окна PiP для журнала: размер вьюпорта, видео и его положение. */
    const val PIP_DIAG_SCRIPT = """
(function(){
  try {
    var vs = document.querySelectorAll('video'); var v = null;
    for (var i = 0; i < vs.length; i++) { if (!vs[i].paused) { v = vs[i]; break; } }
    if (!v && vs.length) v = vs[0];
    var r = v ? v.getBoundingClientRect() : null;
    return JSON.stringify({ vp: innerWidth + 'x' + innerHeight, dpr: devicePixelRatio, videos: vs.length,
      paused: v ? v.paused : null, ready: v ? v.readyState : null, size: v ? (v.videoWidth + 'x' + v.videoHeight) : null,
      rect: r ? [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)] : null,
      fs: !!(document.fullscreenElement || document.webkitFullscreenElement), vis: document.visibilityState });
  } catch (e) { return 'error: ' + e; }
})();
"""

    /** Включает/выключает раскладку «только видео на весь экран» (для окна PiP). */
    fun pipLayoutScript(on: Boolean): String = """
(function(on){
  try {
    var id = '__omniPipStyle'; var s = document.getElementById(id);
    var clearMarks = function(){
      var all = document.querySelectorAll('[data-omni-pip],[data-omni-pip-anc]');
      for (var k = 0; k < all.length; k++) { all[k].removeAttribute('data-omni-pip'); all[k].removeAttribute('data-omni-pip-anc'); }
    };
    var pick = function(){
      try {
        var vs = Array.prototype.slice.call(document.querySelectorAll('video')); if (!vs.length) return;
        var best = null;
        for (var i = 0; i < vs.length; i++) { if (!vs[i].paused && !vs[i].ended && vs[i].readyState > 1) { best = vs[i]; break; } }
        if (!best) { vs.sort(function(a, b){ var ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect(); return (rb.width * rb.height) - (ra.width * ra.height); }); best = vs[0]; }
        if (best.getAttribute('data-omni-pip') === '1') return;
        clearMarks();
        best.setAttribute('data-omni-pip', '1');
        // v4.2.5: помечаем всю цепочку предков — только они остаются видимыми (всё остальное: панели, подложки, оверлеи плеера — скрыто),
        // а их transform/filter/contain снимаются, иначе position:fixed считается не от окна, а от контейнера плеера
        var p = best.parentElement;
        while (p) { p.setAttribute('data-omni-pip-anc', '1'); p = p.parentElement; }
      } catch (e) {}
    };
    window.__omniInPip = !!on;
    if (on) {
      window.__omniResumeCount = 0;
      try { if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen(); } catch (e) {}
      try { if (document.webkitFullscreenElement && document.webkitExitFullscreen) document.webkitExitFullscreen(); } catch (e) {}
      try { window.scrollTo(0, 0); } catch (e) {}
      pick();
      if (!window.__omniPipTimer) window.__omniPipTimer = setInterval(pick, 2000);
      if (!s) {
        s = document.createElement('style'); s.id = id;
        s.textContent =
          'html,body{overflow:hidden!important;background:#000!important;margin:0!important;padding:0!important}' +
          'body *{visibility:hidden!important}' +
          '[data-omni-pip-anc]{visibility:visible!important;transform:none!important;filter:none!important;perspective:none!important;contain:none!important;clip-path:none!important;backdrop-filter:none!important;will-change:auto!important;background:transparent!important;box-shadow:none!important;border:0!important;outline:0!important}' +
          '[data-omni-pip-anc]::before,[data-omni-pip-anc]::after{display:none!important}' +
          'video[data-omni-pip="1"]{visibility:visible!important;position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;min-width:0!important;min-height:0!important;margin:0!important;z-index:2147483647!important;object-fit:contain!important;background:#000!important;transform:none!important;opacity:1!important;filter:none!important;clip-path:none!important}';
        document.documentElement.appendChild(s);
      }
    } else {
      if (s) s.remove();
      if (window.__omniPipTimer) { clearInterval(window.__omniPipTimer); window.__omniPipTimer = null; }
      clearMarks();
    }
  } catch (e) {}
})($on);
"""
}

/** Мост из страницы: OmniMedia.state(true/false). */
class OmniMediaBridge(private val tab: BrowserTabModel) {
    @JavascriptInterface
    fun trace(msg: String) {
        com.winwasm64.vpn.VpnLog.i("Видео", "«${tab.title.take(40)}»: " + msg.take(600))
    }

    /** v4.2.13: сводка «сколько играло и сколько трафика»: {kind: stop|pip|period, seg:{played,wall,bytes,media}, total:{…}}. */
    @JavascriptInterface
    fun stats(json: String) {
        try {
            val o = org.json.JSONObject(json)
            val kind = o.optString("kind")
            val seg = o.getJSONObject("seg")
            val tot = o.getJSONObject("total")
            fun dur(ms: Long): String {
                val s = ms / 1000
                return when {
                    s >= 3600 -> "${s / 3600} ч ${(s % 3600) / 60} мин"
                    s >= 60 -> "${s / 60} мин ${s % 60} с"
                    else -> "$s с"
                }
            }
            fun mb(b: Long): String = if (b >= 1_048_576) String.format(java.util.Locale.US, "%.1f МБ", b / 1_048_576.0) else "${b / 1024} КБ"
            val sp = seg.optLong("played"); val sw = seg.optLong("wall"); val sb = seg.optLong("bytes"); val sm = seg.optLong("media")
            val rate = if (sw > 1000) " ≈${sb * 1000 / sw / 1024} КБ/с" else ""
            val head = when (kind) {
                "pip" -> "PiP закрыт: в окне PiP играло ${dur(sp)} из ${dur(sw)}"
                "period" -> "играет уже ${dur(tot.optLong("played"))}; за последние ${dur(sw)}"
                else -> "остановлено: играло ${dur(sp)} подряд" + (if (sw > sp + 15_000) " (с паузами/буферизацией за ${dur(sw)})" else "")
            }
            val traffic = if (sm > 0) "трафик видео ${mb(sm)}, всего ${mb(sb)}$rate" else "трафик ${mb(sb)}$rate"
            val total = "всего с открытия: играло ${dur(tot.optLong("played"))}, трафик ${mb(tot.optLong("bytes"))}"
            com.winwasm64.vpn.VpnLog.i("Видео", "«${tab.title.take(40)}» — $head, $traffic · $total")
        } catch (_: Exception) {
        }
    }

    /** v4.3.0: страница сообщила, что на ней есть видео/аудио (элемент или медиа-запрос) — показать кнопку «Скачать». */
    @JavascriptInterface
    fun hasMedia(present: Boolean) {
        Handler(Looper.getMainLooper()).post { tab.hasMedia = present }
    }

    @JavascriptInterface
    fun state(playing: Boolean) {
        val was = tab.mediaPlaying
        tab.mediaPlaying = playing
        if (was != playing) {
            com.winwasm64.vpn.VpnLog.i("Видео", "«${tab.title.take(40)}» — " + (if (playing) "играет" else "остановлено") +
                (if (tab.id == MediaState.activeTabId) " (активная вкладка" + (if (MediaState.inPip) ", PiP)" else ")") else " (фоновая вкладка)"))
        }
        if (tab.id == MediaState.activeTabId) {
            Handler(Looper.getMainLooper()).post { MediaState.activeMediaPlaying = playing }
        }
        if (playing && !was) {
            val h = MediaState.onTabStartedPlaying
            if (h != null) Handler(Looper.getMainLooper()).post { h(tab.id) }
        }
    }
}
