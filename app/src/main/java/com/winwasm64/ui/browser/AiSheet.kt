package com.winwasm64.ui.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winwasm64.ui.theme.Omni

/**
 * v4.6.0: шторка ИИ-ассистента. [requestPage] запрашивает текст/выделение текущей страницы (через WebView JS),
 * [onOpenUrl] открывает ссылку (страницы получения ключей) и закрывает шторку.
 */
@Composable
fun AiSheetContent(
    requestPage: ((AiAssistant.PageInfo?) -> Unit) -> Unit,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    var settings by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(AiAssistant.selected(ctx)) }
    val busy by AiAssistant.busy
    val status by AiAssistant.status

    fun send(question: String, withPage: Boolean) {
        val q = question.trim()
        if (q.isBlank() || busy) return
        if (withPage) requestPage { p -> AiAssistant.ask(ctx, q, AiAssistant.contextOf(p)) }
        else AiAssistant.ask(ctx, q, null)
    }

    Column(Modifier.fillMaxWidth().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                SheetTitle(
                    if (settings) "Провайдеры и ключи" else "ИИ-ассистент",
                    subtitle = if (settings) "Бесплатные модели · ключи хранятся только на устройстве"
                    else status.ifBlank { if (selected == "auto") "Авто: " + AiAssistant.autoOrder(ctx).joinToString(" → ") { it.title } else AiAssistant.providers.first { it.id == selected }.title + " · " + AiAssistant.model(ctx, selected) }
                )
            }
            if (!settings) OmniIconButton(OmniIcons.Delete, "Очистить", onClick = { AiAssistant.stop(); AiAssistant.clear() })
            OmniIconButton(if (settings) OmniIcons.Back else OmniIcons.Tune, if (settings) "Назад" else "Провайдеры", onClick = { settings = !settings })
        }
        if (settings) {
            AiSettings(onOpenUrl = { onDismiss(); onOpenUrl(it) })
            return@Column
        }

        // выбор провайдера
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AiChip("Авто", selected == "auto") { selected = "auto"; AiAssistant.setSelected(ctx, "auto") }
            for (p in AiAssistant.providers) {
                val ok = AiAssistant.configured(ctx, p)
                AiChip(p.title + (if (ok) "" else " 🔑"), selected == p.id, dim = !ok) {
                    if (ok) { selected = p.id; AiAssistant.setSelected(ctx, p.id) } else settings = true
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // лента сообщений
        val list = AiAssistant.messages
        val lazy = rememberLazyListState()
        val lastText = list.lastOrNull()?.text ?: ""
        LaunchedEffect(list.size, lastText.length) { if (list.isNotEmpty()) lazy.scrollToItem(list.size - 1) }
        Box(Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 380.dp)) {
            if (list.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text("Спросите что угодно или нажмите быстрое действие — ассистент прочитает открытую страницу.", color = Omni.TextDim, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Без ключей работает Pollinations (1 ответ в 30 с). Бесплатный ключ Groq, OpenRouter или Gemini снимает лимиты — см. ⚙.", color = Omni.TextFaint, fontSize = 12.sp)
                }
            } else LazyColumn(state = lazy, modifier = Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                itemsIndexed(list, key = { i, _ -> i }) { _, m -> AiBubble(m, isLast = m === list.lastOrNull(), busy = busy) }
            }
        }

        // быстрые действия
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AiChip("Кратко о странице", false, enabled = !busy) { send("Кратко перескажи, о чём эта страница: 3–6 пунктов с главным.", true) }
            AiChip("Переведи на русский", false, enabled = !busy) { send("Переведи текст страницы (или выделенный фрагмент, если он есть) на русский язык. Сохраняй структуру, ничего не добавляй.", true) }
            AiChip("Объясни выделенное", false, enabled = !busy) { send("Объясни простыми словами выделенный фрагмент (если выделения нет — ключевую мысль страницы). Если это термин или код — поясни, что он делает.", true) }
            AiChip("Главные факты", false, enabled = !busy) { send("Выпиши из страницы ключевые факты, цифры, даты и имена списком. Только то, что есть в тексте.", true) }
        }

        // ввод
        var input by remember { mutableStateOf("") }
        var usePage by remember { mutableStateOf(true) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(Omni.SurfaceHighest).padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    maxLines = 4,
                    textStyle = TextStyle(color = Omni.Text, fontSize = 14.sp),
                    cursorBrush = SolidColor(Omni.Accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(input, usePage); input = "" }),
                    modifier = Modifier.fillMaxWidth()
                )
                if (input.isEmpty()) Text("Вопрос ассистенту…", color = Omni.TextFaint, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            if (busy) {
                Button(onClick = { AiAssistant.stop() }, colors = ButtonDefaults.buttonColors(containerColor = Omni.DangerContainer, contentColor = Omni.Danger), modifier = Modifier.height(44.dp)) { Text("Стоп") }
            } else {
                Button(
                    onClick = { send(input, usePage); input = "" },
                    enabled = input.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Omni.Accent, contentColor = Color.White),
                    modifier = Modifier.height(44.dp)
                ) { Text("→") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(if (usePage) Omni.Accent else Omni.SurfaceHighest).clickable { usePage = !usePage }, contentAlignment = Alignment.Center) {
                if (usePage) Text("✓", color = Color.White, fontSize = 11.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text("Передавать текст страницы вместе с вопросом", color = Omni.TextDim, fontSize = 12.sp, modifier = Modifier.clickable { usePage = !usePage })
        }
    }
}

