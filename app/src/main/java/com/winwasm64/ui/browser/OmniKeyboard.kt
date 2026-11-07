package com.winwasm64.ui.browser

import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v4.21.0: ввод в страницу — теперь настоящими (доверенными) событиями клавиш.
 *
 * Что показало устройство:
 *  • 4.19.0 — печать символов работает, кнопки нет;
 *  • 4.20.0 — «Пробел» работает (это вставка текста в поле), а Enter, ⌫, Tab и «Далее» — нет.
 *
 * Вывод: страница принимает только то, что пришло от системы. Подделанные нами события браузер
 * помечает `isTrusted = false`, и игра их выбрасывает — вставку текста она видит (значение поля
 * изменилось), а «нажатие клавиши» нет. Родная сенсорная клавиатура GeForce NOW по той же причине
 * не работает: она тоже шлёт синтетику.
 *
 * Настоящий путь — отдать клавишу через сам WebView: `WebView.dispatchKeyEvent(KeyEvent(...))`.
 * Он идёт внутрь Chromium как системная, с `isTrusted = true`, и страница обязана её увидеть.
 * Поэтому Enter, Tab, ⌫, Esc и стрелки теперь отправляются так, а хитрости из 4.20.0 (правка
 * значения поля, отправка формы, click() ближайшей кнопки) остаются запасным путём.
 *
 * Ещё: в альбомном режиме панель сжимается в одну строку, чтобы не закрывать игру.
 */
