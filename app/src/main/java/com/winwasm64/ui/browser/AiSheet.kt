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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import com.winwasm64.vpn.VpnLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Шторка ИИ-ассистента: чат (с агентами и инструментами), список чатов, провайдеры/каталог моделей, агенты и MCP.
 * [host] — мост к браузеру (текущая страница, JS, открыть адрес, скачать). [onOpenUrl] открывает ссылку и закрывает шторку.
 */
@Composable
fun AiSheetContent(host: AiHost, onOpenUrl: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AiAssistant.init(ctx)
    LaunchedEffect(Unit) { AiCatalog.refreshIfStale(ctx); if (AiAssistant.mcpEnabled(ctx)) McpClient.refreshAll(ctx) }
    var view by remember { mutableStateOf("chat") } // chat | chats | providers | agents
    var selected by remember { mutableStateOf(AiAssistant.selected(ctx)) }
    val busy by AiAssistant.busy
    val status by AiAssistant.status
    val chat by AiAssistant.current
    val agent = AiTools.agent(chat?.agent ?: "chat")

    fun send(question: String, withPage: Boolean) {
        val q = question.trim()
        if (q.isBlank() || busy) return
        if (withPage) host.currentPage { p -> AiAssistant.ask(ctx, q, AiAssistant.contextOf(p), host) }
        else AiAssistant.ask(ctx, q, null, host)
    }

    Column(Modifier.fillMaxWidth().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (view != "chat") OmniIconButton(OmniIcons.Back, "Назад", onClick = { view = "chat" })
            Box(Modifier.weight(1f)) {
                when (view) {
                    "chats" -> SheetTitle("Чаты", "${AiAssistant.chats.size} сохранено · хранятся на устройстве")
                    "providers" -> SheetTitle("Провайдеры и модели", "Каталог обновляется автоматически каждые 6 ч · ключи только на устройстве")
                    "agents" -> SheetTitle("Агенты и MCP", "Инструменты браузера + внешние MCP-серверы")
                    // v4.72.0: рабочая папка агента и заметки по проекту
                    "workspace" -> SheetTitle("Workspace агента", com.winwasm64.torrent.AgentWorkspace.displayPath(ctx))
                    "notes" -> SheetTitle("Заметки агента", "NOTES.md в корне workspace — агент дописывает их сам")
                    else -> Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, bottom = 4.dp)) {
                        Text((chat?.title ?: "ИИ-ассистент").ifBlank { "ИИ-ассистент" }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Omni.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(status.ifBlank { "${agent.emoji} ${agent.title} · " + (if (selected == "auto") "Авто: " + AiAssistant.autoOrder(ctx).joinToString(" → ") { it.title } else AiAssistant.providers.first { it.id == selected }.title + " · " + AiAssistant.model(ctx, selected)) },
                            fontSize = 11.sp, color = Omni.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (view == "chat") {
                OmniIconButton(OmniIcons.Add, "Новый чат", onClick = { AiAssistant.stop(); AiAssistant.newChat(ctx); AiAssistant.save(ctx) })
                OmniIconButton(OmniIcons.History, "Чаты", onClick = { view = "chats" })
                OmniIconButton(OmniIcons.Apps, "Агенты и MCP", onClick = { view = "agents" })
                OmniIconButton(OmniIcons.Tune, "Провайдеры", onClick = { view = "providers" })
                // v4.72.0: файлы агента и заметки по проекту
                OmniIconButton(OmniIcons.Folder, "Workspace агента", onClick = { view = "workspace" })
                OmniIconButton(OmniIcons.Note, "Заметки агента", onClick = { view = "notes" })
            }
        }
        when (view) {
            "chats" -> { AiChatsList(onPick = { view = "chat" }); return@Column }
            "providers" -> { AiSettings(onOpenUrl = { onDismiss(); onOpenUrl(it) }); return@Column }
            "agents" -> { AiAgentsView(onOpenUrl = { onDismiss(); onOpenUrl(it) }); return@Column }
            "workspace" -> { AiWorkspaceView(host); return@Column }
            "notes" -> { AiNotesView(); return@Column }
        }

        // агент и провайдер
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (a in AiTools.agents) AiChip("${a.emoji} ${a.title}", agent.id == a.id, enabled = !busy) { chat?.agent = a.id; AiAssistant.save(ctx) }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AiChip("Авто", selected == "auto") { selected = "auto"; AiAssistant.setSelected(ctx, "auto") }
            for (p in AiAssistant.providers) {
                val ok = AiAssistant.configured(ctx, p)
                AiChip(p.title + (if (ok) "" else " 🔑"), selected == p.id, dim = !ok) {
                    if (ok) { selected = p.id; AiAssistant.setSelected(ctx, p.id) } else view = "providers"
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // лента сообщений
        val list = chat?.messages ?: remember { mutableStateListOf<AiAssistant.Msg>() }
        val lazy = rememberLazyListState()
        val lastText = list.lastOrNull()?.text ?: ""
        LaunchedEffect(list.size, lastText.length) { if (list.isNotEmpty()) lazy.scrollToItem(list.size - 1) }
        Box(Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 380.dp)) {
            if (list.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text("${agent.emoji} ${agent.title}: ${agent.hint}.", color = Omni.TextDim, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(if (agent.hasTools) "Агент сам вызывает инструменты (поиск, открытие страниц, чтение, JS, загрузки" + (if (AiAssistant.mcpEnabled(ctx) && McpClient.tools.value.isNotEmpty()) ", MCP: ${McpClient.tools.value.size}" else "") + ") и показывает шаги в чате."
                        else "Спросите что угодно или нажмите быстрое действие — ассистент прочитает открытую страницу.", color = Omni.TextFaint, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Кнопка «+» слева от поля ввода прикрепляет файлы: фото, PDF, документы, архивы, аудио и видео любого объёма — ассистент ищет по ним и читает их целиком. Для фото/сканов нужна модель со зрением (ключ api.navy, LLM7, Gemini, Groq), для речи — ключ Groq или api.navy.", color = Omni.TextFaint, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Без ключей работают Pollinations и LLM7 (turbo). Бесплатный ключ LLM7/Groq/OpenRouter/Gemini открывает полные модели — см. ⚙.", color = Omni.TextFaint, fontSize = 12.sp)
                }
            } else LazyColumn(state = lazy, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                itemsIndexed(list, key = { i, _ -> i }) { _, m ->
                    AiBubble(
                        m,
                        isLast = m === list.lastOrNull(),
                        busy = busy,
                        onRetry = if (m === list.lastOrNull()) ({ AiAssistant.retry(ctx, host) }) else null
                    )
                }
            }
        }

        // ---------- v4.73.0: карточки вариантов ----------
        val vars by AiAssistant.variants
        val rec by AiAssistant.variantRec
        val recIdx by AiAssistant.variantRecIndex
        val vBusy by AiAssistant.variantBusy
        if (vBusy || vars.isNotEmpty()) {
            val question = list.lastOrNull { it.role == "user" }?.text.orEmpty()
            Surface(
                color = Omni.SurfaceHigh,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (vBusy) "🧩 собираю варианты…" else "🧩 Варианты ответа (${vars.size})",
                            color = Omni.Text, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Text("✕ Не хочу", color = Omni.TextFaint, fontSize = 11.sp, modifier = Modifier.clickable { AiAssistant.clearVariants() })
                    }
                    if (rec.isNotBlank()) {
                        Text("⭐ Рекомендация агента: $rec", color = Omni.Accent, fontSize = 11.sp)
                    }
                    var expanded by remember(vars.size) { mutableStateOf(-1) }
                    vars.forEachIndexed { i, v ->
                        Row(Modifier.fillMaxWidth().clickable { expanded = if (expanded == i) -1 else i }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${i + 1}. ${v.provider} · ${v.model}" + (if (i == recIdx) "  ⭐" else ""),
                                    color = if (i == recIdx) Omni.Accent else Omni.Text, fontSize = 11.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                )
                                Text(
                                    v.text, color = Omni.TextDim, fontSize = 11.sp,
                                    maxLines = if (expanded == i) 40 else 3, overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(onClick = {
                                AiAssistant.adoptVariant(ctx, host, question, i)
                            }) { Text("Взять", color = Omni.Accent, fontSize = 11.sp) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (recIdx >= 0 && vars.isNotEmpty()) {
                            TextButton(onClick = { AiAssistant.adoptVariant(ctx, host, question, recIdx) }) {
                                Text("⭐ Взять рекомендованный", color = Omni.Accent, fontSize = 11.sp)
                            }
                        }
                        TextButton(onClick = {
                            if (question.isNotBlank()) host.currentPage { p ->
                                AiAssistant.askVariants(ctx, question, AiAssistant.contextOf(p), host)
                            }
                        }) { Text("⟳ Ещё варианты", color = Omni.TextDim, fontSize = 11.sp) }
                    }
                }
            }
        }

        // быстрые действия
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AiChip("Кратко о странице", false, enabled = !busy) { send("Кратко перескажи, о чём эта страница: 3–6 пунктов с главным.", true) }
            AiChip("Переведи на русский", false, enabled = !busy) { send("Переведи текст страницы (или выделенный фрагмент, если он есть) на русский язык. Сохраняй структуру, ничего не добавляй.", true) }
            AiChip("Объясни выделенное", false, enabled = !busy) { send("Объясни простыми словами выделенный фрагмент (если выделения нет — ключевую мысль страницы). Если это термин или код — поясни, что он делает.", true) }
            AiChip("Главные факты", false, enabled = !busy) { send("Выпиши из страницы ключевые факты, цифры, даты и имена списком. Только то, что есть в тексте.", true) }
            if (agent.hasTools) AiChip("Найди похожее", false, enabled = !busy) { send("Найди в интернете 3–5 источников по теме этой страницы и кратко сравни их с ней.", true) }
            // v4.73.0: несколько вариантов ответа от разных провайдеров + рекомендация агента
            AiChip("🧩 Варианты", false, enabled = !busy && !AiAssistant.variantBusy.value) {
                val q = list.lastOrNull { it.role == "user" }?.text.orEmpty()
                if (q.isBlank()) Toast.makeText(ctx, "Сначала напишите задачу", Toast.LENGTH_SHORT).show()
                else host.currentPage { p -> AiAssistant.askVariants(ctx, q, AiAssistant.contextOf(p), host) }
            }
        }

        // файлы чата (v4.8.0)
        val docsMap by AiFiles.docs
        val processing by AiFiles.processing
        val chatDocs = chat?.let { docsMap[it.id] } ?: emptyList()
        if (chatDocs.isNotEmpty() || processing.isNotBlank()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (processing.isNotBlank()) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Omni.Accent); Text(processing, color = Omni.TextDim, fontSize = 11.sp, maxLines = 1) }
                for (d in chatDocs) {
                    val icon = when (d.kind) { "image" -> "🖼"; "audio" -> "🎵"; "video" -> "🎬"; "pdf" -> "📄"; "archive" -> "🗜"; "other" -> "📦"; else -> "📝" }
                    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Omni.SurfaceHigh).border(0.5.dp, Omni.Outline, RoundedCornerShape(12.dp)).padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.clickable { Toast.makeText(ctx, "${d.name}: ${d.summary}", Toast.LENGTH_LONG).show() }) {
                            Text("$icon ${d.name}", color = Omni.Text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(160.dp))
                            Text(d.summary, color = Omni.TextFaint, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(160.dp))
                        }
                        OmniIconButton(OmniIcons.Close, "Убрать", onClick = { chat?.let { AiFiles.remove(ctx, it.id, d) } }, tint = Omni.TextFaint, size = 28.dp, iconSize = 14.dp)
                    }
                }
            }
        }

        // ввод
        var input by remember { mutableStateOf("") }
        var usePage by remember { mutableStateOf(true) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OmniIconButton(OmniIcons.Add, "Прикрепить файлы", onClick = {
                val c = chat ?: return@OmniIconButton
                host.pickFiles { uris ->
                    AiFiles.attach(ctx, c.id, uris) { added ->
                        Toast.makeText(ctx, "Добавлено: " + added.joinToString(", ") { it.name }, Toast.LENGTH_SHORT).show()
                        VpnLog.i(AiAssistant.TAG, "к чату «${c.title}» прикреплено ${added.size} файл(ов)")
                    }
                }
            }, tint = if (chatDocs.isEmpty()) Omni.TextDim else Omni.Accent, size = 40.dp, iconSize = 22.dp)
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
                if (input.isEmpty()) Text(if (chatDocs.isNotEmpty()) "Спросите о файлах…" else if (agent.hasTools) "Задача агенту…" else "Вопрос ассистенту…", color = Omni.TextFaint, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            Text("Передавать текст страницы вместе с вопросом", color = Omni.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f).clickable { usePage = !usePage })
            TextButton(onClick = { AiAssistant.stop(); AiAssistant.clearCurrent(ctx) }, enabled = list.isNotEmpty()) { Text("Очистить", color = Omni.TextFaint, fontSize = 11.sp) }
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
private fun AiBubble(m: AiAssistant.Msg, isLast: Boolean, busy: Boolean, onRetry: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val text by m.textState
    val meta by m.metaState
    val reasoning by m.reasonState
    var showReason by remember(m) { mutableStateOf(false) }
    val user = m.role == "user"
    if (m.kind == "tool") {
        Text(text, color = Omni.TextFaint, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
        return
    }
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
                    Text(if (isLast && busy) "думает…" else "—", color = Omni.TextDim, fontSize = 13.sp)
                }
            } else SelectionContainer { Text(text, color = Omni.Text, fontSize = 14.sp, lineHeight = 20.sp) }
        }
        if (!user && text.isNotBlank() && !(isLast && busy)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ai", text))
                    Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
                }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("Копировать", color = Omni.TextFaint, fontSize = 11.sp)
                }
                // v4.72.0: повтор последнего вопроса и размышления модели
                if (onRetry != null) {
                    TextButton(onClick = onRetry, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text("Повторить", color = Omni.TextFaint, fontSize = 11.sp)
                    }
                }
                if (reasoning.isNotBlank()) {
                    TextButton(onClick = { showReason = !showReason }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text(if (showReason) "🧠 скрыть размышления" else "🧠 размышления", color = Omni.TextFaint, fontSize = 11.sp)
                    }
                }
                if (meta.isNotBlank()) {
                    Text(meta, color = Omni.TextFaint, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (showReason && reasoning.isNotBlank()) {
                Box(
                    Modifier.fillMaxWidth(0.92f).clip(RoundedCornerShape(10.dp))
                        .background(Omni.Surface).border(0.5.dp, Omni.Outline, RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) { Text(reasoning, color = Omni.TextDim, fontSize = 11.sp, lineHeight = 15.sp) }
            }
        }
    }
}

