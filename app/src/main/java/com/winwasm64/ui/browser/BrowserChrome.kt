package com.winwasm64.ui.browser

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winwasm64.ui.theme.Omni
import kotlinx.coroutines.delay

/** Адрес для отображения в неактивной строке: домен без www (как в Chrome). */
fun omniDisplayUrl(url: String): String {
    if (url.isBlank() || url == "about:blank") return ""
    return try {
        val u = Uri.parse(url)
        val host = u.host
        if (host.isNullOrBlank()) url.take(120) else host.removePrefix("www.")
    } catch (_: Exception) {
        url.take(120)
    }
}

@Composable
fun OmniIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Omni.Text,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    badge: String? = null
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = if (enabled) tint else Omni.TextFaint, modifier = Modifier.size(iconSize))
        if (!badge.isNullOrEmpty()) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 1.dp)
                    .background(Omni.Accent, RoundedCornerShape(6.dp))
                    .padding(horizontal = 4.dp)
            ) {
                Text(badge.take(4), fontSize = 9.sp, color = Color.White, maxLines = 1, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Адресная строка: без фокуса — домен и замок, при фокусе — полный адрес, выделенный целиком. */
@Composable
fun OmniBox(
    url: String,
    isLoading: Boolean,
    isIncognito: Boolean,
    onSubmit: (String) -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue(url)) }
    LaunchedEffect(url, focused) {
        if (!focused) field = TextFieldValue(url)
    }
    LaunchedEffect(focused) {
        if (focused) {
            // Касание ставит курсор после получения фокуса — выделяем адрес чуть позже.
            delay(60)
            field = field.copy(selection = TextRange(0, field.text.length))
        }
    }
    val secure = url.startsWith("https://")
    val display = omniDisplayUrl(url)
    fun submit() {
        val text = field.text.trim()
        if (text.isNotEmpty()) onSubmit(text)
        focusManager.clearFocus()
    }
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (isIncognito) Color(0xFF221F2E) else Omni.SurfaceHighest)
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            when {
                isIncognito && !focused -> OmniIcons.Incognito
                focused || display.isEmpty() -> OmniIcons.Search
                secure -> OmniIcons.Lock
                else -> OmniIcons.Globe
            },
            contentDescription = null,
            tint = if (!focused && !secure && display.isNotEmpty()) Omni.Warning else Omni.TextDim,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = if (focused) field else TextFieldValue(display),
                onValueChange = { if (focused) field = it },
                singleLine = true,
                textStyle = TextStyle(color = Omni.Text, fontSize = 15.sp),
                cursorBrush = SolidColor(Omni.Accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }, onSearch = { submit() }, onDone = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { st ->
                        if (st.isFocused && !focused) field = TextFieldValue(url, TextRange(0, url.length))
                        focused = st.isFocused
                    }
            )
            if ((focused && field.text.isEmpty()) || (!focused && display.isEmpty())) {
                Text("Поиск или адрес сайта", color = Omni.TextFaint, fontSize = 15.sp, maxLines = 1)
            }
        }
        if (focused) {
            if (field.text.isNotEmpty()) {
                OmniIconButton(OmniIcons.Close, "Очистить", { field = TextFieldValue("") }, size = 36.dp, iconSize = 18.dp, tint = Omni.TextDim)
            }
        } else if (display.isNotEmpty()) {
            OmniIconButton(
                if (isLoading) OmniIcons.Close else OmniIcons.Refresh,
                if (isLoading) "Остановить" else "Обновить",
                { if (isLoading) onStop() else onReload() },
                size = 36.dp, iconSize = 18.dp, tint = Omni.TextDim
            )
        }
    }
}

@Composable
fun OmniTopBar(
    url: String,
    isLoading: Boolean,
    progress: Int,
    isIncognito: Boolean,
    vpnOn: Boolean,
    tabsCount: Int,
    extensionsBadge: String?,
    onSubmit: (String) -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onVpnClick: () -> Unit,
    onTabsClick: () -> Unit,
    onExtensionsClick: () -> Unit,
    onMenuClick: () -> Unit
) {
    Column(Modifier.fillMaxWidth().background(Omni.Surface)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OmniIconButton(OmniIcons.Shield, if (vpnOn) "VPN включён" else "VPN выключен", onVpnClick, tint = if (vpnOn) Omni.Success else Omni.TextFaint)
            OmniBox(url, isLoading, isIncognito, onSubmit, onReload, onStop, Modifier.weight(1f))
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onTabsClick),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(21.dp).border(1.6.dp, Omni.Text, RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (tabsCount > 99) "∞" else tabsCount.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Omni.Text)
                }
            }
            OmniIconButton(OmniIcons.Extension, "Расширения", onExtensionsClick, badge = extensionsBadge)
            OmniIconButton(OmniIcons.More, "Меню", onMenuClick)
        }
        if (isLoading) {
            LinearProgressIndicator(
                progress = { (progress / 100f).coerceIn(0.05f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Omni.Accent,
                trackColor = Omni.Surface
            )
        } else {
            HorizontalDivider(thickness = 0.5.dp, color = Omni.Outline)
        }
    }
}

