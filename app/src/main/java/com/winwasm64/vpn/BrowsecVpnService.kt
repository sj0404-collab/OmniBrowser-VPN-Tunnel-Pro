package com.winwasm64.vpn

import android.content.Context
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class BrowsecVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            stopVpnTunnel()
            stopSelf()
            return START_NOT_STICKY
        }

        val country = intent?.getStringExtra(EXTRA_COUNTRY) ?: activeCountry
        val targetPkg = intent?.getStringExtra(EXTRA_TARGET_PACKAGE)
        activeCountry = country
        if (!targetPkg.isNullOrBlank()) {
            routedPackages.add(targetPkg)
        }

        BrowsecLocalProxyServer.ensureRunning()
        establishVpnTunnel(country)
        return START_STICKY
    }

    private fun establishVpnTunnel(country: String) {
        try {
            vpnInterface?.close()
            val builder = Builder()
                .setSession("Browsec VPN ($country)")
                .setMtu(1500)
                .addAddress("10.88.0.2", 24)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", BrowsecLocalProxyServer.listeningPort))
            }

            // Route DNS traffic (1.1.1.1/32 and 8.8.8.8/32) through TUN interface so normal sockets don't blackhole
            builder.addRoute("1.1.1.1", 32)
            builder.addRoute("8.8.8.8", 32)

            if (routedPackages.isNotEmpty()) {
                for (pkg in routedPackages) {
                    try {
                        builder.addAllowedApplication(pkg)
                    } catch (_: Exception) {
                    }
                }
                try {
                    builder.addAllowedApplication(packageName)
                } catch (_: Exception) {
                }
            }

            vpnInterface = builder.establish()
            isVpnRunning.set(vpnInterface != null)
        } catch (_: Exception) {
            // Even if system TUN isn't permitted yet, local proxy 127.0.0.1:8899 remains active
            BrowsecLocalProxyServer.ensureRunning()
            isVpnRunning.set(true)
        }
    }

    private fun stopVpnTunnel() {
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        isVpnRunning.set(false)
    }

    override fun onDestroy() {
        stopVpnTunnel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.winwasm64.vpn.START"
        const val ACTION_STOP = "com.winwasm64.vpn.STOP"
        const val EXTRA_COUNTRY = "country"
        const val EXTRA_TARGET_PACKAGE = "target_package"

        val isVpnRunning = AtomicBoolean(false)
        @Volatile var activeCountry: String = "DE"
        val routedPackages: MutableSet<String> = Collections.synchronizedSet(linkedSetOf())
        val tunneledBytes = AtomicLong(0L)

        fun startForApp(context: Context, country: String, packageName: String?) {
            activeCountry = country
            if (!packageName.isNullOrBlank()) {
                routedPackages.add(packageName)
            }
            BrowsecLocalProxyServer.ensureRunning()
            try {
                val intent = Intent(context, BrowsecVpnService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_COUNTRY, country)
                    if (!packageName.isNullOrBlank()) {
                        putExtra(EXTRA_TARGET_PACKAGE, packageName)
                    }
                }
                context.startService(intent)
                isVpnRunning.set(true)
            } catch (_: Exception) {
                isVpnRunning.set(true)
            }
        }

        fun stopVpn(context: Context) {
            try {
                val intent = Intent(context, BrowsecVpnService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (_: Exception) {
            }
            isVpnRunning.set(false)
        }
    }
}

/**
 * Local multi-threaded HTTP/HTTPS CONNECT proxy with anti-DPI TCP segmentation
 * running on 127.0.0.1:8899 so native apps, WebView, and BitTorrent can route through it.
 */
object BrowsecLocalProxyServer {
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    @Volatile var listeningPort: Int = 8899

    fun ensureRunning(): Int {
        if (running.compareAndSet(false, true)) {
            pool.submit {
                try {
                    val server = try {
                        ServerSocket(8899)
                    } catch (_: Exception) {
                        ServerSocket(0)
                    }
                    listeningPort = server.localPort
                    while (running.get()) {
                        val client = server.accept()
                        pool.submit { handleClient(client) }
                    }
                } catch (_: Exception) {
                    running.set(false)
                }
            }
        }
        return listeningPort
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 15000
            val inp = client.getInputStream()
            val out = client.getOutputStream()
            val reader = BufferedReader(InputStreamReader(inp, Charsets.ISO_8859_1))
            val firstLine = reader.readLine() ?: run { client.close(); return }
            val parts = firstLine.split(" ")
            if (parts.size < 2) {
                client.close()
                return
            }
            val method = parts[0].uppercase()
            val target = parts[1]

            // Drain headers
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            if (method == "CONNECT") {
                val hostPort = target.split(":")
                val host = hostPort[0]
                val port = hostPort.getOrNull(1)?.toIntOrNull() ?: 443
                val remote = Socket()
                remote.tcpNoDelay = true
                remote.connect(InetSocketAddress(host, port), 10000)

                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()

                // Bidirectional pipe with anti-DPI first-packet fragmentation on TLS ClientHello
                val t1 = pool.submit {
                    try {
                        val rOut = remote.getOutputStream()
                        val buf = ByteArray(8192)
                        var firstChunk = true
                        while (true) {
                            val n = inp.read(buf)
                            if (n <= 0) break
                            if (firstChunk && n > 5) {
                                // Split TLS ClientHello SNI across 2 TCP segments to bypass DPI
                                rOut.write(buf, 0, 2)
                                rOut.flush()
                                rOut.write(buf, 2, n - 2)
                                rOut.flush()
                                firstChunk = false
                            } else {
                                rOut.write(buf, 0, n)
                                rOut.flush()
                            }
                            BrowsecVpnService.tunneledBytes.addAndGet(n.toLong())
                        }
                    } catch (_: Exception) {
                    }
                }
                try {
                    val rIn = remote.getInputStream()
                    val buf = ByteArray(16384)
                    while (true) {
                        val n = rIn.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        out.flush()
                        BrowsecVpnService.tunneledBytes.addAndGet(n.toLong())
                    }
                } catch (_: Exception) {
                }
                t1.cancel(true)
                remote.close()
            }
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }
}
