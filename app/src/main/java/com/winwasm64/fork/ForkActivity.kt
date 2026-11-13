package com.winwasm64.fork

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winwasm64.CrashCatcher
import com.winwasm64.ui.MediaPositions
import com.winwasm64.ui.theme.OmniTheme

/**
 * v4.86.0: ВТОРОЕ приложение в одном установочном пакете — RupoopFork.
 *
 * Оно собрано из исходников RupoopFork и лежит внутри нашего APK. Здесь — экран запуска:
 * поставить/обновить/открыть. После установки в системе появляется отдельное приложение
 * «RupoopFork» со своим ярлыком; данные у него свои.
 */
class ForkActivity : ComponentActivity() {
    override fun onStop() {
        super.onStop()
        try { MediaPositions.flush() } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MediaPositions.init(this)
        CrashCatcher.install(this)
        setContent { OmniTheme { ForkScreen() } }
    }
}

@Composable
private fun ForkScreen() {
    val ctx = LocalContext.current
    var installed by remember { mutableStateOf(RupoopFork.isInstalled(ctx)) }
    var version by remember { mutableStateOf(RupoopFork.installedVersion(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val bundled = remember { RupoopFork.hasBundle(ctx) }
    val sizeMb = remember { (RupoopFork.bundledBytes(ctx) / (1024.0 * 1024.0)) }
    val installResult by ForkInstallReceiver.lastResult

    LaunchedEffect(installResult) {
        if (installResult.isNotBlank()) {
            msg = installResult
            installed = RupoopFork.isInstalled(ctx)
            version = RupoopFork.installedVersion(ctx)
        }
    }

    fun doInstall() {
        busy = true
        msg = "Распаковываю встроенный APK…"
        val err = RupoopFork.install(ctx)
        busy = false
        msg = err ?: "Открыл системный установщик — подтвердите установку"
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0E1621)).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(Color(0xFF4C8DFF), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) { Text("R", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("RupoopFork", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Второе приложение в установочном пакете Omnibrowser",
                    color = Color(0xFF8FA3B8), fontSize = 11.sp
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "RuTube · ВКонтакте · Одноклассники · LordFilm — без рекламы.\n" +
                "Ставим из APK, который уже лежит внутри этого приложения: скачивать отдельно нечего.",
            color = Color(0xFFC7D3DF), fontSize = 12.sp, lineHeight = 17.sp
        )

        Spacer(Modifier.height(18.dp))
        Box(
            Modifier.fillMaxWidth().background(Color(0xFF17222F), RoundedCornerShape(14.dp)).padding(14.dp)
        ) {
            Column {
                Text(
                    if (installed) "Установлено" else "Не установлено",
                    color = if (installed) Color(0xFF5BD28A) else Color(0xFFFFC266),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
                if (installed && version.isNotBlank()) {
                    Text("Версия: $version", color = Color(0xFF8FA3B8), fontSize = 11.sp)
                }
                Text(
                    if (bundled) "Внутри пакета: rupofork.apk · " + String.format(java.util.Locale.US, "%.1f", sizeMb) + " МБ"
                    else "Встроенный APK не найден (сборка без второго приложения)",
                    color = Color(0xFF8FA3B8), fontSize = 11.sp
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        if (bundled) {
            Button(
                text = if (installed) "⬆ Обновить до встроенной версии" else "⬇ Установить RupoopFork",
                enabled = !busy,
                onClick = { doInstall() }
            )
            Spacer(Modifier.height(8.dp))
        }
        if (installed) {
            Button(
                text = "▶ Открыть RupoopFork",
                enabled = true,
                onClick = {
                    if (!RupoopFork.launch(ctx)) msg = "Не удалось открыть: приложение не отвечает"
                }
            )
        }

        if (msg.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(msg, color = Color(0xFFFFC266), fontSize = 12.sp)
        }
        if (busy) {
            Spacer(Modifier.height(10.dp))
            Text("Устанавливаю…", color = Color(0xFF8FA3B8), fontSize = 12.sp)
        }
        Spacer(Modifier.weight(1f))
        Text(
            "После установки RupoopFork появится в системе отдельным приложением со своим ярлыком.",
            color = Color(0xFF5B6B7B), fontSize = 10.sp
        )
    }
}

@Composable
private fun Button(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                if (enabled) Color(0xFF4C8DFF) else Color(0xFF2A3949),
                RoundedCornerShape(12.dp)
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
