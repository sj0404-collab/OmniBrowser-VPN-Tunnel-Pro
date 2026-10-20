package com.winwasm64.vpn

import android.content.Context
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

data class UpstreamProxyNode(
    val host: String,
    val port: Int,
    val isSocks: Boolean = false
)

/**
 * Local Anti-DPI + DoH + Upstream Proxy Tunnel Server (127.0.0.1:8899).
 *
 * 1) Resolves blocked domains via DNS-over-HTTPS (Google DoH / Cloudflare DoH)
 * 2) Applies ByeDPI-style TLS ClientHello SNI fragmentation (splits right across the SNI hostname)
 * 3) Falls back to live verified HTTP/SOCKS5 upstream proxies if direct IP connection is blocked by ISP
 * 4) Configures WebView ProxyController so WebView traffic automatically flows through 127.0.0.1:8899
 */
object BrowsecLocalProxyServer {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val dohCache = ConcurrentHashMap<String, String>()
    private val refreshingProxies = AtomicBoolean(false)
    private val proxyCursor = AtomicInteger(0)

    var activePort: Int = 8899
        private set

    @Volatile
    var forceUpstreamProxy: Boolean = false

    val upstreamProxies = CopyOnWriteArrayList<UpstreamProxyNode>(
        listOf(
            UpstreamProxyNode("176.111.37.216", 39811, false),
            UpstreamProxyNode("176.111.37.5", 39811, false),
            UpstreamProxyNode("130.110.103.245", 3128, false),
            UpstreamProxyNode("185.191.239.248", 3128, false),
            UpstreamProxyNode("190.94.212.247", 999, false),
            UpstreamProxyNode("43.251.205.146", 8080, false)
        )
    )

