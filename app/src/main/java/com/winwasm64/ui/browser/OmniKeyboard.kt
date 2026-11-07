package com.winwasm64.ui.browser

import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Общее состояние виртуального курсора: панель его двигает, а оверлей полного экрана — рисует.
 * В самой странице курсор не видно: клиент GeForce NOW занимает экран через HTML-fullscreen.
 */
object OmniCursor {
    var active by mutableStateOf(false)
    var fx by mutableStateOf(0.5f)
    var fy by mutableStateOf(0.5f)
}

/**
 * v4.25.0: правка текста родными средствами браузера, мышь — настоящими MotionEvent,
 * строка ввода — зеркало поля на экране.
 *
 * Что показали статусы 4.24.0:
 *
 *  1. `вставка: не удалось записать в поле` — мы писали в `.value` и надеялись, что страница
 *     это примет. Оверлей GeForce NOW написан на React: он держит своё состояние и откатывает
 *     нашу запись. Теперь текст вводится так же, как это делает человек: фокусируем поле,
 *     ставим курсор в конец и вызываем `document.execCommand('insertText')` — браузер сам
 *     генерирует честное событие ввода, которое React принимает. Удаление — выделяем последний
 *     символ и `execCommand('delete')`. Прямая запись осталась запасным путём.
 *
 *  2. `ЛКМ: нажато: VIDEO` — клик отправлялся синтетическим DOM-событием, а игра (и её движок)
 *     такие события игнорирует, потому что они недоверенные. Теперь мышь двигается и щёлкает
 *     **настоящими** `MotionEvent` с `source = SOURCE_MOUSE` прямо в WebView: движение —
 *     `ACTION_HOVER_MOVE`, клик — `ACTION_DOWN/UP`, правая — с `BUTTON_SECONDARY`. Плюс
 *     DOM-событие как запасной путь для кнопок веб-интерфейса.
 *
 *  3. Строка ввода была «пустышкой»: в ней копился наш текст, а не то, что реально в поле
 *     страницы. Теперь после каждого действия строка **перечитывает значение из поля** и
 *     показывает его (кнопка «⟳» — перечитать вручную). В статусе видно `в поле: N символов`.
 *
 *  4. `Enter: кнопка не найдена` — искали кнопку только рядом с полем. Теперь «Далее/Продолжить/
 *     Отправить/Войти/OK» ищется по всей странице, берётся самый «глубокий» подходящий элемент
 *     (в том числе обычный div с обработчиком) и по нему выполняется `click()`.
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
    var curFx by remember { mutableStateOf(0.5f) }
    var curFy by remember { mutableStateOf(0.5f) }
    val focus = remember { FocusRequester() }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val context = LocalContext.current
    val view = LocalView.current

    fun hideIme() {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        } catch (_: Throwable) {
        }
    }

    fun showSystemKeyboard() {
        try {
            focus.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            status = "системная клавиатура для строки ввода"
        } catch (_: Throwable) {
        }
    }

    /** Вызов JS и разбор ответа. `onValue` получает распакованную строку ответа. */
    fun js(code: String, tag: String, onValue: ((String) -> Unit)? = null) {
        val wv = webView ?: return
        try {
            wv.evaluateJavascript("try{$code}catch(e){'err:'+e}") { raw ->
                var text = (raw ?: "").trim()
                if (text.startsWith("\"")) {
                    text = try {
                        (JSONTokener(text).nextValue() as? String) ?: text.trim('"')
                    } catch (_: Throwable) {
                        text.trim('"')
                    }
                }
                if (text.isNotEmpty() && text != "null") status = "$tag: $text"
                onValue?.invoke(text)
            }
        } catch (_: Throwable) {
        }
    }

    /** Перечитать значение из поля страницы в нашу строку — чтобы строка не была «пустышкой». */
    fun refreshValue() {
        js("window.__omniRead?window.__omniRead():''", "поле") { text ->
            try {
                val json = JSONObject(text)
                val v = json.optString("v")
                val tag = json.optString("tag")
                proxy = v.takeLast(400)
                status = if (json.optBoolean("ok")) {
                    "в поле «$tag»: ${json.optInt("n")} симв. — $v".take(90)
                } else {
                    "поле страницы не найдено"
                }
            } catch (_: Throwable) {
                // ответ не JSON — ничего не делаем
            }
        }
    }

    fun insert(ch: String) = js("window.__omniInsert&&window.__omniInsert('${esc(ch)}')", "вставка") { refreshValue() }
    fun back() = js("window.__omniBack?window.__omniBack():'нет функций'", "⌫") { refreshValue() }
    fun enter() = js("window.__omniAct?window.__omniAct('enter'):'нет функций'", "Enter") { refreshValue() }

    /** Клавиша внутрь WebView: доходит только при фокусе у WebView, поэтому фокус даём, а IME гасим. */
    fun nativeKey(keyCode: Int, tag: String) {
        val wv = webView ?: return
        val prev: View? = try { view.rootView.findFocus() } catch (_: Throwable) { null }
        try {
            wv.evaluateJavascript("try{window.__omniBlur&&window.__omniBlur()}catch(e){}", null)
            if (!wv.hasFocus()) wv.requestFocus()
            val t = SystemClock.uptimeMillis()
            wv.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
            wv.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0))
            status = "$tag → доверенная клавиша отправлена"
            com.winwasm64.vpn.VpnLog.i("Браузер", "ввод: $tag → KeyEvent($keyCode)")
        } catch (e: Throwable) {
            status = "$tag: сбой — ${e.message}"
        } finally {
            try { if (prev != null && prev !== wv) prev.requestFocus() } catch (_: Throwable) {}
            wv.postDelayed({ hideIme() }, 60)
        }
    }

    fun handleKey(code: Int, tag: String) {
        when (code) {
            KeyEvent.KEYCODE_DEL -> back()
            KeyEvent.KEYCODE_ENTER -> enter()
            else -> nativeKey(code, tag)
        }
    }

    /** Настоящее событие мыши внутрь WebView (доверенное, игра его видит). */
    fun mouseNative(action: Int, hover: Boolean, buttons: Int) {
        val wv = webView ?: return
        try {
            val x = curFx * wv.width
            val y = curFy * wv.height
            val t = SystemClock.uptimeMillis()
            val ev = MotionEvent.obtain(t, t, action, x, y, 0)
            ev.source = InputDevice.SOURCE_MOUSE
            if (buttons != 0) {
                try {
                    val m = MotionEvent::class.java.getMethod("setButtonState", Integer.TYPE)
                    m.invoke(ev, buttons)
                } catch (_: Throwable) {
                }
            }
            if (hover) wv.dispatchGenericMotionEvent(ev) else wv.dispatchTouchEvent(ev)
            ev.recycle()
        } catch (_: Throwable) {
        }
    }

    fun click(right: Boolean) {
        mouseNative(MotionEvent.ACTION_DOWN, false, if (right) 2 else 1)
        mouseNative(MotionEvent.ACTION_UP, false, 0)
        // для кнопок веб-интерфейса (DOM) — ещё и событие страницы
        js(
            "window.__omniClick?window.__omniClick('${if (right) "right" else "left"}'):''",
            if (right) "ПКМ" else "ЛКМ"
        ) { refreshValue() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x66101216))
            .padding(if (landscape) 3.dp else 6.dp),
        horizontalAlignment = Alignment.End
    ) {
        if (mouseOpen) {
            MousePad(
                compact = landscape,
                onMove = { dx, dy ->
                    val wv = webView
                    if (wv != null) {
                        try {
                            wv.evaluateJavascript(
                                "try{window.__omniMouseMove?window.__omniMouseMove(${dx.toInt()},${dy.toInt()}):''}catch(e){}"
                            ) { raw ->
                                val text = ((raw ?: "").trim()).trim('"')
                                val parts = text.split(",")
                                if (parts.size >= 4) {
                                    val fx = parts[2].toFloatOrNull()
                                    val fy = parts[3].toFloatOrNull()
                                    if (fx != null && fy != null) {
                                        OmniCursor.active = true
                                        OmniCursor.fx = fx.coerceIn(0f, 1f)
                                        OmniCursor.fy = fy.coerceIn(0f, 1f)
                                        curFx = OmniCursor.fx
                                        curFy = OmniCursor.fy
                                        // настоящее движение мыши: видят и игра, и страница
                                        mouseNative(MotionEvent.ACTION_HOVER_MOVE, true, 0)
                                        status = "курсор ${(curFx * 100).toInt()}% × ${(curFy * 100).toInt()}%"
                                    }
                                }
                            }
                        } catch (_: Throwable) {
                        }
                    }
                },
                onClick = { right -> click(right) },
                onWheel = { dy -> js("window.__omniWheel?window.__omniWheel(${dy.toInt()}):''", "прокрутка") },
                onCollapse = {
                    mouseOpen = false
                    OmniCursor.active = false
                    js("window.__omniMouseShow?window.__omniMouseShow(0):''", "курсор")
                }
            )
        }
        if (keysOpen) {
            SoftKeyboard(
                ru = ru,
                shift = shift,
                digits = digits,
                onInsert = { ch -> insert(ch) },
                onKey = { code, tag -> handleKey(code, tag) },
                onToggleLang = { ru = !ru },
                onToggleShift = { shift = !shift },
                onToggleDigits = { digits = !digits }
            )
        }
        InputLine(
            value = proxy,
            onValueChange = { next ->
                if (next.length > proxy.length) {
                    next.substring(proxy.length).forEach { ch -> insert(ch.toString()) }
                } else if (next.length < proxy.length) {
                    repeat(proxy.length - next.length) { back() }
                }
                proxy = if (next.length > 400) next.takeLast(400) else next
            },
            focus = focus,
            compact = landscape,
            keysOpen = keysOpen,
            onToggleKeys = {
                keysOpen = !keysOpen
                if (keysOpen) hideIme()
                refreshValue()
            },
            mouseOpen = mouseOpen,
            onToggleMouse = {
                mouseOpen = !mouseOpen
                if (mouseOpen) {
                    OmniCursor.active = true
                    status = "мышь: тяни по площадке — курсор едет; тап — клик"
                } else {
                    OmniCursor.active = false
                    js("window.__omniMouseShow?window.__omniMouseShow(0):''", "курсор")
                }
            },
            onRefresh = { refreshValue() },
            onShowSystemKeyboard = { showSystemKeyboard() },
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
private fun InputLine(
    value: String,
    onValueChange: (String) -> Unit,
    focus: FocusRequester,
    compact: Boolean,
    keysOpen: Boolean,
    onToggleKeys: () -> Unit,
    mouseOpen: Boolean,
    onToggleMouse: () -> Unit,
    onRefresh: () -> Unit,
    onShowSystemKeyboard: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
                Text("строка зеркалит поле игры — ⟳ перечитать", fontSize = 11.sp, color = Color(0x99AAB0BC))
            }
            inner()
        }
        SmallChip("⟳") { onRefresh() }
        SmallChip(if (keysOpen) "⌨▾" else "⌨", active = keysOpen) { onToggleKeys() }
        SmallChip(if (mouseOpen) "🖱▾" else "🖱", active = mouseOpen) { onToggleMouse() }
        SmallChip("сист") { onShowSystemKeyboard() }
        SmallChip("✕") { onClose() }
    }
}

