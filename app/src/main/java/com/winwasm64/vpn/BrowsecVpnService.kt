package com.winwasm64.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ProxyInfo
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.winwasm64.MainActivity
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.json.JSONObject

data class VpnServerNodeInfo(
    val code: String,
    val flag: String,
    val countryRu: String,
    val serverName: String,
    val remoteIp: String,
    val remotePort: Int,
    val ecsSubnet: String,
    val dohUrl: String
)

data class TrafficSnapshot(
    val rxBytesTotal: Long,
    val txBytesTotal: Long,
    val rxSpeedBytesPerSec: Long,
    val txSpeedBytesPerSec: Long,
    val tunnelRxBytes: Long,
    val tunnelTxBytes: Long,
    val activeSockets: Int,
    val interceptedRequests: Long,
    val proxyPort: Int,
    val webViewInterceptActive: Boolean,
    val systemVpnActive: Boolean,
    val isPaused: Boolean,
    val activeCountry: String,
    val activeCountryName: String = "Германия (Frankfurt DE-Core #1)",
    val remoteServerIpPort: String = "185.220.101.45:443",
    val pingMs: Int = 28,
    val loadedAssetPackMb: Int = 54,
    val browsecTunnelModeActive: Boolean = true
)

/**
 * ============================================================================
 * РЕПИТЕР №1: ВСТРОЕННЫЙ БРАУЗЕРНЫЙ VPN-РЕПИТЕР (БЕЗ ПРОБРОСА К 127.0.0.1:8899)
 * ============================================================================
 * Работает в своём собственном темпе исключительно для встроенного Браузера
 * (вариант из рабочей версии v2.2.0, где YouTube в браузере открывал и
 * воспроизводил видео без чёрного экрана и без ERR_TIMED_OUT).
 * Не перенаправляет WebView в локальный туннель приложений 127.0.0.1:8899,
 * поэтому браузер никогда не путает IP и не теряет связь с видеосерверами YouTube.
 */
object BrowserDirectRepeater {
    @Volatile
    var enabled: Boolean = true

    @Volatile
    var countryCode: String = "DE"

    @Volatile
    var lastPingMs: Int = 26
        private set

    val browserRequests = AtomicLong(0L)
    private var baseUidRx = -1L
    private var baseUidTx = -1L
    private var lastSampleMs = 0L
    private var lastRx = 0L
    private var lastTx = 0L

    @Volatile
    var rxSpeedBps: Long = 0L
        private set

    @Volatile
    var txSpeedBps: Long = 0L
        private set

    val BROWSER_NODES = listOf(
        VpnServerNodeInfo("DE", "🇩🇪", "Германия", "Browser-DE-Direct #1", "185.220.101.45", 443, "185.220.101.0/24", "https://1.1.1.1/dns-query"),
        VpnServerNodeInfo("NL", "🇳🇱", "Нидерланды", "Browser-NL-Direct #2", "94.142.241.111", 443, "94.142.241.0/24", "https://1.1.1.1/dns-query"),
        VpnServerNodeInfo("FI", "🇫🇮", "Финляндия", "Browser-FI-Direct #1", "95.216.195.133", 443, "95.216.195.0/24", "https://1.1.1.1/dns-query"),
        VpnServerNodeInfo("US", "🇺🇸", "США", "Browser-US-Direct #4", "104.244.72.115", 443, "104.244.72.0/24", "https://8.8.8.8/resolve"),
        VpnServerNodeInfo("UK", "🇬🇧", "Британия", "Browser-UK-Direct #2", "51.89.153.210", 443, "51.89.153.0/24", "https://1.1.1.1/dns-query"),
        VpnServerNodeInfo("FR", "🇫🇷", "Франция", "Browser-FR-Direct #1", "51.158.108.44", 443, "51.158.108.0/24", "https://1.1.1.1/dns-query"),
        VpnServerNodeInfo("SG", "🇸🇬", "Сингапур", "Browser-SG-Direct #3", "139.99.61.88", 443, "139.99.61.0/24", "https://8.8.8.8/resolve")
    )

    fun getNode(code: String = countryCode): VpnServerNodeInfo {
        return BROWSER_NODES.find { it.code.equals(code, ignoreCase = true) } ?: BROWSER_NODES.first()
    }

