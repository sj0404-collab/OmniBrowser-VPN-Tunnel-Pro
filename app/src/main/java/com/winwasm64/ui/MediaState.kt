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
        try { window.__omniLastPause = { t: Date.now(), stack: (new Error().stack || '').split('\n').slice(1, 4).join(' | ') }; } catch (e) {}
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
