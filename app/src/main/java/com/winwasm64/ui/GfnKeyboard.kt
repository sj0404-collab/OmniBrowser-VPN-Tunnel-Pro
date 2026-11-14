package com.winwasm64.ui

import android.app.Activity
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * v4.97.0: наша клавиатура для GeForce NOW — белые клавиши, панель тащится и масштабируется.
 *
 * Зачем своя: у клиента NVIDIA и у самой игры есть экранные клавиатуры, но их нажатия не доходят
 * до стрима (символы не вводятся, клавиатура сразу закрывается), а убрать их мы не можем — они
 * рисуются внутри их интерфейса. Наша панель отправляет настоящие KeyEvent туда, где сейчас фокус
 * (в игре это видеопоток), и в WebView — так клиент NVIDIA превращает клавиши во ввод для игры.
 */

private const val EN_LOWER = "qwertyuiop|asdfghjkl|zxcvbnm"
private const val RU_LOWER = "йцукенгшщзх|фывапролджэ|ячсмитьбю"

@Composable
fun GfnKeyboardPanel(
    activity: Activity?,
    webView: WebView?,
    onClose: () -> Unit
) {
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    var scale by remember { mutableFloatStateOf(1f) }
    var shift by remember { mutableStateOf(false) }
    var ru by remember { mutableStateOf(false) }

    val rows = (if (ru) RU_LOWER else EN_LOWER).split("|")

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .offset { IntOffset(dx.roundToInt(), dy.roundToInt()) }
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .width(340.dp)
                .background(Color(0xF0101216), RoundedCornerShape(14.dp))
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // ── шапка: за неё тащим ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x33FFFFFF), RoundedCornerShape(8.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            dx += drag.x
                            dy += drag.y
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("⠿ Клавиатура", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Box(Modifier.weight(1f))
                KbChip("−") { scale = (scale - 0.1f).coerceIn(0.6f, 1.8f) }
                KbChip("+") { scale = (scale + 0.1f).coerceIn(0.6f, 1.8f) }
                KbChip("⤢") { scale = 1f; dx = 0f; dy = 0f }
                KbChip("✕") { onClose() }
            }

            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    row.forEach { c ->
                        val t = if (shift) c.uppercase() else c.toString()
                        Key(t) { typeChar(if (shift) c.uppercaseChar() else c, activity, webView) }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                "1234567890".forEach { c -> Key(c.toString()) { typeChar(c, activity, webView) } }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                "-=.,!?@#".forEach { c -> Key(c.toString()) { typeChar(c, activity, webView) } }
            }

            // ── служебные клавиши ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KbChip(if (shift) "⇧▴" else "⇧") { shift = !shift }
                KbChip(if (ru) "Рус" else "Eng") { ru = !ru }
                KbChip("Esc") { sendKey(KeyEvent.KEYCODE_ESCAPE, activity, webView) }
                KbChip("Tab") { sendKey(KeyEvent.KEYCODE_TAB, activity, webView) }
                Key("⌫", weight = 0.8f) { sendKey(KeyEvent.KEYCODE_DEL, activity, webView) }
                Key("⏎", weight = 0.8f) { sendKey(KeyEvent.KEYCODE_ENTER, activity, webView) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KbChip("←") { sendKey(KeyEvent.KEYCODE_DPAD_LEFT, activity, webView) }
                KbChip("↑") { sendKey(KeyEvent.KEYCODE_DPAD_UP, activity, webView) }
                KbChip("↓") { sendKey(KeyEvent.KEYCODE_DPAD_DOWN, activity, webView) }
                KbChip("→") { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, activity, webView) }
                Key("Пробел", weight = 1f) { typeChar(' ', activity, webView) }
            }
        }
    }
}

@Composable
private fun RowScope.Key(label: String, weight: Float = 1f, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(weight)
            .height(34.dp)
            .background(Color(0xF2FFFFFF), RoundedCornerShape(7.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color(0xFF15171C), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun KbChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .height(26.dp)
            .background(Color(0x40FFFFFF), RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/* ================= отправка клавиш в игру ================= */

/**
 * Кому отдаём клавиши: сначала виду с фокусом (в полном экране это видеопоток игры),
 * затем WebView — в нём живёт веб-клиент NVIDIA, превращающий клавиши во ввод для игры.
 */
private fun targets(activity: Activity?, webView: WebView?): List<View> {
    val list = ArrayList<View>(2)
    try {
        activity?.currentFocus?.let { list.add(it) }
    } catch (_: Throwable) {
    }
    webView?.let { if (list.none { it === webView }) list.add(webView) }
    return list
}

fun sendKey(keyCode: Int, activity: Activity?, webView: WebView?) {
    val now = SystemClock.uptimeMillis()
    targets(activity, webView).forEach { v ->
        try {
            v.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            v.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        } catch (_: Throwable) {
        }
    }
}

/** Символ → настоящие клавиатурные события (с шифтом там, где он нужен). */
@Suppress("DEPRECATION")
fun typeChar(c: Char, activity: Activity?, webView: WebView?) {
    val events = try {
        KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(charArrayOf(c))
    } catch (_: Throwable) {
        null
    }
    if (events.isNullOrEmpty()) return
    targets(activity, webView).forEach { v ->
        try {
            events.forEach { e ->
                v.dispatchKeyEvent(KeyEvent(e.downTime, e.eventTime, e.action, e.keyCode, 0, e.metaState))
            }
        } catch (_: Throwable) {
        }
    }
}