@Composable
private fun SmallChip(
    title: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .background(if (active) Color(0x66FFC857) else Color(0x40FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) { Text(title, fontSize = 12.sp, color = Color.White) }
}

/* ================= мышь: тачпад как в Winlator ================= */

@Composable
private fun MousePad(
    compact: Boolean,
    onMove: (Float, Float) -> Unit,
    onClick: (Boolean) -> Unit,
    onWheel: (Float) -> Unit,
    onCollapse: () -> Unit
) {
    Column(modifier = Modifier.padding(bottom = 3.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("мышь: тяни — курсор едет, тап — клик", fontSize = 9.sp, color = Color(0xFFB9BFC9))
            SmallChip("ЛКМ") { onClick(false) }
            SmallChip("ПКМ") { onClick(true) }
            SmallChip("↑") { onWheel(320f) }
            SmallChip("↓") { onWheel(-320f) }
            SmallChip("—") { onCollapse() }
        }
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .size(if (compact) 230.dp else 260.dp, if (compact) 120.dp else 150.dp)
                .background(Color(0xC4141B24))
                .border(1.dp, Color(0x77FFC857))
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        onMove(drag.x * 2.4f, drag.y * 2.4f)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onClick(false) },
                        onLongPress = { onClick(true) }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text("ТАЧПАД\nтяни — курсор, тап — клик", fontSize = 10.sp, color = Color(0x88FFFFFF))
        }
    }
}

