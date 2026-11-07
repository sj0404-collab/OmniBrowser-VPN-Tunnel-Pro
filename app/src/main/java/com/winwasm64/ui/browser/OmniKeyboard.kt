package com.winwasm64.ui.browser

import android.content.res.Configuration
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v4.22.0: полупрозрачная строка ввода + отдельная сенсорная клавиатура + виртуальная мышь.
 *
 * По просьбе с устройства: сама панель не нужна — нужна строка, в которую можно печатать,
 * чтобы символы доходили до игры, и чтобы работало удаление. Кнопки-клавиши при этом должны
 * быть отдельной сенсорной клавиатурой (своей, не системной), а не висеть в строке.
 *
 * Что было выяснено на устройстве:
 *  • печать символов и пробел работают всегда — это вставка текста в поле страницы;
 *  • «нажатия» клавиш, созданные в JS, страница выбрасывает (`isTrusted = false`), поэтому Enter,
 *    Tab, ⌫ и стрелки ничего не делали. Теперь они идут настоящими `KeyEvent` через
 *    `WebView.dispatchKeyEvent` — такие события Chromium считает системными.
 *
 * Устройство интерфейса:
 *  • по умолчанию видна только полупрозрачная строка ввода (системная клавиатура не всплывает сама);
 *  • «⌨» — своя сенсорная клавиатура (рус/eng, цифры, ⇧, ⌫, Пробел, Enter, Tab, Esc, стрелки);
 *  • «🖱» — виртуальная мышь-тачпад: точка на площадке = точка на экране игры, тап = клик левой,
 *    кнопка «ПКМ» — правой, «↑/↓» — прокрутка. Сворачивается кнопкой «—»;
 *  • «✕» — закрыть всё.
 */
@Composable
fun OmniKeyPanel(
    webView: WebView?,
    onClose: () -> Unit,
    onShowSystemKeyboard: () -> Unit
) {
    var proxy by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var keysOpen by remember { mutableStateOf(false) }
    var mouseOpen by remember { mutableStateOf(false) }
    var ru by remember { mutableStateOf(true) }
    var shift by remember { mutableStateOf(false) }
    var digits by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val root = LocalView.current.rootView

    // v4.22.0: НЕ просим фокус при открытии — из-за этого вылезала системная клавиатура
    // и закрывала игру. Печатать можно своей сенсорной клавиатурой.

    /** Настоящее нажатие клавиши внутрь WebView: страница видит его как системное. */
    fun nativeKey(keyCode: Int, tag: String) {
        val wv = webView ?: return
        val prev: View? = try { root.findFocus() } catch (_: Throwable) { null }
        try {
            val t = SystemClock.uptimeMillis()
            if (!wv.hasFocus()) wv.requestFocus()
            wv.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
            wv.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0))
            status = "$tag → KeyEvent $keyCode"
            com.winwasm64.vpn.VpnLog.i("Браузер", "ввод: $tag → KeyEvent($keyCode)")
        } catch (e: Throwable) {
            status = "$tag: сбой — ${e.message}"
        } finally {
            try { if (prev != null && prev !== wv) prev.requestFocus() } catch (_: Throwable) {}
        }
    }

    /** Свой JS — вставка текста и запасной путь. */
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

    fun insert(ch: String) = js("window.__omniInsert&&window.__omniInsert('${esc(ch)}')", "вставка")
    fun del() {
        nativeKey(KeyEvent.KEYCODE_DEL, "⌫")
        js("window.__omniAct&&window.__omniAct('del')", "⌫ (запасной путь)", 160)
    }
    fun enter() {
        nativeKey(KeyEvent.KEYCODE_ENTER, "Enter")
        js("window.__omniAct&&window.__omniAct('enter')", "Enter (запасной путь)", 200)
    }

    fun key(code: Int, tag: String) {
        when (code) {
            KeyEvent.KEYCODE_DEL -> del()
            KeyEvent.KEYCODE_ENTER -> enter()
            else -> nativeKey(code, tag)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x66101216))
            .padding(if (landscape) 3.dp else 6.dp)
    ) {
        if (mouseOpen) {
            OmniTouchPad(
                webView = webView,
                compact = landscape,
                onStatus = { status = it },
                onCollapse = { mouseOpen = false }
            )
        }
        if (keysOpen) {
            OmniSoftKeyboard(
                ru = ru,
                shift = shift,
                digits = digits,
                compact = landscape,
                onInsert = { ch -> insert(ch) },
                onKey = { code, tag -> key(code, tag) },
                onToggleLang = { ru = !ru },
                onToggleShift = { shift = !shift },
                onToggleDigits = { digits = !digits }
            )
        }
        OmniInputLine(
            value = proxy,
            onValueChange = { next ->
                if (next.length > proxy.length) {
                    next.substring(proxy.length).forEach { ch -> insert(ch.toString()) }
                } else if (next.length < proxy.length) {
                    repeat(proxy.length - next.length) { del() }
                }
                proxy = if (next.length > 400) next.takeLast(400) else next
            },
            focus = focus,
            compact = landscape,
            keysOpen = keysOpen,
            onToggleKeys = {
                keysOpen = !keysOpen
                if (keysOpen) {
                    // закрываем системную клавиатуру, чтобы не занимала пол-экрана
                    try { root.findFocus()?.clearFocus() } catch (_: Throwable) {}
                }
            },
            mouseOpen = mouseOpen,
            onToggleMouse = { mouseOpen = !mouseOpen },
            onShowSystemKeyboard = onShowSystemKeyboard,
            onClose = onClose
        )
        if (status.isNotEmpty()) {
            Text(
                status,
                fontSize = 9.sp,
                color = Color(0xFF9AA0AA),
                modifier = Modifier.padding(top = 2.dp, start = 4.dp)
            )
        }
    }
}