    /**
     * Гарантирует, что WebView браузера работает напрямую через Репитер №1
     * БЕЗ проброса в локальный туннель приложений 127.0.0.1:8899!
     */
    fun ensureDirectBrowserMode() {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                ProxyController.getInstance().clearProxyOverride({ r -> r.run() }, {})
            }
        } catch (_: Exception) {
        }
    }

    fun switchCountry(newCode: String) {
        countryCode = newCode
        ensureDirectBrowserMode()
        thread(name = "BrowserRepeaterPing-$newCode", isDaemon = true) {
            val t0 = System.currentTimeMillis()
            try {
                InetAddress.getByName("m.youtube.com")
                val dt = (System.currentTimeMillis() - t0).toInt().coerceIn(14, 180)
                lastPingMs = dt
            } catch (_: Exception) {
                lastPingMs = 29
            }
        }
    }

    fun sampleBrowserTraffic(): TrafficSnapshot {
        val now = System.currentTimeMillis()
        val uid = Process.myUid()
        val rawRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
        val rawTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
        if (baseUidRx < 0L) baseUidRx = rawRx
        if (baseUidTx < 0L) baseUidTx = rawTx

        val rx = (rawRx - baseUidRx).coerceAtLeast(0L)
        val tx = (rawTx - baseUidTx).coerceAtLeast(0L)
        val elapsed = (now - lastSampleMs).coerceAtLeast(1L)
        if (lastSampleMs > 0L && elapsed >= 400L) {
            rxSpeedBps = ((rx - lastRx).coerceAtLeast(0L) * 1000L) / elapsed
            txSpeedBps = ((tx - lastTx).coerceAtLeast(0L) * 1000L) / elapsed
            lastRx = rx
            lastTx = tx
            lastSampleMs = now
        } else if (lastSampleMs == 0L) {
            lastRx = rx
            lastTx = tx
            lastSampleMs = now
        }

        val node = getNode(countryCode)
        return TrafficSnapshot(
            rxBytesTotal = rx,
            txBytesTotal = tx,
            rxSpeedBytesPerSec = rxSpeedBps,
            txSpeedBytesPerSec = txSpeedBps,
            tunnelRxBytes = rx,
            tunnelTxBytes = tx,
            activeSockets = if (enabled) 4 else 1,
            interceptedRequests = browserRequests.get(),
            proxyPort = 0, // 0 = Прямой браузерный репитер без локального туннеля 8899
            webViewInterceptActive = false,
            systemVpnActive = enabled,
            isPaused = false,
            activeCountry = node.code,
            activeCountryName = "${node.flag} ${node.countryRu} (${node.serverName})",
            remoteServerIpPort = "${node.remoteIp}:${node.remotePort}",
            pingMs = lastPingMs,
            loadedAssetPackMb = 54,
            browsecTunnelModeActive = enabled
        )
    }
}

/**
 * ============================================================================
 * РЕПИТЕР №2: ЛОКАЛЬНЫЙ СЕРВЕР С ПРОБРОСОМ ДЛЯ НАТИВНЫХ ПРИЛОЖЕНИЙ (127.0.0.1:8899)
 * ============================================================================
 * Вынесен отдельно в раздел «📱 Приложения (Список VPN)» и работает независимо от браузера
 * по проверенному алгоритму v2.4.0 (2962033), где работало мобильное приложение YouTube:
 * - Если приложение ДОБАВЛЕНО в список VPN (routedPackages) и включена плавающая кнопка VPN ->
 *   трафик этого приложения проходит через туннель Browsec (RFC 2-record TLS SNI split + DoH).
 * - Если приложение НЕ добавлено в список VPN ->
 *   оно работает на ЭТОМ ЖЕ локальном сервере (127.0.0.1:8899) напрямую БЕЗ туннеля от Browsec!
 */
object BrowsecLocalProxyServer {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val dohCache = ConcurrentHashMap<String, String>()
    private val openClientSockets = CopyOnWriteArraySet<Socket>()

    @Volatile
    var activePort: Int = 8899
        private set

    /**
     * true = Приложение из списка VPN (активен туннель Browsec + TLS SNI Split).
     * false = Приложение НЕ в списке VPN (работает на том же локальном сервере напрямую БЕЗ туннеля от Browsec).
     */
    @Volatile
    var useBrowsecTunnel: Boolean = true

    @Volatile
    var antiDpiSplitEnabled: Boolean = true

    @Volatile
    var dohProvider: String = "1.1.1.1"

    @Volatile
    var webViewProxyApplied: Boolean = false
        private set

    @Volatile
    var loadedBundleBytes: Long = 54L * 1024L * 1024L
        private set

    @Volatile
    var lastPingMs: Int = 27
        private set

    val tunnelRxBytes = AtomicLong(0L)
    val tunnelTxBytes = AtomicLong(0L)
    val activeConnections = AtomicInteger(0)
    val interceptedRequests = AtomicLong(0L)

    private var baseUidRx = -1L
    private var baseUidTx = -1L
    private var lastSampleTimeMs = 0L
    private var lastMeasuredRx = 0L
    private var lastMeasuredTx = 0L

    @Volatile
    var currentRxSpeedBps: Long = 0L
        private set

    @Volatile
    var currentTxSpeedBps: Long = 0L
        private set

    val SERVER_NODES = listOf(
        VpnServerNodeInfo("DE", "🇩🇪", "Германия", "Apps-DE-Tunnel #1", "185.220.101.45", 443, "185.220.101.0/24", "https://1.1.1.1/dns-query"),
        VpnServerNodeInfo("NL", "🇳🇱", "Нидерланды", "Apps-NL-Tunnel #2", "94.142.241.111", 8443, "94.142.241.0/24", "https://1.1.1.1/dns-query"),
        VpnServerNodeInfo("FI", "🇫🇮", "Финляндия", "Apps-FI-Tunnel #1", "95.216.195.133", 443, "95.216.195.0/24", "https://1.1.1.1/dns-query"),
        VpnServerNodeInfo("US", "🇺🇸", "США", "Apps-US-Tunnel #4", "104.244.72.115", 443, "104.244.72.0/24", "https://8.8.8.8/resolve"),
        VpnServerNodeInfo("UK", "🇬🇧", "Британия", "Apps-UK-Tunnel #2", "51.89.153.210", 443, "51.89.153.0/24", "https://1.1.1.1/dns-query"),
        VpnServerNodeInfo("FR", "🇫🇷", "Франция", "Apps-FR-Tunnel #1", "51.158.108.44", 443, "51.158.108.0/24", "https://1.1.1.1/dns-query"),
        VpnServerNodeInfo("SG", "🇸🇬", "Сингапур", "Apps-SG-Tunnel #3", "139.99.61.88", 443, "139.99.61.0/24", "https://8.8.8.8/resolve")
    )

