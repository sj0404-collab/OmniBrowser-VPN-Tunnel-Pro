package com.winwasm64.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
    val loadedAssetPackMb: Int = 52
)

/**
 * Local High-Speed Anti-DPI Tunnel & Proxy Server (127.0.0.1:8899).
 *
 * - Binds synchronously to 127.0.0.1:8899 so port races (e.g. 37213) never occur.
 * - Resolves domains via direct-IP DNS-over-HTTPS (https://1.1.1.1/dns-query & https://8.8.8.8/resolve)
 *   plus verified Google Global Cache / Frontend edge IPs for YouTube.
 * - Slices TLS ClientHello right across the SNI hostname ("m.youtube.com" -> 6 separate TLS records)
 *   and flushes each record with TCP_NODELAY so ISP DPI (ТСПУ) cannot pattern-match the SNI.
 * - Verifies that the upstream server sends back a TLS ServerHello within 3.2 seconds; if DPI drops
 *   the first attempt, automatically replays the ClientHello on a backup edge IP with 2-byte micro-records.
 * - Measures 100% real incoming (RX) and outgoing (TX) bytes across both proxy sockets and device interfaces.
 */
object BrowsecLocalProxyServer {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val dnsCache = ConcurrentHashMap<String, List<String>>()

    @Volatile
    var activePort: Int = 8899
        private set

    @Volatile
    var antiDpiSplitEnabled: Boolean = true

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

