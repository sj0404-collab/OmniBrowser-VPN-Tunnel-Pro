package com.winwasm64.vpn

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import org.conscrypt.Conscrypt
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Поддержка современного TLS (TLS 1.2 + TLS 1.3 через Conscrypt BoringSSL) и актуальных корневых
 * сертификатов 2026 года (ISRG Root X1/X2 Let's Encrypt, GTS Root R1–R4 Google, DigiCert G2/G3/G5,
 * GlobalSign, USERTrust, SSL.com) на Android 5.0–9.0 (API 21–28).
 *
 * На Android 5–7 системное хранилище `/system/etc/security/cacerts` не содержит ISRG Root X1 и GTS Root,
 * из-за чего без этого модуля соединения с Browsec, DoH (`dns.google`, `1.1.1.1`), GitHub и сайтами
 * на Let's Encrypt падают с `CertPathValidatorException`.
 */
object ModernTls {
    private const val TAG = "TLS"

    @Volatile
    private var installed = false

    @Volatile
    private var modernFactory: SSLSocketFactory? = null

    @Volatile
    private var compositeTrustManager: X509TrustManager? = null

    private val verifiedWebHosts = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        try {
            // На Android 5–9 включаем Conscrypt (современный BoringSSL с TLS 1.3 и ChaCha20-Poly1305)
            var conscryptProviderLoaded = false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                try {
                    val provider = Conscrypt.newProvider()
                    if (Security.getProvider(provider.name) == null) {
                        Security.insertProviderAt(provider, 1)
                    }
                    conscryptProviderLoaded = true
                } catch (t: Throwable) {
                    VpnLog.w(TAG, "Conscrypt недоступен, используем системный TLS: ${t.message ?: t.javaClass.simpleName}")
                }
            }

            val sysTm = defaultX509TrustManager(null)
            val extraTm = loadBundledRootsTrustManager(app)
            val tm = if (sysTm != null && extraTm != null) {
                CompositeX509TrustManager(listOf(sysTm, extraTm))
            } else {
                sysTm ?: extraTm
            }
            compositeTrustManager = tm

