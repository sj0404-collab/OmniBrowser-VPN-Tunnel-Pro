package com.winwasm64.vpn

import android.content.Context
import android.os.SystemClock
import ca.psiphon.PsiphonTunnel
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * v4.4.3: запасной канал — встроенная библиотека Psiphon (ca.psiphon:psiphontunnel).
 *
 * Зачем: бесплатный пул Browsec в час пик отдаёт 10–100 КБ/с и 3 живых сервера из 15 — никакой алгоритм
 * на клиенте не добавит скорости, которой нет у сервера. Psiphon — отдельная сеть с сотнями серверов и
 * собственной обфускацией (OSSH/meek/QUIC-obfs), она поднимает локальный HTTP/SOCKS-прокси, который
 * [com.winwasm64.extensions.ExtensionProxy] подмешивает к серверам Browsec как ещё один «сервер».
 *
 * Режимы (настройка `psiphon_mode`):
 *  - `auto` (по умолчанию): кандидат последней очереди; первым — когда пул Browsec перегружен;
 *  - `always`: все соединения приложений/автотуннеля идут через Psiphon первым;
 *  - `off`: не запускается.
 *
 * Учётные данные конфигурации — публичные (из консольного клиента Psiphon для Linux), сервера — бесплатный пул.
 */
object PsiphonBackup {
    const val TAG = "Psiphon"
    const val HTTP_PORT = 8921
    const val SOCKS_PORT = 8922
    private const val PREFS = "omni_psiphon"
    const val MODE_AUTO = "auto"
    const val MODE_ALWAYS = "always"
    const val MODE_OFF = "off"

    @Volatile var mode: String = MODE_AUTO; private set
    @Volatile var region: String = ""; private set

    @Volatile var httpPort: Int = 0; private set
    @Volatile var socksPort: Int = 0; private set
    @Volatile var connected: Boolean = false; private set
    @Volatile var starting: Boolean = false; private set
    @Volatile var connectedRegion: String = ""; private set
    @Volatile var clientAddress: String = ""; private set
    @Volatile var lastError: String = ""; private set
    @Volatile var startedAt: Long = 0L; private set
    @Volatile var connectedAt: Long = 0L; private set
    @Volatile var availableRegions: List<String> = emptyList(); private set
    val rxBytes = AtomicLong(); val txBytes = AtomicLong()
    @Volatile var lastUsedAt: Long = 0L

    private var tunnel: PsiphonTunnel? = null
    private val lock = Any()
    private val loaded = AtomicBoolean(false)
    @Volatile private var appCtx: Context? = null
    @Volatile private var waitingNetworkLoggedAt = 0L

    fun load(ctx: Context) {
        if (!loaded.compareAndSet(false, true)) return
        appCtx = ctx.applicationContext
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mode = p.getString("mode", MODE_AUTO) ?: MODE_AUTO
        region = p.getString("region", "") ?: ""
    }

    fun setMode(ctx: Context, m: String) {
        load(ctx)
        mode = m
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mode", m).apply()
        VpnLog.i(TAG, "режим: " + modeTitle(m))
        if (m == MODE_OFF) stop() else if (BrowsecVpnService.isRunning) ensureStarted(ctx, "смена режима")
    }

    fun setRegion(ctx: Context, r: String) {
        load(ctx)
        region = r
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("region", r).apply()
        VpnLog.i(TAG, "регион выхода: " + (if (r.isBlank()) "любой (быстрейший)" else r))
        if (tunnel != null) restart()
    }

    fun modeTitle(m: String = mode): String = when (m) {
        MODE_ALWAYS -> "всегда (все соединения через Psiphon)"
        MODE_OFF -> "выкл"
        else -> "авто (резерв при перегрузке Browsec)"
    }

    /** Локальный HTTP-прокси Psiphon готов к CONNECT. */
    fun ready(): Boolean = connected && httpPort > 0

    fun statusLine(): String = when {
        mode == MODE_OFF -> "Psiphon: выключен"
        ready() -> "Psiphon: подключён" + (if (connectedRegion.isNotBlank()) " · регион $connectedRegion" else "") +
            (if (clientAddress.isNotBlank()) " · IP $clientAddress" else "") +
            " · ↓${MediaFmt.size(rxBytes.get())} ↑${MediaFmt.size(txBytes.get())}" +
            (if (mode == MODE_ALWAYS) " · основной канал" else " · резерв")
        starting -> "Psiphon: подключается… (${(SystemClock.elapsedRealtime() - startedAt) / 1000} с)" + (if (lastError.isNotBlank()) " · $lastError" else "")
        else -> "Psiphon: не запущен (${modeTitle()})"
    }

    private object MediaFmt {
        fun size(b: Long): String = when {
            b < 1024 -> "$b Б"
            b < 1024 * 1024 -> String.format(java.util.Locale.US, "%.0f КБ", b / 1024.0)
            b < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f МБ", b / 1048576.0)
            else -> String.format(java.util.Locale.US, "%.2f ГБ", b / 1073741824.0)
        }
    }

