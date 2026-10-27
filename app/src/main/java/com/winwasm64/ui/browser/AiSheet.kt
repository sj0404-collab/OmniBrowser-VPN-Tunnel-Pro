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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
                    else -> SheetTitle(
                        (chat?.title ?: "ИИ-ассистент").ifBlank { "ИИ-ассистент" },
                        subtitle = status.ifBlank { "${agent.emoji} ${agent.title} · " + (if (selected == "auto") "Авто: " + AiAssistant.autoOrder(ctx).joinToString(" → ") { it.title } else AiAssistant.providers.first { it.id == selected }.title + " · " + AiAssistant.model(ctx, selected)) }
                    )
                }
            }
            if (view == "chat") {
                OmniIconButton(OmniIcons.Add, "Новый чат", onClick = { AiAssistant.stop(); AiAssistant.newChat(ctx); AiAssistant.save(ctx) })
                OmniIconButton(OmniIcons.History, "Чаты", onClick = { view = "chats" })
                OmniIconButton(OmniIcons.Apps, "Агенты и MCP", onClick = { view = "agents" })
                OmniIconButton(OmniIcons.Tune, "Провайдеры", onClick = { view = "providers" })
            }
        }
        when (view) {
            "chats" -> { AiChatsList(onPick = { view = "chat" }); return@Column }
            "providers" -> { AiSettings(onOpenUrl = { onDismiss(); onOpenUrl(it) }); return@Column }
            "agents" -> { AiAgentsView(onOpenUrl = { onDismiss(); onOpenUrl(it) }); return@Column }
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
                    Text("Без ключей работают Pollinations и LLM7 (turbo). Бесплатный ключ LLM7/Groq/OpenRouter/Gemini открывает полные модели — см. ⚙.", color = Omni.TextFaint, fontSize = 12.sp)
                }
            } else LazyColumn(state = lazy, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                itemsIndexed(list, key = { i, _ -> i }) { _, m -> AiBubble(m, isLast = m === list.lastOrNull(), busy = busy) }
            }
        }

        // быстрые действия
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AiChip("Кратко о странице", false, enabled = !busy) { send("Кратко перескажи, о чём эта страница: 3–6 пунктов с главным.", true) }
            AiChip("Переведи на русский", false, enabled = !busy) { send("Переведи текст страницы (или выделенный фрагмент, если он есть) на русский язык. Сохраняй структуру, ничего не добавляй.", true) }
            AiChip("Объясни выделенное", false, enabled = !busy) { send("Объясни простыми словами выделенный фрагмент (если выделения нет — ключевую мысль страницы). Если это термин или код — поясни, что он делает.", true) }
            AiChip("Главные факты", false, enabled = !busy) { send("Выпиши из страницы ключевые факты, цифры, даты и имена списком. Только то, что есть в тексте.", true) }
            if (agent.hasTools) AiChip("Найди похожее", false, enabled = !busy) { send("Найди в интернете 3–5 источников по теме этой страницы и кратко сравни их с ней.", true) }
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
                if (input.isEmpty()) Text(if (agent.hasTools) "Задача агенту…" else "Вопрос ассистенту…", color = Omni.TextFaint, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun AiBubble(m: AiAssistant.Msg, isLast: Boolean, busy: Boolean) {
    val ctx = LocalContext.current
    val text by m.textState
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
            TextButton(onClick = {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ai", text))
                Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
            }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text("Копировать", color = Omni.TextFaint, fontSize = 11.sp)
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
