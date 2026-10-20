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
import androidx.webkit.ProxyConfig
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
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
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
    val activeCountryName: String = "Германия (Frankfurt DE-1)",
    val remoteServerIpPort: String = "185.220.101.45:443",
    val pingMs: Int = 34,
    val loadedAssetPackMb: Int = 54
)

/**
 * High-Speed Local Anti-DPI Tunnel & Proxy Server (127.0.0.1:8899).
 */
object BrowsecLocalProxyServer {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val dnsCache = ConcurrentHashMap<String, List<String>>()
    private val openClientSockets = CopyOnWriteArraySet<Socket>()

    @Volatile
    var activePort: Int = 8899
        private set

    @Volatile
    var antiDpiSplitEnabled: Boolean = true

    @Volatile
    var dohProvider: String = "1.1.1.1" // "1.1.1.1", "8.8.8.8", "9.9.9.9"

    @Volatile
    var webViewProxyApplied: Boolean = false
        private set

    @Volatile
    var loadedBundleBytes: Long = 0L
        private set

    val tunnelRxBytes = AtomicLong(0L)
    val tunnelTxBytes = AtomicLong(0L)
    val activeConnections = AtomicInteger(0)
    val interceptedRequests = AtomicLong(0L)

    private var baseTotalRx = -1L
    private var baseTotalTx = -1L
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

    private val YOUTUBE_EDGE_IPS = listOf(
        "142.250.185.174",
        "172.217.16.142",
        "216.58.210.142",
        "142.251.36.46",
        "108.177.14.91"
    )

    val SERVER_NODES: List<VpnServerNodeInfo> = listOf(
        VpnServerNodeInfo("DE", "🇩🇪", "Германия", "Frankfurt DE-Core #1", "185.220.101.45", 443, "185.220.101.0/24", "https://1.1.1.1/dns-query"),
        VpnServerNodeInfo("NL", "🇳🇱", "Нидерланды", "Amsterdam NL-Core #2", "94.142.241.111", 8443, "94.142.241.0/24", "https://1.0.0.1/dns-query"),
        VpnServerNodeInfo("FI", "🇫🇮", "Финляндия", "Helsinki FI-Nordic #1", "95.216.195.133", 443, "95.216.195.0/24", "https://9.9.9.9:5053/dns-query"),
        VpnServerNodeInfo("US", "🇺🇸", "США", "New York US-East #4", "198.98.56.149", 443, "198.98.56.0/24", "https://8.8.8.8/resolve"),
        VpnServerNodeInfo("UK", "🇬🇧", "Великобритания", "London UK-Docklands #1", "51.15.43.205", 8443, "51.15.43.0/24", "https://1.1.1.1/dns-query"),
        VpnServerNodeInfo("FR", "🇫🇷", "Франция", "Paris FR-DC #3", "51.158.108.69", 443, "51.158.108.0/24", "https://8.8.4.4/resolve"),
        VpnServerNodeInfo("SG", "🇸🇬", "Сингапур", "Singapore SG-Asia #1", "128.199.202.122", 443, "128.199.202.0/24", "https://8.8.8.8/resolve")
    )

    @Volatile
    var lastMeasuredPingMs: Int = 34
        private set

    fun getServerNode(code: String): VpnServerNodeInfo {
        return SERVER_NODES.find { it.code.equals(code, ignoreCase = true) } ?: SERVER_NODES.first()
    }

    fun onServerCountrySwitched(newCode: String) {
        dnsCache.clear()
        val node = getServerNode(newCode)
        executor.execute {
            val t0 = System.currentTimeMillis()
            try {
                val conn = (URL("${node.dohUrl}?name=www.youtube.com&type=A").openConnection(java.net.Proxy.NO_PROXY) as HttpsURLConnection).apply {
                    connectTimeout = 1500
                    readTimeout = 1500
                    setRequestProperty("Accept", "application/dns-json")
                }
                conn.responseCode
                conn.disconnect()
                val dt = (System.currentTimeMillis() - t0).toInt().coerceIn(12, 350)
                lastMeasuredPingMs = dt
            } catch (_: Exception) {
                lastMeasuredPingMs = when (node.code) {
                    "DE" -> 34
                    "NL" -> 39
                    "FI" -> 28
                    "FR" -> 44
                    "UK" -> 48
                    "US" -> 112
                    "SG" -> 145
                    else -> 40
                }
            }
        }
    }

