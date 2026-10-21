package com.winwasm64.ui.browser

import android.graphics.BitmapFactory
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winwasm64.extensions.CatalogExtension
import com.winwasm64.extensions.ChromeWebStore
import com.winwasm64.extensions.ExtensionRuntime
import com.winwasm64.extensions.ExtensionStore
import com.winwasm64.extensions.InstalledExtension
import com.winwasm64.ui.theme.Omni

private fun loadExtensionIcon(ext: InstalledExtension): ImageBitmap? = try {
    val path = ext.manifest.iconPath
    val file = if (path != null) ExtensionStore.resolveFile(ext, path) else null
    if (file != null && file.isFile && !file.name.endsWith(".svg", true)) {
        BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
    } else {
        null
    }
} catch (_: Throwable) {
    null
}

@Composable
private fun ExtensionAvatar(icon: ImageBitmap?, name: String) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Omni.SurfaceHighest),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(28.dp))
        } else {
            Text(name.firstOrNull()?.uppercaseChar()?.toString() ?: "?", color = Omni.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, color: Color = Omni.Accent, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun InstalledExtensionRow(
    ext: InstalledExtension,
    onOpenPopup: (String) -> Unit,
    onOpenOptions: (String) -> Unit
) {
    val icon = remember(ext.id, ext.installedAt) { loadExtensionIcon(ext) }
    var confirmDelete by remember(ext.id) { mutableStateOf(false) }
    val m = ext.manifest
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Omni.SurfaceHigh)
            .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ExtensionAvatar(icon, m.name)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(m.name, fontSize = 15.sp, color = Omni.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                val details = buildList {
                    add("v${m.version}")
                    add("MV${m.manifestVersion}")
                    if (m.dnrRulesets.isNotEmpty()) add("сетевые правила")
                    if (m.contentScripts.isNotEmpty()) add("скрипты страниц")
                    if (m.hasBackground) add("фон")
                }.joinToString(" · ")
                Text(details, fontSize = 12.sp, color = Omni.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OmniSwitch(ext.enabled, { ExtensionRuntime.setEnabled(ext.id, it) })
        }
        Row(Modifier.fillMaxWidth().padding(start = 44.dp), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
            if (ext.enabled) {
                if (m.popupPath != null) {
                    SmallAction(OmniIcons.Play, "Открыть") { onOpenPopup(ext.id) }
                } else {
                    SmallAction(OmniIcons.Play, "Нажать") {
                        if (ExtensionRuntime.actionClicked(ext.id)) onOpenPopup(ext.id)
                    }
                }
            }
            if (m.optionsPath != null) {
                SmallAction(OmniIcons.Settings, "Настройки", Omni.TextDim) { onOpenOptions(ext.id) }
            }
            Spacer(Modifier.weight(1f))
            if (confirmDelete) {
                SmallAction(OmniIcons.Delete, "Точно удалить", Omni.Danger) { ExtensionRuntime.uninstall(ext.id) }
            } else {
                SmallAction(OmniIcons.Delete, "Удалить", Omni.TextDim) { confirmDelete = true }
            }
        }
    }
}

@Composable
private fun CatalogRow(item: CatalogExtension, installed: Boolean, busyText: String?, onInstall: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(Omni.SurfaceHighest),
            contentAlignment = Alignment.Center
        ) {
            Text(item.name.first().uppercaseChar().toString(), color = Omni.Text, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(item.name, fontSize = 14.sp, color = Omni.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.summary, fontSize = 12.sp, color = Omni.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.note, fontSize = 11.sp, color = Omni.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            busyText != null -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Omni.Accent)
            installed -> Text("Установлено", fontSize = 12.sp, color = Omni.Success)
            else -> Button(
                onClick = onInstall,
                colors = ButtonDefaults.buttonColors(containerColor = Omni.AccentContainer, contentColor = Omni.Accent),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp)
            ) { Text("Добавить", fontSize = 13.sp) }
        }
    }
}

/**
 * Содержимое шторки «Расширения». Всё настоящее: .crx скачивается с серверов Google
 * (clients2.google.com), распаковывается и исполняется движком ExtensionRuntime.
 */
