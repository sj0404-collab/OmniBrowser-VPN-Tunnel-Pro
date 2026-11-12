package com.winwasm64.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winwasm64.vpn.WgVpnService
import com.winwasm64.ui.theme.Omni

/**
 * v4.66.0: WireGuard в интерфейсе — то, чего не хватало в 4.64/4.65 (сервис был, а включить нельзя).
 *
 * Своя конфигурация `.conf` (как от провайдера, так и свой сервер) → «Включить» → Android один раз
 * спрашивает согласие на VPN → туннель поднимается в фоне. Browsec при этом останавливается:
 * Android разрешает только один активный VPN, и лучше сказать об этом прямо, чем «молча отвалиться».
 */
@Composable
fun WireGuardCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hasConfig by remember { mutableStateOf(WgVpnService.hasConfig(context)) }
    var active by remember { mutableStateOf(WgVpnService.isWgActive) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    // Обновляем состояние без перерисовок «в пустоту»
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1200)
            active = WgVpnService.isWgActive
            hasConfig = WgVpnService.hasConfig(context)
        }
    }

    fun startTunnel() {
        busy = true
        note = "подключаюсь…"
        Thread({
            val err = WgVpnService.start(context)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                busy = false
                active = err == null
                note = err ?: "подключено · ${WgVpnService.backendName()}"
            }
        }, "WgStart").apply { isDaemon = true; start() }
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) startTunnel() else {
            busy = false
            note = "без согласия на VPN туннель не поднять"
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        Thread({
            val text = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            } catch (_: Throwable) {
                ""
            }
            val err = WgVpnService.saveConfig(context, text)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                hasConfig = WgVpnService.hasConfig(context)
                note = err ?: "конфиг сохранён — нажмите «Включить»"
            }
        }, "WgConf").apply { isDaemon = true; start() }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Omni.Surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Omni.Outline)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🧱 WireGuard (свой сервер или .conf провайдера)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                when {
                    active -> "🟢 туннель поднят · ${WgVpnService.statsLine()}"
                    hasConfig -> "⚪ конфиг загружен, туннель выключен"
                    else -> "⚪ конфига нет — загрузите .conf (Interface + Peer)"
                },
                color = Omni.TextDim, fontSize = 11.sp
            )
            if (note.isNotBlank()) {
                Text(note, color = Omni.TextDim, fontSize = 11.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { picker.launch("*/*") },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) { Text("📄 Загрузить .conf", fontSize = 11.sp, color = Color.White) }

                Button(
                    onClick = {
                        if (!hasConfig) {
                            note = "сначала загрузите .conf"
                            return@Button
                        }
                        if (active) {
                            WgVpnService.stop()
                            active = false
                            note = "выключено"
                        } else {
                            busy = true
                            // согласие на VPN спрашивается только у активити
                            val intent = WgVpnService.prepareIntent(context)
                            if (intent != null) {
                                note = "нужно разрешение Android на VPN"
                                consent.launch(intent)
                            } else startTunnel()
                        }
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (active) Color(0xFF3A3B40) else Omni.AccentContainer
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(if (active) "Выключить" else if (busy) "Подключаюсь…" else "Включить", fontSize = 11.sp, color = Color.White)
                }

                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(context, com.winwasm64.vpn.VpnLogActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) { Text("📜 Журнал", fontSize = 11.sp, color = Color.White) }
            }
            Text(
                "Browsec и WireGuard одновременно нельзя — Android разрешает один VPN. " +
                    "При включении WireGuard Browsec останавливается (и наоборот).",
                color = Omni.TextDim, fontSize = 10.sp
            )
        }
    }
}