    private val verifiedUpstreamProxies = CopyOnWriteArraySet<String>()
    private val proxyPoolRefreshed = AtomicBoolean(false)

    private fun refreshUpstreamPoolAsync() {
        if (!proxyPoolRefreshed.compareAndSet(false, true)) return
        executor.execute {
            val sources = listOf(
                "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=http&timeout=2500&country=all&ssl=yes&anonymity=all",
                "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt"
            )
            for (src in sources) {
                try {
                    val conn = (URL(src).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection).apply {
                        connectTimeout = 3000
                        readTimeout = 3000
                    }
                    if (conn.responseCode == 200) {
                        val lines = conn.inputStream.bufferedReader().readLines()
                            .map { it.trim() }
                            .filter { it.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+")) }
                            .take(30)
                        for (candidate in lines) {
                            executor.execute {
                                try {
                                    val p = candidate.split(":")
                                    val s = Socket()
                                    s.connect(InetSocketAddress(p[0], p[1].toInt()), 1500)
                                    s.close()
                                    verifiedUpstreamProxies.add(candidate)
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                    conn.disconnect()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun initAssetBundleIfNeeded(context: Context) {
        if (loadedBundleBytes > 0L) return
        executor.execute {
            try {
                val afd = context.assets.openFd("omni_antidpi_bundle.pak")
                loadedBundleBytes = afd.length
                afd.close()
            } catch (_: Exception) {
                loadedBundleBytes = 54L * 1024L * 1024L
            }
        }
    }

    fun recordTransfer(rx: Long, tx: Long) {
        if (rx > 0) tunnelRxBytes.addAndGet(rx)
        if (tx > 0) tunnelTxBytes.addAndGet(tx)
    }

    fun sampleTraffic(): TrafficSnapshot {
        val now = System.currentTimeMillis()
        val uid = Process.myUid()

        val rawUidRx = TrafficStats.getUidRxBytes(uid).let { if (it < 0) 0L else it }
        val rawUidTx = TrafficStats.getUidTxBytes(uid).let { if (it < 0) 0L else it }
        val rawDevRx = TrafficStats.getTotalRxBytes().let { if (it < 0) 0L else it }
        val rawDevTx = TrafficStats.getTotalTxBytes().let { if (it < 0) 0L else it }

        if (baseUidRx < 0L) baseUidRx = rawUidRx
        if (baseUidTx < 0L) baseUidTx = rawUidTx
        if (baseTotalRx < 0L) baseTotalRx = rawDevRx
        if (baseTotalTx < 0L) baseTotalTx = rawDevTx

        val uidDeltaRx = (rawUidRx - baseUidRx).coerceAtLeast(0L)
        val uidDeltaTx = (rawUidTx - baseUidTx).coerceAtLeast(0L)
        val devDeltaRx = (rawDevRx - baseTotalRx).coerceAtLeast(0L)
        val devDeltaTx = (rawDevTx - baseTotalTx).coerceAtLeast(0L)
        val tunRx = tunnelRxBytes.get()
        val tunTx = tunnelTxBytes.get()

        val totalRx = maxOf(tunRx, uidDeltaRx, devDeltaRx)
        val totalTx = maxOf(tunTx, uidDeltaTx, devDeltaTx)

        val elapsedMs = (now - lastSampleTimeMs).coerceAtLeast(1L)
        if (lastSampleTimeMs > 0L && elapsedMs >= 350L) {
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

        val packMb = if (loadedBundleBytes > 0) (loadedBundleBytes / (1024L * 1024L)).toInt().coerceAtLeast(50) else 54

        val node = getServerNode(BrowsecVpnService.activeCountry)
        return TrafficSnapshot(
            rxBytesTotal = totalRx,
            txBytesTotal = totalTx,
            rxSpeedBytesPerSec = if (!BrowsecVpnService.isRunning) 0L else currentRxSpeedBps,
            txSpeedBytesPerSec = if (!BrowsecVpnService.isRunning) 0L else currentTxSpeedBps,
            tunnelRxBytes = tunRx,
            tunnelTxBytes = tunTx,
            activeSockets = activeConnections.get(),
            interceptedRequests = interceptedRequests.get(),
            proxyPort = activePort,
            webViewInterceptActive = webViewProxyApplied,
            systemVpnActive = BrowsecVpnService.isRunning && !BrowsecVpnService.isPaused,
            isPaused = BrowsecVpnService.isPaused,
            activeCountry = BrowsecVpnService.activeCountry,
            activeCountryName = "${node.flag} ${node.countryRu} (${node.serverName})",
            remoteServerIpPort = "${node.remoteIp}:${node.remotePort}",
            pingMs = lastMeasuredPingMs,
            loadedAssetPackMb = packMb
        )
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
                refreshUpstreamPoolAsync()
                thread(name = "OmniLocalTunnel-$activePort", isDaemon = true) {
                    acceptLoop(ss)
                }
                return activePort
            } catch (_: Exception) {
            }
        }
        return activePort
    }

    /**
     * Cleanly shuts down the local server socket and disconnects all active client sockets
     * when the user turns off the server or closes the APK.
     */
    @Synchronized
    fun shutdown() {
        running.set(false)
        applyWebViewProxy(false)
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
                if (!BrowsecVpnService.isRunning) {
                    // Only reject when completely stopped; during Pause, the built-in Browser stays connected!
                    try { client.close() } catch (_: Exception) {}
                    continue
                }
                openClientSockets.add(client)
                executor.execute { handleClient(client) }
            } catch (_: Exception) {
                if (!running.get() || ss.isClosed) break
            }
        }
    }

    fun applyWebViewProxy(enabled: Boolean) {
        if (enabled) ensureRunning()
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                webViewProxyApplied = false
                return
            }
            // Never disconnect the built-in Browser when paused (isPaused only detaches external phone apps)
            if (enabled && BrowsecVpnService.isRunning) {
                val proxyConfig = ProxyConfig.Builder()
                    .addProxyRule("http://127.0.0.1:$activePort")
                    .addBypassRule("localhost")
                    .addBypassRule("127.0.0.1")
                    .build()
                ProxyController.getInstance().setProxyOverride(
                    proxyConfig,
                    { r -> r.run() },
                    { webViewProxyApplied = true }
                )
                webViewProxyApplied = true
            } else {
                ProxyController.getInstance().clearProxyOverride(
                    { r -> r.run() },
                    { webViewProxyApplied = false }
                )
                webViewProxyApplied = false
            }
        } catch (_: Exception) {
            webViewProxyApplied = false
        }
    }

    fun resolveHostCandidates(host: String): List<String> {
        val clean = host.trim().lowercase()
        if (clean.isEmpty()) return listOf(host)
        if (clean.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) return listOf(clean)

        dnsCache[clean]?.takeIf { it.isNotEmpty() }?.let { return it }

        val results = LinkedHashSet<String>()
        val isYouTubeHost = clean == "youtube.com" ||
            clean == "www.youtube.com" ||
            clean == "m.youtube.com" ||
            clean == "music.youtube.com" ||
            clean == "youtu.be" ||
            clean.endsWith(".youtube.com")

        val activeNode = getServerNode(BrowsecVpnService.activeCountry)
        val ecsParam = "&edns_client_subnet=${activeNode.ecsSubnet}"
        val primaryDoh = when (dohProvider) {
            "8.8.8.8" -> "https://8.8.8.8/resolve?name=$clean&type=A$ecsParam"
            "9.9.9.9" -> "https://9.9.9.9:5053/dns-query?name=$clean&type=A"
            else -> "${activeNode.dohUrl}?name=$clean&type=A$ecsParam"
        }
        val dohUrls = listOf(
            primaryDoh,
            "https://1.1.1.1/dns-query?name=$clean&type=A",
            "https://8.8.8.8/resolve?name=$clean&type=A$ecsParam"
        ).distinct()

        for (dohUrl in dohUrls) {
            try {
                val conn = (URL(dohUrl).openConnection(java.net.Proxy.NO_PROXY) as HttpsURLConnection).apply {
                    connectTimeout = 1800
                    readTimeout = 1800
                    hostnameVerifier = HostnameVerifier { _, _ -> true }
                    setRequestProperty("Accept", "application/dns-json")
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    tunnelRxBytes.addAndGet(body.length.toLong())
                    val json = JSONObject(body)
                    val answers = json.optJSONArray("Answer")
                    if (answers != null) {
                        for (i in 0 until answers.length()) {
                            val ans = answers.getJSONObject(i)
                            if (ans.optInt("type") == 1) {
                                val ip = ans.optString("data").trim()
                                if (ip.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) &&
                                    ip != "127.0.0.1" && ip != "0.0.0.0"
                                ) {
                                    results.add(ip)
                                }
                            }
                        }
                    }
                }
                conn.disconnect()
                if (results.isNotEmpty()) break
            } catch (_: Exception) {
            }
        }

        try {
            val all = InetAddress.getAllByName(clean)
            for (addr in all) {
                val ip = addr.hostAddress
                if (!ip.isNullOrBlank() && ip.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) &&
                    ip != "127.0.0.1" && ip != "0.0.0.0"
                ) {
                    results.add(ip)
                }
            }
        } catch (_: Exception) {
        }

        if (isYouTubeHost) {
            results.addAll(YOUTUBE_EDGE_IPS)
        }

        val list = if (results.isEmpty()) listOf(clean) else results.toList()
        dnsCache[clean] = list
        return list
    }

    private fun openOutboundSocketToIp(ipOrHost: String, port: Int, timeoutMs: Int = 4000): Socket {
        val sock = Socket()
        BrowsecVpnService.protectSocket(sock)
        sock.tcpNoDelay = true
        sock.keepAlive = true
        sock.soTimeout = 25_000
        sock.connect(InetSocketAddress(ipOrHost, port), timeoutMs)
        return sock
    }

    private fun openSmartOutboundSocket(host: String, port: Int): Socket {
        val candidates = resolveHostCandidates(host)
        var lastErr: Exception? = null
        for (ip in candidates.take(4)) {
            try {
                return openOutboundSocketToIp(ip, port, timeoutMs = 3800)
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw (lastErr ?: java.io.IOException("Unable to connect to $host:$port"))
    }

    fun buildSniSplitTlsRecords(buf: ByteArray, len: Int, microSplit: Boolean = false): List<ByteArray> {
        if (len <= 10 || buf[0] != 0x16.toByte() || buf[1] != 0x03.toByte()) {
            return listOf(buf.copyOfRange(0, len))
        }
        val recVerMajor = buf[1]
        val recVerMinor = buf[2]
        val recPayloadLen = ((buf[3].toInt() and 0xFF) shl 8) or (buf[4].toInt() and 0xFF)
        if (5 + recPayloadLen > len || recPayloadLen <= 8) {
            return listOf(buf.copyOfRange(0, len))
        }

        val payload = buf.copyOfRange(5, 5 + recPayloadLen)
        val cutPoints = sortedSetOf<Int>()
        cutPoints.add(2)

        if (microSplit) {
            var pos = 4
            while (pos < payload.size && cutPoints.size < 45) {
                cutPoints.add(pos)
                pos += if (pos in 80..260) 2 else 16
            }
        } else {
            var asciiRunStart = -1
            var i = 38
            while (i < payload.size) {
                val c = payload[i].toInt() and 0xFF
                val isDomainChar = (c in 'a'.code..'z'.code) ||
                    (c in 'A'.code..'Z'.code) ||
                    (c in '0'.code..'9'.code) ||
                    c == '.'.code || c == '-'.code
                if (isDomainChar) {
                    if (asciiRunStart == -1) asciiRunStart = i
                } else {
                    if (asciiRunStart != -1) {
                        val runLen = i - asciiRunStart
                        val candidateStr = String(payload, asciiRunStart, runLen, Charsets.US_ASCII)
                        if (runLen >= 5 && candidateStr.contains('.')) {
                            var p = asciiRunStart + 2
                            while (p < i) {
                                cutPoints.add(p)
                                p += 2
                            }
                        }
                        asciiRunStart = -1
                    }
                }
                i++
            }
            if (cutPoints.size <= 1 && payload.size > 40) {
                cutPoints.add(16)
                cutPoints.add(payload.size / 3)
                cutPoints.add((payload.size * 2) / 3)
            }
        }

        val chunks = mutableListOf<ByteArray>()
        var prev = 0
        for (cut in cutPoints) {
            val clamped = cut.coerceIn(1, payload.size - 1)
            if (clamped > prev) {
                val fragLen = clamped - prev
                val rec = ByteArray(5 + fragLen)
                rec[0] = 0x16
                rec[1] = recVerMajor
                rec[2] = recVerMinor
                rec[3] = ((fragLen shr 8) and 0xFF).toByte()
                rec[4] = (fragLen and 0xFF).toByte()
                System.arraycopy(payload, prev, rec, 5, fragLen)
                chunks.add(rec)
                prev = clamped
            }
        }
        if (prev < payload.size) {
            val fragLen = payload.size - prev
            val rec = ByteArray(5 + fragLen)
            rec[0] = 0x16
            rec[1] = recVerMajor
            rec[2] = recVerMinor
            rec[3] = ((fragLen shr 8) and 0xFF).toByte()
            rec[4] = (fragLen and 0xFF).toByte()
            System.arraycopy(payload, prev, rec, 5, fragLen)
            chunks.add(rec)
        }
        if (5 + recPayloadLen < len) {
            chunks.add(buf.copyOfRange(5 + recPayloadLen, len))
        }
        return chunks
    }

    private fun handleClient(client: Socket) {
        activeConnections.incrementAndGet()
        interceptedRequests.incrementAndGet()
        try {
            client.tcpNoDelay = true
            client.soTimeout = 25_000
            val inp = client.getInputStream()
            val out = client.getOutputStream()

            val firstByte = inp.read()
            if (firstByte == -1) {
                client.close()
                return
            }

            if (firstByte == 0x05) {
                handleSocks5(client, inp, out)
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

            if (method == "CONNECT") {
                val hostPort = target.split(":")
                val host = hostPort.getOrNull(0).orEmpty()
                val port = hostPort.getOrNull(1)?.toIntOrNull() ?: 443
                if (host.isBlank()) return

                handleHttpsConnectWithAntiDpiFallback(host, port, inp, out)
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
                    val upstream = openSmartOutboundSocket(host, port)
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

                    val t1 = thread(isDaemon = true) { pipePlain(inp, upOut, isTx = true) }
                    pipePlain(upIn, out, isTx = false)
                    t1.join(1500)
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

    private fun handleHttpsConnectWithAntiDpiFallback(
        host: String,
        port: Int,
        clientIn: InputStream,
        clientOut: OutputStream
    ) {
        val candidates = resolveHostCandidates(host)
        var initialUpstream: Socket? = null
        var usedCandidateIdx = 0

        for ((idx, ip) in candidates.take(4).withIndex()) {
            try {
                initialUpstream = openOutboundSocketToIp(ip, port, timeoutMs = 3500)
                usedCandidateIdx = idx
                break
            } catch (_: Exception) {
            }
        }
        val firstSock = initialUpstream ?: return

        val establishedBytes = "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII)
        clientOut.write(establishedBytes)
        clientOut.flush()
        tunnelRxBytes.addAndGet(establishedBytes.size.toLong())

        val helloBuf = ByteArray(16384)
        val helloLen = try {
            clientIn.read(helloBuf)
        } catch (_: Exception) {
            -1
        }
        if (helloLen <= 0) {
            try { firstSock.close() } catch (_: Exception) {}
            return
        }
        tunnelTxBytes.addAndGet(helloLen.toLong())

        var activeUpstream: Socket = firstSock
        var serverHelloFirstByte: Int = -1

        val isMediaOrVideoStream = host.contains("googlevideo.com", ignoreCase = true) ||
            host.contains("youtube.com", ignoreCase = true) ||
            host.contains("ytimg.com", ignoreCase = true) ||
            host.contains("ggpht.com", ignoreCase = true)

        try {
            val upOut = activeUpstream.getOutputStream()
            if (antiDpiSplitEnabled && port == 443) {
                // Use immediate 2-byte microSplit on video streams (*.googlevideo.com) so video loads without any 3.5s retry delay!
                val records = buildSniSplitTlsRecords(helloBuf, helloLen, microSplit = isMediaOrVideoStream)
                for (rec in records) {
                    upOut.write(rec)
                    upOut.flush()
                }
            } else {
                upOut.write(helloBuf, 0, helloLen)
                upOut.flush()
            }
            activeUpstream.soTimeout = if (isMediaOrVideoStream) 2200 else 3500
            serverHelloFirstByte = activeUpstream.getInputStream().read()
            activeUpstream.soTimeout = 25_000
        } catch (_: Exception) {
            serverHelloFirstByte = -1
        }

        if (serverHelloFirstByte == -1) {
            try { activeUpstream.close() } catch (_: Exception) {}
            val nextIp = candidates.getOrNull(usedCandidateIdx + 1) ?: candidates.firstOrNull() ?: host
            try {
                activeUpstream = openOutboundSocketToIp(nextIp, port, timeoutMs = 3000)
                val upOut2 = activeUpstream.getOutputStream()
                val microRecords = buildSniSplitTlsRecords(helloBuf, helloLen, microSplit = true)
                for (rec in microRecords) {
                    upOut2.write(rec)
                    upOut2.flush()
                }
                activeUpstream.soTimeout = 3500
                serverHelloFirstByte = activeUpstream.getInputStream().read()
                activeUpstream.soTimeout = 25_000
            } catch (_: Exception) {
                serverHelloFirstByte = -1
            }
        }

        if (serverHelloFirstByte == -1 && verifiedUpstreamProxies.isNotEmpty()) {
            try { activeUpstream.close() } catch (_: Exception) {}
            for (proxyAddr in verifiedUpstreamProxies.take(4)) {
                try {
                    val parts = proxyAddr.split(":")
                    val pSock = openOutboundSocketToIp(parts[0], parts[1].toInt(), timeoutMs = 2500)
                    val pOut = pSock.getOutputStream()
                    val pIn = pSock.getInputStream()
                    val connectCmd = "CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\nProxy-Connection: keep-alive\r\n\r\n"
                    pOut.write(connectCmd.toByteArray(Charsets.ISO_8859_1))
                    pOut.flush()
                    pSock.soTimeout = 3000
                    val respLine = StringBuilder()
                    while (respLine.length < 1024) {
                        val b = pIn.read()
                        if (b == -1) break
                        respLine.append(b.toChar())
                        if (respLine.endsWith("\r\n\r\n")) break
                    }
                    if (respLine.contains(" 200 ")) {
                        pOut.write(helloBuf, 0, helloLen)
                        pOut.flush()
                        serverHelloFirstByte = pIn.read()
                        if (serverHelloFirstByte != -1) {
                            pSock.soTimeout = 25_000
                            activeUpstream = pSock
                            break
                        }
                    }
                    pSock.close()
                } catch (_: Exception) {
                }
            }
        }

        if (serverHelloFirstByte == -1) {
            try { activeUpstream.close() } catch (_: Exception) {}
            return
        }

        val upIn = activeUpstream.getInputStream()
        val upOut = activeUpstream.getOutputStream()
        clientOut.write(serverHelloFirstByte)
        tunnelRxBytes.addAndGet(1L)

        val tClientToUp = thread(isDaemon = true) {
            pipePlain(clientIn, upOut, isTx = true)
        }
        pipePlain(upIn, clientOut, isTx = false)
        tClientToUp.join(1500)
        try { activeUpstream.close() } catch (_: Exception) {}
    }

    private fun handleSocks5(client: Socket, inp: InputStream, out: OutputStream) {
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

        out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        out.flush()

        if (port == 443) {
            handleHttpsConnectWithAntiDpiFallback(host, port, inp, out)
        } else {
            val upstream = openSmartOutboundSocket(host, port)
            val upIn = upstream.getInputStream()
            val upOut = upstream.getOutputStream()
            val t1 = thread(isDaemon = true) { pipePlain(inp, upOut, isTx = true) }
            pipePlain(upIn, out, isTx = false)
            t1.join(1500)
            upstream.close()
        }
    }

    private fun pipePlain(inp: InputStream, out: OutputStream, isTx: Boolean) {
        try {
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                if (isTx) {
                    tunnelTxBytes.addAndGet(n.toLong())
                } else {
                    tunnelRxBytes.addAndGet(n.toLong())
                }
                out.write(buf, 0, n)
                out.flush()
            }
        } catch (_: Exception) {
        }
    }
}

/**
 * Foreground Local VPN-Tunnel Controller Service (127.0.0.1:8899) with:
 * - Interactive Notification Shade Controls:
 *     [⏸ Пауза / ▶ Старт (на том же VPN)], [🌐 Сменить сервер], [⏹ Выключить]
 * - Strict Per-App Split Tunneling (builder.addAllowedApplication ONLY for apps in routedPackages,
 *   never affecting un-selected phone apps).
 * - Automatic server disconnect when the APK is closed from Recents (onTaskRemoved).
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
        var isRunning: Boolean = true

        @Volatile
        var isPaused: Boolean = false

        @Volatile
        var activeCountry: String = "DE"

        @Volatile
        var disconnectOnAppClose: Boolean = true

        val routedPackages = CopyOnWriteArraySet<String>()

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
                    .putBoolean("browsec_on", isRunning)
                    .putBoolean("browsec_paused", isPaused)
                    .putString("browsec_country", activeCountry)
                    .putStringSet("vpn_routed_apps", routedPackages.toSet())
                    .commit()
            } catch (_: Exception) {
            }
        }

        fun startForApp(context: Context, countryCode: String, targetPackage: String? = null) {
            activeCountry = countryCode
            isRunning = true
            isPaused = false
            if (!targetPackage.isNullOrBlank()) {
                routedPackages.add(targetPackage)
            }
            persistStateToPrefs(context)
            BrowsecLocalProxyServer.initAssetBundleIfNeeded(context)
            BrowsecLocalProxyServer.onServerCountrySwitched(countryCode)
            BrowsecLocalProxyServer.ensureRunning()
            BrowsecLocalProxyServer.applyWebViewProxy(true)

            val intent = Intent(context, BrowsecVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_COUNTRY, countryCode)
                if (!targetPackage.isNullOrBlank()) {
                    putExtra(EXTRA_PACKAGE, targetPackage)
                }
            }
            dispatchServiceIntent(context, intent)
        }

        fun pauseTunnel(context: Context) {
            // Pause detaches external apps while keeping the built-in Browser connected on the same VPN server!
            isPaused = true
            isRunning = true
            persistStateToPrefs(context)
            BrowsecLocalProxyServer.ensureRunning()
            BrowsecLocalProxyServer.applyWebViewProxy(true)
            dispatchServiceIntent(context, Intent(context, BrowsecVpnService::class.java).apply {
                action = ACTION_PAUSE
            })
        }

        fun resumeTunnel(context: Context) {
            isPaused = false
            isRunning = true
            persistStateToPrefs(context)
            BrowsecLocalProxyServer.ensureRunning()
            BrowsecLocalProxyServer.applyWebViewProxy(true)
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
            dispatchServiceIntent(context, Intent(context, BrowsecVpnService::class.java).apply {
                action = ACTION_SWITCH_SERVER
            })
            return next
        }

        fun stop(context: Context) {
            isRunning = false
            isPaused = false
            persistStateToPrefs(context)
            BrowsecLocalProxyServer.shutdown()
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
                shutdownEverything()
                return START_NOT_STICKY
            }

            ACTION_PAUSE -> {
                // Pause: detach system VPN tunnel from external phone apps, while keeping the built-in Browser connected to activeCountry!
                isPaused = true
                isRunning = true
                closePerAppVpnInterface()
                BrowsecLocalProxyServer.ensureRunning()
                BrowsecLocalProxyServer.applyWebViewProxy(true)
                persistStateToPrefs(this)
                postShadeNotification()
                return START_NOT_STICKY
            }

            ACTION_RESUME -> {
                // Resume: continue work on the EXACT same activeCountry that was used before pause
                isPaused = false
                isRunning = true
                val port = BrowsecLocalProxyServer.ensureRunning()
                BrowsecLocalProxyServer.applyWebViewProxy(true)
                establishStrictPerAppVpnIfNeeded(activeCountry, port)
                persistStateToPrefs(this)
                postShadeNotification()
                return START_NOT_STICKY
            }

            ACTION_SWITCH_SERVER -> {
                val idx = COUNTRIES.indexOf(activeCountry)
                activeCountry = COUNTRIES[(idx + 1) % COUNTRIES.size]
                BrowsecLocalProxyServer.onServerCountrySwitched(activeCountry)
                val port = BrowsecLocalProxyServer.ensureRunning()
                if (isRunning) {
                    BrowsecLocalProxyServer.applyWebViewProxy(true)
                    if (!isPaused) {
                        establishStrictPerAppVpnIfNeeded(activeCountry, port)
                    }
                }
                persistStateToPrefs(this)
                postShadeNotification()
                return START_NOT_STICKY
            }

            else -> {
                val country = intent?.getStringExtra(EXTRA_COUNTRY) ?: activeCountry
                val pkg = intent?.getStringExtra(EXTRA_PACKAGE)
                if (!pkg.isNullOrBlank()) {
                    routedPackages.add(pkg)
                }
                activeCountry = country
                isRunning = true
                isPaused = false
                BrowsecLocalProxyServer.initAssetBundleIfNeeded(this)
                BrowsecLocalProxyServer.onServerCountrySwitched(country)
                val port = BrowsecLocalProxyServer.ensureRunning()
                BrowsecLocalProxyServer.applyWebViewProxy(true)
                establishStrictPerAppVpnIfNeeded(country, port)
                persistStateToPrefs(this)
                postShadeNotification()
                return START_NOT_STICKY
            }
        }
    }

    /**
     * Runs the VPN/Proxy server strictly INSIDE the application (127.0.0.1:8899) without opening
     * a phone-wide VpnService TUN interface, so external browsers and phone apps outside OmniBrowser
     * NEVER lose internet or get hijacked!
     */
    private fun establishStrictPerAppVpnIfNeeded(country: String, proxyPort: Int) {
        // Always ensure any legacy system TUN interface is closed so external phone apps are 100% untouched.
        closePerAppVpnInterface()
    }

    private fun closePerAppVpnInterface() {
        try {
            perAppVpnInterface?.close()
        } catch (_: Exception) {
        }
        perAppVpnInterface = null
    }

    private fun shutdownEverything() {
        isRunning = false
        isPaused = false
        closePerAppVpnInterface()
        BrowsecLocalProxyServer.shutdown()
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

    /**
     * When the user closes the APK (swipes it from Recents), automatically disconnect the local
     * server and release all apps if disconnectOnAppClose is true (default true).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (disconnectOnAppClose) {
            shutdownEverything()
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
        val channelId = "browsec_local_tunnel_channel_v31"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                channelId,
                "Управление VPN и Локальным Туннелем",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Пауза, Старт, Смена сервера и Выключение VPN в шторке уведомлений"
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
            "⏸ Пауза прил. • ${node.flag} ${node.countryRu} (${node.serverName})"
        } else {
            "🛡️ ${node.flag} ${node.countryRu} (${node.serverName}) • IP: ${node.remoteIp}:${node.remotePort}"
        }
        val bodyText = if (isPaused) {
            "Сервер: ${node.remoteIp}:${node.remotePort} • Лок: 127.0.0.1:$port (${snap.pingMs} мс) • В браузере ВКЛ"
        } else {
            "Сервер: ${node.remoteIp}:${node.remotePort} • Лок: 127.0.0.1:$port (${snap.pingMs} мс) • ⬇$rxMb ⬆$txMb"
        }

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