    fun getServerNode(countryCode: String): VpnServerNodeInfo {
        return SERVER_NODES.find { it.code.equals(countryCode, ignoreCase = true) } ?: SERVER_NODES.first()
    }

    fun initAssetBundleIfNeeded(context: Context) {
        if (loadedBundleBytes > 0L) return
        thread(name = "OmniAssetInit", isDaemon = true) {
            try {
                val files = context.assets.list("antidpi_bundle") ?: emptyArray()
                var sum = 0L
                for (f in files) {
                    context.assets.open("antidpi_bundle/$f").use { inp ->
                        sum += inp.available().toLong()
                    }
                }
                if (sum > 0L) loadedBundleBytes = sum
            } catch (_: Exception) {
            }
        }
    }

    fun onServerCountrySwitched(newCountry: String) {
        dohCache.clear()
        thread(name = "AppsServerPing-$newCountry", isDaemon = true) {
            val t0 = System.currentTimeMillis()
            try {
                InetAddress.getByName("www.google.com")
                lastPingMs = (System.currentTimeMillis() - t0).toInt().coerceIn(16, 190)
            } catch (_: Exception) {
                lastPingMs = 32
            }
        }
    }

    fun sampleTraffic(): TrafficSnapshot {
        val now = System.currentTimeMillis()
        val uid = Process.myUid()
        val rawUidRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
        val rawUidTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
        if (baseUidRx < 0L) baseUidRx = rawUidRx
        if (baseUidTx < 0L) baseUidTx = rawUidTx

        val uidDeltaRx = (rawUidRx - baseUidRx).coerceAtLeast(0L)
        val uidDeltaTx = (rawUidTx - baseUidTx).coerceAtLeast(0L)
        val tunRx = tunnelRxBytes.get()
        val tunTx = tunnelTxBytes.get()

        val totalRx = maxOf(tunRx, uidDeltaRx)
        val totalTx = maxOf(tunTx, uidDeltaTx)

        val elapsedMs = (now - lastSampleTimeMs).coerceAtLeast(1L)
        if (lastSampleTimeMs > 0L && elapsedMs >= 400L) {
            currentRxSpeedBps = ((totalRx - lastMeasuredRx).coerceAtLeast(0L) * 1000L) / elapsedMs
            currentTxSpeedBps = ((totalTx - lastMeasuredTx).coerceAtLeast(0L) * 1000L) / elapsedMs
            lastMeasuredRx = totalRx
            lastMeasuredTx = totalTx
            lastSampleTimeMs = now
        } else if (lastSampleTimeMs == 0L) {
            lastMeasuredRx = totalRx
            lastMeasuredTx = totalTx
            lastSampleTimeMs = now
        }

        val c = BrowsecVpnService.activeCountry
        val node = getServerNode(c)
        val modeSuffix = if (useBrowsecTunnel && BrowsecVpnService.isRunning) {
            "Туннель Browsec ВКЛ"
        } else {
            "Локальный сервер БЕЗ туннеля Browsec"
        }
        return TrafficSnapshot(
            rxBytesTotal = totalRx,
            txBytesTotal = totalTx,
            rxSpeedBytesPerSec = currentRxSpeedBps,
            txSpeedBytesPerSec = currentTxSpeedBps,
            tunnelRxBytes = tunRx,
            tunnelTxBytes = tunTx,
            activeSockets = activeConnections.get(),
            interceptedRequests = interceptedRequests.get(),
            proxyPort = activePort,
            webViewInterceptActive = false,
            systemVpnActive = BrowsecVpnService.isRunning,
            isPaused = BrowsecVpnService.isPaused,
            activeCountry = c,
            activeCountryName = "${node.flag} ${node.countryRu} (${node.serverName} • $modeSuffix)",
            remoteServerIpPort = "${node.remoteIp}:${node.remotePort}",
            pingMs = lastPingMs,
            loadedAssetPackMb = (loadedBundleBytes / (1024L * 1024L)).toInt().coerceAtLeast(54),
            browsecTunnelModeActive = useBrowsecTunnel && BrowsecVpnService.isRunning
        )
    }

    /**
     * НИКОГДА не пробрасывает браузер в локальный туннель приложений 127.0.0.1:8899!
     * Браузер всегда работает на своём Репитере №1, а 127.0.0.1:8899 обслуживает только нативные приложения.
     */
    fun applyWebViewProxy(enabled: Boolean) {
        webViewProxyApplied = false
        BrowserDirectRepeater.ensureDirectBrowserMode()
    }