@Composable
fun OmniKeyPanel(
    webView: WebView?,
    onClose: () -> Unit,
    onShowSystemKeyboard: () -> Unit
) {
    var proxy by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val root = LocalView.current.rootView

    LaunchedEffect(Unit) {
        try { focus.requestFocus() } catch (_: Throwable) {}
        com.winwasm64.vpn.VpnLog.i("Браузер", "панель ввода открыта (4.21.0): Enter/Tab/⌫/стрелки идут доверенными событиями WebView")
    }

    /** Настоящее нажатие клавиши внутрь WebView: страница видит его как системное. */
    fun nativeKey(keyCode: Int, tag: String) {
        val wv = webView ?: return
        val prev: View? = try { root.findFocus() } catch (_: Throwable) { null }
        try {
            val t = SystemClock.uptimeMillis()
            if (!wv.hasFocus()) wv.requestFocus()
            wv.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
            wv.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0))
            status = "$tag: системная клавиша отправлена"
            com.winwasm64.vpn.VpnLog.i("Браузер", "панель ввода: $tag → KeyEvent($keyCode)")
        } catch (e: Throwable) {
            status = "$tag: сбой — ${e.message}"
        } finally {
            try { if (prev != null && prev !== wv) prev.requestFocus() } catch (_: Throwable) {}
        }
    }

    /** Свой JS — запасной путь и вставка текста. */
    fun js(code: String, tag: String, delay: Long = 0) {
        val wv = webView ?: return
        val run = Runnable {
            try {
                wv.evaluateJavascript("try{$code}catch(e){'err:'+e}") { r ->
                    val res = (r ?: "").trim().trim('"')
                    if (res.isNotEmpty() && res != "null") status = "$tag: $res"
                }
            } catch (_: Throwable) {
            }
        }
        if (delay > 0) wv.postDelayed(run, delay) else run.run()
    }

    fun insertChar(ch: String) = js("window.__omniInsert&&window.__omniInsert('${esc(ch)}')", "вставка")
    fun del() {
        nativeKey(KeyEvent.KEYCODE_DEL, "⌫")
        js("window.__omniAct&&window.__omniAct('del')", "⌫ (запасной путь)", 160)
    }
    fun enter() {
        nativeKey(KeyEvent.KEYCODE_ENTER, "Enter")
        js("window.__omniAct&&window.__omniAct('enter')", "Enter (запасной путь)", 200)
    }

    @Composable
    fun chip(title: String, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .background(Color(0x40FFFFFF))
                .clickable {
                    onClick()
                    try { focus.requestFocus() } catch (_: Throwable) {}
                }
                .padding(horizontal = 10.dp, vertical = if (landscape) 4.dp else 8.dp)
        ) { Text(title, fontSize = if (landscape) 11.sp else 12.sp, color = Color.White) }
    }

    @Composable
    fun fieldInput(mod: Modifier) {
        BasicTextField(
            value = proxy,
            onValueChange = { next ->
                if (next.length > proxy.length) {
                    next.substring(proxy.length).forEach { ch -> insertChar(ch.toString()) }
                } else if (next.length < proxy.length) {
                    repeat(proxy.length - next.length) { js("window.__omniAct&&window.__omniAct('del')", "⌫") }
                }
                proxy = if (next.length > 400) next.takeLast(400) else next
            },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = if (landscape) 13.sp else 15.sp),
            modifier = mod
                .background(Color(0xFF26282F))
                .padding(horizontal = 8.dp, vertical = if (landscape) 6.dp else 10.dp)
                .focusRequester(focus)
        ) { inner ->
            if (proxy.isEmpty()) {
                Text("Печатайте здесь", fontSize = 12.sp, color = Color(0xFF8A8F99))
            }
            inner()
        }
    }

    @Composable
    fun keyRow() {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(if (landscape) 4.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            chip("Пробел") { js("window.__omniInsert&&window.__omniInsert(' ')", "Пробел") }
            chip("Enter") { enter() }
            chip("Далее") { js("window.__omniAct&&window.__omniAct('submit')", "Далее") }
            chip("⌫") { del() }
            chip("Tab") { nativeKey(KeyEvent.KEYCODE_TAB, "Tab") }
            chip("Esc") { nativeKey(KeyEvent.KEYCODE_ESCAPE, "Esc") }
            chip("←") { nativeKey(KeyEvent.KEYCODE_DPAD_LEFT, "←") }
            chip("↑") { nativeKey(KeyEvent.KEYCODE_DPAD_UP, "↑") }
            chip("↓") { nativeKey(KeyEvent.KEYCODE_DPAD_DOWN, "↓") }
            chip("→") { nativeKey(KeyEvent.KEYCODE_DPAD_RIGHT, "→") }
            chip("Стереть всё") {
                js("window.__omniAct&&window.__omniAct('clear')", "очистка")
                proxy = ""
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xE6141519))
            .padding(if (landscape) 4.dp else 8.dp)
    ) {
        if (landscape) {
            // Альбомный режим: всё в одну строку, максимум места игре
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                fieldInput(Modifier.weight(1f))
                keyRow()
                chip("⌨") { onShowSystemKeyboard() }
                chip("✕") { onClose() }
            }
            if (status.isNotEmpty()) {
                Text(status, fontSize = 9.sp, color = Color(0xFF9AA0AA), modifier = Modifier.padding(top = 2.dp))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "⌨ Ввод в страницу",
                    fontSize = 11.sp,
                    color = Color(0xFFECECEC),
                    modifier = Modifier.weight(1f)
                )
                chip("системная") { onShowSystemKeyboard() }
                Box(Modifier.padding(start = 6.dp)) { chip("✕") { onClose() } }
            }
            Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Row(Modifier.fillMaxWidth()) { fieldInput(Modifier.weight(1f)) }
            }
            Box(Modifier.fillMaxWidth().padding(top = 6.dp)) { keyRow() }
            if (status.isNotEmpty()) {
                Text(status, fontSize = 10.sp, color = Color(0xFF9AA0AA), modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private fun esc(s: String): String = s
    .replace("\\", "\\\\")
    .replace("'", "\\'")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

/**
 * Приёмник ввода в странице. Ставится один раз (idempotent), вызывается из Kotlin:
 *   __omniInsert('символ')  — вставить символ (это работает всегда: это правка значения поля)
 *   __omniAct('space'|'del'|'clear'|'enter'|'submit'|'tab'|'esc'|'left'|'right'|'up'|'down')
 *
 * Кнопки-клавиши в основном идут доверенными событиями через WebView.dispatchKeyEvent;
 * здесь — запасной путь: если страница всё равно ничего не сделала, мы правим DOM напрямую:
 * отправляем форму, нажимаем ближайшую кнопку «Далее», двигаем фокус, чистим поле.
 *
 * Важная деталь: значение поля меняем через родной сеттер (`HTMLInputElement.prototype.value`),
 * иначе React-подобные страницы не замечают изменения — они сравнивают значение со своим
 * «следящим» и молча игнорируют прямую запись.
 */
const val OMNI_KEY_JS = """
(function(){
  if (window.__omniAct) return;

  window.__omniLastEl = null;

  function isField(el) {
    return !!el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable);
  }

  function visible(el) {
    if (!el) return false;
    try {
      var r = el.getBoundingClientRect();
      return r.width > 1 && r.height > 1;
    } catch (e) { return false; }
  }

  function target() {
    var el = document.activeElement;
    if (isField(el)) { window.__omniLastEl = el; return el; }
    var last = window.__omniLastEl;
    if (last && last.isConnected) {
      try { last.focus(); } catch (e) {}
      return last;
    }
    var all = document.querySelectorAll('input, textarea');
    for (var i = 0; i < all.length; i++) {
      var c = all[i];
      var t = (c.type || 'text').toLowerCase();
      if (t === 'hidden' || t === 'checkbox' || t === 'radio' || t === 'submit' || c.disabled || c.readOnly) continue;
      if (!visible(c)) continue;
      window.__omniLastEl = c;
      return c;
    }
    return null;
  }

  function fire(el, type, extra) {
    try {
      if (type === 'keydown' || type === 'keypress' || type === 'keyup') {
        var o = {bubbles: true, cancelable: true};
        if (extra) for (var k in extra) o[k] = extra[k];
        el.dispatchEvent(new KeyboardEvent(type, o));
      } else {
        el.dispatchEvent(new Event(type, {bubbles: true}));
      }
    } catch (e) {}
  }

  function setNativeValue(el, v) {
    try {
      var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      var d = Object.getOwnPropertyDescriptor(proto, 'value');
      if (d && d.set) { d.set.call(el, v); return true; }
    } catch (e) {}
    try { el.value = v; return true; } catch (e) { return false; }
  }

  function putText(el, txt) {
    if (!el) {
      try {
        if (document.queryCommandSupported && document.queryCommandSupported('insertText')) {
          return document.execCommand('insertText', false, txt) ? 'ok (insertText)' : 'no-field';
        }
      } catch (e) {}
      return 'no-field';
    }
    try { el.focus(); } catch (e) {}
    if (el.isContentEditable) {
      try {
        var okC = document.execCommand('insertText', false, txt);
        fire(el, 'input');
        return okC ? 'ok (contenteditable)' : 'no-field';
      } catch (e) { return 'no-field'; }
    }
    try {
      var s = el.selectionStart, e2 = el.selectionEnd;
      if (s === null || s === undefined) { s = (el.value || '').length; e2 = s; }
      if (el.setRangeText) {
        el.setRangeText(txt, s, e2, 'end');
      } else {
        var nv = (el.value || '').slice(0, s) + txt + (el.value || '').slice(e2);
        setNativeValue(el, nv);
        try { el.selectionStart = el.selectionEnd = s + txt.length; } catch (e) {}
      }
      fire(el, 'input');
      fire(el, 'change');
      return 'ok';
    } catch (e) { return 'err:' + e; }
  }

  function deleteBackward() {
    var el = target();
    if (!el) return 'no-field';
    if (el.isContentEditable) {
      try {
        var ok = document.execCommand('delete', false, null);
        fire(el, 'input');
        return ok ? 'ok (contenteditable)' : 'no-field';
      } catch (e) {}
      return 'no-field';
    }
    try { el.focus(); } catch (e) {}
    var v = (el.value || '');
    var s = el.selectionStart, e2 = el.selectionEnd;
    if (s === null || s === undefined) { s = v.length; e2 = s; }
    if (s === e2) { if (s === 0) return 'в начале поля'; s = s - 1; }
    var nv = v.slice(0, s) + v.slice(e2);
    setNativeValue(el, nv);
    try { el.selectionStart = el.selectionEnd = s; } catch (e) {}
    fire(el, 'input');
    fire(el, 'change');
    return 'ok (' + v.length + '→' + nv.length + ')';
  }

  function label(el) {
    var s = '';
    try {
      s = (el.innerText || el.value || el.getAttribute('aria-label') || el.getAttribute('title') || '') + '';
    } catch (e) {}
    return s.replace(/\s+/g, ' ').trim().toLowerCase();
  }

  var WORDS = ['далее', 'дальше', 'продолжить', 'отправить', 'войти', 'вход', 'подтвердить',
               'ok', 'okay', 'next', 'continue', 'submit', 'send', 'sign in', 'login'];

  function clickNear() {
    var f = target();
    var scope = document;
    if (f) {
      var n = f;
      for (var d = 0; d < 6 && n; d++) {
        n = n.parentElement;
        if (!n) break;
        var probe = n.querySelectorAll('button, [role="button"], input[type="submit"], input[type="button"], a');
        if (probe.length) { scope = n; break; }
      }
    }
    var list = scope.querySelectorAll('button, [role="button"], input[type="submit"], input[type="button"], a');
    var found = null, best = 0, bestText = '';
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (!visible(el)) continue;
      var t = label(el);
      for (var w = 0; w < WORDS.length; w++) {
        if (t.indexOf(WORDS[w]) >= 0) {
          var rank = WORDS.length - w;
          if (rank > best) { best = rank; found = el; bestText = t; }
          break;
        }
      }
    }
    if (!found) return 'кнопка не найдена';
    try { found.focus(); } catch (e) {}
    try { found.click(); return 'нажато: ' + bestText; } catch (e) { return 'err:' + e; }
  }

  function submitForm() {
    var f = target();
    if (!f) return false;
    var form = f.form || (f.closest ? f.closest('form') : null);
    if (!form) return false;
    try {
      if (form.requestSubmit) { form.requestSubmit(); return true; }
      var sb = form.querySelector('button[type="submit"], input[type="submit"], button');
      if (sb) { sb.click(); return true; }
      form.submit();
      return true;
    } catch (e) { return false; }
  }

  function nextFocusable(back) {
    var all = document.querySelectorAll('a[href], button, input, select, textarea, [tabindex]');
    var list = [];
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (el.disabled || !visible(el) || el.type === 'hidden') continue;
      list.push(el);
    }
    if (!list.length) return 'нет элементов';
    var idx = list.indexOf(document.activeElement);
    var nxt = back ? (idx <= 0 ? list.length - 1 : idx - 1) : (idx + 1) % list.length;
    try { list[nxt].focus(); return 'фокус передан'; } catch (e) { return 'err:' + e; }
  }

  function moveCaret(dir) {
    var el = target();
    if (!el || el.selectionStart === null || el.selectionStart === undefined) return 'нет поля';
    try {
      var len = (el.value || '').length;
      var s = el.selectionStart, e2 = el.selectionEnd, p = s;
      if (dir === 'left') p = Math.max(0, s - 1);
      if (dir === 'right') p = Math.min(len, e2 + 1);
      el.selectionStart = el.selectionEnd = p;
      return 'курсор ' + p + '/' + len;
    } catch (e) { return 'err:' + e; }
  }

  window.__omniInsert = function(txt) {
    var el = target();
    var res = putText(el, txt);
    if (el) {
      fire(el, 'keydown', {key: txt, keyCode: txt.charCodeAt(0)});
      fire(el, 'keypress', {key: txt, keyCode: txt.charCodeAt(0)});
      fire(el, 'keyup', {key: txt, keyCode: txt.charCodeAt(0)});
    }
    return res;
  };

  window.__omniAct = function(name) {
    switch (name) {
      case 'space': return window.__omniInsert(' ');
      case 'del': return deleteBackward();
      case 'clear':
        var el = target();
        if (!el) return 'no-field';
        try {
          if (el.isContentEditable) {
            document.execCommand('selectAll', false, null);
            document.execCommand('delete', false, null);
          } else {
            setNativeValue(el, '');
            try { el.selectionStart = el.selectionEnd = 0; } catch (e) {}
          }
          fire(el, 'input');
          fire(el, 'change');
          return 'ok';
        } catch (e) { return 'err:' + e; }
      case 'enter':
        if (submitForm()) return 'форма отправлена';
        return clickNear();
      case 'submit': return clickNear();
      case 'tab': return nextFocusable(false);
      case 'esc':
        var f = document.activeElement || document.body;
        fire(f, 'keydown', {key: 'Escape', code: 'Escape', keyCode: 27});
        fire(f, 'keyup', {key: 'Escape', code: 'Escape', keyCode: 27});
        return 'событие отправлено';
      case 'left': return moveCaret('left');
      case 'right': return moveCaret('right');
      case 'up': case 'down': return 'событие через WebView';
      default: return 'неизвестное действие';
    }
  };
})();
"""
