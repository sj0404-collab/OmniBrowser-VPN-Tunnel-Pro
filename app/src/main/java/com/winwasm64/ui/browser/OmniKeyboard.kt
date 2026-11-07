package com.winwasm64.ui.browser

import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyEvent
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

/**
 * Общее состояние виртуального курсора: панель его двигает, а оверлей полного экрана —
 * рисует. В странице курсор не видно: клиент GeForce NOW занимает экран через HTML-fullscreen,
 * и всё, что мы вставляем в документ, остаётся под этим слоем.
 */
object OmniCursor {
    var active by mutableStateOf(false)
    var fx by mutableStateOf(0.5f)
    var fy by mutableStateOf(0.5f)
}

/**
 * v4.24.0: панель сама ведёт текст страницы, а мышь стала тачпадом как в Winlator.
 *
 * Что было сломано на устройстве (4.22.0) и почему:
 *
 *  1. «⌫ пишет начало чего-то / не найдено». Мы удаляли символ по позиции курсора, а страница
 *     (оверлей GeForce NOW) держит курсор в позиции 0. Теперь панель НЕ доверяет курсору:
 *     читает значение поля из страницы, убирает последний символ и записывает значение обратно.
 *     Удаление становится предсказуемым: `удалено, осталось N`.
 *
 *  2. «на каждое нажатие открывается системная клавиатура и сразу закрывается». Виноват был
 *     `WebView.requestFocus()` перед отправкой клавиши: WebView получал фокус, страница открывала
 *     свою клавиатуру, а мы тут же возвращали фокус обратно. Теперь фокус не трогаем вообще,
 *     а системную клавиатуру дополнительно прячем после каждого нажатия.
 *
 *  3. «тачпад не видно, курсор не видно». Площадка рисовалась прозрачной плашкой в оверлее,
 *     а курсор вообще не отображался. Теперь мышь — это настоящий тачпад: тянем по площадке —
 *     курсор едет по странице (относительное движение, чувствительность 2.4), тап — клик левой,
 *     долгое нажатие — правой. Сам курсор рисуется В САМОЙ СТРАНИЦЕ (жёлтое кольцо поверх игры),
 *     поэтому его видно всегда, и по нему же бьёт клик (`elementFromPoint`).
 *
 * Кнопки: «ЛКМ», «ПКМ», «↑/↓» — прокрутка, «—» — свернуть мышь, «⌨» — своя клавиатура,
 * «сист» — системная, «✕» — закрыть. По умолчанию видна только полупрозрачная строка ввода.
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
    val context = LocalContext.current
    val view = LocalView.current

    fun showSystemKeyboard() {
        try {
            focus.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            status = "системная клавиатура для строки ввода"
        } catch (_: Throwable) {
        }
    }

    fun hideIme() {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        } catch (_: Throwable) {
        }
    }

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

    fun back() {
        val wv = webView ?: return
        try {
            wv.evaluateJavascript("try{window.__omniBack?window.__omniBack():'нет функций'}catch(e){'err:'+e}") { r ->
                val res = (r ?: "").trim().trim('"')
                status = "⌫: $res"
                com.winwasm64.vpn.VpnLog.i("Браузер", "ввод: ⌫ → $res")
                if (res.contains("поле не найдено")) {
                    // запасной путь: настоящая клавиша Delete внутрь WebView
                    try {
                        val t = SystemClock.uptimeMillis()
                        wv.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0))
                        wv.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0))
                        status = "⌫: системная клавиша"
                    } catch (_: Throwable) {
                    }
                }
                hideIme()
            }
        } catch (_: Throwable) {
        }
    }

    fun enter() {
        val wv = webView ?: return
        try {
            wv.evaluateJavascript("try{window.__omniAct?window.__omniAct('enter'):'нет функций'}catch(e){'err:'+e}") { r ->
                val res = (r ?: "").trim().trim('"')
                status = "Enter: $res"
                com.winwasm64.vpn.VpnLog.i("Браузер", "ввод: Enter → $res")
                hideIme()
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Клавиша «как есть» внутрь WebView.
     *
     * Такие события доходят до страницы только когда фокус у WebView — именно поэтому Tab
     * раньше открывал верхний оверлей GeForce NOW. Но при передаче фокуса страница открывает
     * свою клавиатуру. Поэтому перед нажатием снимаем фокус с поля страницы (`__omniBlur`),
     * а после — сразу прячем клавиатуру.
     */
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
                            ) { r ->
                                val parts = (r ?: "").trim().trim('"').split(",")
                                if (parts.size >= 4) {
                                    val fx = parts[2].toFloatOrNull()
                                    val fy = parts[3].toFloatOrNull()
                                    if (fx != null && fy != null) {
                                        OmniCursor.active = true
                                        OmniCursor.fx = fx.coerceIn(0f, 1f)
                                        OmniCursor.fy = fy.coerceIn(0f, 1f)
                                    }
                                    status = "курсор ${parts[0]},${parts[1]} (${(OmniCursor.fx * 100).toInt()}%, ${(OmniCursor.fy * 100).toInt()}%)"
                                }
                            }
                        } catch (_: Throwable) {
                        }
                    }
                },
                onClick = { right ->
                    js(
                        "window.__omniClick?window.__omniClick('${if (right) "right" else "left"}'):''",
                        if (right) "ПКМ" else "ЛКМ"
                    )
                },
                onWheel = { dy ->
                    js("window.__omniWheel?window.__omniWheel(${dy.toInt()}):''", "прокрутка")
                },
                onCollapse = {
                    mouseOpen = false
                    OmniCursor.active = false
                    js("window.__omniMouseShow?window.__omniMouseShow(false):''", "курсор")
                }
            )
        }
        if (keysOpen) {
            SoftKeyboard(
                ru = ru,
                shift = shift,
                digits = digits,
                compact = landscape,
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
            },
            mouseOpen = mouseOpen,
            onToggleMouse = {
                mouseOpen = !mouseOpen
                if (mouseOpen) {
                    OmniCursor.active = true
                    status = "мышь: тяни по площадке — курсор едет по экрану"
                } else {
                    OmniCursor.active = false
                    js("window.__omniMouseShow?window.__omniMouseShow(false):''", "курсор")
                }
            },
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
                Text("печать сюда →", fontSize = 12.sp, color = Color(0x99AAB0BC))
            }
            inner()
        }
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
            Text(
                "ТАЧПАД\nтяни — курсор, тап — клик",
                fontSize = 10.sp,
                color = Color(0x88FFFFFF)
            )
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
    compact: Boolean,
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

  // v4.24.0: привязываемся к тому полю, в которое реально пишем.
  // Раньше брали первое видимое — и удаление уходило в пустое поле, а на экране текст оставался.
  window.__omniBound = null;
  window.__omniCursorPos = {x: Math.round(window.innerWidth / 2), y: Math.round(window.innerHeight / 2)};

  function textish(el) {
    if (!el) return false;
    if (el.isContentEditable) return true;
    if (el.tagName === 'TEXTAREA') return true;
    if (el.tagName !== 'INPUT') return false;
    var t = (el.type || 'text').toLowerCase();
    return t === 'text' || t === 'search' || t === 'email' || t === 'tel' || t === 'url' || t === 'password' || t === 'number' || t === '';
  }

  function box(el) {
    try { return el.getBoundingClientRect(); } catch (e) { return null; }
  }

  function visible(el) {
    if (!el) return false;
    var r = box(el);
    return !!r && r.width > 1 && r.height > 1;
  }

  function area(el) {
    var r = box(el);
    return r ? r.width * r.height : 0;
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
      var a = area(el);
      if (a > bestArea) { bestArea = a; best = el; }
    }
    if (!best) {
      var ae = document.activeElement;
      if (textish(ae)) best = ae;
    }
    if (best) window.__omniBound = best;
    return best;
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

  function readOf(el) {
    if (!el) return null;
    if (el.isContentEditable) return (el.innerText || '');
    return el.value == null ? '' : String(el.value);
  }

  function writeTo(el, v) {
    if (!el) return false;
    if (el.isContentEditable) {
      try {
        el.focus();
        document.execCommand('selectAll', false, null);
        document.execCommand('insertText', false, v);
        fire(el, 'input');
        return true;
      } catch (e) { return false; }
    }
    setNativeValue(el, v);
    fire(el, 'input');
    fire(el, 'change');
    try { el.selectionStart = el.selectionEnd = v.length; } catch (e) {}
    return readOf(el) === v;
  }

  window.__omniInsert = function(txt) {
    var el = pick();
    if (!el) {
      try {
        if (document.queryCommandSupported && document.queryCommandSupported('insertText')) {
          return document.execCommand('insertText', false, txt) ? 'ok (по фокусу)' : 'поле не найдено';
        }
      } catch (e) {}
      return 'поле не найдено';
    }
    var v = readOf(el);
    var nv = v + txt;
    if (!writeTo(el, nv)) return 'не удалось записать в поле';
    fire(el, 'keydown', {key: txt, keyCode: txt.charCodeAt(0)});
    fire(el, 'keyup', {key: txt, keyCode: txt.charCodeAt(0)});
    return 'ок, в поле ' + nv.length + ' символов';
  };

  window.__omniBack = function() {
    var el = pick();
    if (!el) return 'поле не найдено';
    var v = readOf(el);
    if (!v || !v.length) return 'поле пустое (' + (el.tagName || '?') + ')';
    var nv = v.slice(0, v.length - 1);
    if (!writeTo(el, nv)) return 'не удалось записать в поле';
    return 'было ' + v.length + ' → стало ' + nv.length;
  };

  window.__omniClear = function() {
    var el = pick();
    if (!el) return 'поле не найдено';
    var v = readOf(el);
    if (!writeTo(el, '')) return 'не удалось записать в поле';
    return 'очищено (было ' + v.length + ')';
  };

  // Снять фокус с поля страницы: иначе при передаче фокуса WebView всплывает системная клавиатура
  window.__omniBlur = function() {
    try {
      var el = document.activeElement;
      if (el && el.blur && (textish(el) || el.isContentEditable)) el.blur();
    } catch (e) {}
    return 'blur';
  };

  var WORDS = ['далее', 'дальше', 'продолжить', 'отправить', 'войти', 'вход', 'подтвердить',
               'ok', 'okay', 'next', 'continue', 'submit', 'send', 'sign in', 'login'];

  window.__omniClickButton = function() {
    var f = pick();
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
      var t = '';
      try { t = (el.innerText || el.value || el.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim().toLowerCase(); } catch (e) {}
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

  function cursorEl() {
    var d = document.getElementById('__omni_cursor');
    if (!d) {
      d = document.createElement('div');
      d.id = '__omni_cursor';
      d.style.cssText = 'position:fixed;left:0;top:0;width:18px;height:18px;margin:-9px 0 0 -9px;' +
        'border:2px solid #FFC857;border-radius:50%;background:rgba(255,200,87,0.25);' +
        'pointer-events:none;z-index:2147483647;';
      (document.body || document.documentElement).appendChild(d);
    }
    return d;
  }

  function placeCursor() {
    var d = cursorEl();
    d.style.left = window.__omniCursorPos.x + 'px';
    d.style.top = window.__omniCursorPos.y + 'px';
  }

  window.__omniMouseShow = function(show) {
    try {
      var d = cursorEl();
      d.style.display = show ? 'block' : 'none';
      placeCursor();
    } catch (e) {}
    return 'ок';
  };

  // Относительное движение, как тачпад в Winlator. Возвращаем ещё и доли экрана:
  // по ним оверлей рисует курсор поверх игры (в самой странице его перекрывает fullscreen-слой).
  window.__omniMouseMove = function(dx, dy) {
    var p = window.__omniCursorPos;
    p.x = Math.max(0, Math.min(window.innerWidth - 1, Math.round(p.x + dx)));
    p.y = Math.max(0, Math.min(window.innerHeight - 1, Math.round(p.y + dy)));
    try { placeCursor(); } catch (e) {}
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
      try { if (el.click) el.click(); } catch (e) {}
    }
    var t = '';
    try { t = (el.innerText || el.value || el.tagName || '').replace(/\s+/g, ' ').trim().slice(0, 28); } catch (e) {}
    return 'нажато: ' + t;
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