@Composable
private fun AiChip(text: String, selected: Boolean, dim: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Omni.AccentContainer else Omni.SurfaceHigh)
            .border(0.5.dp, if (selected) Omni.Accent else Omni.Outline, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(text, fontSize = 12.sp, color = if (!enabled || dim) Omni.TextFaint else if (selected) Omni.Accent else Omni.Text, maxLines = 1)
    }
}

@Composable
private fun AiBubble(m: AiAssistant.Msg, isLast: Boolean, busy: Boolean) {
    val ctx = LocalContext.current
    val text by m.textState
    val user = m.role == "user"
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Box(
            Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(14.dp))
                .background(if (user) Omni.AccentContainer else Omni.SurfaceHigh)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (text.isBlank() && !user) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Omni.Accent)
                    Spacer(Modifier.width(8.dp))
                    Text("думает…", color = Omni.TextDim, fontSize = 13.sp)
                }
            } else SelectionContainer { Text(text, color = Omni.Text, fontSize = 14.sp, lineHeight = 20.sp) }
        }
        if (!user && text.isNotBlank() && !(isLast && busy)) {
            TextButton(onClick = {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ai", text))
                Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
            }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text("Копировать", color = Omni.TextFaint, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun AiSettings(onOpenUrl: (String) -> Unit) {
    val ctx = LocalContext.current
    val results by AiAssistant.testResults
    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Ответ ищется по порядку: провайдеры с ключом → Pollinations → OpenCode.", color = Omni.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { AiAssistant.testAll(ctx) }) { Text("Проверить все", color = Omni.Accent, fontSize = 12.sp) }
        }
        for (p in AiAssistant.providers) {
            var key by remember(p.id) { mutableStateOf(AiAssistant.key(ctx, p.id)) }
            var model by remember(p.id) { mutableStateOf(AiAssistant.model(ctx, p.id)) }
            var url by remember(p.id) { mutableStateOf(AiAssistant.customUrl(ctx)) }
            var models by remember(p.id) { mutableStateOf(AiAssistant.cachedModels(ctx, p.id)) }
            var menu by remember(p.id) { mutableStateOf(false) }
            var loading by remember(p.id) { mutableStateOf(false) }
            val res = results[p.id]
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(Omni.SurfaceHigh).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.title, color = Omni.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(if (p.needsKey) "нужен ключ" else "без ключа", color = if (p.needsKey) Omni.Warning else Omni.Success, fontSize = 11.sp)
                }
                Text(p.note, color = Omni.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                if (p.id == "custom") {
                    AiField(url, "https://host/v1/chat/completions", KeyboardType.Uri) { url = it; AiAssistant.setCustomUrl(ctx, it) }
                }
                AiField(key, if (p.needsKey) "API-ключ" else "API-ключ (необязательно)", KeyboardType.Password) { key = it; AiAssistant.setKey(ctx, p.id, it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        AiField(model, "модель", KeyboardType.Text) { model = it; AiAssistant.setModel(ctx, p.id, it) }
                    }
                    Spacer(Modifier.width(6.dp))
                    Box {
                        TextButton(onClick = {
                            if (models.isNotEmpty()) menu = true
                            else { loading = true; AiAssistant.fetchModels(ctx, p) { l, e -> loading = false; models = l; if (l.isNotEmpty()) menu = true else Toast.makeText(ctx, e ?: "список пуст", Toast.LENGTH_SHORT).show() } }
                        }, enabled = !loading) { Text(if (loading) "…" else "Список ▾", color = Omni.Accent, fontSize = 12.sp) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(Omni.SurfaceHighest).heightIn(max = 320.dp)) {
                            DropdownMenuItem(text = { Text("↻ обновить список", color = Omni.TextDim, fontSize = 12.sp) }, onClick = {
                                menu = false; loading = true
                                AiAssistant.fetchModels(ctx, p) { l, e -> loading = false; if (l.isNotEmpty()) { models = l; menu = true } else Toast.makeText(ctx, e ?: "список пуст", Toast.LENGTH_SHORT).show() }
                            })
                            for (id in models) DropdownMenuItem(text = { Text(id, color = if (id == model) Omni.Accent else Omni.Text, fontSize = 13.sp) }, onClick = { model = id; AiAssistant.setModel(ctx, p.id, id); menu = false })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (p.keyUrl.isNotBlank()) TextButton(onClick = { onOpenUrl(p.keyUrl) }) { Text("Получить ключ ↗", color = Omni.Accent, fontSize = 12.sp) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { AiAssistant.test(ctx, p) }, enabled = AiAssistant.configured(ctx, p) || key.isNotBlank()) { Text("Проверить", color = Omni.Accent, fontSize = 12.sp) }
                }
                if (!res.isNullOrBlank()) Text(res, color = if (res.startsWith("✓")) Omni.Success else if (res.startsWith("⏳")) Omni.TextDim else Omni.Danger, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AiField(value: String, hint: String, type: KeyboardType, onChange: (String) -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).background(Omni.SurfaceHighest).padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Omni.Text, fontSize = 13.sp),
            cursorBrush = SolidColor(Omni.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth()
        )
        if (value.isEmpty()) Text(hint, color = Omni.TextFaint, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