// ---------------------------------------------------------------------- чаты
@Composable
private fun AiChatsList(onPick: () -> Unit) {
    val ctx = LocalContext.current
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
    val cur by AiAssistant.current
    Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
        SheetItem(OmniIcons.Add, "Новый чат", subtitle = "Текущий агент: ${AiTools.agent(cur?.agent ?: "chat").title}", tint = Omni.Accent, onClick = { AiAssistant.newChat(ctx); AiAssistant.save(ctx); onPick() })
        SheetDivider()
        for (c in AiAssistant.chats.toList()) {
            val title by c.titleState
            val a = AiTools.agent(c.agent)
            SheetItem(
                OmniIcons.History, "${a.emoji} $title",
                subtitle = "${fmt.format(Date(c.updated))} · ${c.messages.count { it.kind == "text" }} сообщ." + (c.messages.lastOrNull { it.role == "assistant" && it.kind == "text" }?.text?.let { " · " + it.replace('\n', ' ').take(60) } ?: ""),
                titleColor = if (cur === c) Omni.Accent else Omni.Text,
                onClick = { AiAssistant.select(ctx, c); onPick() },
                trailing = { OmniIconButton(OmniIcons.Delete, "Удалить", onClick = { AiAssistant.deleteChat(ctx, c) }, tint = Omni.TextFaint, size = 36.dp, iconSize = 18.dp) }
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------- агенты и MCP
@Composable
private fun AiAgentsView(onOpenUrl: (String) -> Unit) {
    val ctx = LocalContext.current
    val servers by McpClient.servers
    val tools by McpClient.tools
    val st by McpClient.status
    var mcpOn by remember { mutableStateOf(AiAssistant.mcpEnabled(ctx)) }
    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
        SheetSectionLabel("Агенты")
        for (a in AiTools.agents) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text("${a.emoji} ${a.title}", color = Omni.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(a.hint + (if (a.hasTools) " · инструменты: " + a.tools.joinToString(", ") else ""), color = Omni.TextDim, fontSize = 11.sp)
            }
        }
        SheetSectionLabel("MCP-серверы")
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Давать агентам инструменты MCP", color = Omni.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked = mcpOn, onCheckedChange = { mcpOn = it; AiAssistant.setMcpEnabled(ctx, it); if (it) McpClient.refreshAll(ctx) }, colors = SwitchDefaults.colors(checkedTrackColor = Omni.Accent))
        }
        Text("Streamable HTTP (JSON-RPC). Подключено инструментов: ${tools.size}. Проверено: DeepWiki и Context7.", color = Omni.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 20.dp))
        for (s in servers) {
            val res = st[s.url]
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(Omni.SurfaceHigh).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, color = Omni.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(s.url, color = Omni.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Switch(checked = s.enabled, onCheckedChange = { McpClient.setEnabled(ctx, s, it) }, colors = SwitchDefaults.colors(checkedTrackColor = Omni.Accent))
                }
                if (!res.isNullOrBlank()) Text(res, color = if (res.startsWith("✓")) Omni.Success else if (res.startsWith("⏳")) Omni.TextDim else Omni.Danger, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                Row {
                    TextButton(onClick = { McpClient.refresh(ctx, s) }) { Text("Проверить", color = Omni.Accent, fontSize = 12.sp) }
                    TextButton(onClick = { McpClient.remove(ctx, s) }) { Text("Удалить", color = Omni.TextFaint, fontSize = 12.sp) }
                }
            }
        }
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var token by remember { mutableStateOf("") }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(Omni.SurfaceHigh).padding(12.dp)) {
            Text("Добавить MCP-сервер", color = Omni.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            AiField(name, "название", KeyboardType.Text) { name = it }
            AiField(url, "https://host/mcp", KeyboardType.Uri) { url = it }
            AiField(token, "токен (необязательно)", KeyboardType.Password) { token = it }
            Row {
                TextButton(onClick = {
                    if (url.startsWith("http")) { McpClient.add(ctx, McpClient.Server(name.ifBlank { url.substringAfter("//").substringBefore('/') }, url.trim(), token.trim(), true).also { McpClient.refresh(ctx, it) }); name = ""; url = ""; token = "" }
                }, enabled = url.startsWith("http")) { Text("Добавить и проверить", color = Omni.Accent, fontSize = 12.sp) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onOpenUrl("https://github.com/modelcontextprotocol/servers#-remote-servers") }) { Text("Каталог серверов ↗", color = Omni.TextDim, fontSize = 12.sp) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------- провайдеры
@Composable
private fun AiSettings(onOpenUrl: (String) -> Unit) {
    val ctx = LocalContext.current
    val results by AiAssistant.testResults
    val catalog by AiCatalog.models
    val refreshing by AiCatalog.refreshing
    val last by AiCatalog.lastRefresh
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Авто: провайдеры с ключом → Pollinations → LLM7 → OpenCode.", color = Omni.TextDim, fontSize = 12.sp)
                // v4.69.0: человек должен видеть, каким каналом идут запросы и почему «лимит на этот IP»
                Text(
                    "Запросы ИИ идут: " + NetDirect.channelLabel() +
                        " · лимит бесплатных моделей считается по внешнему IP (он общий у оператора и на выходе VPN)",
                    color = if (NetDirect.appChannelActive()) Omni.Success else Omni.Warning,
                    fontSize = 11.sp
                )
                if (!NetDirect.appChannelActive()) {
                    Text(
                        "Включите VPN браузера (щит сверху) и смените страну — внешний IP изменится, и лимит OpenCode/LLM7 сбросится.",
                        color = Omni.TextFaint, fontSize = 10.sp
                    )
                }
                Text("Каталог: ${catalog.values.sumOf { it.size }} моделей · " + (if (last > 0) "обновлён ${fmt.format(Date(last))}" else "не обновлялся") + " · ● без ключа ○ по ключу", color = Omni.TextFaint, fontSize = 11.sp)
            }
            TextButton(onClick = { AiCatalog.refreshAll(ctx) }, enabled = !refreshing) { Text(if (refreshing) "…" else "Обновить", color = Omni.Accent, fontSize = 12.sp) }
            TextButton(onClick = { AiAssistant.testAll(ctx) }) { Text("Тест всех", color = Omni.Accent, fontSize = 12.sp) }
        }
        for (p in AiAssistant.providers) {
            var key by remember(p.id) { mutableStateOf(AiAssistant.key(ctx, p.id)) }
            var model by remember(p.id) { mutableStateOf(AiAssistant.model(ctx, p.id)) }
            var url by remember(p.id) { mutableStateOf(AiAssistant.customUrl(ctx)) }
            var menu by remember(p.id) { mutableStateOf(false) }
            var loading by remember(p.id) { mutableStateOf(false) }
            val entries = catalog[p.id] ?: emptyList()
            val res = results[p.id]
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(Omni.SurfaceHigh).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.title, color = Omni.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (entries.isNotEmpty()) Text("${entries.count { it.anon }}● ${entries.count { !it.anon }}○  ", color = Omni.TextFaint, fontSize = 11.sp)
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
                            if (entries.isNotEmpty()) menu = true
                            else { loading = true; AiCatalog.refreshProvider(ctx, p) { l, e -> loading = false; if (l.isNotEmpty()) menu = true else Toast.makeText(ctx, e ?: "список пуст", Toast.LENGTH_SHORT).show() } }
                        }, enabled = !loading && p.id != "custom") { Text(if (loading) "…" else "Список ▾", color = Omni.Accent, fontSize = 12.sp) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(Omni.SurfaceHighest).heightIn(max = 340.dp)) {
                            DropdownMenuItem(text = { Text("↻ обновить список с сайта", color = Omni.TextDim, fontSize = 12.sp) }, onClick = {
                                menu = false; loading = true
                                AiCatalog.refreshProvider(ctx, p) { l, e -> loading = false; if (l.isNotEmpty()) menu = true else Toast.makeText(ctx, e ?: "список пуст", Toast.LENGTH_SHORT).show() }
                            })
                            for (e in entries) DropdownMenuItem(
                                text = { Text((if (e.anon) "● " else "○ ") + e.id, color = if (e.id == model) Omni.Accent else Omni.Text, fontSize = 13.sp) },
                                onClick = { model = e.id; AiAssistant.setModel(ctx, p.id, e.id); menu = false }
                            )
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

/* ============================================================================
 * v4.72.0: WORKSPACE АГЕНТА
 * Папка Download/OmniBrowser/agent/workspace — всё, что создал агент. Здесь можно
 * создать папку, импортировать файл с устройства, скачать папку в ZIP, удалить,
 * выбрать «работать здесь» (агент видит этот путь и пишет туда напрямую).
 * ========================================================================== */

@Composable
private fun AiWorkspaceView(host: AiHost) {
    val ctx = LocalContext.current
    val ws = com.winwasm64.torrent.AgentWorkspace
    var current by remember { mutableStateOf(ws.current(ctx)) }
    var refresh by remember { mutableIntStateOf(0) }
    var newName by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    val entries = remember(current, refresh) { ws.list(ctx) }

    fun path(): String = ws.displayPath(ctx)

    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("📂 " + path(), color = Omni.TextDim, fontSize = 11.sp, maxLines = 2, modifier = Modifier.weight(1f))
            if (current.isNotBlank()) {
                TextButton(onClick = {
                    current = current.substringBeforeLast('/', "")
                    ws.setCurrent(ctx, current)
                    refresh++
                }) { Text("⬆ Вверх", color = Omni.Accent, fontSize = 11.sp) }
            }
        }
        if (status.isNotBlank()) Text(status, color = Omni.Warning, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp))

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { picking = !picking }) { Text("＋ Папка", color = Omni.Accent, fontSize = 12.sp) }
            TextButton(onClick = {
                // импорт файлов с устройства в текущую папку
                host.pickFiles { uris ->
                    Thread({
                        val names = ArrayList<String>()
                        for (u in uris) ws.import(ctx, u)?.let { names.add(it.name) }
                        status = if (names.isEmpty()) "не удалось импортировать" else "добавлено: " + names.joinToString(", ")
                        refresh++
                    }, "ws-import").also { it.isDaemon = true; it.start() }
                }
            }) { Text("📥 Импорт", color = Omni.Accent, fontSize = 12.sp) }
            TextButton(onClick = {
                Thread({
                    val zip = ws.zip(ctx)
                    if (zip == null) {
                        status = "не удалось собрать ZIP"
                    } else {
                        val dst = java.io.File(com.winwasm64.torrent.AppDownloads.folder(), zip.name)
                        try {
                            zip.copyTo(dst, overwrite = true)
                            status = "ZIP: Загрузки/${com.winwasm64.torrent.AppDownloads.SUBDIR}/${zip.name} (${dst.length() / 1024} КБ)"
                        } catch (t: Throwable) {
                            status = "ZIP остался в кеше: ${zip.absolutePath}"
                        }
                    }
                }, "ws-zip").also { it.isDaemon = true; it.start() }
            }) { Text("⬇ ZIP", color = Omni.Accent, fontSize = 12.sp) }
            TextButton(onClick = { refresh++ }) { Text("⟳", color = Omni.Accent, fontSize = 12.sp) }
            TextButton(onClick = {
                ws.setCurrent(ctx, "")
                current = ""
                refresh++
                status = "агент теперь работает в корне workspace"
            }) { Text("🏠 Корень", color = Omni.Accent, fontSize = 12.sp) }
        }

        if (picking) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).heightIn(min = 38.dp).clip(RoundedCornerShape(10.dp)).background(Omni.SurfaceHigh).padding(horizontal = 10.dp, vertical = 8.dp)) {
                    BasicTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Omni.Text, fontSize = 13.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Omni.Accent),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (newName.isEmpty()) Text("имя новой папки", color = Omni.TextFaint, fontSize = 13.sp)
                }
                TextButton(onClick = {
                    val name = newName.trim()
                    if (name.isNotBlank()) {
                        val rel = if (current.isBlank()) name else "$current/$name"
                        status = if (ws.mkdir(ctx, rel) != null) "папка создана: $rel" else "не удалось создать папку"
                        newName = ""
                        refresh++
                    }
                }) { Text("Создать", color = Omni.Accent, fontSize = 12.sp) }
            }
        }

        if (entries.isEmpty()) {
            Text(
                "Папка пуста. Агент складывает сюда всё, что создаёт: файлы, папки, заметки. " +
                    "Можно создать папку самому и сказать в чате «работай в папке …».",
                color = Omni.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        for (e in entries) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Omni.SurfaceHigh).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).clickable {
                    if (e.isDir) {
                        current = e.rel
                        ws.setCurrent(ctx, e.rel)
                        refresh++
                    } else {
                        Thread({
                            val res = ws.readText(ctx, e.rel, 3000)
                            status = if (res == null) "файл не читается" else res.take(600)
                        }, "ws-read").also { it.isDaemon = true; it.start() }
                    }
                }) {
                    Text((if (e.isDir) "📁 " else "📄 ") + e.name, color = Omni.Text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (e.isDir) "папка" else "${e.size / 1024} КБ", color = Omni.TextFaint, fontSize = 10.sp)
                }
                OmniIconButton(OmniIcons.Delete, "Удалить", onClick = {
                    if (ws.delete(ctx, e.rel)) { status = "удалено: ${e.name}"; refresh++ } else status = "не удалось удалить ${e.name}"
                }, tint = Omni.Danger, size = 28.dp, iconSize = 15.dp)
            }
        }
        Text(
            "Агент видит этот workspace инструментами ws_list / ws_read / ws_write / ws_mkdir. " +
                "Токен GitHub, если положить его в Download/OmniBrowser/secrets/github.token, даёт инструменты gh_read / gh_write / gh_actions.",
            color = Omni.TextFaint, fontSize = 10.sp, modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun AiNotesView() {
    val ctx = LocalContext.current
    val ws = com.winwasm64.torrent.AgentWorkspace
    var text by remember { mutableStateOf(ws.notes(ctx)) }
    var saved by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
        Text(
            "Заметки по проекту: агент дописывает их сам (инструмент note_append) и читает перед работой.",
            color = Omni.TextDim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 200.dp)
            .clip(RoundedCornerShape(12.dp)).background(Omni.SurfaceHigh).padding(12.dp)) {
            BasicTextField(
                value = text,
                onValueChange = { text = it; saved = false },
                textStyle = androidx.compose.ui.text.TextStyle(color = Omni.Text, fontSize = 13.sp, lineHeight = 18.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Omni.Accent),
                modifier = Modifier.fillMaxWidth()
            )
            if (text.isEmpty()) Text("# Заметки проекта\n\n- ", color = Omni.TextFaint, fontSize = 13.sp)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                saved = ws.saveNotes(ctx, text)
                Toast.makeText(ctx, if (saved) "Заметки сохранены" else "Не удалось сохранить", Toast.LENGTH_SHORT).show()
            }) { Text(if (saved) "✓ Сохранено" else "Сохранить", color = Omni.Accent, fontSize = 12.sp) }
            TextButton(onClick = { text = ws.notes(ctx) }) { Text("Отменить", color = Omni.TextFaint, fontSize = 12.sp) }
        }
    }
}
