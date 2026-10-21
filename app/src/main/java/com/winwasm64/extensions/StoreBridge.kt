package com.winwasm64.extensions

import android.net.Uri
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * Кнопка «Установить» прямо на страницах Chrome Web Store.
 * Скрипт и канал сообщений доступны только для https://chromewebstore.google.com.
 */
object StoreBridge {
    private const val TAG = "OmniStore"
    private const val ORIGIN = "https://chromewebstore.google.com"
    private val ID_RE = Regex("^[a-p]{32}$")

    fun isStoreUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val u = Uri.parse(url)
            val host = u.host?.lowercase() ?: return false
            host == "chromewebstore.google.com" || (host == "chrome.google.com" && (u.path ?: "").startsWith("/webstore"))
        } catch (_: Exception) {
            false
        }
    }

    fun attach(wv: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        try {
            WebViewCompat.addWebMessageListener(wv, "OmniStore", setOf(ORIGIN), listener)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(wv, SCRIPT, setOf(ORIGIN))
            }
        } catch (e: Exception) {
            Log.w(TAG, "attach", e)
        }
    }

    /** Для WebView без DOCUMENT_START_SCRIPT — внедрение после загрузки страницы магазина. */
    fun injectFallback(wv: WebView, url: String?) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        if (url == null || !url.startsWith(ORIGIN)) return
        wv.evaluateJavascript(SCRIPT, null)
    }

    private fun status(id: String): String = JSONObject()
        .put("op", "status").put("id", id)
        .put("installed", ExtensionStore.get(id) != null)
        .put("busy", ExtensionRuntime.busy[id] ?: "")
        .toString()

    private val listener = WebViewCompat.WebMessageListener { _, message, _, isMainFrame, reply ->
        if (!isMainFrame) return@WebMessageListener
        val m = try {
            JSONObject(message.data ?: return@WebMessageListener)
        } catch (_: Exception) {
            return@WebMessageListener
        }
        val id = m.optString("id")
        if (!ID_RE.matches(id)) return@WebMessageListener
        when (m.optString("op")) {
            "status" -> reply.postMessage(status(id))
            "install" -> {
                reply.postMessage(JSONObject().put("op", "busy").put("id", id).put("text", "Скачиваю с серверов Google…").toString())
                ExtensionRuntime.installFromStore(id) { err, ext ->
                    reply.postMessage(
                        JSONObject().put("op", "result").put("id", id).put("ok", err == null)
                            .put("error", err ?: "").put("name", ext?.manifest?.name ?: "").toString()
                    )
                }
            }
            "uninstall" -> {
                ExtensionRuntime.uninstall(id)
                reply.postMessage(status(id))
            }
        }
    }

    private val SCRIPT = """
(function () {
  if (window.__omniStoreHook || typeof OmniStore === 'undefined') return;
  window.__omniStoreHook = 1;
  var ID_RE = /\/detail\/(?:[^\/?#]+\/)?([a-p]{32})(?:[\/?#]|$)/;
  var LABEL_RE = /^(Установить|Добавить в Chrome|Удалить из Chrome|Add to Chrome|Remove from Chrome|Install|Get|Установить в OmniBrowser|Удалить из OmniBrowser|Скачиваю.*|Установка.*)$/i;
  var state = {}, asked = {}, timer = 0, lastUrl = '';
  function currentId() { var m = location.pathname.match(ID_RE) || location.href.match(ID_RE); return m ? m[1] : null; }
  function ask(id) {
    if (asked[id] && Date.now() - asked[id] < 1500) return;
    asked[id] = Date.now();
    OmniStore.postMessage(JSON.stringify({ op: 'status', id: id }));
  }
  OmniStore.onmessage = function (ev) {
    var m; try { m = JSON.parse(ev.data); } catch (e) { return; }
    var s = state[m.id] || (state[m.id] = {});
    if (m.op === 'status') { s.installed = !!m.installed; s.busy = m.busy || ''; }
    else if (m.op === 'busy') { s.busy = m.text || 'Установка…'; }
    else if (m.op === 'result') {
      s.busy = ''; s.installed = !!m.ok;
      if (!m.ok) setTimeout(function () { alert('Не удалось установить: ' + m.error); }, 0);
    }
    render();
  };
  function buttons() {
    var out = [], els = document.querySelectorAll('button');
    for (var i = 0; i < els.length; i++) {
      var b = els[i];
      if (b.hasAttribute('data-omni-store')) { out.push(b); continue; }
      var t = (b.textContent || '').replace(/\s+/g, ' ').trim();
      if (t && t.length < 40 && LABEL_RE.test(t)) out.push(b);
    }
    return out;
  }
  function setLabel(b, text) {
    var node = b, spans = b.querySelectorAll('span');
    for (var i = 0; i < spans.length; i++) { if ((spans[i].textContent || '').trim()) node = spans[i]; }
    if (node.textContent !== text) node.textContent = text;
  }
  function onClick(e) {
    e.preventDefault(); e.stopImmediatePropagation();
    var id = currentId(); if (!id) return;
    var s = state[id] || {};
    if (s.busy) return;
    if (s.installed) {
      if (confirm('Удалить расширение из OmniBrowser?')) OmniStore.postMessage(JSON.stringify({ op: 'uninstall', id: id }));
    } else {
      s.busy = 'Установка…'; render();
      OmniStore.postMessage(JSON.stringify({ op: 'install', id: id }));
    }
  }
  function render() {
    var id = currentId(); if (!id) return;
    var s = state[id]; if (!s) { ask(id); return; }
    var label = s.busy ? s.busy : (s.installed ? 'Удалить из OmniBrowser' : 'Установить в OmniBrowser');
    buttons().forEach(function (b) {
      b.setAttribute('data-omni-store', '1');
      b.disabled = false; b.removeAttribute('disabled'); b.removeAttribute('aria-disabled');
      setLabel(b, label);
      b.style.setProperty('opacity', '1', 'important');
      b.style.setProperty('pointer-events', 'auto', 'important');
      b.style.setProperty('cursor', 'pointer', 'important');
      b.style.setProperty('background', s.installed ? '#e8eaed' : '#0b57d0', 'important');
      b.style.setProperty('color', s.installed ? '#1f1f1f' : '#ffffff', 'important');
      if (!b.__omniBound) { b.__omniBound = true; b.addEventListener('click', onClick, true); }
    });
  }
  function schedule() { if (timer) return; timer = setTimeout(function () { timer = 0; render(); }, 250); }
  function start() {
    new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ['disabled', 'aria-disabled'] });
    setInterval(function () {
      if (location.href !== lastUrl) { lastUrl = location.href; var id = currentId(); if (id) { delete state[id]; asked[id] = 0; ask(id); } }
    }, 700);
    schedule();
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
})();
"""
}