/* ================= полупрозрачная строка ввода ================= */

@Composable
private fun OmniInputLine(
    value: String,
    onValueChange: (String) -> Unit,
    focus: FocusRequester,
    compact: Boolean,
    keysOpen: Boolean,
    onToggleKeys: () -> Unit,
    mouseOpen: Boolean,
    onToggleMouse: () -> Unit,
    onShowSystemKeyboard: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 5.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = if (compact) 13.sp else 15.sp),
            modifier = Modifier
                .weight(1f)
                .background(Color(0x4D26282F))
                .padding(horizontal = 8.dp, vertical = if (compact) 5.dp else 8.dp)
                .focusRequester(focus)
        ) { inner ->
            if (value.isEmpty()) {
                Text("печать сюда →", fontSize = 12.sp, color = Color(0x99AAB0BC))
            }
            inner()
        }
        SmallChip(if (keysOpen) "⌨▾" else "⌨", onToggleKeys, active = keysOpen)
        SmallChip(if (mouseOpen) "🖱▾" else "🖱", onToggleMouse, active = mouseOpen)
        SmallChip("сист", onShowSystemKeyboard)
        SmallChip("✕", onClose)
    }
}

@Composable
private fun SmallChip(
    title: String,
    onClick: () -> Unit,
    active: Boolean = false
) {
    Box(
        modifier = Modifier
            .background(if (active) Color(0x66FFC857) else Color(0x40FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) { Text(title, fontSize = 12.sp, color = Color.White) }
}

/* ================= своя сенсорная клавиатура ================= */

private val RU_LOWER = listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю")
private val EN_LOWER = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
private const val SYMBOL_ROW = "1234567890"
private const val PUNCT_ROW = "-=.,!?()/@#"

@Composable
private fun OmniSoftKeyboard(
    ru: Boolean,
    shift: Boolean,
    digits: Boolean,
    compact: Boolean,
    onInsert: (String) -> Unit,
    onKey: (Int, String) -> Unit,
    onToggleLang: () -> Unit,
    onToggleShift: () -> Unit,
    onToggleDigits: () -> Unit
) {
    val rows = if (ru) RU_LOWER else EN_LOWER
    val pad = if (compact) 2.dp else 3.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = pad)
            .background(Color(0x59000000))
            .padding(pad),
        verticalArrangement = Arrangement.spacedBy(pad)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(pad)
            ) {
                row.forEach { c ->
                    val t = if (shift) c.toString().uppercase() else c.toString()
                    SmallKey(t) { onInsert(t) }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(pad)
        ) {
            (SYMBOL_ROW + PUNCT_ROW).forEach { c ->
                val t = c.toString()
                SmallKey(t) { onInsert(t) }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(pad),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmallChip(if (ru) "Рус" else "Eng", onToggleLang, active = true)
            SmallChip(if (shift) "⇧▴" else "⇧", onToggleShift, active = shift)
            SmallChip("123", onToggleDigits, active = digits)
            SmallKey("Пробел") { onInsert(" ") }
            SmallKey("⌫") { onKey(KeyEvent.KEYCODE_DEL, "⌫") }
            SmallKey("Enter") { onKey(KeyEvent.KEYCODE_ENTER, "Enter") }
            SmallKey("Tab") { onKey(KeyEvent.KEYCODE_TAB, "Tab") }
            SmallKey("Esc") { onKey(KeyEvent.KEYCODE_ESCAPE, "Esc") }
            SmallKey("←") { onKey(KeyEvent.KEYCODE_DPAD_LEFT, "←") }
            SmallKey("↑") { onKey(KeyEvent.KEYCODE_DPAD_UP, "↑") }
            SmallKey("↓") { onKey(KeyEvent.KEYCODE_DPAD_DOWN, "↓") }
            SmallKey("→") { onKey(KeyEvent.KEYCODE_DPAD_RIGHT, "→") }
        }
    }
}

@Composable
private fun SmallKey(title: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Color(0x4DFFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 9.dp)
    ) { Text(title, fontSize = 13.sp, color = Color.White) }
}