/** Поиск по странице: поле, счётчик «3/17», вверх/вниз, закрыть. */
@Composable
fun FindInPageBar(
    query: String,
    onQueryChange: (String) -> Unit,
    current: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {
        }
    }
    Row(
        Modifier.fillMaxWidth().background(Omni.Surface).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp)).background(Omni.SurfaceHighest).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(color = Omni.Text, fontSize = 15.sp),
                    cursorBrush = SolidColor(Omni.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onNext() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                )
                if (query.isEmpty()) Text("Найти на странице", color = Omni.TextFaint, fontSize = 15.sp)
            }
            if (query.isNotEmpty()) {
                Text(
                    if (total == 0) "0/0" else "${current + 1}/$total",
                    color = if (total == 0) Omni.Danger else Omni.TextDim,
                    fontSize = 13.sp
                )
            }
        }
        OmniIconButton(OmniIcons.Up, "Предыдущее", onPrev, enabled = total > 0)
        OmniIconButton(OmniIcons.Down, "Следующее", onNext, enabled = total > 0)
        OmniIconButton(OmniIcons.Close, "Закрыть поиск", onClose)
    }
}

data class OmniNavItem(val label: String, val icon: ImageVector)

@Composable
fun OmniBottomBar(selected: Int, items: List<OmniNavItem>, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Omni.Surface)) {
        HorizontalDivider(thickness = 0.5.dp, color = Omni.Outline)
        Row(Modifier.fillMaxWidth().height(58.dp)) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selected
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isSelected) Omni.AccentContainer else Color.Transparent)
                            .padding(horizontal = 18.dp, vertical = 3.dp)
                    ) {
                        Icon(item.icon, item.label, tint = if (isSelected) Omni.Accent else Omni.TextDim, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.label,
                        fontSize = 11.sp,
                        color = if (isSelected) Omni.Text else Omni.TextDim,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** Единая шторка снизу для всех меню и панелей. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Omni.Surface,
        contentColor = Omni.Text,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Omni.Outline) }
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), content = content)
    }
}

@Composable
fun SheetTitle(text: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Omni.Text)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, fontSize = 12.sp, color = Omni.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SheetSectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        letterSpacing = 0.8.sp,
        fontWeight = FontWeight.SemiBold,
        color = Omni.TextFaint,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp)
    )
}

@Composable
fun SheetDivider() {
    HorizontalDivider(thickness = 0.5.dp, color = Omni.Outline, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
fun SheetItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color = Omni.TextDim,
    titleColor: Color = Omni.Text,
    enabled: Boolean = true,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (enabled) tint else Omni.TextFaint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = if (enabled) titleColor else Omni.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, fontSize = 12.sp, color = Omni.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun OmniSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Omni.Accent,
            checkedBorderColor = Omni.Accent,
            uncheckedThumbColor = Omni.TextDim,
            uncheckedTrackColor = Omni.SurfaceHighest,
            uncheckedBorderColor = Omni.Outline
        )
    )
}

@Composable
fun SheetSwitchItem(icon: ImageVector, title: String, subtitle: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SheetItem(icon = icon, title = title, subtitle = subtitle, tint = if (checked) Omni.Accent else Omni.TextDim, onClick = { onCheckedChange(!checked) }) {
        OmniSwitch(checked, onCheckedChange)
    }
}

/** Строка настройки без внешних отступов (для панелей внутри шторки). */
@Composable
fun SettingSwitchRow(title: String, subtitle: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onCheckedChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = Omni.Text)
            if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 12.sp, color = Omni.TextDim)
        }
        Spacer(Modifier.width(10.dp))
        OmniSwitch(checked, onCheckedChange)
    }
}

data class TabRowInfo(val title: String, val url: String, val incognito: Boolean, val loading: Boolean)

@Composable
fun TabsSheetContent(
    tabs: List<TabRowInfo>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onNewTab: () -> Unit,
    onNewIncognito: () -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Вкладки · ${tabs.size}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Omni.Text, modifier = Modifier.weight(1f))
        OmniIconButton(OmniIcons.Incognito, "Новая инкогнито-вкладка", onNewIncognito, tint = Omni.TextDim)
        OmniIconButton(OmniIcons.Add, "Новая вкладка", onNewTab, tint = Omni.Accent)
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
        itemsIndexed(tabs) { index, tab ->
            val active = index == activeIndex
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (active) Omni.AccentContainer else Omni.SurfaceHigh)
                    .clickable { onSelect(index) }
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Omni.SurfaceHighest),
                    contentAlignment = Alignment.Center
                ) {
                    if (tab.loading) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Omni.Accent)
                    } else if (tab.incognito) {
                        Icon(OmniIcons.Incognito, null, tint = Omni.TextDim, modifier = Modifier.size(18.dp))
                    } else {
                        val letter = omniDisplayUrl(tab.url).firstOrNull()?.uppercaseChar()?.toString() ?: "•"
                        Text(letter, color = Omni.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        tab.title.ifBlank { "Новая вкладка" },
                        fontSize = 14.sp,
                        color = Omni.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                    )
                    Text(omniDisplayUrl(tab.url).ifBlank { "about:blank" }, fontSize = 12.sp, color = Omni.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OmniIconButton(OmniIcons.Close, "Закрыть вкладку", { onClose(index) }, size = 36.dp, iconSize = 18.dp, tint = Omni.TextDim)
            }
        }
    }
}

/** Плашка на странице расширения в Chrome Web Store: «Установить в OmniBrowser». */
@Composable
fun StoreInstallBanner(installed: Boolean, busyText: String?, onInstall: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Omni.AccentContainer).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(OmniIcons.Extension, null, tint = Omni.Accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                busyText != null -> busyText
                installed -> "Это расширение уже установлено в OmniBrowser"
                else -> "Расширение Chrome — можно установить в OmniBrowser"
            },
            fontSize = 13.sp,
            color = Omni.Text,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        when {
            busyText != null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Omni.Accent)
            !installed -> Button(
                onClick = onInstall,
                colors = ButtonDefaults.buttonColors(containerColor = Omni.Accent, contentColor = Color.White),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier.height(34.dp)
            ) { Text("Установить", fontSize = 13.sp) }
        }
    }
}