/* ================= своя сенсорная клавиатура ================= */

private val RU_LOWER = listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю")
private val EN_LOWER = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
private const val SYMBOL_ROW = "1234567890"
private const val PUNCT_ROW = "-=.,!?()/@#"

@Composable
private fun SoftKeyboard(
    ru: Boolean,
    shift: Boolean,
    digits: Boolean,
    onInsert: (String) -> Unit,
    onKey: (Int, String) -> Unit,
    onToggleLang: () -> Unit,
    onToggleShift: () -> Unit,
    onToggleDigits: () -> Unit
) {
    val rows = if (ru) RU_LOWER else EN_LOWER
    val pad = 1.dp

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
            SmallChip(if (ru) "Рус" else "Eng", active = true) { onToggleLang() }
            SmallChip(if (shift) "⇧▴" else "⇧", active = shift) { onToggleShift() }
            SmallChip("123", active = digits) { onToggleDigits() }
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
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) { Text(title, fontSize = 12.sp, color = Color.White) }
}

private fun esc(s: String): String = s
    .replace("\\", "\\\\")
    .replace("'", "\\'")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

const val OMNI_KEY_JS = """
(function(){
  if (window.__omniAct) return;

  window.__omniBound = null;
  window.__omniCursorPos = {x: Math.round(window.innerWidth / 2), y: Math.round(window.innerHeight / 2)};

  function textish(el) {
    if (!el) return false;
    if (el.isContentEditable) return true;
    if (el.tagName === 'TEXTAREA') return true;
    if (el.tagName !== 'INPUT') return false;
    var t = (el.type || 'text').toLowerCase();
    return t === 'text' || t === 'search' || t === 'email' || t === 'tel' || t === 'url' ||
           t === 'password' || t === 'number' || t === '';
  }

  function box(el) { try { return el.getBoundingClientRect(); } catch (e) { return null; } }

  function visible(el) {
    if (!el) return false;
    var r = box(el);
    return !!r && r.width > 1 && r.height > 1;
  }

  function pick() {
    var b = window.__omniBound;
    if (b && b.isConnected && visible(b)) return b;
    window.__omniBound = null;
    var all = document.querySelectorAll('input, textarea, [contenteditable="true"]');
    var best = null, bestArea = 0;
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (!textish(el) || el.disabled || el.readOnly) continue;
      if (!visible(el)) continue;
      var r = box(el);
      var a = r.width * r.height;
      if (a > bestArea) { bestArea = a; best = el; }
    }
    if (!best) {
      var ae = document.activeElement;
      if (textish(ae)) best = ae;
    }
    if (best) window.__omniBound = best;
    return best;
  }

  function readOf(el) {
    if (!el) return null;
    if (el.isContentEditable) return (el.innerText || '');
    return el.value == null ? '' : String(el.value);
  }

  function setNativeValue(el, v) {
    try {
      var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      var d = Object.getOwnPropertyDescriptor(proto, 'value');
      if (d && d.set) { d.set.call(el, v); return true; }
    } catch (e) {}
    try { el.value = v; return true; } catch (e) { return false; }
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

  // ── Вставка «как человек»: фокус, курсор в конец, родная команда браузера ──────────────
  // Так страница на React получает честное событие ввода и не откатывает значение.
  function nativeInsert(el, txt) {
    try {
      el.focus();
      var len = readOf(el).length;
      if (el.setSelectionRange) { el.setSelectionRange(len, len); }
      var ok = document.execCommand('insertText', false, txt);
      return ok && readOf(el).length === len + txt.length;
    } catch (e) { return false; }
  }

  function nativeDeleteLast(el) {
    try {
      el.focus();
      var v = readOf(el);
      if (!v.length) return false;
      if (el.setSelectionRange) { el.setSelectionRange(v.length - 1, v.length); }
      var ok = document.execCommand('delete', false, null);
      return ok && readOf(el).length === v.length - 1;
    } catch (e) { return false; }
  }

  window.__omniRead = function() {
    var el = pick();
    if (!el) return JSON.stringify({ok: false, v: '', n: 0, tag: '—'});
    var v = readOf(el) || '';
    return JSON.stringify({ok: true, v: v.slice(-200), n: v.length, tag: el.tagName || '?'});
  };

  window.__omniInsert = function(txt) {
    var el = pick();
    if (!el) return 'поле не найдено';
    var before = readOf(el);
    if (nativeInsert(el, txt)) {
      try { el.blur(); } catch (e) {}
      return 'ок, в поле ' + (before.length + txt.length) + ' символов';
    }
    // запасной путь: прямая запись значения
    var v = before + txt;
    setNativeValue(el, v);
    fire(el, 'input');
    fire(el, 'change');
    try { el.blur(); } catch (e) {}
    return readOf(el) === v ? ('ок (запись), ' + v.length + ' символов') : 'не удалось записать в поле';
  };

  window.__omniBack = function() {
    var el = pick();
    if (!el) return 'поле не найдено';
    var v = readOf(el) || '';
    if (!v.length) return 'поле пустое (' + (el.tagName || '?') + ')';
    if (nativeDeleteLast(el)) {
      try { el.blur(); } catch (e) {}
      return 'было ' + v.length + ' → стало ' + readOf(el).length;
    }
    var nv = v.slice(0, v.length - 1);
    setNativeValue(el, nv);
    fire(el, 'input');
    fire(el, 'change');
    try { el.blur(); } catch (e) {}
    return readOf(el) === nv ? ('было ' + v.length + ' → стало ' + nv.length + ' (запись)') : 'не удалось удалить';
  };

  window.__omniClear = function() {
    var el = pick();
    if (!el) return 'поле не найдено';
    var v = readOf(el) || '';
    try {
      el.focus();
      if (el.setSelectionRange) el.setSelectionRange(0, v.length);
      if (document.execCommand('delete', false, null) && readOf(el).length === 0) {
        try { el.blur(); } catch (e) {}
        return 'очищено (было ' + v.length + ')';
      }
    } catch (e) {}
    setNativeValue(el, '');
    fire(el, 'input');
    fire(el, 'change');
    try { el.blur(); } catch (e) {}
    return readOf(el).length === 0 ? ('очищено (было ' + v.length + ')') : 'не удалось очистить';
  };

  window.__omniBlur = function() {
    try {
      var el = document.activeElement;
      if (el && el.blur && (textish(el) || el.isContentEditable)) el.blur();
    } catch (e) {}
    return 'blur';
  };

  // ── Поиск и нажатие кнопки по всей странице ───────────────────────────────────────────
  var WORDS = ['далее', 'дальше', 'продолжить', 'отправить', 'войти', 'вход', 'подтвердить',
               'ok', 'okay', 'next', 'continue', 'submit', 'send', 'sign in', 'login'];

  function textOf(el) {
    var t = '';
    try {
      t = (el.innerText || el.value || el.getAttribute('aria-label') || el.getAttribute('title') || '') + '';
    } catch (e) {}
    return t.replace(/\s+/g, ' ').trim().toLowerCase();
  }

  window.__omniClickButton = function() {
    var all = document.querySelectorAll('*');
    var best = null, bestDepth = -1, bestText = '';
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      var t = textOf(el);
      if (!t || t.length > 40) continue;
      var rank = 0;
      for (var w = 0; w < WORDS.length; w++) {
        if (t.indexOf(WORDS[w]) >= 0) { rank = WORDS.length - w; break; }
      }
      if (!rank) continue;
      if (!visible(el)) continue;
      var depth = 0, n = el;
      while (n) { depth++; n = n.parentElement; }
      if (depth > bestDepth) { bestDepth = depth; best = el; bestText = t; }
    }
    if (!best) return 'кнопка не найдена';
    try { best.scrollIntoView({block: 'center'}); } catch (e) {}
    try { best.click(); return 'нажато: ' + bestText.slice(0, 24); } catch (e) {}
    try {
      var r = best.getBoundingClientRect();
      var o = {bubbles: true, cancelable: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, view: window};
      best.dispatchEvent(new MouseEvent('mousedown', o));
      best.dispatchEvent(new MouseEvent('mouseup', o));
      best.dispatchEvent(new MouseEvent('click', o));
      return 'нажато событием: ' + bestText.slice(0, 24);
    } catch (e) { return 'err:' + e; }
  };

  window.__omniSubmitForm = function() {
    var f = pick();
    if (!f) return 'поле не найдено';
    var form = f.form || (f.closest ? f.closest('form') : null);
    if (!form) return 'формы нет';
    try {
      if (form.requestSubmit) { form.requestSubmit(); return 'форма отправлена'; }
      var sb = form.querySelector('button[type="submit"], input[type="submit"], button');
      if (sb) { sb.click(); return 'нажата кнопка формы'; }
      form.submit();
      return 'форма отправлена';
    } catch (e) { return 'err:' + e; }
  };

  // ── Курсор (в оверлее приложения) и мышь ──────────────────────────────────────────────
  window.__omniMouseShow = function(show) { return 'ок'; };

  window.__omniMouseMove = function(dx, dy) {
    var p = window.__omniCursorPos;
    p.x = Math.max(0, Math.min(window.innerWidth - 1, Math.round(p.x + dx)));
    p.y = Math.max(0, Math.min(window.innerHeight - 1, Math.round(p.y + dy)));
    try {
      var el = document.elementFromPoint(p.x, p.y);
      if (el) {
        var o = {bubbles: true, cancelable: true, clientX: p.x, clientY: p.y, view: window};
        el.dispatchEvent(new MouseEvent('mousemove', o));
      }
    } catch (e) {}
    var fx = window.innerWidth > 0 ? (p.x / window.innerWidth) : 0.5;
    var fy = window.innerHeight > 0 ? (p.y / window.innerHeight) : 0.5;
    return p.x + ',' + p.y + ',' + fx.toFixed(4) + ',' + fy.toFixed(4);
  };

  window.__omniClick = function(btn) {
    var p = window.__omniCursorPos;
    var el = document.elementFromPoint(p.x, p.y);
    if (!el) return 'под курсором пусто';
    var right = btn === 'right';
    var o = {bubbles: true, cancelable: true, clientX: p.x, clientY: p.y, view: window,
             button: right ? 2 : 0, buttons: right ? 2 : 1};
    try { el.dispatchEvent(new MouseEvent('mousedown', o)); } catch (e) {}
    try { el.dispatchEvent(new MouseEvent('mouseup', o)); } catch (e) {}
    if (right) {
      try { el.dispatchEvent(new MouseEvent('contextmenu', o)); } catch (e) {}
    } else {
      try { el.dispatchEvent(new MouseEvent('click', o)); } catch (e) {}
    }
    return 'под курсором: ' + (el.tagName || '?');
  };

  window.__omniWheel = function(dy) {
    try { window.scrollBy(0, dy); return 'прокрутка ' + dy; } catch (e) { return 'err:' + e; }
  };

  window.__omniAct = function(name) {
    switch (name) {
      case 'space': return window.__omniInsert(' ');
      case 'del': return window.__omniBack();
      case 'clear': return window.__omniClear();
      case 'enter':
        var r = window.__omniSubmitForm();
        if (r === 'поле не найдено' || r === 'формы нет') return window.__omniClickButton();
        return r;
      case 'submit': return window.__omniClickButton();
      default: return 'неизвестное действие';
    }
  };
})();
"""