/* ================= виртуальная мышь (тачпад) ================= */

@Composable
private fun OmniTouchPad(
    webView: WebView?,
    compact: Boolean,
    onStatus: (String) -> Unit,
    onCollapse: () -> Unit
) {
    // положение «курсора» в долях площадки: 0..1
    var fx by remember { mutableStateOf(0.5f) }
    var fy by remember { mutableStateOf(0.5f) }
    var padW by remember { mutableStateOf(1f) }
    var padH by remember { mutableStateOf(1f) }

    fun point(): Offset {
        val wv = webView ?: return Offset.Zero
        return Offset(fx * wv.width.toFloat(), fy * wv.height.toFloat())
    }

    fun hover(x: Float, y: Float) {
        val wv = webView ?: return
        try {
            val t = SystemClock.uptimeMillis()
            val ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, x, y, 0)
            ev.source = InputDevice.SOURCE_MOUSE
            wv.dispatchGenericMotionEvent(ev)
            ev.recycle()
        } catch (_: Throwable) {
        }
    }

    fun click(secondary: Boolean) {
        val wv = webView ?: return
        val p = point()
        try {
            val t = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, p.x, p.y, 0)
            down.source = InputDevice.SOURCE_MOUSE
            down.buttonState = if (secondary) MotionEvent.BUTTON_SECONDARY else MotionEvent.BUTTON_PRIMARY
            wv.dispatchTouchEvent(down)
            down.recycle()
            val up = MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, p.x, p.y, 0)
            up.source = InputDevice.SOURCE_MOUSE
            up.buttonState = 0
            wv.dispatchTouchEvent(up)
            up.recycle()
            onStatus(if (secondary) "мышь: правая кнопка ${p.x.toInt()},${p.y.toInt()}" else "мышь: клик ${p.x.toInt()},${p.y.toInt()}")
        } catch (_: Throwable) {
        }
    }

    fun scroll(dy: Float) {
        val wv = webView ?: return
        val p = point()
        try {
            val t = SystemClock.uptimeMillis()
            val ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_SCROLL, p.x, p.y, 0)
            ev.source = InputDevice.SOURCE_MOUSE
            ev.setAxisValue(MotionEvent.AXIS_VSCROLL, dy)
            wv.dispatchGenericMotionEvent(ev)
            ev.recycle()
            onStatus(if (dy > 0) "мышь: колесо вверх" else "мышь: колесо вниз")
        } catch (_: Throwable) {
        }
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("мышь (тап по площадке = клик)", fontSize = 9.sp, color = Color(0xFFB9BFC9))
            SmallChip("ЛКМ") { click(false) }
            SmallChip("ПКМ") { click(true) }
            SmallChip("↑") { scroll(1f) }
            SmallChip("↓") { scroll(-1f) }
            SmallChip("—") { onCollapse() }
        }
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .size(if (compact) 200.dp else 240.dp, if (compact) 110.dp else 140.dp)
                .background(Color(0x4A1E2A38))
                .onSizeChanged { padW = it.width.toFloat().coerceAtLeast(1f); padH = it.height.toFloat().coerceAtLeast(1f) }
                .pointerInput(webView) {
                    detectTapGestures(
                        onTap = { off ->
                            fx = (off.x / padW).coerceIn(0f, 1f)
                            fy = (off.y / padH).coerceIn(0f, 1f)
                            hover(fx * padW, fy * padH)
                            click(false)
                        },
                        onLongPress = { off ->
                            fx = (off.x / padW).coerceIn(0f, 1f)
                            fy = (off.y / padH).coerceIn(0f, 1f)
                            click(true)
                        }
                    )
                }
                .pointerInput(webView) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        fx = (change.position.x / padW).coerceIn(0f, 1f)
                        fy = (change.position.y / padH).coerceIn(0f, 1f)
                        val p = point()
                        hover(p.x, p.y)
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color(0xFFFFC857),
                    radius = 7f,
                    center = Offset(fx * size.width, fy * size.height)
                )
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

/** Приёмник ввода в странице: вставка текста, удаление, отправка формы, нажатие «Далее», фокус. */

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