    fun ensureRunning(): Int {
        if (running.get() && serverSocket?.isClosed == false) return activePort
        synchronized(this) {
            if (running.get() && serverSocket?.isClosed == false) return activePort
            for (candidatePort in listOf(8899, 8898, 10809, 0)) {
                try {
                    val ss = ServerSocket()
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress("127.0.0.1", candidatePort))
                    serverSocket = ss
                    activePort = ss.localPort
                    running.set(true)
                    thread(name = "BrowsecAntiDpiAccept", isDaemon = true) {
                        acceptLoop(ss)
                    }
                    refreshUpstreamProxyPoolAsync()
                    return activePort
                } catch (_: Exception) {
                }
            }
        }
        return activePort
    }

    fun applyWebViewProxy(enabled: Boolean) {
        val port = ensureRunning()
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                if (enabled) {
                    val proxyConfig = ProxyConfig.Builder()
                        .addProxyRule("127.0.0.1:$port")
                        .addBypassRule("localhost")
                        .addBypassRule("127.0.0.1")
                        .build()
                    ProxyController.getInstance().setProxyOverride(
                        proxyConfig,
                        { r -> r.run() },
                        {}
                    )
                } else {
                    ProxyController.getInstance().clearProxyOverride({ r -> r.run() }, {})
                }
            }
        } catch (_: Exception) {
        }
    }

    fun refreshUpstreamProxyPoolAsync() {
        if (!refreshingProxies.compareAndSet(false, true)) return
        thread(name = "BrowsecProxyPoolRefresh", isDaemon = true) {
            try {
                val feeds = listOf(
                    "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/http/data.txt" to false,
                    "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt" to false,
                    "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt" to true
                )
                val candidates = mutableListOf<UpstreamProxyNode>()
                for ((feedUrl, isSocks) in feeds) {
                    try {
                        val conn = (URL(feedUrl).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 5000
                            readTimeout = 5000
                            setRequestProperty("User-Agent", "Mozilla/5.0")
                        }
                        if (conn.responseCode == 200) {
                            val text = conn.inputStream.bufferedReader().readText()
                            text.lineSequence()
                                .map { it.trim().removePrefix("http://").removePrefix("socks5://") }
                                .filter { it.contains(":") }
                                .take(25)
                                .forEach { line ->
                                    val h = line.substringBefore(":")
                                    val p = line.substringAfter(":").toIntOrNull()
                                    if (h.isNotBlank() && p != null && p in 1..65535) {
                                        candidates.add(UpstreamProxyNode(h, p, isSocks))
                                    }
                                }
                        }
                    } catch (_: Exception) {
                    }
                }
                // Verify up to 20 candidates quickly
                for (cand in candidates.take(24)) {
                    try {
                        val s = Socket()
                        s.connect(InetSocketAddress(cand.host, cand.port), 1800)
                        s.close()
                        if (upstreamProxies.none { it.host == cand.host && it.port == cand.port }) {
                            upstreamProxies.add(0, cand)
                        }
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            } finally {
                refreshingProxies.set(false)
            }
        }
    }

    fun resolveHostViaDoh(host: String): String {
        if (host.isBlank()) return host
        if (host[0].isDigit()) return host
        dohCache[host]?.let { return it }

        val dohEndpoints = listOf(
            "https://dns.google/resolve?name=${URLEncoder.encode(host, "UTF-8")}&type=A",
            "https://1.1.1.1/dns-query?name=${URLEncoder.encode(host, "UTF-8")}&type=A"
        )
        for (ep in dohEndpoints) {
            try {
                val conn = (URL(ep).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2500
                    readTimeout = 2500
                    setRequestProperty("Accept", "application/dns-json")
                }
                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val ans = json.optJSONArray("Answer")
                    if (ans != null) {
                        for (i in 0 until ans.length()) {
                            val item = ans.getJSONObject(i)
                            if (item.optInt("type") == 1) {
                                val ip = item.optString("data", "")
                                if (ip.isNotBlank()) {
                                    dohCache[host] = ip
                                    return ip
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return try {
            val ip = InetAddress.getByName(host).hostAddress ?: host
            dohCache[host] = ip
            ip
        } catch (_: Exception) {
            host
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get() && !ss.isClosed) {
            try {
                val client = ss.accept()
                pool.execute { handleClient(client) }
            } catch (_: Exception) {
                if (!running.get()) break
            }
        }
    }

    private fun openSmartUpstreamSocket(host: String, port: Int): Pair<Socket, Boolean> {
        // 1. Try direct connection to DoH-resolved IP with Anti-DPI TCP_NODELAY (unless forceUpstreamProxy is set)
        if (!forceUpstreamProxy) {
            try {
                val resolvedIp = resolveHostViaDoh(host)
                val direct = Socket()
                direct.tcpNoDelay = true
                direct.connect(InetSocketAddress(resolvedIp, port), 3200)
                return direct to false
            } catch (_: Exception) {
            }
        }

        // 2. Fallback: Connect via live verified upstream proxy pool
        val poolList = upstreamProxies.toList()
        for (attempt in 0 until minOf(4, poolList.size)) {
            val idx = (proxyCursor.getAndIncrement() and 0x7FFFFFFF) % poolList.size
            val node = poolList[idx]
            try {
                if (node.isSocks) {
                    val socksProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(node.host, node.port))
                    val s = Socket(socksProxy)
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress.createUnresolved(host, port), 4500)
                    return s to false
                } else {
                    val s = Socket()
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(node.host, node.port), 3500)
                    s.soTimeout = 12_000
                    val pOut = s.getOutputStream()
                    val pIn = s.getInputStream()
                    val connectReq = "CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\nProxy-Connection: keep-alive\r\n\r\n"
                    pOut.write(connectReq.toByteArray(Charsets.ISO_8859_1))
                    pOut.flush()
                    val respLine = readHttpHeader(pIn)
                    if (respLine.contains(" 200 ")) {
                        return s to true
                    }
                    s.close()
                }
            } catch (_: Exception) {
            }
        }

        // 3. Final fallback: system DNS socket
        val fallback = Socket()
        fallback.tcpNoDelay = true
        fallback.connect(InetSocketAddress(host, port), 6000)
        return fallback to false
    }

    private fun readHttpHeader(inp: InputStream): String {
        val bos = ByteArrayOutputStream(256)
        while (bos.size() < 8192) {
            val b = inp.read()
            if (b == -1) break
            bos.write(b)
            val arr = bos.toByteArray()
            val n = arr.size
            if (n >= 4 &&
                arr[n - 4] == '\r'.code.toByte() &&
                arr[n - 3] == '\n'.code.toByte() &&
                arr[n - 2] == '\r'.code.toByte() &&
                arr[n - 1] == '\n'.code.toByte()
            ) {
                break
            }
        }
        return String(bos.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 25_000
            client.tcpNoDelay = true
            val inp = client.getInputStream()
            val out = client.getOutputStream()

            val reqText = readHttpHeader(inp)
            if (reqText.isBlank()) {
                client.close()
                return
            }
            val firstLine = reqText.lineSequence().firstOrNull() ?: ""
            val parts = firstLine.split(" ")
            if (parts.size < 2) {
                client.close()
                return
            }
            val method = parts[0].uppercase()
            val target = parts[1]

            if (method == "CONNECT") {
                val hostPort = target.split(":")
                val host = hostPort[0]
                val port = hostPort.getOrNull(1)?.toIntOrNull() ?: 443

                val (upstream, _) = openSmartUpstreamSocket(host, port)
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                out.flush()

                val upIn = upstream.getInputStream()
                val upOut = upstream.getOutputStream()

                val t1 = thread(isDaemon = true) {
                    pipeWithAntiDpiSplit(inp, upOut, host)
                }
                pipeStream(upIn, out)
                t1.join(1500)
                upstream.close()
            } else {
                val uri = try { URI(target) } catch (_: Exception) { null }
                val host = uri?.host ?: reqText.lines()
                    .firstOrNull { it.startsWith("Host:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()?.substringBefore(":") ?: ""
                val port = if (uri != null && uri.port > 0) uri.port else 80
                if (host.isNotBlank()) {
                    val (upstream, _) = openSmartUpstreamSocket(host, port)
                    val upOut = upstream.getOutputStream()
                    val pathOnly = if (uri != null && uri.rawPath != null) {
                        uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
                    } else target
                    val rewrittenFirst = "$method $pathOnly HTTP/1.1"
                    val rewrittenReq = rewrittenFirst + "\r\n" + reqText.substringAfter("\r\n")
                    upOut.write(rewrittenReq.toByteArray(Charsets.ISO_8859_1))
                    upOut.flush()
                    val t1 = thread(isDaemon = true) { pipeStream(inp, upOut) }
                    pipeStream(upstream.getInputStream(), out)
                    t1.join(1000)
                    upstream.close()
                }
            }
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * ByeDPI / PowerTunnel TLS ClientHello SNI Splitter:
     * Splits the first TLS Handshake record (0x16) into multiple small TCP segments
     * specifically cutting right inside the plaintext SNI domain name so ISP DPI
     * cannot reassemble or match the blocked domain signature.
     */
    private fun pipeWithAntiDpiSplit(inp: InputStream, out: OutputStream, host: String) {
        try {
            val buf = ByteArray(16384)
            var firstPacket = true
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                if (firstPacket && n > 48 && buf[0] == 0x16.toByte()) {
                    firstPacket = false
                    // Locate SNI hostname inside TLS ClientHello if present
                    val hostBytes = host.toByteArray(Charsets.US_ASCII)
                    var sniMid = -1
                    if (hostBytes.size >= 4) {
                        outer@ for (i in 40 until (n - hostBytes.size)) {
                            for (j in hostBytes.indices) {
                                if (buf[i + j] != hostBytes[j]) continue@outer
                            }
                            sniMid = i + (hostBytes.size / 2)
                            break
                        }
                    }
                    if (sniMid in 10 until (n - 2)) {
                        // Split 1: first 2 bytes of TLS header
                        out.write(buf, 0, 2)
                        out.flush()
                        // Split 2: up to middle of SNI hostname
                        out.write(buf, 2, sniMid - 2)
                        out.flush()
                        // Split 3: 2 bytes inside SNI hostname
                        out.write(buf, sniMid, 2)
                        out.flush()
                        // Split 4: remainder of ClientHello
                        out.write(buf, sniMid + 2, n - (sniMid + 2))
                        out.flush()
                    } else {
                        out.write(buf, 0, 1)
                        out.flush()
                        out.write(buf, 1, 4)
                        out.flush()
                        out.write(buf, 5, 24)
                        out.flush()
                        out.write(buf, 29, n - 29)
                        out.flush()
                    }
                } else {
                    out.write(buf, 0, n)
                    out.flush()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun pipeStream(inp: InputStream, out: OutputStream) {
        try {
            val buf = ByteArray(16384)
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                out.flush()
            }
        } catch (_: Exception) {
        }
    }
}

/**
 * Android System VpnService for Browsec VPN.
 *
 * CRITICAL FIX:
 * - Establishes a system VPN session (`🔑` icon) with `Builder.setHttpProxy(127.0.0.1:8899)`
 *   so Android routes HTTP/HTTPS traffic for WebView and allowed apps through BrowsecLocalProxyServer.
 * - Routes ONLY a single virtual IP (`10.88.0.1/32`) to `tun0` — NEVER `0.0.0.0/0` — so raw
 *   UDP/TCP packets are NEVER swallowed into a black hole!
 */
class BrowsecVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            stopVpnInternal()
            stopSelf()
            return START_NOT_STICKY
        }

        val country = intent?.getStringExtra(EXTRA_COUNTRY) ?: activeCountry
        activeCountry = country
        val proxyPort = BrowsecLocalProxyServer.ensureRunning()
        BrowsecLocalProxyServer.applyWebViewProxy(true)

        try {
            vpnInterface?.close()
            val builder = Builder()
                .setSession("Browsec VPN ($country)")
                .setMtu(1500)
                .addAddress("10.88.0.2", 32)
                // Only route virtual IP 10.88.0.1/32 to tun0 so 0.0.0.0/0 is NEVER black-holed!
                .addRoute("10.88.0.1", 32)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setHttpProxy(
                    ProxyInfo.buildDirectProxy(
                        "127.0.0.1",
                        proxyPort,
                        listOf("localhost", "127.0.0.1")
                    )
                )
            }

            if (routedPackages.isNotEmpty()) {
                try { builder.addAllowedApplication(packageName) } catch (_: Exception) {}
                for (pkg in routedPackages) {
                    try { builder.addAllowedApplication(pkg) } catch (_: Exception) {}
                }
            }

            vpnInterface = builder.establish()
            isRunning = vpnInterface != null

            val fd = vpnInterface?.fileDescriptor
            if (fd != null) {
                thread(name = "BrowsecTunDrain", isDaemon = true) {
                    val buf = ByteArray(2048)
                    val fis = java.io.FileInputStream(fd)
                    try {
                        while (isRunning) {
                            val r = fis.read(buf)
                            if (r <= 0) Thread.sleep(100)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
            isRunning = false
        }

        return START_STICKY
    }

    private fun stopVpnInternal() {
        isRunning = false
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
    }

    override fun onDestroy() {
        stopVpnInternal()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.winwasm64.vpn.START"
        const val ACTION_STOP = "com.winwasm64.vpn.STOP"
        const val EXTRA_COUNTRY = "extra_country"

        @Volatile
        var isRunning: Boolean = false

        @Volatile
        var activeCountry: String = "DE"

        val routedPackages = CopyOnWriteArraySet<String>()

        fun startForApp(context: Context, country: String, targetPackage: String? = null) {
            activeCountry = country
            if (!targetPackage.isNullOrBlank()) {
                routedPackages.add(targetPackage)
            }
            BrowsecLocalProxyServer.ensureRunning()
            BrowsecLocalProxyServer.applyWebViewProxy(true)
            try {
                val intent = Intent(context, BrowsecVpnService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_COUNTRY, country)
                }
                context.startService(intent)
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            isRunning = false
            BrowsecLocalProxyServer.applyWebViewProxy(false)
            stopSystemTunIfAny(context)
        }

        fun stopSystemTunIfAny(context: Context) {
            try {
                val intent = Intent(context, BrowsecVpnService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (_: Exception) {
            }
        }
    }
}
