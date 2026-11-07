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
 * v4.19.0: клавиатура, которая реально печатает.
 *
 * Проблема с устройства: в полноэкранной игре (GeForce NOW) клавиатура открывалась, но
 * нажатия до игры не доходили — приходилось выходить из полного экрана и заходить снова.
 * Причина в том, что система показывает IME для WebView, а страница слушает клавиши на
 * своём элементе: если DOM-фокус потерян (мы забрали его кнопкой), символы уходят «в никуда».
 *
 * Здесь другой путь: своё поле ввода, а каждый символ уходит **прямо в страницу** —
 * событием клавиатуры (`keydown`/`keypress`/`keyup`) и вставкой текста в активный элемент.
 * Печатает даже тогда, когда родная экранная клавиатура сайта не реагирует.
 */
@Composable
fun OmniKeyPanel(
    webView: WebView?,
    onClose: () -> Unit,
    onShowSystemKeyboard: () -> Unit
) {
    var proxy by remember { mutableStateOf("") }
    // v4.19.0: клавиатуру показываем для СВОЕГО поля — так IME не конфликтует с фокусом страницы,
    // а символы уходят в игру по одному. Именно этого не хватало: раньше IME открывался «в никуда».
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { focus.requestFocus() } catch (_: Throwable) {}
    }

    fun send(key: String, code: String, ch: String) {
        val wv = webView ?: return
        try {
            wv.evaluateJavascript(
                "try{window.__omniKeyFn&&window.__omniKeyFn('${esc(key)}','${esc(code)}','${esc(ch)}')}catch(e){}",
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
            Text("⌨ Ввод в страницу (работает и когда клавиатура сайта не реагирует)", fontSize = 11.sp, color = Color(0xFFECECEC), modifier = Modifier.weight(1f))
            Button(
                onClick = onShowSystemKeyboard,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF)),
                modifier = Modifier.padding(end = 6.dp)
            ) { Text("системная", fontSize = 10.sp, color = Color.White) }
            Button(onClick = onClose, colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF))) {
                Text("✕", fontSize = 11.sp, color = Color.White)
            }
        }
        BasicTextField(
            value = proxy,
            onValueChange = { next ->
                if (next.length > proxy.length) {
                    // добавленные символы — по одному
                    next.substring(proxy.length).forEach { ch -> send(ch.toString(), codeFor(ch), ch.toString()) }
                } else if (next.length < proxy.length) {
                    repeat(proxy.length - next.length) { send("Backspace", "Backspace", "") }
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
            fun keyButton(title: String, key: String, code: String, ch: String = "") {
                Button(
                    onClick = { send(key, code, ch) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF))
                ) { Text(title, fontSize = 11.sp, color = Color.White) }
            }
            keyButton("Пробел", " ", "Space", " ")
            keyButton("Enter", "Enter", "Enter", "\n")
            keyButton("⌫", "Backspace", "Backspace")
            keyButton("Tab", "Tab", "Tab", "\t")
            keyButton("Esc", "Escape", "Escape")
            keyButton("←", "ArrowLeft", "ArrowLeft")
            keyButton("↑", "ArrowUp", "ArrowUp")
            keyButton("↓", "ArrowDown", "ArrowDown")
            keyButton("→", "ArrowRight", "ArrowRight")
        }
    }
}

/** Код физической клавиши для события: KeyA, Digit1, Space… */
private fun codeFor(ch: Char): String = when {
    ch.isLetter() && ch.code < 128 -> "Key" + ch.uppercaseChar()
    ch.isDigit() -> "Digit$ch"
    ch == ' ' -> "Space"
    ch == '\n' -> "Enter"
    ch == '\t' -> "Tab"
    else -> ""
}

private fun esc(s: String): String = s
    .replace("\\", "\\\\")
    .replace("'", "\\'")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

/**
 * JS-приёмник клавиш. Ставится один раз на WebView (idempotent).
 * Печатает в активный элемент (input/textarea/contenteditable) и дублирует событие на document —
 * так его видят и обычные поля, и игры, которые слушают клавиатуру на уровне страницы.
 */
const val OMNI_KEY_JS = """
(function(){
  if (window.__omniKeyFn) return;
  window.__omniKeyFn = function(key, code, ch) {
    try {
      if (key === '__clear__') { document.activeElement && document.activeElement.blur && document.activeElement.blur(); return; }
      var el = document.activeElement;
      if (!el || el === document.body || el === document.documentElement) el = document.body;
      var base = {key: key, code: code, bubbles: true, cancelable: true};
      try { el.dispatchEvent(new KeyboardEvent('keydown', base)); } catch (e) {}
      try { document.dispatchEvent(new KeyboardEvent('keydown', {key: key, code: code, bubbles: true, cancelable: true})); } catch (e) {}
      if (ch) {
        try {
          if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
            var s = el.selectionStart == null ? el.value.length : el.selectionStart;
            var e2 = el.selectionEnd == null ? s : el.selectionEnd;
            if (el.setRangeText) { el.setRangeText(ch, s, e2, 'end'); } else { el.value = (el.value || '') + ch; }
            el.dispatchEvent(new Event('input', {bubbles: true}));
            el.dispatchEvent(new Event('change', {bubbles: true}));
          } else if (document.queryCommandSupported && document.queryCommandSupported('insertText')) {
            document.execCommand('insertText', false, ch);
          }
        } catch (e) {}
        try { el.dispatchEvent(new KeyboardEvent('keypress', {key: key, code: code, charCode: ch.charCodeAt(0), which: ch.charCodeAt(0), bubbles: true, cancelable: true})); } catch (e) {}
      }
      try { el.dispatchEvent(new KeyboardEvent('keyup', base)); } catch (e) {}
      try { document.dispatchEvent(new KeyboardEvent('keyup', {key: key, code: code, bubbles: true, cancelable: true})); } catch (e) {}
    } catch (e) {}
  };
})();
"""
