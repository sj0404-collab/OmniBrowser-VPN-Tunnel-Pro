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
import androidx.compose.foundation.layout.aspectRatio
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
    // v4.28.0: если поле диалога закрыто от чтения, строка показывает свой буфер —
    // иначе она всегда была бы пустой, хотя текст вводится.
    var localBuf by remember { mutableStateOf("") }
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

    /** Показать в строке наш буфер (единственный надёжный источник, когда поле закрыто). */
    fun syncLine() {
        proxy = if (localBuf.length > 400) localBuf.takeLast(400) else localBuf
    }

    /** Только статус: что сейчас в поле страницы. Строку НЕ трогаем. */
    fun pageStatus() {
        js("window.__omniRead?window.__omniRead():''", "поле") { text ->
            try {
                val json = JSONObject(text)
                val cands = json.optString("cands")
                status = if (json.optBoolean("blind")) {
                    "поле компонента: чтение закрыто (наш ввод: ${localBuf.length} симв.) | поля: $cands"
                } else if (json.optBoolean("ok")) {
                    "«${json.optString("tag")}»: ${json.optInt("n")} симв. — ${json.optString("v")}".take(90)
                } else {
                    "поле не найдено | поля: $cands"
                }
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Явное перечитывание (кнопка «⟳»): тянем значение из страницы и, если доступно, обновляем буфер.
     *
     * v4.29.0: обычный ввод строку больше не перезаписывает. Раньше после каждого нажатия мы
     * перечитывали поле и подставляли ответ в строку — а закрытое поле всегда отдаёт «0»,
     * поэтому строка «исчезала» прямо во время набора, хотя текст в игре оставался.
     */
    fun refreshValue() {
        js("window.__omniRead?window.__omniRead():''", "поле") { text ->
            try {
                val json = JSONObject(text)
                val cands = json.optString("cands")
                if (json.optBoolean("blind")) {
                    status = "поле компонента: чтение закрыто (наш ввод: ${localBuf.length} симв.) | поля: $cands"
                    syncLine()
                    return@js
                }
                if (json.optBoolean("ok")) {
                    localBuf = json.optString("v").takeLast(200)
                    syncLine()
                    status = "перечитано из «${json.optString("tag")}»: ${json.optInt("n")} симв."
                } else {
                    status = "поле не найдено | поля: $cands"
                    syncLine()
                }
            } catch (_: Throwable) {
                syncLine()
            }
        }
    }

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

    /**
     * Клавиша без снятия фокуса с поля страницы: нужна для Backspace, когда поле закрыто
     * от чтения — стирать приходится самой системной клавишей.
     */
    fun nativeKeyKeepFocus(keyCode: Int, tag: String) {
        val wv = webView ?: return
        val prev: View? = try { view.rootView.findFocus() } catch (_: Throwable) { null }
        try {
            if (!wv.hasFocus()) wv.requestFocus()
            val t = SystemClock.uptimeMillis()
            wv.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
            wv.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0))
            status = "$tag → Backspace внутрь WebView"
        } catch (_: Throwable) {
        } finally {
            try { if (prev != null && prev !== wv) prev.requestFocus() } catch (_: Throwable) {}
            wv.postDelayed({ hideIme() }, 60)
        }
    }

    fun insert(ch: String) {
        // Строка обновляется сразу и не зависит от того, что ответит страница.
        localBuf = (localBuf + ch).takeLast(200)
        syncLine()
        js("window.__omniInsert&&window.__omniInsert('${esc(ch)}')", "вставка") { text ->
            status = "вставка: $text"
            // v4.65.0: если JS не вставил текст в DOM-поле (игра на canvas слушает
            // KeyEvent напрямую), отправляем доверенную клавишу внутрь WebView.
            if (text.contains("не изменилось") || text.contains("не удалось") || text.contains("не читается")) {
                // Отправляем симметричный KeyEvent для буквы
                val keyCode = charToKeyCode(ch)
                if (keyCode != 0) {
                    nativeKey(keyCode, ch)
                }
            }
        }
    }

    /** Преобразовать символ в Android keyCode (для доверенной отправки KeyEvent). */
    fun charToKeyCode(c: String): Int = when (c.uppercase()) {
        "A" -> KeyEvent.KEYCODE_A; "B" -> KeyEvent.KEYCODE_B; "C" -> KeyEvent.KEYCODE_C
        "D" -> KeyEvent.KEYCODE_D; "E" -> KeyEvent.KEYCODE_E; "F" -> KeyEvent.KEYCODE_F
        "G" -> KeyEvent.KEYCODE_G; "H" -> KeyEvent.KEYCODE_H; "I" -> KeyEvent.KEYCODE_I
        "J" -> KeyEvent.KEYCODE_J; "K" -> KeyEvent.KEYCODE_K; "L" -> KeyEvent.KEYCODE_L
        "M" -> KeyEvent.KEYCODE_M; "N" -> KeyEvent.KEYCODE_N; "O" -> KeyEvent.KEYCODE_O
        "P" -> KeyEvent.KEYCODE_P; "Q" -> KeyEvent.KEYCODE_Q; "R" -> KeyEvent.KEYCODE_R
        "S" -> KeyEvent.KEYCODE_S; "T" -> KeyEvent.KEYCODE_T; "U" -> KeyEvent.KEYCODE_U
        "V" -> KeyEvent.KEYCODE_V; "W" -> KeyEvent.KEYCODE_W; "X" -> KeyEvent.KEYCODE_X
        "Y" -> KeyEvent.KEYCODE_Y; "Z" -> KeyEvent.KEYCODE_Z
        "0" -> KeyEvent.KEYCODE_0; "1" -> KeyEvent.KEYCODE_1; "2" -> KeyEvent.KEYCODE_2
        "3" -> KeyEvent.KEYCODE_3; "4" -> KeyEvent.KEYCODE_4; "5" -> KeyEvent.KEYCODE_5
        "6" -> KeyEvent.KEYCODE_6; "7" -> KeyEvent.KEYCODE_7; "8" -> KeyEvent.KEYCODE_8
        "9" -> KeyEvent.KEYCODE_9
        else -> 0
    }
    fun back() {
        localBuf = if (localBuf.isEmpty()) "" else localBuf.dropLast(1)
        syncLine()
        js("window.__omniBack?window.__omniBack():'нет функций'", "⌫") { text ->
            status = "⌫: $text"
            // v4.65.0: всегда пробуем настоящую клавишу Backspace внутрь WebView как запасной путь.
            // Раньше этого делали только при конкретных ошибках, но игра может просто не отвечать
            // на execCommand('delete') в своём поле — тогда нужна доверенная клавиша.
            if (text.contains("не удалось") || text.contains("нужна клавиша") || text.contains("не читается") || text.contains("вне доступа") || text.contains("пустое") || text.contains("поле пустое") || text.contains("не удалось удалить")) {
                nativeKeyKeepFocus(KeyEvent.KEYCODE_DEL, "⌫")
            } else {
                // v4.65.0: даже если JS сказал «успешно», игра может не иметь DOM-поля
                // (canvas-based игра слушает KeyEvent напрямую). Всегда пробуем настоящую клавишу.
                nativeKeyKeepFocus(KeyEvent.KEYCODE_DEL, "⌫")
            }
        }
    }

    fun pickAtCursor() = js("window.__omniPickAtCursor?window.__omniPickAtCursor():'нет функций'", "поле") { refreshValue() }
    fun enter() = js("window.__omniAct?window.__omniAct('enter'):'нет функций'", "Enter") { refreshValue() }

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
                syncLine()
                pageStatus()
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
            onPickField = { pickAtCursor() },
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
    onPickField: () -> Unit,
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
        SmallChip("◎") { onPickField() }
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
                .fillMaxWidth(if (compact) 0.42f else 0.5f)
                .aspectRatio(2.0f)
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
  window.__omniBlind = false;   // true, если поле живёт в недоступном документе (iframe)
  window.__omniCursorPos = {x: Math.round(window.innerWidth / 2), y: Math.round(window.innerHeight / 2)};

  // Документы, до которых можем дотянуться: свой и вложенные того же происхождения.
  function docs() {
    var out = [];
    function walk(doc, depth) {
      if (!doc || depth > 4) return;
      out.push(doc);
      var fr;
      try { fr = doc.querySelectorAll('iframe, frame'); } catch (e) { return; }
      for (var i = 0; i < fr.length; i++) {
        try { var d = fr[i].contentDocument; if (d) walk(d, depth + 1); } catch (e) {}
      }
    }
    walk(document, 0);
    return out;
  }

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

  function readOf(el) {
    if (!el) return null;
    try {
      if (el.isContentEditable) return (el.innerText || '');
      return el.value == null ? '' : String(el.value);
    } catch (e) { return null; }
  }

  /**
   * v4.27.0: обходим ВСЁ дерево, включая Shadow DOM.
   * Диалог GeForce NOW рисуется веб-компонентом: его поле ввода лежит в shadowRoot, а обычный
   * document.querySelectorAll его не видит — поэтому раньше находилось только пустое служебное поле.
   */
  function collect(root, out, depth) {
    if (!root || depth > 8 || out.length > 4000) return;
    var nodes;
    try { nodes = root.querySelectorAll('*'); } catch (e) { return; }
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (textish(el) && !el.disabled && !el.readOnly) out.push(el);
      var sr = null;
      try { sr = el.shadowRoot; } catch (e) {}
      if (sr) collect(sr, out, depth + 1);
    }
  }

  function allCandidates() {
    var out = [];
    var ds = docs();
    for (var k = 0; k < ds.length; k++) collect(ds[k], out, 0);
    return out;
  }

  /** Активный элемент с проваливанием внутрь shadowRoot. */
  function deepActive() {
    var el = document.activeElement;
    var guard = 0;
    while (el && guard++ < 10) {
      var sr = null;
      try { sr = el.shadowRoot; } catch (e) {}
      if (sr && sr.activeElement) { el = sr.activeElement; continue; }
      break;
    }
    return el;
  }

  function diag() {
    var cs = allCandidates();
    var parts = [];
    for (var i = 0; i < cs.length && i < 4; i++) {
      parts.push((cs[i].tagName || '?') + '(' + ((readOf(cs[i]) || '').length) + ')');
    }
    return parts.length ? parts.join(' ') : 'полей нет';
  }

  /** Кандидат, который реально принимает фокус и самый заметный. */
  function focusTarget() {
    var ae = deepActive();
    if (textish(ae)) return ae;
    var cs = allCandidates();
    var best = null, bestScore = -1;
    for (var i = 0; i < cs.length; i++) {
      var el = cs[i];
      if (!visible(el)) continue;
      var r = box(el);
      var v = readOf(el) || '';
      var score = (v.length > 0 ? 100000000 : 0) + r.width * r.height;
      if (score > bestScore) { bestScore = score; best = el; }
    }
    return best;
  }

  function snapshot() {
    var cs = allCandidates();
    var snap = [];
    for (var i = 0; i < cs.length; i++) snap.push({el: cs[i], v: readOf(cs[i])});
    return snap;
  }

  function changed(snap) {
    var best = null, bestLen = -1;
    for (var i = 0; i < snap.length; i++) {
      var el = snap[i].el;
      if (!el || !el.isConnected) continue;
      var v = readOf(el);
      if (v !== snap[i].v) {
        var len = (v || '').length;
        if (len > bestLen) { bestLen = len; best = el; }
      }
    }
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

  function bindTo(el) { if (el) window.__omniBound = el; return el; }

  /**
   * v4.28.0: поле диалога GeForce NOW лежит в ЗАКРЫТОМ shadow root — прочитать его нельзя,
   * но браузер умеет в него печатать и стирать по сфокусированному полю. Поэтому ввод, удаление
   * и очистка идут командами браузера (insertText / delete / selectAll+delete), а чтение —
   * по возможности, если поле всё-таки доступно.
   */
  function activeEditable() {
    var el = deepActive();
    if (textish(el)) return el;
    if (el && el.isContentEditable) return el;
    return null;
  }

  function cmdInsert(txt) {
    try { return document.execCommand('insertText', false, txt) === true; } catch (e) { return false; }
  }
  function cmdDelete() {
    try { return document.execCommand('delete', false, null) === true; } catch (e) { return false; }
  }

  // ── Чтение того поля, которое реально на экране ───────────────────────────────────────
  window.__omniRead = function() {
    var b = window.__omniBound;
    if (!b || !b.isConnected || !visible(b)) {
      b = focusTarget();
      bindTo(b);
    }
    if (!b) return JSON.stringify({ok: false, v: '', n: 0, tag: '—', cands: diag(), blind: window.__omniBlind});
    var v = readOf(b) || '';
    return JSON.stringify({ok: true, v: v.slice(-200), n: v.length, tag: b.tagName || '?', cands: diag(), blind: window.__omniBlind});
  };

  /** Привязаться к полю, над которым сейчас курсор (ручной выбор). */
  window.__omniPickAtCursor = function() {
    var p = window.__omniCursorPos;
    var el = document.elementFromPoint(p.x, p.y);
    if (!el) return 'под курсором пусто';
    var target = el;
    // проваливаемся внутрь shadowRoot, если компонент его использует
    var guard = 0;
    while (target && guard++ < 10) {
      var deep = null;
      try { deep = target.shadowRoot && target.shadowRoot.elementFromPoint ? target.shadowRoot.elementFromPoint(p.x, p.y) : null; } catch (e) {}
      if (!deep || deep === target) break;
      target = deep;
    }
    var guard2 = 0;
    while (target && !textish(target) && guard2++ < 12) {
      var next = target.parentElement || null;
      if (!next && target.getRootNode) {
        var root = null;
        try { root = target.getRootNode(); } catch (e) {}
        if (root && root.host) next = root.host;
      }
      target = next;
    }
    if (!target) {
      if (el.tagName === 'IFRAME') {
        try {
          var d = el.contentDocument;
          if (d) {
            var inner = d.querySelector('input, textarea, [contenteditable="true"]');
            if (inner) { bindTo(inner); return 'поле в iframe (доступно), ' + (readOf(inner) || '').length + ' симв.'; }
          } else {
            window.__omniBlind = true;
            return 'поле в iframe, документ недоступен — только ввод и Backspace';
          }
        } catch (e) {}
      }
      return 'под курсором не поле: ' + (el.tagName || '?');
    }
    bindTo(target);
    return 'выбрано поле «' + (target.tagName || '?') + '», ' + (readOf(target) || '').length + ' симв.';
  };

  // ── Вставка: находим поле ПО ФАКТУ ИЗМЕНЕНИЯ ─────────────────────────────────────────
  // v4.65.0: execCommand('insertText') — единственный путь для ввода. Если он вернул true,
  // НЕ падаем через setNativeValue/cmdInsert — иначе текст появляется 2-3 раза («I» → «III»).
  window.__omniInsert = function(txt) {
    var snap = snapshot();
    var el = activeEditable() || focusTarget();
    var okCmd = false;
    if (el) {
      try {
        el.focus();
        var len = (readOf(el) || '').length;
        if (el.setSelectionRange) el.setSelectionRange(len, len);
        okCmd = document.execCommand('insertText', false, txt);
      } catch (e) { okCmd = false; }
    } else {
      try { okCmd = document.execCommand('insertText', false, txt); } catch (e) {}
    }
    // v4.65.0: если execCommand вернул true — текст вставлен. Не проверяем changed(),
    // не пишем в setNativeValue, не вызываем cmdInsert — это был бы дубликат.
    if (okCmd) {
      var ch0 = changed(snap);
      if (ch0) { bindTo(ch0); window.__omniBlind = false; try { ch0.blur(); } catch (e) {} }
      return 'введено (execCommand) ' + ((readOf(ch0 || el) || '').length) + ' симв.';
    }
    var ch = changed(snap);
    if (ch) {
      bindTo(ch);
      window.__omniBlind = false;
      try { ch.blur(); } catch (e) {}
      return 'ок → «' + ch.tagName + '» ' + ((readOf(ch) || '').length) + ' симв.';
    }
    // Последняя попытка — команда по сфокусированному полю (работает и в закрытом shadow root)
    if (cmdInsert(txt)) {
      window.__omniBlind = true;
      return 'введено (поле компонента, чтение закрыто)';
    }
    return 'поле не изменилось (' + diag() + ')';
  };

  // v4.65.0: execCommand('delete') — единственный путь для удаления. Если он вернул true,
  // НЕ падаем через setNativeValue — иначе удаление не работает или удаляет дважды.
  window.__omniBack = function() {
    var b = window.__omniBound;
    var el = activeEditable() || ((b && b.isConnected && visible(b)) ? b : focusTarget());
    var v = el ? (readOf(el) || '') : '';
    if (!el || !v.length) {
      if (cmdDelete()) {
        window.__omniBlind = true;
        return 'удалено (поле компонента)';
      }
      if (window.__omniBlind) return 'поле вне доступа — нужна клавиша Backspace';
      if (!el) return 'поле не читается (' + diag() + ')';
      return 'поле пустое («' + el.tagName + '»)';
    }
    try {
      el.focus();
      if (el.setSelectionRange) el.setSelectionRange(v.length - 1, v.length);
      if (document.execCommand('delete', false, null)) {
        try { el.blur(); } catch (e) {}
        return 'было ' + v.length + ' → стало ' + (v.length - 1);
      }
    } catch (e) {}
    // execCommand не сработал — пробуем запись
    var nv = v.slice(0, v.length - 1);
    setNativeValue(el, nv);
    fire(el, 'input');
    fire(el, 'change');
    try { el.blur(); } catch (e) {}
    return (readOf(el) === nv) ? ('было ' + v.length + ' → стало ' + nv.length + ' (запись)') : 'не удалось удалить';
  };

  window.__omniClear = function() {
    var el = activeEditable() || window.__omniBound;
    if (!el || !el.isConnected) el = focusTarget();
    if (!el) {
      try {
        document.execCommand('selectAll', false, null);
        if (cmdDelete()) return 'очищено (поле компонента)';
      } catch (e) {}
      return 'поле не читается (' + diag() + ')';
    }
    var v = readOf(el) || '';
    try {
      el.focus();
      if (el.setSelectionRange) el.setSelectionRange(0, v.length);
      if (document.execCommand('delete', false, null)) {
        try { el.blur(); } catch (e) {}
        return 'очищено (было ' + v.length + ')';
      }
    } catch (e) {}
    setNativeValue(el, '');
    fire(el, 'input');
    fire(el, 'change');
    try { el.blur(); } catch (e) {}
    return (readOf(el) || '').length === 0 ? ('очищено (было ' + v.length + ')') : 'не удалось очистить';
  };

  window.__omniBlur = function() {
    try {
      var el = deepActive();
      if (el && el.blur && (textish(el) || el.isContentEditable)) el.blur();
    } catch (e) {}
    return 'blur';
  };

  // ── Кнопка «Далее» и подобные ────────────────────────────────────────────────────────
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
    var best = null, bestDepth = -1, bestText = '', ds = docs();
    for (var k = 0; k < ds.length; k++) {
      var all;
      try { all = ds[k].querySelectorAll('*'); } catch (e) { continue; }
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
    }
    if (!best) return 'кнопка не найдена';
    try { best.click(); return 'нажато: ' + bestText.slice(0, 24); } catch (e) {}
    return 'err:' + e;
  };

  window.__omniSubmitForm = function() {
    var f = focusTarget();
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

  // ── Мышь ────────────────────────────────────────────────────────────────────────────
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
      case 'pick': return window.__omniPickAtCursor();
      default: return 'неизвестное действие';
    }
  };
})();
"""
