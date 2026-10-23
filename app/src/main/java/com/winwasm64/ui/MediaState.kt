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

    /** Включает/выключает раскладку «только видео на весь экран» (для окна PiP). */
    fun pipLayoutScript(on: Boolean): String = """
(function(on){
  try {
    var id = '__omniPipStyle'; var s = document.getElementById(id);
    if (on) {
      if (!s) {
        s = document.createElement('style'); s.id = id;
        s.textContent = 'video{position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;z-index:2147483647!important;object-fit:contain!important;background:#000!important;transform:none!important} html,body{overflow:hidden!important;background:#000!important}';
        document.documentElement.appendChild(s);
      }
    } else if (s) { s.remove(); }
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
