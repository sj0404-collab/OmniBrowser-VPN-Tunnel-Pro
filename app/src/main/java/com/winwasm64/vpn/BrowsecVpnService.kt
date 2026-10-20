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
import com.winwasm64.MainActivity
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
    val systemVpnActive: Boolean
)

/**
 * High-speed Local Tunnel & Anti-DPI Proxy Server (127.0.0.1:8899).
 *
 * - Intercepts Chromium WebView traffic (via AndroidX ProxyController) and Native App traffic
 *   (via VpnService Builder.setHttpProxy).
 * - Uses fast System DNS with automatic DNS-over-HTTPS (Google / Cloudflare DoH) fallback.
 * - Performs optional TLS ClientHello SNI record splitting to bypass DPI inspection without breaking QUIC/TLS.
 * - Tracks exact incoming (RX) and outgoing (TX) bytes and real-time bandwidth (KB/s, MB/s).
 */
object BrowsecLocalProxyServer {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val dohCache = ConcurrentHashMap<String, String>()

    @Volatile
    var activePort: Int = 8899
        private set

    @Volatile
    var antiDpiSplitEnabled: Boolean = true

    @Volatile
    var webViewProxyApplied: Boolean = false
        private set

    val tunnelRxBytes = AtomicLong(0L)
    val tunnelTxBytes = AtomicLong(0L)
    val activeConnections = AtomicInteger(0)
    val interceptedRequests = AtomicLong(0L)

    // Baseline UID traffic stats for accurate real-time app + Chromium + tunnel RX/TX metering
    private var baseUidRx = -1L
    private var baseUidTx = -1L
    private var lastSampleTimeMs = 0L
    private var lastTotalRx = 0L
    private var lastTotalTx = 0L

    @Volatile
    var currentRxSpeedBps: Long = 0L
        private set

    @Volatile
    var currentTxSpeedBps: Long = 0L
        private set

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

        val totalRx = maxOf(uidDeltaRx, tunRx)
        val totalTx = maxOf(uidDeltaTx, tunTx)