    /** Запуск (идемпотентно). [why] — для журнала. */
    fun ensureStarted(ctx: Context, why: String) {
        load(ctx)
        if (mode == MODE_OFF) return
        synchronized(lock) {
            if (tunnel != null) return
            starting = true
            connected = false
            lastError = ""
            startedAt = SystemClock.elapsedRealtime()
            val host = Host(ctx.applicationContext)
            val t = try { PsiphonTunnel.newPsiphonTunnel(host) } catch (e: Throwable) {
                starting = false
                lastError = e.message ?: e.javaClass.simpleName
                VpnLog.e(TAG, "библиотека не загрузилась: $lastError")
                return
            }
            tunnel = t
            VpnLog.i(TAG, "запуск ($why), режим: ${modeTitle()}" + (if (region.isNotBlank()) ", регион $region" else ""))
            Thread({
                try {
                    t.setVpnMode(false)
                    t.startTunneling("")
                } catch (e: Throwable) {
                    lastError = e.message ?: e.javaClass.simpleName
                    VpnLog.e(TAG, "не запустился: $lastError")
                    synchronized(lock) { if (tunnel === t) { tunnel = null; starting = false } }
                }
            }, "PsiphonStart").start()
        }
    }

    fun stop() {
        val t: PsiphonTunnel?
        synchronized(lock) { t = tunnel; tunnel = null }
        if (t == null) return
        connected = false
        starting = false
        httpPort = 0
        socksPort = 0
        Thread({
            try { t.stop() } catch (_: Throwable) {}
            VpnLog.i(TAG, "остановлен · за сеанс ↓${MediaFmt.size(rxBytes.get())} ↑${MediaFmt.size(txBytes.get())}")
        }, "PsiphonStop").start()
    }

    fun restart() {
        val ctx = appCtx ?: return
        stop()
        Thread({ try { Thread.sleep(800) } catch (_: InterruptedException) {}; ensureStarted(ctx, "перезапуск") }, "PsiphonRestart").start()
    }

    private fun buildConfig(ctx: Context): String {
        val dir = File(ctx.filesDir, "psiphon").apply { mkdirs() }
        val o = JSONObject()
        o.put("ClientVersion", "440")
        o.put("ClientPlatform", "Android")
        o.put("PropagationChannelId", "FFFFFFFFFFFFFFFF")
        o.put("SponsorId", "FFFFFFFFFFFFFFFF")
        o.put("RemoteServerListUrl", "https://s3.amazonaws.com//psiphon/web/mjr4-p23r-puwl/server_list_compressed")
        o.put("RemoteServerListSignaturePublicKey", "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM=")
        o.put("RemoteServerListDownloadFilename", File(dir, "remote_server_list").absolutePath)
        o.put("DataRootDirectory", dir.absolutePath)
        o.put("DataStoreDirectory", dir.absolutePath)
        o.put("UseIndistinguishableTLS", true)
        o.put("LocalHttpProxyPort", HTTP_PORT)
        o.put("LocalSocksProxyPort", SOCKS_PORT)
        o.put("EgressRegion", region)
        o.put("ConnectionWorkerPoolSize", 10)
        o.put("TunnelPoolSize", 1)
        o.put("PortForwardFailureThreshold", 10)
        o.put("EmitBytesTransferred", true)
        o.put("EmitDiagnosticNotices", false)
        o.put("EstablishTunnelTimeoutSeconds", 0)
        return o.toString()
    }

    private class Host(private val ctx: Context) : PsiphonTunnel.HostService {
        override fun getContext(): Context = ctx
        override fun getPsiphonConfig(): String = buildConfig(ctx)
        override fun onDiagnosticMessage(message: String) {
            // только важное: ошибки и смена состояния, иначе журнал тонет
            val m = message.lowercase()
            if (m.contains("error") || m.contains("failed")) {
                val now = SystemClock.elapsedRealtime()
                if (now - diagLoggedAt > 30_000L) { diagLoggedAt = now; VpnLog.w(TAG, message.take(180)) }
            }
        }
        @Volatile private var diagLoggedAt = 0L
        override fun onListeningHttpProxyPort(port: Int) { httpPort = port; VpnLog.i(TAG, "локальный HTTP-прокси 127.0.0.1:$port") }
        override fun onListeningSocksProxyPort(port: Int) { socksPort = port }
        override fun onHttpProxyPortInUse(port: Int) { VpnLog.w(TAG, "порт $port занят — Psiphon не сможет принять соединения") }
        override fun onConnecting() {
            if (connected) VpnLog.w(TAG, "соединение потеряно — переподключение")
            connected = false
            starting = true
        }
        override fun onConnected() {
            connected = true
            starting = false
            connectedAt = SystemClock.elapsedRealtime()
            VpnLog.ok(TAG, "подключён за ${(connectedAt - startedAt) / 1000} с" + (if (connectedRegion.isNotBlank()) " · регион $connectedRegion" else "") + " · прокси 127.0.0.1:$httpPort")
            try { com.winwasm64.extensions.ExtensionProxy.onPsiphonConnected() } catch (_: Throwable) {}
        }
        override fun onConnectedServerRegion(region: String) { connectedRegion = region }
        override fun onClientRegion(region: String) {}
        override fun onClientAddress(address: String) { clientAddress = address }
        override fun onAvailableEgressRegions(regions: MutableList<String>) { availableRegions = regions.toList().sorted() }
        override fun onBytesTransferred(sent: Long, received: Long) { txBytes.addAndGet(sent); rxBytes.addAndGet(received) }
        override fun onStartedWaitingForNetworkConnectivity() {
            val now = SystemClock.elapsedRealtime()
            if (now - waitingNetworkLoggedAt > 60_000L) { waitingNetworkLoggedAt = now; VpnLog.w(TAG, "ждём сеть") }
        }
        override fun onUpstreamProxyError(message: String) { lastError = message; VpnLog.w(TAG, "upstream proxy: $message") }
        override fun onExiting() { connected = false; starting = false }
    }
}
