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

    fun control(cmd: String) {
        val h = controlHandler ?: return
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
    if (cmd === 'play') play();
    else if (cmd === 'pause') v.pause();
    else if (cmd === 'toggle') { if (v.paused) play(); else v.pause(); }
    else if (cmd === 'back') v.currentTime = Math.max(0, v.currentTime - 10);
    else if (cmd === 'fwd') { var d = isFinite(v.duration) ? v.duration : 1e12; v.currentTime = Math.min(d, v.currentTime + 10); }
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
        for (var i = 0; i < vs.length; i++) { var v = vs[i]; if (!v.paused && !v.ended && v.readyState > 2) { p = true; break; } }
        if (p !== last) { last = p; if (window.OmniMedia) OmniMedia.state(p); }
      } catch (e) {}
    };
    ['play','playing','pause','ended','emptied','abort'].forEach(function(ev){ document.addEventListener(ev, function(){ setTimeout(report, 80); }, true); });
    setInterval(report, 3000);
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
    var pick = function(){
      try {
        var vs = Array.prototype.slice.call(document.querySelectorAll('video')); if (!vs.length) return;
        var best = null;
        for (var i = 0; i < vs.length; i++) { if (!vs[i].paused && !vs[i].ended && vs[i].readyState > 1) { best = vs[i]; break; } }
        if (!best) { vs.sort(function(a, b){ var ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect(); return (rb.width * rb.height) - (ra.width * ra.height); }); best = vs[0]; }
        for (var j = 0; j < vs.length; j++) { if (vs[j] === best) vs[j].setAttribute('data-omni-pip', '1'); else vs[j].removeAttribute('data-omni-pip'); }
      } catch (e) {}
    };
    if (on) {
      try { if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen(); } catch (e) {}
      try { if (document.webkitFullscreenElement && document.webkitExitFullscreen) document.webkitExitFullscreen(); } catch (e) {}
      try { window.scrollTo(0, 0); } catch (e) {}
      pick();
      if (!window.__omniPipTimer) window.__omniPipTimer = setInterval(pick, 2000);
      if (!s) {
        s = document.createElement('style'); s.id = id;
        // v4.2.4: растягиваем только ОДНО видео (играющее/самое крупное): скрытые превью-видео страницы раньше ложились сверху чёрным
        s.textContent = 'video[data-omni-pip="1"]{position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;z-index:2147483647!important;object-fit:contain!important;background:#000!important;transform:none!important;opacity:1!important;visibility:visible!important} html,body{overflow:hidden!important;background:#000!important}';
        document.documentElement.appendChild(s);
      }
    } else {
      if (s) s.remove();
      if (window.__omniPipTimer) { clearInterval(window.__omniPipTimer); window.__omniPipTimer = null; }
      var all = document.querySelectorAll('video[data-omni-pip]'); for (var k = 0; k < all.length; k++) all[k].removeAttribute('data-omni-pip');
    }
  } catch (e) {}
})($on);
"""
}

/** Мост из страницы: OmniMedia.state(true/false). */
class OmniMediaBridge(private val tab: BrowserTabModel) {
    @JavascriptInterface
    fun state(playing: Boolean) {
        tab.mediaPlaying = playing
        if (tab.id == MediaState.activeTabId) {
            Handler(Looper.getMainLooper()).post { MediaState.activeMediaPlaying = playing }
        }
    }
}