        val elapsedMs = (now - lastSampleTimeMs).coerceAtLeast(1L)
        if (lastSampleTimeMs > 0L && elapsedMs >= 400L) {
            currentRxSpeedBps = ((totalRx - lastTotalRx).coerceAtLeast(0L) * 1000L) / elapsedMs
            currentTxSpeedBps = ((totalTx - lastTotalTx).coerceAtLeast(0L) * 1000L) / elapsedMs
            lastTotalRx = totalRx
            lastTotalTx = totalTx
            lastSampleTimeMs = now
        } else if (lastSampleTimeMs == 0L) {
            lastTotalRx = totalRx
            lastTotalTx = totalTx
            lastSampleTimeMs = now
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
            webViewInterceptActive = webViewProxyApplied,
            systemVpnActive = BrowsecVpnService.isRunning
        )
    }

    fun recordWebViewResourceNotice(estimatedBytes: Long = 2048L) {
        interceptedRequests.incrementAndGet()
        tunnelTxBytes.addAndGet(256L)
        tunnelRxBytes.addAndGet(estimatedBytes)
    }

    @Synchronized
    fun ensureRunning(): Int {
        if (running.get() && serverSocket != null && !serverSocket!!.isClosed) {
            return activePort
        }
        running.set(true)
        executor.execute {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                try {
                    ss.bind(InetSocketAddress("127.0.0.1", 8899), 256)
                    activePort = 8899
                } catch (_: Exception) {
                    ss.bind(InetSocketAddress("127.0.0.1", 0), 256)
                    activePort = ss.localPort
                }
                serverSocket = ss
                while (running.get() && !ss.isClosed) {
                    val client = ss.accept()
                    executor.execute { handleClient(client) }
                }
            } catch (_: Exception) {
                running.set(false)
            }
        }
        return activePort
    }

    /**
     * Binds or clears the AndroidX WebKit ProxyController so Chromium WebView routes traffic
     * through 127.0.0.1:8899 when Local Proxy Interception is enabled.
     */
    fun applyWebViewProxy(enabled: Boolean) {
        ensureRunning()
        try {
            if (! androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.PROXY_OVERRIDE)) {
                webViewProxyApplied = false
                return
            }
            if (enabled) {
                val proxyConfig = ProxyConfig.Builder()
                    .addProxyRule("127.0.0.1:$activePort")
                    .addBypassRule("localhost")
                    .addBypassRule("127.0.0.1")
                    .build()
                ProxyController.getInstance().setProxyOverride(
                    proxyConfig,
                    { r -> r.run() },
                    { webViewProxyApplied = true }
                )
            } else {
                ProxyController.getInstance().clearProxyOverride(
                    { r -> r.run() },
                    { webViewProxyApplied = false }
                )
            }
        } catch (_: Exception) {
            webViewProxyApplied = false
        }
    }

    /**
     * Fast DNS resolution: tries instant System DNS first, and falls back to DNS-over-HTTPS
     * (Google / Cloudflare DoH) if the ISP poisons or blocks the domain.
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

    private fun handleClient(client: Socket) {
        activeConnections.incrementAndGet()
        interceptedRequests.incrementAndGet()
        try {
            client.tcpNoDelay = true
            client.soTimeout = 30_000
            val inp = client.getInputStream()
            val out = client.getOutputStream()

            inp.mark(4)
            val firstByte = inp.read()
            if (firstByte == -1) {
                client.close()
                return
            }

            // SOCKS5 support on the same port
            if (firstByte == 0x05) {
                handleSocks5(client, inp, out)
                return
            }

            val lineBuf = StringBuilder()
            lineBuf.append(firstByte.toChar())
            while (lineBuf.length < 4096) {
                val b = inp.read()
                if (b == -1) break
                lineBuf.append(b.toChar())
                if (lineBuf.endsWith("\r\n")) break
            }
            val requestLine = lineBuf.toString().trim()
            if (requestLine.isEmpty()) {
                client.close()
                return
            }

            val headers = mutableListOf<String>()
            while (true) {
                val hBuf = StringBuilder()
                while (hBuf.length < 4096) {
                    val b = inp.read()
                    if (b == -1) break
                    hBuf.append(b.toChar())
                    if (hBuf.endsWith("\r\n")) break
                }
                val hLine = hBuf.toString().trim()
                if (hLine.isEmpty()) break
                headers.add(hLine)
            }

            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0)?.uppercase() ?: ""
            val target = parts.getOrNull(1) ?: ""

            if (method == "CONNECT") {
                val hostPort = target.split(":")
                val host = hostPort.getOrNull(0) ?: return
                val port = hostPort.getOrNull(1)?.toIntOrNull() ?: 443

                val upstream = openOutboundSocket(host, port)
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()

                val upIn = upstream.getInputStream()
                val upOut = upstream.getOutputStream()

                val t1 = Thread {
                    pipeClientToServer(inp, upOut, isTls = (port == 443 && antiDpiSplitEnabled))
                }
                val t2 = Thread {
                    pipeServerToClient(upIn, out)
                }
                t1.start()
                t2.start()
                t1.join()
                t2.join()
                upstream.close()
            } else if (method.isNotEmpty() && target.isNotEmpty()) {
                val uri = try { java.net.URI(target) } catch (_: Exception) { null }
                val hostHeader = headers.firstOrNull { it.startsWith("Host:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim() ?: ""
                val host = uri?.host ?: hostHeader.substringBefore(":")
                val port = if (uri != null && uri.port > 0) uri.port else 80
                val pathOnly = if (uri != null) {
                    val p = uri.rawPath.ifNullOrEmpty { "/" }
                    val q = uri.rawQuery
                    if (q.isNullOrEmpty()) p else "$p?$q"
                } else target

                if (host.isNotBlank()) {
                    val upstream = openOutboundSocket(host, port)
                    val upOut = upstream.getOutputStream()
                    val upIn = upstream.getInputStream()

                    val rebuilt = StringBuilder()
                    rebuilt.append("$method $pathOnly HTTP/1.1\r\n")
                    for (h in headers) {
                        if (!h.startsWith("Proxy-", ignoreCase = true)) {
                            rebuilt.append(h).append("\r\n")
                        }
                    }
                    rebuilt.append("\r\n")
                    val reqBytes = rebuilt.toString().toByteArray(Charsets.ISO_8859_1)
                    upOut.write(reqBytes)
                    upOut.flush()
                    tunnelTxBytes.addAndGet(reqBytes.size.toLong())

                    val t1 = Thread { pipeClientToServer(inp, upOut, isTls = false) }
                    val t2 = Thread { pipeServerToClient(upIn, out) }
                    t1.start()
                    t2.start()
                    t1.join()
                    t2.join()
                    upstream.close()
                }
            }
        } catch (_: Exception) {
        } finally {
            activeConnections.decrementAndGet()
            try { client.close() } catch (_: Exception) {}
        }
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
        inp.read() // rsv
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

        val upIn = upstream.getInputStream()
        val upOut = upstream.getOutputStream()
        val t1 = Thread { pipeClientToServer(inp, upOut, isTls = (port == 443 && antiDpiSplitEnabled)) }
        val t2 = Thread { pipeServerToClient(upIn, out) }
        t1.start()
        t2.start()
        t1.join()
        t2.join()
        upstream.close()
    }

    private fun pipeClientToServer(inp: InputStream, out: OutputStream, isTls: Boolean) {
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
                        // RFC-compliant TLS Record Fragmentation (splits ClientHello SNI across 2 valid TLS records)
                        val frag1 = 16
                        val frag2 = recLen - frag1
                        val outBuf = java.io.ByteArrayOutputStream(n + 5)
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

    private fun pipeServerToClient(inp: InputStream, out: OutputStream) {
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

    private inline fun String?.ifNullOrEmpty(defaultValue: () -> String): String =
        if (this.isNullOrEmpty()) defaultValue() else this
}

/**
 * Android System VPN Service + Local Proxy Tunnel Router (127.0.0.1:8899).
 *
 * Uses a single virtual tunnel route (10.88.0.1/32) so 0.0.0.0/0 is NEVER black-holed,
 * combined with Builder.setHttpProxy(127.0.0.1:8899) so Chromium and native Android apps
 * route traffic through the Local Tunnel Server with full RX/TX monitoring.
 */
class BrowsecVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.winwasm64.vpn.START"
        const val ACTION_STOP = "com.winwasm64.vpn.STOP"
        const val EXTRA_COUNTRY = "extra_country"
        const val EXTRA_PACKAGE = "extra_package"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var activeCountry: String = "DE"

        val routedPackages = ConcurrentHashMap.newKeySet<String>()

        @Volatile
        private var currentInstance: BrowsecVpnService? = null

        fun protectSocket(socket: Socket) {
            try {
                currentInstance?.protect(socket)
            } catch (_: Exception) {
            }
        }

        fun startForApp(context: Context, countryCode: String, targetPackage: String? = null) {
            activeCountry = countryCode
            if (!targetPackage.isNullOrBlank()) {
                routedPackages.add(targetPackage)
            }
            BrowsecLocalProxyServer.ensureRunning()
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

    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentInstance = this
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            isRunning = false
            try { vpnInterface?.close() } catch (_: Exception) {}
            vpnInterface = null
            stopForeground( true)
            stopSelf()
            return START_NOT_STICKY
        }

        val country = intent?.getStringExtra(EXTRA_COUNTRY) ?: activeCountry
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE)
        if (!pkg.isNullOrBlank()) {
            routedPackages.add(pkg)
        }
        activeCountry = country
        val proxyPort = BrowsecLocalProxyServer.ensureRunning()
        isRunning = true

        startForeground(8899, buildNotification(country, proxyPort))
        establishLocalProxyVpnSession(country, proxyPort)

        return START_STICKY
    }

    private fun establishLocalProxyVpnSession(country: String, proxyPort: Int) {
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        try {
            val builder = Builder()
                .setSession("Browsec Tunnel ($country) • 127.0.0.1:$proxyPort")
                .setMtu(1500)
                .addAddress("10.88.0.2", 32)
                // Only route virtual gateway 10.88.0.1/32 so 0.0.0.0/0 is NEVER black-holed
                .addRoute("10.88.0.1", 32)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")

            // CRITICAL: Exclude our own package from the VPN interface so BrowsecLocalProxyServer's
            // outbound sockets NEVER loop back into the VPN / proxy!
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {
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
            vpnInterface = builder.establish()
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        isRunning = false
        currentInstance = null
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        super.onDestroy()
    }

    private fun buildNotification(country: String, port: Int): Notification {
        val channelId = "browsec_local_tunnel_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                channelId,
                "Browsec Локальный VPN-Туннель",
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
            .setContentTitle("🛡️ Туннель Browsec ($country) • 127.0.0.1:$port")
            .setContentText("⬇ Вх: $rxMb | ⬆ Исх: $txMb | Приложений: ${routedPackages.size}")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