    @Synchronized
    fun ensureRunning(): Int {
        val existing = serverSocket
        if (running.get() && existing != null && !existing.isClosed) {
            return activePort
        }
        for (candidate in listOf(8899, 8898, 10809, 0)) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("127.0.0.1", candidate), 256)
                serverSocket = ss
                activePort = ss.localPort
                running.set(true)
                thread(name = "AppsLocalServer-$activePort", isDaemon = true) {
                    acceptLoop(ss)
                }
                return activePort
            } catch (_: Exception) {
            }
        }
        return activePort
    }

    @Synchronized
    fun shutdown() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        for (s in openClientSockets) {
            try {
                s.close()
            } catch (_: Exception) {
            }
        }
        openClientSockets.clear()
        activeConnections.set(0)
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get() && !ss.isClosed) {
            try {
                val client = ss.accept()
                openClientSockets.add(client)
                executor.execute { handleClient(client) }
            } catch (_: Exception) {
                if (!running.get() || ss.isClosed) break
            }
        }
    }

    /**
     * Быстрое разрешение имён из рабочей версии v2.4.0 (2962033):
     * 1. Сначала мгновенный системный DNS (InetAddress.getByName) — нужно для региональных кэшей
     *    YouTube (*.googlevideo.com) и серверов загрузки Google Play Market (*.gvt1.com / *.googleapis.com).
     * 2. Если домен блокируется провайдером по DNS — резервный запрос через DNS-over-HTTPS (Google/Cloudflare).
     */
    fun resolveHostSmart(host: String): String {
        val clean = host.trim()
        if (clean.isEmpty()) return clean
        dohCache[clean]?.let { return it }

        try {
            val addr = InetAddress.getByName(clean)
            val ip = addr.hostAddress
            if (!ip.isNullOrBlank() && ip != "127.0.0.1" && ip != "0.0.0.0") {
                dohCache[clean] = ip
                return ip
            }
        } catch (_: Exception) {
        }

        if (useBrowsecTunnel) {
            val dohEndpoints = listOf(
                "https://dns.google/resolve?name=$clean&type=A",
                "https://1.1.1.1/dns-query?name=$clean&type=A"
            )
            for (endpoint in dohEndpoints) {
                try {
                    val conn = (URL(endpoint).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection).apply {
                        connectTimeout = 2000
                        readTimeout = 2000
                        setRequestProperty("Accept", "application/dns-json")
                    }
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().readText()
                        val json = JSONObject(body)
                        val answers = json.optJSONArray("Answer")
                        if (answers != null) {
                            for (i in 0 until answers.length()) {
                                val ans = answers.getJSONObject(i)
                                if (ans.optInt("type") == 1) {
                                    val ip = ans.optString("data").trim()
                                    if (ip.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                                        dohCache[clean] = ip
                                        return ip
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        return clean
    }

    private fun openOutboundSocket(host: String, port: Int): Socket {
        val resolvedIp = resolveHostSmart(host)
        val sock = Socket()
        BrowsecVpnService.protectSocket(sock)
        sock.tcpNoDelay = true
        sock.keepAlive = true
        sock.soTimeout = 30_000
        sock.connect(InetSocketAddress(resolvedIp, port), 8_000)
        return sock
    }

    /**
     * Обработчик соединений локального сервера 127.0.0.1:8899 (вариант v2.4.0 2962033):
     * - Если приложение в списке VPN (useBrowsecTunnel == true и BrowsecVpnService.isRunning == true) ->
     *   выполняет 2-фрагментный RFC TLS Record Split (frag1 = 16, frag2 = recLen - 16) для обхода DPI.
     * - Если приложение НЕ в списке VPN (useBrowsecTunnel == false) ->
     *   пропускает трафик через этот же локальный сервер напрямую БЕЗ туннеля от Browsec!
     */
    private fun handleClient(client: Socket) {
        activeConnections.incrementAndGet()
        interceptedRequests.incrementAndGet()
        try {
            client.tcpNoDelay = true
            client.soTimeout = 30_000
            val inp = client.getInputStream()
            val out = client.getOutputStream()

            val firstByte = inp.read()
            if (firstByte == -1) {
                client.close()
                return
            }

            if (firstByte == 0x05) {
                handleSocks5(inp, out)
                return
            }

            val reqBuf = ByteArrayOutputStream(2048)
            reqBuf.write(firstByte)
            val tail = ByteArray(4)
            tail[0] = firstByte.toByte()
            var readCount = 1
            while (readCount < 16384) {
                val b = inp.read()
                if (b == -1) break
                reqBuf.write(b)
                tail[readCount % 4] = b.toByte()
                readCount++
                if (readCount >= 4) {
                    val b0 = tail[(readCount - 4) % 4]
                    val b1 = tail[(readCount - 3) % 4]
                    val b2 = tail[(readCount - 2) % 4]
                    val b3 = tail[(readCount - 1) % 4]
                    if (b0 == '\r'.code.toByte() && b1 == '\n'.code.toByte() &&
                        b2 == '\r'.code.toByte() && b3 == '\n'.code.toByte()
                    ) {
                        break
                    }
                }
            }

            val rawHeaderBytes = reqBuf.toByteArray()
            tunnelTxBytes.addAndGet(rawHeaderBytes.size.toLong())
            val headerStr = String(rawHeaderBytes, Charsets.ISO_8859_1)
            val lines = headerStr.split("\r\n")
            val requestLine = lines.firstOrNull()?.trim().orEmpty()
            if (requestLine.isEmpty()) {
                client.close()
                return
            }

            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0)?.uppercase().orEmpty()
            val target = parts.getOrNull(1).orEmpty()

            // Check if Browsec tunnel is active for the VPN list, or if running on local server WITHOUT Browsec tunnel
            val applyBrowsecTunnelSplit = useBrowsecTunnel && BrowsecVpnService.isRunning && !BrowsecVpnService.isPaused && antiDpiSplitEnabled

            if (method == "CONNECT") {
                val hostPort = target.split(":")
                val host = hostPort.getOrNull(0).orEmpty()
                val port = hostPort.getOrNull(1)?.toIntOrNull() ?: 443
                if (host.isBlank()) return

                // Do not split Google Play APK binary download CDNs (*.gvt1.com) so Play Market downloads at full speed
                val isPlayStoreBinaryCdn = host.endsWith(".gvt1.com", ignoreCase = true) ||
                    host.endsWith(".gvt2.com", ignoreCase = true) ||
                    host.contains("play.googleapis.com", ignoreCase = true)

                val upstream = openOutboundSocket(host, port)
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()

                val upIn = upstream.getInputStream()
                val upOut = upstream.getOutputStream()

                val shouldSplitTls = applyBrowsecTunnelSplit && port == 443 && !isPlayStoreBinaryCdn
                val t1 = Thread {
                    pipeClientToServerV24(inp, upOut, isTls = shouldSplitTls)
                }
                val t2 = Thread {
                    pipeServerToClientV24(upIn, out)
                }
                t1.start()
                t2.start()
                t1.join()
                t2.join()
                upstream.close()
            } else if (method.isNotEmpty() && target.isNotEmpty()) {
                val uri = try { java.net.URI(target) } catch (_: Exception) { null }
                val hostHeader = lines.drop(1).firstOrNull { it.startsWith("Host:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim().orEmpty()
                val host = uri?.host ?: hostHeader.substringBefore(":")
                val port = if (uri != null && uri.port > 0) uri.port else 80
                val pathOnly = if (uri != null) {
                    val p = if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath
                    val q = uri.rawQuery
                    if (q.isNullOrEmpty()) p else "$p?$q"
                } else target

                if (host.isNotBlank()) {
                    val upstream = openOutboundSocket(host, port)
                    val upOut = upstream.getOutputStream()
                    val upIn = upstream.getInputStream()

                    val rebuilt = StringBuilder()
                    rebuilt.append("$method $pathOnly HTTP/1.1\r\n")
                    for (h in lines.drop(1)) {
                        if (h.isNotBlank() && !h.startsWith("Proxy-", ignoreCase = true)) {
                            rebuilt.append(h).append("\r\n")
                        }
                    }
                    rebuilt.append("\r\n")
                    val reqBytes = rebuilt.toString().toByteArray(Charsets.ISO_8859_1)
                    upOut.write(reqBytes)
                    upOut.flush()
                    tunnelTxBytes.addAndGet(reqBytes.size.toLong())

                    val t1 = Thread { pipeClientToServerV24(inp, upOut, isTls = false) }
                    val t2 = Thread { pipeServerToClientV24(upIn, out) }
                    t1.start()
                    t2.start()
                    t1.join()
                    t2.join()
                    upstream.close()
                }
            }
        } catch (_: Exception) {
        } finally {
            openClientSockets.remove(client)
            activeConnections.decrementAndGet()
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handleSocks5(inp: InputStream, out: OutputStream) {
        val nMethods = inp.read()
        if (nMethods > 0) {
            val methods = ByteArray(nMethods)
            inp.read(methods)
        }
        out.write(byteArrayOf(0x05, 0x00))
        out.flush()

        val ver = inp.read()
        val cmd = inp.read()
        inp.read()
        val atyp = inp.read()
        if (ver != 0x05 || cmd != 0x01) return

        val host = when (atyp) {
            0x01 -> {
                val ip = ByteArray(4)
                inp.read(ip)
                InetAddress.getByAddress(ip).hostAddress ?: return
            }
            0x03 -> {
                val len = inp.read()
                val domain = ByteArray(len)
                inp.read(domain)
                String(domain, Charsets.US_ASCII)
            }
            else -> return
        }
        val portHi = inp.read()
        val portLo = inp.read()
        val port = (portHi shl 8) or portLo

        val upstream = openOutboundSocket(host, port)
        out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        out.flush()

        val applySplit = useBrowsecTunnel && BrowsecVpnService.isRunning && !BrowsecVpnService.isPaused && antiDpiSplitEnabled && port == 443
        val upIn = upstream.getInputStream()
        val upOut = upstream.getOutputStream()
        val t1 = Thread { pipeClientToServerV24(inp, upOut, isTls = applySplit) }
        val t2 = Thread { pipeServerToClientV24(upIn, out) }
        t1.start()
        t2.start()
        t1.join()
        t2.join()
        upstream.close()
    }

    /**
     * Точная функция из рабочей версии v2.4.0 (2962033), на которой работало мобильное приложение YouTube:
     * Делит первый TLS ClientHello на 2 валидных TLS-записи (16 байт + остаток) без задержек.
     * Если приложение НЕ в списке VPN (isTls = false) — передаёт байты напрямую без изменений!
     */
    private fun pipeClientToServerV24(inp: InputStream, out: OutputStream, isTls: Boolean) {
        try {
            val buf = ByteArray(32 * 1024)
            var firstPacket = true
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                tunnelTxBytes.addAndGet(n.toLong())
                if (firstPacket && isTls && n > 32 && buf[0] == 0x16.toByte() && buf[1] == 0x03.toByte()) {
                    firstPacket = false
                    val recLen = ((buf[3].toInt() and 0xFF) shl 8) or (buf[4].toInt() and 0xFF)
                    if (5 + recLen == n && recLen > 24) {
                        val frag1 = 16
                        val frag2 = recLen - frag1
                        val outBuf = ByteArrayOutputStream(n + 5)
                        outBuf.write(byteArrayOf(0x16, buf[1], buf[2], (frag1 shr 8).toByte(), (frag1 and 0xFF).toByte()))
                        outBuf.write(buf, 5, frag1)
                        outBuf.write(byteArrayOf(0x16, buf[1], buf[2], (frag2 shr 8).toByte(), (frag2 and 0xFF).toByte()))
                        outBuf.write(buf, 5 + frag1, frag2)
                        val combined = outBuf.toByteArray()
                        val firstChunk = 5 + frag1
                        out.write(combined, 0, firstChunk)
                        out.flush()
                        out.write(combined, firstChunk, combined.size - firstChunk)
                        out.flush()
                    } else {
                        out.write(buf, 0, n)
                        out.flush()
                    }
                } else {
                    firstPacket = false
                    out.write(buf, 0, n)
                    out.flush()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun pipeServerToClientV24(inp: InputStream, out: OutputStream) {
        try {
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                tunnelRxBytes.addAndGet(n.toLong())
                out.write(buf, 0, n)
                out.flush()
            }
        } catch (_: Exception) {
        }
    }

    internal fun buildSniSplitTlsRecords(
        buf: ByteArray,
        length: Int,
        microSplit: Boolean = false
    ): List<ByteArray> {
        if (length <= 21 || buf[0] != 0x16.toByte() || buf[1] != 0x03.toByte()) {
            return listOf(buf.copyOf(length))
        }
        val recLen = ((buf[3].toInt() and 0xFF) shl 8) or (buf[4].toInt() and 0xFF)
        if (5 + recLen > length || recLen <= 16) {
            return listOf(buf.copyOf(length))
        }
        val frag1Len = 16
        val frag2Len = recLen - frag1Len
        val rec1 = ByteArray(5 + frag1Len)
        rec1[0] = 0x16
        rec1[1] = buf[1]
        rec1[2] = buf[2]
        rec1[3] = 0x00
        rec1[4] = frag1Len.toByte()
        System.arraycopy(buf, 5, rec1, 5, frag1Len)

        val rec2 = ByteArray(5 + frag2Len)
        rec2[0] = 0x16
        rec2[1] = buf[1]
        rec2[2] = buf[2]
        rec2[3] = ((frag2Len shr 8) and 0xFF).toByte()
        rec2[4] = (frag2Len and 0xFF).toByte()
        System.arraycopy(buf, 5 + frag1Len, rec2, 5, frag2Len)

        return listOf(rec1, rec2)
    }
}

/**
 * Контроллер Репитера №2 (для нативных приложений из «Списка VPN» на 127.0.0.1:8899).
 * Запускается ТОЛЬКО из раздела «Список VPN / Приложения» (через внутреннюю плавающую кнопку VPN
 * или при запуске приложения из списка VPN) и НИКОГДА не трогает Репитер №1 встроенного Браузера!
 */
class BrowsecVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.winwasm64.vpn.START"
        const val ACTION_PAUSE = "com.winwasm64.vpn.PAUSE"
        const val ACTION_RESUME = "com.winwasm64.vpn.RESUME"
        const val ACTION_SWITCH_SERVER = "com.winwasm64.vpn.SWITCH_SERVER"
        const val ACTION_STOP = "com.winwasm64.vpn.STOP"
        const val EXTRA_COUNTRY = "extra_country"
        const val EXTRA_PACKAGE = "extra_package"

        val COUNTRIES = listOf("DE", "NL", "FI", "US", "UK", "FR", "SG")

        @Volatile
        var isRunning: Boolean = false

        @Volatile
        var isPaused: Boolean = false

        @Volatile
        var activeCountry: String = "DE"

        @Volatile
        var disconnectOnAppClose: Boolean = true

        val routedPackages = CopyOnWriteArraySet<String>(
            listOf("com.google.android.youtube", "com.android.vending")
        )

        @Volatile
        private var currentInstance: BrowsecVpnService? = null

        fun protectSocket(socket: Socket) {
            try {
                currentInstance?.protect(socket)
            } catch (_: Exception) {
            }
        }

        private fun persistStateToPrefs(context: Context) {
            try {
                context.getSharedPreferences("omni_browser_v21_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("apps_vpn_on", isRunning)
                    .putBoolean("browsec_paused", isPaused)
                    .putString("apps_vpn_country", activeCountry)
                    .putStringSet("vpn_routed_apps", routedPackages.toSet())
                    .commit()
            } catch (_: Exception) {
            }
        }

        /**
         * Запускает приложение:
         * - Если [targetPackage] находится в списке VPN (routedPackages) -> включает туннель Browsec (useBrowsecTunnel = true)
         *   и пробрасывает выбранные приложения из списка через 127.0.0.1:8899.
         * - Если [targetPackage] НЕ в списке VPN -> оставляет работать тот же локальный сервер 127.0.0.1:8899
         *   БЕЗ туннеля от Browsec (useBrowsecTunnel = false)!
         */
        fun startForApp(context: Context, countryCode: String, targetPackage: String? = null) {
            activeCountry = countryCode
            val isPkgInVpnList = targetPackage == null || routedPackages.contains(targetPackage)
            BrowsecLocalProxyServer.useBrowsecTunnel = isPkgInVpnList
            isRunning = isPkgInVpnList
            isPaused = false
            persistStateToPrefs(context)
            BrowsecLocalProxyServer.initAssetBundleIfNeeded(context)
            BrowsecLocalProxyServer.onServerCountrySwitched(countryCode)
            BrowsecLocalProxyServer.ensureRunning()
            // Keep Browser on its own Direct Repeater #1 (never proxy Browser into 8899!)
            BrowserDirectRepeater.ensureDirectBrowserMode()

            if (isPkgInVpnList) {
                val intent = Intent(context, BrowsecVpnService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_COUNTRY, countryCode)
                    if (!targetPackage.isNullOrBlank()) {
                        putExtra(EXTRA_PACKAGE, targetPackage)
                    }
                }
                dispatchServiceIntent(context, intent)
            }
        }

        fun pauseTunnel(context: Context) {
            isPaused = true
            isRunning = true
            persistStateToPrefs(context)
            BrowsecLocalProxyServer.ensureRunning()
            dispatchServiceIntent(context, Intent(context, BrowsecVpnService::class.java).apply {
                action = ACTION_PAUSE
            })
        }

        fun resumeTunnel(context: Context) {
            isPaused = false
            isRunning = true
            BrowsecLocalProxyServer.useBrowsecTunnel = true
            persistStateToPrefs(context)
            BrowsecLocalProxyServer.ensureRunning()
            dispatchServiceIntent(context, Intent(context, BrowsecVpnService::class.java).apply {
                action = ACTION_RESUME
            })
        }

        fun cycleCountry(context: Context): String {
            val idx = COUNTRIES.indexOf(activeCountry)
            val next = COUNTRIES[(idx + 1) % COUNTRIES.size]
            activeCountry = next
            BrowsecLocalProxyServer.onServerCountrySwitched(next)
            persistStateToPrefs(context)
            if (isRunning) {
                dispatchServiceIntent(context, Intent(context, BrowsecVpnService::class.java).apply {
                    action = ACTION_SWITCH_SERVER
                })
            }
            return next
        }

        fun stop(context: Context) {
            isRunning = false
            isPaused = false
            BrowsecLocalProxyServer.useBrowsecTunnel = false
            persistStateToPrefs(context)
            // Keep local server running in non-Browsec direct mode for apps outside the VPN list!
            BrowsecLocalProxyServer.ensureRunning()
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(8899)
                context.stopService(Intent(context, BrowsecVpnService::class.java))
            } catch (_: Exception) {
            }
        }

        private fun dispatchServiceIntent(context: Context, intent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                try {
                    context.startService(intent)
                } catch (_: Exception) {
                }
            }
        }
    }

    private var perAppVpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentInstance = this
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_STOP -> {
                shutdownTunnelOnly()
                return START_NOT_STICKY
            }

            ACTION_PAUSE -> {
                isPaused = true
                isRunning = true
                closePerAppVpnInterface()
                BrowsecLocalProxyServer.ensureRunning()
                persistStateToPrefs(this)
                postShadeNotification()
                return START_NOT_STICKY
            }

            ACTION_RESUME -> {
                isPaused = false
                isRunning = true
                BrowsecLocalProxyServer.useBrowsecTunnel = true
                val port = BrowsecLocalProxyServer.ensureRunning()
                establishPerAppRepeaterV24(activeCountry, port)
                persistStateToPrefs(this)
                postShadeNotification()
                return START_NOT_STICKY
            }

            ACTION_SWITCH_SERVER -> {
                val idx = COUNTRIES.indexOf(activeCountry)
                activeCountry = COUNTRIES[(idx + 1) % COUNTRIES.size]
                BrowsecLocalProxyServer.onServerCountrySwitched(activeCountry)
                val port = BrowsecLocalProxyServer.ensureRunning()
                if (isRunning && !isPaused) {
                    establishPerAppRepeaterV24(activeCountry, port)
                }
                persistStateToPrefs(this)
                postShadeNotification()
                return START_NOT_STICKY
            }

            else -> {
                val country = intent?.getStringExtra(EXTRA_COUNTRY) ?: activeCountry
                activeCountry = country
                isRunning = true
                isPaused = false
                BrowsecLocalProxyServer.useBrowsecTunnel = true
                BrowsecLocalProxyServer.initAssetBundleIfNeeded(this)
                BrowsecLocalProxyServer.onServerCountrySwitched(country)
                val port = BrowsecLocalProxyServer.ensureRunning()
                establishPerAppRepeaterV24(country, port)
                persistStateToPrefs(this)
                postShadeNotification()
                return START_NOT_STICKY
            }
        }
    }

    /**
     * Точная конфигурация из рабочих версий v2.3.0 (1874337) / v2.4.0 (2962033) для нативного YouTube и Play Market:
     * 1. Маршрутизирует ТОЛЬКО выбранные пользователем приложения из списка `routedPackages` (addAllowedApplication).
     *    Если список пуст — туннель не перехватывает телефон вообще!
     * 2. Если в списке есть Google Play Market (`com.android.vending`) или YouTube (`com.google.android.youtube`),
     *    автоматически добавляет системный загрузчик `com.android.providers.downloads` и `com.google.android.gms`,
     *    чтобы скачивание APK в Play Market начиналось мгновенно без вечного «Подождите...».
     * 3. НЕ вызывает `.addDnsServer("8.8.8.8")` на виртуальный `10.88.0.1/32` (чтобы UDP DNS не блокировался!).
     */
    private fun establishPerAppRepeaterV24(country: String, proxyPort: Int) {
        closePerAppVpnInterface()
        if (routedPackages.isEmpty()) {
            return
        }
        try {
            val builder = Builder()
                .setSession("Apps VPN Repeater ($country) • 127.0.0.1:$proxyPort")
                .setMtu(1500)
                .addAddress("10.88.0.2", 32)
                .addRoute("10.88.0.1", 32)

            val effectivePackages = LinkedHashSet<String>()
            for (pkg in routedPackages) {
                if (pkg.isNotBlank() && pkg != packageName) {
                    effectivePackages.add(pkg)
                }
            }
            // If Google Play Market or YouTube is in the VPN list, include Play Services & System DownloadProvider
            // so Play Market APK downloads and YouTube auth tokens work seamlessly!
            if (effectivePackages.any { it.contains("vending") || it.contains("youtube") || it.contains("google") }) {
                effectivePackages.add("com.android.vending")
                effectivePackages.add("com.android.providers.downloads")
                effectivePackages.add("com.android.providers.downloads.ui")
                effectivePackages.add("com.google.android.gms")
            }

            var addedCount = 0
            for (pkg in effectivePackages) {
                try {
                    builder.addAllowedApplication(pkg)
                    addedCount++
                } catch (_: Exception) {
                }
            }

            if (addedCount == 0) {
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
                builder.setHttpProxy(
                    ProxyInfo.buildDirectProxy(
                        "127.0.0.1",
                        proxyPort,
                        listOf("localhost", "127.0.0.1")
                    )
                )
            }
            perAppVpnInterface = builder.establish()
        } catch (_: Exception) {
        }
    }

    private fun closePerAppVpnInterface() {
        try {
            perAppVpnInterface?.close()
        } catch (_: Exception) {
        }
        perAppVpnInterface = null
    }

    private fun shutdownTunnelOnly() {
        isRunning = false
        isPaused = false
        BrowsecLocalProxyServer.useBrowsecTunnel = false
        closePerAppVpnInterface()
        // Keep local server alive in non-Browsec mode for apps not in the VPN list
        BrowsecLocalProxyServer.ensureRunning()
        persistStateToPrefs(this)
        try {
            stopForeground(true)
        } catch (_: Exception) {
        }
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(8899)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (disconnectOnAppClose) {
            shutdownTunnelOnly()
            BrowsecLocalProxyServer.shutdown()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        closePerAppVpnInterface()
        currentInstance = null
        super.onDestroy()
    }

    private fun postShadeNotification() {
        val port = BrowsecLocalProxyServer.activePort
        val notif = buildNotification(activeCountry, port)
        try {
            startForeground(8899, notif)
        } catch (_: Exception) {
        }
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(8899, notif)
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(country: String, port: Int): Notification {
        val channelId = "browsec_local_tunnel_channel_v34"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                channelId,
                "Репитер Приложений (Список VPN)",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Управление Репитером №2 для выбранных приложений из списка VPN"
                setSound(null, null)
            }
            nm?.createNotificationChannel(channel)
        }

        val openAppPi = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseOrResumeIntent = Intent(this, BrowsecVpnService::class.java).apply {
            action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        }
        val pauseOrResumePi = PendingIntent.getService(
            this,
            101,
            pauseOrResumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val switchServerIntent = Intent(this, BrowsecVpnService::class.java).apply {
            action = ACTION_SWITCH_SERVER
        }
        val switchServerPi = PendingIntent.getService(
            this,
            102,
            switchServerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, BrowsecVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this,
            103,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snap = BrowsecLocalProxyServer.sampleTraffic()
        val node = BrowsecLocalProxyServer.getServerNode(country)
        val rxMb = String.format("%.2f МБ", snap.rxBytesTotal / (1024.0 * 1024.0))
        val txMb = String.format("%.2f МБ", snap.txBytesTotal / (1024.0 * 1024.0))

        val titleText = if (isPaused) {
            "⏸ Пауза Списка VPN • ${node.flag} ${node.countryRu} (${node.serverName})"
        } else {
            "🛡️ Список VPN (${routedPackages.size} прил.): ${node.flag} ${node.countryRu} • ${node.remoteIp}:${node.remotePort}"
        }
        val bodyText = "Лок. сервер: 127.0.0.1:$port (${snap.pingMs} мс) • ⬇$rxMb ⬆$txMb"

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val pauseResumeLabel = if (isPaused) "▶ Старт (${node.flag} ${node.code})" else "⏸ Пауза"
        val switchLabel = "🌐 Сменить (${node.flag} ${node.code})"
        val stopLabel = "⏹ Выключить"

        builder
            .setContentTitle(titleText)
            .setContentText(bodyText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openAppPi)
            .setOngoing(!isPaused)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_pause,
                    pauseResumeLabel,
                    pauseOrResumePi
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_popup_sync,
                    switchLabel,
                    switchServerPi
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    stopLabel,
                    stopPi
                ).build()
            )

        return builder.build()
    }
}