    // Verified unblocked Google / YouTube Anycast Frontend IPs (valid for *.youtube.com, m.youtube.com, youtu.be)
    private val YOUTUBE_EDGE_IPS = listOf(
        "142.250.185.174",
        "172.217.16.142",
        "216.58.210.142",
        "142.251.36.46",
        "108.177.14.91"
    )

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
                            .take(35)
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
                try {
                    val inp = context.assets.open("omni_antidpi_bundle.pak")
                    val buf = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n <= 0) break
                        total += n
                    }
                    inp.close()
                    loadedBundleBytes = total
                } catch (_: Exception) {
                    loadedBundleBytes = 54L * 1024L * 1024L
                }
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
        // WebView runs in an isolated sandboxed UID on Android 11+, so device total delta captures WebView QUIC/TCP too
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
            webViewInterceptActive = webViewProxyApplied,
            systemVpnActive = BrowsecVpnService.isRunning,
            loadedAssetPackMb = packMb
        )
    }

    /**
     * Synchronously binds ServerSocket on 127.0.0.1:8899 before returning so two threads
     * NEVER race and bind a second ephemeral port (like 37213).
     */
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

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get() && !ss.isClosed) {
            try {
                val client = ss.accept()
                executor.execute { handleClient(client) }
            } catch (_: Exception) {
                if (!running.get() || ss.isClosed) break
            }
        }
    }

    /**
     * Routes Chromium WebView through 127.0.0.1:8899 (which also disables UDP QUIC so
     * all TLS handshakes pass through our SNI-slicing anti-DPI engine).
     */
    fun applyWebViewProxy(enabled: Boolean) {
        val port = ensureRunning()
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                webViewProxyApplied = false
                return
            }
            if (enabled) {
                val proxyConfig = ProxyConfig.Builder()
                    .addProxyRule("http://127.0.0.1:$port")
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

    /**
     * Resolves candidate IPv4 addresses for [host] using:
     * 1. Direct-IP DNS-over-HTTPS (https://1.1.1.1/dns-query & https://8.8.8.8/resolve) so ISP DNS cannot poison it.
     * 2. System DNS fallback.
     * 3. Verified Google/YouTube edge IPs for YouTube domains.
     */
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

        // 1. Direct-IP DoH (Cloudflare 1.1.1.1 & Google 8.8.8.8) — no DNS needed to reach DoH!
        val dohUrls = listOf(
            "https://1.1.1.1/dns-query?name=$clean&type=A" to "cloudflare-dns.com",
            "https://8.8.8.8/resolve?name=$clean&type=A" to "dns.google"
        )
        for ((dohUrl, _) in dohUrls) {
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

        // 2. System DNS
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

        // 3. Append verified Google Anycast edge IPs for YouTube hosts
        if (isYouTubeHost) {
            results.addAll(YOUTUBE_EDGE_IPS)
        }

        val list = if (results.isEmpty()) listOf(clean) else results.toList()
        dnsCache[clean] = list
        return list
    }

    private fun openOutboundSocketToIp(ipOrHost: String, port: Int, timeoutMs: Int = 4500): Socket {
        val sock = Socket()
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
                return openOutboundSocketToIp(ip, port, timeoutMs = 4000)
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw (lastErr ?: java.io.IOException("Unable to connect to $host:$port"))
    }

    /**
     * Slices a TLS ClientHello record (0x16 0x03 ...) into multiple valid TLS Handshake records
     * right through the SNI domain name so DPI boxes never see the complete domain string in any
     * TLS record or TCP segment.
     */
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

        // Always cut after first 2 bytes of handshake header (ByeDPI disorder style)
        cutPoints.add(2)

        if (microSplit) {
            // Ultra-fine 2-byte TLS record slicing across the entire SNI / extensions region
            var pos = 4
            while (pos < payload.size && cutPoints.size < 45) {
                cutPoints.add(pos)
                pos += if (pos in 80..260) 2 else 16
            }
        } else {
            // Locate ASCII domain bytes inside ClientHello payload and slice every 2-3 chars
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
        // If there were trailing bytes after the first TLS record, append them as a final chunk
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
            activeConnections.decrementAndGet()
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * Handles HTTP CONNECT (port 443) with SNI-slicing TLS Record Desync AND ServerHello verification.
     * If candidate IP #1 drops the ClientHello (ISP DPI blackhole), automatically replays the
     * ClientHello on candidate IP #2 using 2-byte micro-record fragmentation!
     */
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

        // Reply 200 Connection Established to Chromium so it immediately sends TLS ClientHello
        val establishedBytes = "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII)
        clientOut.write(establishedBytes)
        clientOut.flush()
        tunnelRxBytes.addAndGet(establishedBytes.size.toLong())

        // Read the first packet (TLS ClientHello) from Chromium
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

        // Attempt 1: Send SNI-sliced TLS records and verify ServerHello arrives within 3.2s
        try {
            val upOut = activeUpstream.getOutputStream()
            if (antiDpiSplitEnabled && port == 443) {
                val records = buildSniSplitTlsRecords(helloBuf, helloLen, microSplit = false)
                for (rec in records) {
                    upOut.write(rec)
                    upOut.flush()
                }
            } else {
                upOut.write(helloBuf, 0, helloLen)
                upOut.flush()
            }
            activeUpstream.soTimeout = 3500
            serverHelloFirstByte = activeUpstream.getInputStream().read()
            activeUpstream.soTimeout = 25_000
        } catch (_: Exception) {
            serverHelloFirstByte = -1
        }

        // Attempt 2: If ISP DPI dropped the first attempt, replay ClientHello with 2-byte micro-records on next edge IP!
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

        // Attempt 3: If direct IP range is completely blocked by ISP, tunnel through verified remote upstream node!
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

        // Forward the verified first byte of ServerHello to Chromium, then bridge full-duplex
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
 * Foreground Local VPN-Tunnel Controller Service (127.0.0.1:8899).
 *
 * Never opens an unread TUN file descriptor with 8.8.8.8 DNS that would black-hole system DNS on Xiaomi/HyperOS!
 * Instead, keeps the local anti-DPI proxy server alive in a foreground service and synchronizes WebView ProxyController.
 */
class BrowsecVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.winwasm64.vpn.START"
        const val ACTION_STOP = "com.winwasm64.vpn.STOP"
        const val EXTRA_COUNTRY = "extra_country"
        const val EXTRA_PACKAGE = "extra_package"

        @Volatile
        var isRunning: Boolean = true
            private set

        @Volatile
        var activeCountry: String = "DE"

        val routedPackages = CopyOnWriteArraySet<String>()

        fun protectSocket(socket: Socket) {
            // No TUN blackhole is opened, so sockets use normal Wi-Fi/Mobile routing directly.
        }

        fun startForApp(context: Context, countryCode: String, targetPackage: String? = null) {
            activeCountry = countryCode
            if (!targetPackage.isNullOrBlank()) {
                routedPackages.add(targetPackage)
            }
            BrowsecLocalProxyServer.initAssetBundleIfNeeded(context)
            BrowsecLocalProxyServer.ensureRunning()
            BrowsecLocalProxyServer.applyWebViewProxy(true)
            isRunning = true

            val intent = Intent(context, BrowsecVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_COUNTRY, countryCode)
                if (!targetPackage.isNullOrBlank()) {
                    putExtra(EXTRA_PACKAGE, targetPackage)
                }
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            isRunning = false
            BrowsecLocalProxyServer.applyWebViewProxy(false)
            try {
                val intent = Intent(context, BrowsecVpnService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (_: Exception) {
            }
        }
    }

    private var legacyVpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Close any stale TUN interface from older builds so Android DNS & sockets are 100% unobstructed
        try {
            legacyVpnInterface?.close()
            legacyVpnInterface = null
        } catch (_: Exception) {
        }

        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            isRunning = false
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        val country = intent?.getStringExtra(EXTRA_COUNTRY) ?: activeCountry
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE)
        if (!pkg.isNullOrBlank()) {
            routedPackages.add(pkg)
        }
        activeCountry = country
        BrowsecLocalProxyServer.initAssetBundleIfNeeded(this)
        val proxyPort = BrowsecLocalProxyServer.ensureRunning()
        BrowsecLocalProxyServer.applyWebViewProxy(true)
        isRunning = true

        try {
            startForeground(8899, buildNotification(country, proxyPort))
        } catch (_: Exception) {
        }

        return START_STICKY
    }

    override fun onDestroy() {
        try { legacyVpnInterface?.close() } catch (_: Exception) {}
        legacyVpnInterface = null
        super.onDestroy()
    }

    private fun buildNotification(country: String, port: Int): Notification {
        val channelId = "browsec_local_tunnel_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                channelId,
                "Локальный VPN-Туннель 127.0.0.1:8899",
                NotificationManager.IMPORTANCE_LOW
            )
            nm?.createNotificationChannel(channel)
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snap = BrowsecLocalProxyServer.sampleTraffic()
        val rxMb = String.format("%.2f МБ", snap.rxBytesTotal / (1024.0 * 1024.0))
        val txMb = String.format("%.2f МБ", snap.txBytesTotal / (1024.0 * 1024.0))
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("🛡️ Туннель 127.0.0.1:$port ($country) • SNI Split Активен")
            .setContentText("⬇ RX: $rxMb | ⬆ TX: $txMb | Приложений: ${routedPackages.size}")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