@Composable
fun ExtensionsSheetContent(
    onOpenPopup: (String) -> Unit,
    onOpenOptions: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onPickFile: () -> Unit
) {
    val version = ExtensionRuntime.stateVersion
    val installed = remember(version) { ExtensionStore.installed }
    val busy = ExtensionRuntime.busy
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    fun install(source: String) {
        if (ChromeWebStore.extractId(source) == null) {
            message = true to "Вставьте ссылку на страницу расширения в Chrome Web Store или его ID (32 латинские буквы a–p)."
            return
        }
        message = null
        ExtensionRuntime.installFromStore(source) { err, ext ->
            message = if (err != null) true to "Не удалось установить: $err" else false to "Установлено: ${ext?.manifest?.name ?: ""}"
            if (err == null) input = ""
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        SheetTitle("Расширения", "Настоящие расширения из Chrome Web Store — пакет скачивается с серверов Google и запускается внутри браузера.")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(Omni.SurfaceHighest).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Omni.Text, fontSize = 14.sp),
                    cursorBrush = SolidColor(Omni.Accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { install(input) }),
                    modifier = Modifier.fillMaxWidth()
                )
                if (input.isEmpty()) Text("Ссылка или ID расширения", color = Omni.TextFaint, fontSize = 14.sp)
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { install(input) },
                enabled = input.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Omni.Accent, contentColor = Color.White),
                modifier = Modifier.height(44.dp)
            ) { Text("Установить") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            SmallAction(OmniIcons.Globe, "Открыть Chrome Web Store") { onOpenUrl("https://chromewebstore.google.com/category/extensions") }
            SmallAction(OmniIcons.Download, "Из файла .crx / .zip", Omni.TextDim) { onPickFile() }
        }
        message?.let { (isError, text) ->
            Text(text, color = if (isError) Omni.Danger else Omni.Success, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        busy.entries.filter { e -> ExtensionStore.CATALOG.none { it.id == e.key } }.forEach { (_, state) ->
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Omni.Accent)
                Spacer(Modifier.width(10.dp))
                Text(state, color = Omni.TextDim, fontSize = 13.sp)
            }
        }

        SheetSectionLabel("Установленные · ${installed.size}")
        if (installed.isEmpty()) {
            Text(
                "Пока пусто. Добавьте расширение из списка ниже или откройте Chrome Web Store — на странице расширения появится кнопка «Установить».",
                color = Omni.TextDim,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }
        installed.forEach { ext -> InstalledExtensionRow(ext, onOpenPopup, onOpenOptions) }
        if (ExtensionRuntime.dnrRuleCount > 0 || ExtensionRuntime.blockedRequests.get() > 0) {
            Text(
                "Сетевых правил: ${ExtensionRuntime.dnrRuleCount} · заблокировано запросов: ${ExtensionRuntime.blockedRequests.get()}",
                color = Omni.TextFaint,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
        }

        SheetSectionLabel("Популярные")
        ExtensionStore.CATALOG.forEach { item ->
            CatalogRow(item, installed.any { it.id == item.id }, busy[item.id]) { install(item.id) }
        }

        HorizontalDivider(thickness = 0.5.dp, color = Omni.Outline, modifier = Modifier.padding(top = 10.dp))
        Text(
            "Ограничения Android WebView: нет блокирующего webRequest из MV2 (используйте MV3-версии блокировщиков), нет native messaging и панелей DevTools; chrome.scripting работает в главном фрейме." +
                if (!ExtensionRuntime.messagingSupported) " Обновите Android System WebView: текущая версия не поддерживает обмен сообщениями с расширениями." else "",
            color = Omni.TextFaint,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
    }
}

/** Окно расширения (popup) или его страница настроек — отдельный WebView в диалоге. */
@Composable
fun ExtensionPageDialog(extId: String, options: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val ext = remember(extId) { ExtensionStore.get(extId) }
    val host = remember(extId, options) {
        if (options) ExtensionRuntime.createOptionsHost(context, extId) else ExtensionRuntime.createPopupHost(context, extId)
    }
    DisposableEffect(host) {
        onDispose {
            host?.let {
                (it.webView.parent as? ViewGroup)?.removeView(it.webView)
                ExtensionRuntime.releaseHost(it)
            }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Omni.Surface,
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(if (options) 0.9f else 0.72f)
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (ext?.manifest?.name ?: extId) + if (options) " — настройки" else "",
                        color = Omni.Text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    OmniIconButton(OmniIcons.Close, "Закрыть", onDismiss)
                }
                HorizontalDivider(thickness = 0.5.dp, color = Omni.Outline)
                if (host == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (options) "У расширения нет страницы настроек" else "У расширения нет всплывающего окна",
                            color = Omni.TextDim
                        )
                    }
                } else {
                    AndroidView(
                        factory = {
                            (host.webView.parent as? ViewGroup)?.removeView(host.webView)
                            host.webView
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
