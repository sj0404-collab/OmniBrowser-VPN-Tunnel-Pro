package com.winwasm64.ui.browser

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v4.20.0: панель ввода, которая делает в странице настоящую работу.
 *
 * История: в полноэкранной игре (GeForce NOW) клавиатура открывалась, но нажатия до игры
 * не доходили — приходилось выходить из полного экрана и заходить снова.
 *
 * Что выяснилось на устройстве (4.19.0):
 *  • печать символов работает — текст вставляется в активное поле страницы;
 *  • кнопки не работали, потому что они отправляли синтетические события клавиш
 *    (`keydown`/`keypress`/`keyup`). Игра их игнорирует: браузер помечает такие события
 *    `isTrusted = false`, и всё, что не является вставкой текста, отбрасывается.
 *
 * Поэтому здесь кнопки не «изображают» нажатия, а меняют страницу по-настоящему:
 *  • текст и пробел — вставка в значение поля (`setRangeText` / `execCommand('insertText')`);
 *  • ⌫ — удаление символа перед курсором правкой значения, а не событием Backspace;
 *  • Enter — сначала настоящая отправка формы (`requestSubmit`/submit-кнопка), если не вышло —
 *    нажатие (`click()`) ближайшей видимой кнопки «Далее/OK/Отправить/Войти/Продолжить»;
 *  • «Далее» — то же нажатие, но без попытки отправки формы (на случай, когда форма не находится);
 *  • Tab — передача фокуса следующему элементу страницы;
 *  • стрелки — перемещение курсора внутри поля (и события клавиш для тех, кто их слушает);
 *  • «Стереть всё» — очистка поля страницы.
 *
 * Каждое нажатие пишется в журнал (`панель ввода: …`) — так видно, доходит ли нажатие до нас.
 */
@Composable
fun OmniKeyPanel(
    webView: WebView?,
    onClose: () -> Unit,
    onShowSystemKeyboard: () -> Unit
) {
    var proxy by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try { focus.requestFocus() } catch (_: Throwable) {}
        com.winwasm64.vpn.VpnLog.i("Браузер", "панель ввода открыта: текст, удаление и кнопки действуют прямо в странице")
    }

    fun run(name: String) {
        val wv = webView ?: return
        com.winwasm64.vpn.VpnLog.i("Браузер", "панель ввода: «$name»")
        try {
            wv.evaluateJavascript(
                "try{window.__omniAct&&window.__omniAct('${esc(name)}')}catch(e){}",
                null
            )
        } catch (_: Throwable) {
        }
    }

    fun insertChar(ch: String) {
        val wv = webView ?: return
        try {
            wv.evaluateJavascript(
                "try{window.__omniInsert&&window.__omniInsert('${esc(ch)}')}catch(e){}",
                null
            )
        } catch (_: Throwable) {
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xF21A1B20))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "⌨ Ввод в страницу (работает и когда клавиатура сайта не реагирует)",
                fontSize = 11.sp,
                color = Color(0xFFECECEC),
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onShowSystemKeyboard,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF)),
                modifier = Modifier.padding(end = 6.dp)
            ) { Text("системная", fontSize = 10.sp, color = Color.White) }
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF))
            ) { Text("✕", fontSize = 11.sp, color = Color.White) }
        }
        BasicTextField(
            value = proxy,
            onValueChange = { next ->
                if (next.length > proxy.length) {
                    next.substring(proxy.length).forEach { ch -> insertChar(ch.toString()) }
                } else if (next.length < proxy.length) {
                    repeat(proxy.length - next.length) { run("del") }
                }
                proxy = if (next.length > 400) next.takeLast(400) else next
            },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .background(Color(0xFF26282F))
                .padding(10.dp)
                .focusRequester(focus)
        ) { inner ->
            if (proxy.isEmpty()) {
                Text("Печатайте здесь — символы уйдут в игру", fontSize = 13.sp, color = Color(0xFF8A8F99))
            }
            inner()
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            @Composable
            fun keyButton(title: String, action: String) {
                Button(
                    onClick = {
                        run(action)
                        // возвращаем фокус своему полю, чтобы клавиатура не захлопнулась
                        try { focus.requestFocus() } catch (_: Throwable) {}
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF))
                ) { Text(title, fontSize = 11.sp, color = Color.White) }
            }

            keyButton("Пробел", "space")
            keyButton("Enter", "enter")
            keyButton("Далее", "submit")
            keyButton("⌫", "del")
            keyButton("Tab", "tab")
            keyButton("Esc", "esc")
            keyButton("←", "left")
            keyButton("↑", "up")
            keyButton("↓", "down")
            keyButton("→", "right")
            keyButton("Стереть всё", "clear")
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
 * Приёмник ввода. Ставится один раз на страницу (idempotent), вызывается из Kotlin:
 *   __omniInsert('символ')  — вставить символ
 *   __omniAct('space'|'enter'|'submit'|'del'|'tab'|'esc'|'left'|'up'|'down'|'right'|'clear')
 *
 * Ключевая мысль: событие клавиши от нас страница считает поддельным (isTrusted = false),
 * поэтому всё, что можно, делаем правкой DOM: вставляем и удаляем текст, отправляем форму,
 * нажимаем кнопку через click(), двигаем фокус. События клавиш всё равно дублируем — тем,
 * кто их слушает, это не мешает.
 */