            if (tm != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || extraTm != null)) {
                val sslCtx = try {
                    if (conscryptProviderLoaded) {
                        SSLContext.getInstance("TLS", "Conscrypt")
                    } else {
                        SSLContext.getInstance("TLS")
                    }
                } catch (_: Throwable) {
                    SSLContext.getInstance("TLS")
                }
                sslCtx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
                val wrapped = ProtocolEnablingSslSocketFactory(sslCtx.socketFactory)
                modernFactory = wrapped
                SSLContext.setDefault(sslCtx)
                HttpsURLConnection.setDefaultSSLSocketFactory(wrapped)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
                    VpnLog.ok(TAG, "современный TLS + корневые сертификаты активированы для Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                }
            }
        } catch (t: Throwable) {
            VpnLog.w(TAG, "инициализация TLS: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** SSLSocketFactory с поддержкой TLS 1.2/1.3 и современных корневых CA на Android 5+. */
    fun sslSocketFactory(): SSLSocketFactory {
        modernFactory?.let { return it }
        return ProtocolEnablingSslSocketFactory(SSLSocketFactory.getDefault() as SSLSocketFactory)
    }

    /** Применяет современную фабрику TLS к HttpsURLConnection (в т.ч. созданным через Network.openConnection). */
    fun applyTo(conn: HttpURLConnection) {
        if (conn is HttpsURLConnection) {
            modernFactory?.let { conn.sslSocketFactory = it }
        }
    }

    /** Включает TLS 1.2/1.3 и выставляет SNI совместимо с Android 5.0+ (без вызова SNIHostName на API < 24). */
    fun configureSocket(sslSock: SSLSocket, host: String) {
        enableModernProtocols(sslSock)
        if (host.isBlank()) return
        try {
            if (Conscrypt.isConscrypt(sslSock)) {
                Conscrypt.setHostname(sslSock, host)
                return
            }
        } catch (_: Throwable) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Api24Sni.apply(sslSock, host)
        } else {
            try {
                sslSock.javaClass.getMethod("setHostname", String::class.java).invoke(sslSock, host)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * На Android 5.0–7.1 (API 21–25) системный WebView не знает корневых сертификатов ISRG Root X1 / GTS Root
     * и вызывает `onReceivedSslError(SSL_UNTRUSTED)`. Строго проверяем реальную цепочку сертификатов и имя хоста
     * через наш `CompositeX509TrustManager` перед тем, как разрешить загрузку.
     */
    fun verifyWebViewUntrustedHost(host: String, port: Int = 443, localProxyPort: Int = -1): Boolean {
        if (host.isBlank()) return false
        val key = "${host.lowercase()}:$port"
        val now = System.currentTimeMillis()
        verifiedWebHosts[key]?.let { if (now - it < 30 * 60_000L) return true }
        var raw: Socket? = null
        var ssl: SSLSocket? = null
        return try {
            if (localProxyPort > 0) {
                val pSock = Socket()
                pSock.connect(InetSocketAddress("127.0.0.1", localProxyPort), 6_000)
                pSock.soTimeout = 7_000
                val connectReq = "CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\nProxy-Connection: keep-alive\r\n\r\n"
                pSock.getOutputStream().write(connectReq.toByteArray(Charsets.ISO_8859_1))
                pSock.getOutputStream().flush()
                val hdr = readHttpHeaderLine(pSock)
                if (!hdr.contains(" 200")) {
                    try { pSock.close() } catch (_: Exception) {}
                    raw = Socket()
                    raw.connect(InetSocketAddress(host, port), 6_000)
                } else {
                    raw = pSock
                }
            } else {
                raw = Socket()
                raw.connect(InetSocketAddress(host, port), 6_000)
            }
            raw.soTimeout = 7_000
            ssl = sslSocketFactory().createSocket(raw, host, port, true) as SSLSocket
            configureSocket(ssl, host)
            ssl.startHandshake()
            val ok = HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)
            if (ok) verifiedWebHosts[key] = now
            ok
        } catch (_: Throwable) {
            false
        } finally {
            try { ssl?.close() } catch (_: Exception) {}
            try { raw?.close() } catch (_: Exception) {}
        }
    }

    private fun readHttpHeaderLine(sock: Socket): String {
        val inp = sock.getInputStream()
        val sb = StringBuilder(128)
        var prev3 = 0; var prev2 = 0; var prev1 = 0
        var count = 0
        while (count < 4096) {
            val b = inp.read()
            if (b < 0) break
            sb.append(b.toChar())
            count++
            if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) break
            prev3 = prev2; prev2 = prev1; prev1 = b
        }
        return sb.lineSequence().firstOrNull().orEmpty()
    }

    internal fun enableModernProtocols(sock: SSLSocket) {
        try {
            val sup = sock.supportedProtocols ?: return
            val want = ArrayList<String>(4)
            if ("TLSv1.3" in sup) want.add("TLSv1.3")
            if ("TLSv1.2" in sup) want.add("TLSv1.2")
            if (want.isEmpty()) {
                for (p in sock.enabledProtocols ?: emptyArray()) {
                    if (!p.equals("SSLv3", ignoreCase = true)) want.add(p)
                }
            } else {
                for (p in sock.enabledProtocols ?: emptyArray()) {
                    if (p !in want && !p.equals("SSLv3", ignoreCase = true) &&
                        !p.equals("TLSv1", ignoreCase = true) && !p.equals("TLSv1.1", ignoreCase = true)
                    ) {
                        want.add(p)
                    }
                }
            }
            if (want.isNotEmpty()) {
                sock.enabledProtocols = want.toTypedArray()
            }
        } catch (_: Throwable) {
        }
    }

    internal fun loadBundledRootsTrustManager(context: Context): X509TrustManager? = try {
        context.assets.open("certs/modern_roots.pem").use { raw ->
            loadPemTrustManager(BufferedInputStream(raw))
        }
    } catch (_: Throwable) {
        null
    }

    internal fun loadPemTrustManager(input: java.io.InputStream): X509TrustManager? {
        val cf = CertificateFactory.getInstance("X.509")
        val certs = cf.generateCertificates(input)
        if (certs.isEmpty()) return null
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        var idx = 0
        for (cert in certs) {
            if (cert is X509Certificate) {
                ks.setCertificateEntry("modern_root_${idx++}", cert)
            }
        }
        if (idx == 0) return null
        return defaultX509TrustManager(ks)
    }

    private fun defaultX509TrustManager(ks: KeyStore?): X509TrustManager? = try {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        tmf.trustManagers?.firstOrNull { it is X509TrustManager } as? X509TrustManager
    } catch (_: Throwable) {
        null
    }

    internal class CompositeX509TrustManager(
        private val managers: List<X509TrustManager>
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            var lastErr: CertificateException? = null
            for (tm in managers) {
                try {
                    tm.checkClientTrusted(chain, authType)
                    return
                } catch (e: CertificateException) {
                    lastErr = e
                }
            }
            throw lastErr ?: CertificateException("No trust manager accepted client certificate")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            var lastErr: CertificateException? = null
            for (tm in managers) {
                try {
                    tm.checkServerTrusted(chain, authType)
                    return
                } catch (e: CertificateException) {
                    lastErr = e
                }
            }
            throw lastErr ?: CertificateException("No trust manager accepted server certificate")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            managers.flatMap { it.acceptedIssuers.toList() }.toTypedArray()
    }

    private class ProtocolEnablingSslSocketFactory(
        private val delegate: SSLSocketFactory
    ) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        private fun wrap(s: Socket): Socket {
            if (s is SSLSocket) enableModernProtocols(s)
            return s
        }

        override fun createSocket(): Socket = wrap(delegate.createSocket())
        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
            wrap(delegate.createSocket(s, host, port, autoClose))
        override fun createSocket(host: String?, port: Int): Socket =
            wrap(delegate.createSocket(host, port))
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            wrap(delegate.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: InetAddress?, port: Int): Socket =
            wrap(delegate.createSocket(host, port))
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            wrap(delegate.createSocket(address, port, localAddress, localPort))
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private object Api24Sni {
        fun apply(sslSock: SSLSocket, host: String) {
            try {
                val params: SSLParameters = sslSock.sslParameters ?: SSLParameters()
                params.serverNames = listOf(SNIHostName(host))
                sslSock.sslParameters = params
            } catch (_: Throwable) {
            }
        }
    }
}
