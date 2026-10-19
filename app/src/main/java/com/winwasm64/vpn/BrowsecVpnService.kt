package com.winwasm64.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Local Anti-DPI HTTP/HTTPS Proxy Server (127.0.0.1:8899)
 * Performs TLS ClientHello packet fragmentation (Anti-DPI) without black-holing
 * Android system traffic into an unread TUN descriptor.
 */
object BrowsecLocalProxyServer {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    var activePort: Int = 8899
        private set

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
                    thread(name = "BrowsecAntiDpiProxy", isDaemon = true) {
                        acceptLoop(ss)
                    }
                    return activePort
                } catch (_: Exception) {
                }
            }
        }
        return activePort
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

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 20_000
            val inp = client.getInputStream()
            val out = client.getOutputStream()

            val headerBuf = ByteArray(8192)
            var readLen = 0
            while (readLen < headerBuf.size) {
                val b = inp.read()
                if (b == -1) break
                headerBuf[readLen++] = b.toByte()
                if (readLen >= 4 &&
                    headerBuf[readLen - 4] == '\r'.code.toByte() &&
                    headerBuf[readLen - 3] == '\n'.code.toByte() &&
                    headerBuf[readLen - 2] == '\r'.code.toByte() &&
                    headerBuf[readLen - 1] == '\n'.code.toByte()
                ) {
                    break
                }
            }
            if (readLen <= 0) {
                client.close()
                return
            }

            val reqText = String(headerBuf, 0, readLen, Charsets.ISO_8859_1)
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
                val upstream = Socket()
                upstream.tcpNoDelay = true
                upstream.connect(InetSocketAddress(host, port), 15_000)

                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                out.flush()

                val upIn = upstream.getInputStream()
                val upOut = upstream.getOutputStream()

                val t1 = thread(isDaemon = true) {
                    pipeWithAntiDpiSplit(inp, upOut)
                }
                pipeStream(upIn, out)
                t1.join(2000)
                upstream.close()
            } else {
                val uri = java.net.URI(target)
                val host = uri.host ?: reqText.lines()
                    .firstOrNull { it.startsWith("Host:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()?.substringBefore(":") ?: ""
                val port = if (uri.port > 0) uri.port else 80
                if (host.isNotBlank()) {
                    val upstream = Socket()
                    upstream.tcpNoDelay = true
                    upstream.connect(InetSocketAddress(host, port), 15_000)
                    val upOut = upstream.getOutputStream()
                    upOut.write(headerBuf, 0, readLen)
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

    private fun pipeWithAntiDpiSplit(inp: InputStream, out: OutputStream) {
        try {
            val buf = ByteArray(16384)
            var firstPacket = true
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                if (firstPacket && n > 6 && buf[0] == 0x16.toByte()) {
                    out.write(buf, 0, 2)
                    out.flush()
                    out.write(buf, 2, 3)
                    out.flush()
                    out.write(buf, 5, n - 5)
                    out.flush()
                    firstPacket = false
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
 * Non-blocking Browsec Service controller.
 * NOTE: Never establishes a black-hole TUN interface that hijacks 0.0.0.0/0 or DNS!
 * If any legacy TUN interface was opened by v2.1.0, stopSelf() immediately closes it
 * so 100% of YouTube and native app traffic passes freely.
 */
class BrowsecVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always close any legacy TUN descriptor so Android never black-holes packets!
        closeTun()
        BrowsecLocalProxyServer.ensureRunning()
        stopSelf()
        return START_NOT_STICKY
    }

    private fun closeTun() {
        isRunning = false
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
    }

    override fun onDestroy() {
        closeTun()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false

        @Volatile
        var activeCountry: String = "NL"

        val routedPackages = CopyOnWriteArraySet<String>()

        fun startForApp(context: Context, country: String, targetPackage: String? = null) {
            activeCountry = country
            if (!targetPackage.isNullOrBlank()) {
                routedPackages.add(targetPackage)
            }
            BrowsecLocalProxyServer.ensureRunning()
            isRunning = true
            // Ensure any previous black-hole TUN interface is closed immediately
            stopSystemTunIfAny(context)
        }

        fun stop(context: Context) {
            isRunning = false
            stopSystemTunIfAny(context)
        }

        fun stopSystemTunIfAny(context: Context) {
            try {
                val intent = Intent(context, BrowsecVpnService::class.java)
                context.stopService(intent)
            } catch (_: Exception) {
            }
        }
    }
}
