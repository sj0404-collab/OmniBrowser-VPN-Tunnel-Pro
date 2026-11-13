package com.santiago43rus.rupoop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.santiago43rus.rupoop.theme.RupoopTheme
import com.santiago43rus.rupoop.util.isTv as isTvDeviceExt
import androidx.compose.ui.platform.LocalContext

/**
 * v4.87.0: экран Rupoop как ЧАСТЬ нашего приложения — без второго APK и без второго ярлыка.
 *
 * Внутри тот же движок (RuTube / ВКонтакте / Одноклассники / LordFilm без рекламы): поиск, подписки,
 * библиотека, скачивание, встроенный плеер Media3, ТВ-интерфейс. Наш интерфейс просто показывает
 * этот экран во вкладке «Кино», поэтому приложение одно и установка одна.
 */
@Composable
fun RupoopRootScreen(
    modifier: Modifier = Modifier,
    deepLinkVideoUrl: String? = null,
    onThemeToggled: (String) -> Unit = {}
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val isTvDevice = remember(ctx) { ctx.isTvDeviceExt() }
    val vm: AppViewModel = viewModel()
    var themeMode by remember { mutableStateOf(vm.settingsManager.themeMode) }
    val systemDark = isSystemInDarkTheme()
    val isDarkTheme = when (themeMode) {
        "light" -> false
        "dark", "easter", "secret" -> true
        else -> systemDark
    }
    val toggle: (String) -> Unit = { mode ->
        themeMode = mode
        vm.settingsManager.themeMode = mode
        onThemeToggled(mode)
    }

    RupoopTheme(themeMode = themeMode, darkTheme = isDarkTheme) {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // ТВ-компоненты требуют API 23+, на Android 5/6 показываем обычный интерфейс
            if (isTvDevice && android.os.Build.VERSION.SDK_INT >= 23) {
                com.santiago43rus.rupoop.tv.TvRoot(vm = vm, onThemeToggle = toggle)
            } else {
                RutubeApp(
                    vm = vm,
                    onThemeToggle = toggle,
                    deepLinkVideoUrl = deepLinkVideoUrl,
                    onDeepLinkConsumed = {}
                )
            }
        }
    }
}