const val OMNI_KEY_JS = """
(function(){
  if (window.__omniInsert) return;

  function field() {
    var el = document.activeElement;
    if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) return el;
    var all = document.querySelectorAll('input, textarea');
    for (var i = 0; i < all.length; i++) {
      var c = all[i];
      if (c.offsetParent === null) continue;
      var t = (c.type || 'text').toLowerCase();
      if (t === 'hidden' || t === 'checkbox' || t === 'radio' || c.disabled || c.readOnly) continue;
      return c;
    }
    return null;
  }

  function fire(el, type, extra) {
    try {
      var o = {bubbles: true, cancelable: true};
      if (extra) for (var k in extra) o[k] = extra[k];
      el.dispatchEvent(new Event(type, {bubbles: true}));
      if (type === 'keydown' || type === 'keyup') el.dispatchEvent(new KeyboardEvent(type, o));
      if (type === 'keypress') el.dispatchEvent(new KeyboardEvent(type, o));
    } catch (e) {}
  }

  function putText(el, txt) {
    if (!el) {
      if (document.queryCommandSupported && document.queryCommandSupported('insertText')) {
        try { document.execCommand('insertText', false, txt); return true; } catch (e) {}
      }
      return false;
    }
    try {
      var s = el.selectionStart, e2 = el.selectionEnd;
      if (s === null || s === undefined) { s = el.value.length; e2 = s; }
      if (el.setRangeText) {
        el.setRangeText(txt, s, e2, 'end');
      } else {
        el.value = el.value.slice(0, s) + txt + el.value.slice(e2);
        try { el.selectionStart = el.selectionEnd = s + txt.length; } catch (e) {}
      }
      fire(el, 'input');
      fire(el, 'change');
      return true;
    } catch (e) { return false; }
  }

  function visible(el) {
    if (!el) return false;
    if (el.offsetParent === null && el.tagName !== 'BODY') {
      var r0 = el.getBoundingClientRect();
      if (r0.width === 0 && r0.height === 0) return false;
    }
    var r = el.getBoundingClientRect();
    return r.width > 1 && r.height > 1;
  }

  function label(el) {
    var s = '';
    try {
      s = (el.innerText || el.value || el.getAttribute('aria-label') || el.getAttribute('title') || '') + '';
    } catch (e) {}
    return s.replace(/\s+/g, ' ').trim().toLowerCase();
  }

  var WORDS = ['далее', 'дальше', 'продолжить', 'отправить', 'войти', 'вход', 'подтвердить',
               'ok', 'okay', 'next', 'continue', 'submit', 'enter', 'send', 'sign in', 'login'];

  function clickNear(force) {
    var f = field();
    var cands = [];
    var scope = document;
    // сначала ищем в пределах диалога/оверлея вокруг поля
    if (f) {
      var n = f;
      for (var d = 0; d < 5 && n; d++) {
        n = n.parentElement;
        if (!n) break;
        var bs = n.querySelectorAll('button, [role="button"], input[type="submit"], input[type="button"], a');
        if (bs.length) { scope = n; break; }
      }
    }
    var list = scope.querySelectorAll('button, [role="button"], input[type="submit"], input[type="button"], a');
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (!visible(el)) continue;
      var t = label(el);
      var rank = -1;
      for (var w = 0; w < WORDS.length; w++) { if (t.indexOf(WORDS[w]) >= 0) { rank = WORDS.length - w; break; } }
      if (rank > 0) cands.push({el: el, rank: rank, t: t});
    }
    if (!cands.length && force) {
      // ничего по словам — берём последнюю видимую кнопку рядом с полем
      for (var j = list.length - 1; j >= 0; j--) {
        if (visible(list[j])) {
          try { list[j].click(); return true; } catch (e) {}
        }
      }
      return false;
    }
    if (!cands.length) return false;
    cands.sort(function(a, b) { return b.rank - a.rank; });
    var target = cands[0].el;
    try { target.focus(); } catch (e) {}
    try { target.click(); } catch (e) { fire(target, 'click'); }
    return true;
  }

  function submitForm() {
    var f = field();
    if (!f) return false;
    var form = f.form || (f.closest ? f.closest('form') : null);
    if (form) {
      try {
        if (form.requestSubmit) { form.requestSubmit(); return true; }
        var sb = form.querySelector('button[type="submit"], input[type="submit"], button');
        if (sb) { sb.click(); return true; }
        form.submit();
        return true;
      } catch (e) {}
    }
    return false;
  }

  function nextFocusable(back) {
    var all = document.querySelectorAll('a[href], button, input, select, textarea, [tabindex]');
    var list = [];
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (el.disabled || !visible(el)) continue;
      if (el.type === 'hidden') continue;
      list.push(el);
    }
    if (!list.length) return false;
    var cur = document.activeElement;
    var idx = list.indexOf(cur);
    var nxt = back ? (idx <= 0 ? list.length - 1 : idx - 1) : (idx + 1) % list.length;
    try { list[nxt].focus(); return true; } catch (e) { return false; }
  }

  function moveCaret(dir) {
    var f = field();
    if (!f) return false;
    try {
      var s = f.selectionStart, e2 = f.selectionEnd;
      if (s === null || s === undefined) return false;
      var p = s;
      if (dir === 'left') p = Math.max(0, s - 1);
      else if (dir === 'right') p = Math.min((f.value || '').length, e2 + 1);
      else if (dir === 'up' || dir === 'down') {
        fire(f, 'keydown', {key: dir === 'up' ? 'ArrowUp' : 'ArrowDown'});
        return true;
      }
      f.selectionStart = f.selectionEnd = p;
      return true;
    } catch (e) { return false; }
  }

  function deleteBackward() {
    var f = field();
    if (f) {
      try {
        var s = f.selectionStart, e2 = f.selectionEnd;
        if (s === null || s === undefined) { s = (f.value || '').length; e2 = s; }
        if (s === e2) { if (s === 0) return true; s = s - 1; }
        if (f.setRangeText) f.setRangeText('', s, e2, 'end');
        else { f.value = (f.value || '').slice(0, s) + (f.value || '').slice(e2); try { f.selectionStart = f.selectionEnd = s; } catch (e) {} }
        fire(f, 'input');
        fire(f, 'change');
        return true;
      } catch (e) {}
    }
    try {
      if (document.queryCommandSupported && document.queryCommandSupported('delete')) {
        return document.execCommand('delete', false, null);
      }
    } catch (e) {}
    return false;
  }

  window.__omniInsert = function(txt) {
    var f = field();
    var ok = putText(f, txt);
    if (f) {
      fire(f, 'keydown', {key: txt, code: '', keyCode: txt.charCodeAt(0)});
      fire(f, 'keypress', {key: txt, code: '', keyCode: txt.charCodeAt(0)});
      fire(f, 'keyup', {key: txt, code: '', keyCode: txt.charCodeAt(0)});
    }
    return ok;
  };

  window.__omniAct = function(name) {
    var f = field();
    switch (name) {
      case 'space': return window.__omniInsert(' ');
      case 'del': return deleteBackward();
      case 'clear':
        if (f) {
          try {
            f.value = '';
            fire(f, 'input');
            fire(f, 'change');
            return true;
          } catch (e) {}
        }
        try {
          document.execCommand('selectAll', false, null);
          document.execCommand('delete', false, null);
        } catch (e) {}
        return false;
      case 'enter':
        if (f) { fire(f, 'keydown', {key: 'Enter', code: 'Enter', keyCode: 13, which: 13}); }
        if (submitForm()) return true;
        return clickNear(true);
      case 'submit': return clickNear(true);
      case 'tab': return nextFocusable(false);
      case 'esc':
        if (f) { fire(f, 'keydown', {key: 'Escape', code: 'Escape', keyCode: 27}); fire(f, 'keyup', {key: 'Escape', code: 'Escape', keyCode: 27}); }
        try { document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true})); } catch (e) {}
        return true;
      case 'left': case 'right': case 'up': case 'down': return moveCaret(name);
      default: return false;
    }
  };
})();
"""
