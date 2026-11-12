package com.winwasm64.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.File

/**
 * v4.66.0: WireGuard доведён до рабочего состояния.
 *
 * Что было не так в 4.64/4.65 (разбор приложен отдельно):
 *  • самосборный модуль `:tunnel` не содержал `libwg-go.so` — `GoBackend` падал
 *    с `UnsatisfiedLinkError`, а это `Error`, не `Exception`, поэтому приложение просто падало;
 *  • запрос согласия на VPN (`VpnService.prepare`) не вызывался вообще;
 *  • сервис нельзя было запустить из интерфейса.
 *
 * Теперь используется официальная библиотека `com.wireguard.android:tunnel` (в ней настоящий
 * userspace-бэкенд `libwg-go.so` для всех ABI), согалсие спрашивается из интерфейса, а этот
 * объект — только контроллер: он не сервис, а тонкая обёртка над `GoBackend`.
 *
 * Сам туннель поднимает сервис библиотеки
 * `com.wireguard.android.backend.GoBackend$VpnService` (объявлен в манифесте) — так задумано
 * в wireguard-android: `GoBackend` запускает именно его.
 */
object WgVpnService {

    const val ACTION_START = "com.winwasm64.vpn.WG_START"
    const val ACTION_STOP = "com.winwasm64.vpn.WG_STOP"
    const val EXTRA_CONFIG = "config_path"

    private const val TAG = "WireGuard"

    @Volatile
    var isWgActive: Boolean = false
        private set

    @Volatile
    var lastError: String = ""
        private set

    @Volatile
    private var backend: GoBackend? = null

    @Volatile
    private var tunnel: Tunnel? = null

    private val tunnelName = "OmniBrowser-WG"

    /** Файл конфигурации внутри приложения: сюда копируется выбранный пользователем `.conf`. */
    fun configFile(context: Context): File =
        File(File(context.filesDir, "wg").also { it.mkdirs() }, "wg.conf")

    fun hasConfig(context: Context): Boolean {
        val f = configFile(context)
        return f.exists() && f.length() > 20
    }

    /** Сохранить выбранный `.conf` (приводим текст к нормальному виду и проверяем разбор). */
    fun saveConfig(context: Context, raw: String): String? {
        return try {
            val text = raw.replace("\r\n", "\n").trim() + "\n"
            val parsed = Config.parse(text.byteInputStream())
            if (parsed.peers.isEmpty()) return "в конфиге нет ни одного [Peer]"
            configFile(context).writeText(text)
            null
        } catch (e: Throwable) {
            "конфиг не разобран: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Нужно ли спросить у пользователя согласие на VPN (вернёт Intent — покажем его). */
    fun prepareIntent(context: Context): Intent? = VpnService.prepare(context)

    /**
     * Поднять туннель. Блокирующий вызов — вызывать из фонового потока.
     * Возвращает null при успехе или текст ошибки.
     */
    fun start(context: Context): String? {
        if (!hasConfig(context)) return "сначала загрузите конфиг .conf"
        // Android разрешает только один активный VPN: два сервиса одновременно жить не могут
        try {
            if (BrowsecVpnService.isRunning) {
                BrowsecVpnService.stop(context.applicationContext)
            }
        } catch (_: Throwable) {
        }
        return try {
            val config = Config.parse(configFile(context).inputStream())
            val be = GoBackend(context.applicationContext)
            backend = be
            val t = object : Tunnel {
                override fun getName(): String = tunnelName
                override fun onStateChange(newState: Tunnel.State) {
                    VpnLog.i(TAG, "состояние туннеля: $newState")
                    isWgActive = newState == Tunnel.State.UP
                }
            }
            tunnel = t
            be.setState(t, Tunnel.State.UP, config)
            isWgActive = true
            lastError = ""
            VpnLog.i(TAG, "туннель поднят")
            null
        } catch (e: Throwable) {
            // v4.66.0: ловим Throwable (а не Exception) — UnsatisfiedLinkError и прочие Error
            // раньше проходили мимо обработчика и роняли процесс
            isWgActive = false
            lastError = e.message ?: e.javaClass.simpleName
            VpnLog.e(TAG, "не удалось поднять туннель: $lastError")
            lastError
        }
    }

    /** Остановить туннель. Блокирующий вызов. */
    fun stop() {
        try {
            val be = backend
            val t = tunnel
            if (be != null && t != null) be.setState(t, Tunnel.State.DOWN, null)
        } catch (_: Throwable) {
        }
        backend = null
        tunnel = null
        isWgActive = false
        VpnLog.i(TAG, "туннель остановлен")
    }

    /** Статистика: сколько принято/отдано — для строки состояния. */
    fun statsLine(): String {
        val t = tunnel ?: return ""
        return try {
            val st: Statistics = backend?.getStatistics(t) ?: return ""
            val rx = st.totalRx()
            val tx = st.totalTx()
            "⬇ ${BrowsecVpnService.formatBytesShort(rx)} · ⬆ ${BrowsecVpnService.formatBytesShort(tx)}"
        } catch (_: Throwable) {
            ""
        }
    }

    /** Для проверки доступности бэкенда без реального подключения. */
    fun backendName(): String = "GoBackend (userspace, без root)"
}
