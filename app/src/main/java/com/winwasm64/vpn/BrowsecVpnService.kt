package com.winwasm64.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.ProxyInfo
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.winwasm64.MainActivity
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread
import kotlin.random.Random
import org.json.JSONObject

data class VpnServerNodeInfo(
    val code: String,
    val flag: String,
    val countryRu: String,
    val serverName: String,
    val remoteIp: String,
    val remotePort: Int,
    val ecsSubnet: String,
    val dohUrl: String,
    val defaultStrategy: AntiDpiStrategy = AntiDpiStrategy.MICRO_SHATTER_SNI
)

/**
 * Настройки обхода DPI / ТСПУ для каждого сервера:
 * - MICRO_SHATTER_SNI: 3-байтовая микро-нарезка TLS ClientHello (YouTube / GoogleVideo с 1-го раза + любые блокируемые сайты)
 * - TLS_RECORD_2SPLIT: 2-фрагментный RFC TLS сплит посередине SNI (идеально для Cloudflare / Gofile / Discord)
 * - PURE_TCP_SEGMENT_DESYNC: чистая TCP-сегментация без изменения заголовков TLS (идеально для Telegram MTProto DC1-DC5)
 * - CLEAN_IP_DIRECT_DOH: прямое подключение к чистому Anycast IP через резервный DoH
 */
enum class AntiDpiStrategy(val titleRu: String, val shortRu: String) {
    MICRO_SHATTER_SNI("Micro-Split 3B SNI + DoH (YouTube/Web)", "Micro-SNI (Исходный)"),
    TLS_RECORD_2SPLIT("2-Frag RFC TLS SNI Split (Cloudflare/Gofile)", "TLS 2-Split"),
    PURE_TCP_SEGMENT_DESYNC("TCP-сегментация без изменения TLS (Telegram/MTProto)", "TCP-Desync"),
    CLEAN_IP_DIRECT_DOH("Прямой Anycast Clean-IP + резервный DoH", "Прямой Clean-IP")
}

/**
 * Индивидуальное состояние КАЖДОГО из 22 серверов:
 * - собственный счётчик входящих (rxBytes) и исходящих (txBytes) байт
 * - собственная текущая скорость входящего (rxSpeedBps) и исходящего (txSpeedBps) трафика
 * - исходные настройки (originalStrategy, originalDoh), чтобы при возврате или переходе
 *   на другой сервер возвращать прошлый сервер строго в исходное состояние!
 */
class ServerNodeRuntimeState(
    val node: VpnServerNodeInfo,
    seedIndex: Int = 0
) {
    val originalStrategy: AntiDpiStrategy = node.defaultStrategy
    val originalDoh: String = if (node.dohUrl.contains("8.8.8.8")) "8.8.8.8" else "1.1.1.1"

    @Volatile
    var currentStrategy: AntiDpiStrategy = originalStrategy

    @Volatile
    var currentDoh: String = originalDoh

    @Volatile
    var tuningStep: Int = 0

    // Счётчики начинаются с нуля и растут только от реально переданных через профиль байт
    val rxBytes = AtomicLong(0L)
    val txBytes = AtomicLong(0L)
    val requestsCount = AtomicLong(0L)
    val activeConnections = AtomicInteger(0)
    val consecutiveFailures = AtomicInteger(0)
    val lastRxTimestampMs = AtomicLong(System.currentTimeMillis())
    val lastAutoTuneTimestampMs = AtomicLong(0L)

    @Volatile
    var rxSpeedBps: Long = 0L

    @Volatile
    var txSpeedBps: Long = 0L

    /** Реально измеренная задержка (мс). 0 — ещё не измерялась. */
    @Volatile
    var pingMs: Int = 0

    /** Результат последней реальной проверки профиля: null — не проверялся. */
    @Volatile
    var lastProbeOk: Boolean? = null

    @Volatile
    var lastProbeAtMs: Long = 0L

    val lastPingMs: Int
        get() = pingMs

    @Volatile
    var statusNoteRu: String = "Не проверялся • ${originalStrategy.shortRu}"

    private var lastSampleMs: Long = 0L
    private var lastSampleRx: Long = rxBytes.get()
    private var lastSampleTx: Long = txBytes.get()
    private var baseSysUidRx: Long = -1L
    private var baseSysUidTx: Long = -1L

    fun recordRx(bytes: Long) {
        if (bytes > 0L) {
            rxBytes.addAndGet(bytes)
            lastRxTimestampMs.set(System.currentTimeMillis())
            consecutiveFailures.set(0)
        }
    }

    fun recordTx(bytes: Long) {
        if (bytes > 0L) {
            txBytes.addAndGet(bytes)
        }
    }

    fun syncUidTrafficDelta(isActiveServer: Boolean) {
        if (!isActiveServer) {
            baseSysUidRx = -1L
            baseSysUidTx = -1L
            return
        }
        try {
            val uid = Process.myUid()
            val uRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
            val uTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
            if (baseSysUidRx < 0L) {
                baseSysUidRx = uRx
                baseSysUidTx = uTx
                return
            }
            val dRx = (uRx - baseSysUidRx).coerceAtLeast(0L)
            val dTx = (uTx - baseSysUidTx).coerceAtLeast(0L)
            if (dRx > 0L) {
                rxBytes.addAndGet(dRx)
                baseSysUidRx = uRx
                lastRxTimestampMs.set(System.currentTimeMillis())
            }
            if (dTx > 0L) {
                txBytes.addAndGet(dTx)
                baseSysUidTx = uTx
            }
        } catch (_: Exception) {
        }
    }

    fun updateSpeedSample(isActiveServer: Boolean = false) {
        // Байты считают сами ретрансляторы (recordRx/recordTx), поэтому общий трафик UID
        // больше не прибавляется — иначе каждый байт учитывался дважды.
        val now = System.currentTimeMillis()
        val curRx = rxBytes.get()
        val curTx = txBytes.get()
        if (lastSampleMs == 0L) {
            lastSampleMs = now
            lastSampleRx = curRx
            lastSampleTx = curTx
            return
        }
        val elapsed = (now - lastSampleMs).coerceAtLeast(1L)
        if (elapsed >= 450L) {
            val measuredRxBps = ((curRx - lastSampleRx).coerceAtLeast(0L) * 1000L) / elapsed
            val measuredTxBps = ((curTx - lastSampleTx).coerceAtLeast(0L) * 1000L) / elapsed
            // Только реально измеренная скорость — без подставных «пульсов» 960/340 Б/с
            rxSpeedBps = measuredRxBps
            txSpeedBps = measuredTxBps
            lastSampleRx = curRx
            lastSampleTx = curTx
            lastSampleMs = now
        }
    }

    fun resetTrafficCounters() {
        rxBytes.set(0L)
        txBytes.set(0L)
        rxSpeedBps = 0L
        txSpeedBps = 0L
        lastSampleMs = System.currentTimeMillis()
        lastSampleRx = 0L
        lastSampleTx = 0L
        baseSysUidRx = -1L
        baseSysUidTx = -1L
    }

    /**
     * Возвращает этот сервер строго в исходное состояние, каким он был до авто-смены настроек!
     */
    fun restoreToOriginalState() {
        currentStrategy = originalStrategy
        currentDoh = originalDoh
        tuningStep = 0
        consecutiveFailures.set(0)
        statusNoteRu = "🟢 Возвращён в исходное состояние (${originalStrategy.shortRu}, DNS $originalDoh)"
    }

    /**
     * Переключает настройки текущего сервера на следующий шаг.
     * Возвращает true, если применилась новая настройка на этом же сервере,
     * или false, если все 3 шага настроек уже испробованы и пора переходить на другой сервер
     * (с возвратом этого сервера в исходное состояние).
     */
    fun advanceTuningSetting(): Boolean {
        val nextStep = tuningStep + 1
        lastAutoTuneTimestampMs.set(System.currentTimeMillis())
        return when (nextStep) {
            1 -> {
                tuningStep = 1
                currentStrategy = AntiDpiStrategy.TLS_RECORD_2SPLIT
                currentDoh = "8.8.8.8"
                statusNoteRu = "⚙️ Проверка настройки [1/3]: ${currentStrategy.shortRu} + DNS $currentDoh"
                true
            }
            2 -> {
                tuningStep = 2
                currentStrategy = AntiDpiStrategy.PURE_TCP_SEGMENT_DESYNC
                currentDoh = "9.9.9.9"
                statusNoteRu = "⚙️ Проверка настройки [2/3]: ${currentStrategy.shortRu} + DNS $currentDoh"
                true
            }
            3 -> {
                tuningStep = 3
                currentStrategy = AntiDpiStrategy.CLEAN_IP_DIRECT_DOH
                currentDoh = "1.1.1.1"
                statusNoteRu = "⚙️ Проверка настройки [3/3]: ${currentStrategy.shortRu} + DNS $currentDoh"
                true
            }
            else -> {
                false
            }
        }
    }
}

/**
 * Индивидуальный туннельный профиль для КАЖДОГО выбранного приложения:
 * у каждого приложения свой собственный сервер (countryCode), свой локальный порт
 * (127.0.0.1:8901..8949) и свой отдельный счётчик входящего/исходящего трафика и скорости!
 */
class AppTunnelProfile(
    val packageName: String,
    @Volatile var countryCode: String,
    val localPort: Int
) {
    val rxBytes = AtomicLong(0L)
    val txBytes = AtomicLong(0L)
    val activeSockets = AtomicInteger(0)
    val requests = AtomicLong(0L)

    // v4.2.14: точные замеры по приложению — время активности (когда реально шёл трафик), соединения, задержка, сервер
    @Volatile var firstActivityAt = 0L
    @Volatile var lastActivityAt = 0L
    val activeMs = AtomicLong(0L)
    val connOk = AtomicLong(0L)
    val connFail = AtomicLong(0L)
    val connMsSum = AtomicLong(0L)
    val tunnelConns = AtomicLong(0L)
    @Volatile var lastServer: String? = null
    @Volatile var lastError: String? = null
    @Volatile var lastErrorAt = 0L
    @Volatile var activityReportedAt = 0L
    @Volatile var activityStartRef: Triple<Long, Long, Long> = Triple(0L, 0L, 0L)
    @Volatile var connRef = 0L
    @Volatile var connFailRef = 0L
    // накопленное «за всё время» (из настроек) и ещё не записанные дельты
    @Volatile var allTimeRx = 0L
    @Volatile var allTimeTx = 0L
    @Volatile var allTimeActiveMs = 0L
    @Volatile var allTimeConns = 0L
    @Volatile var flushedRx = 0L
    @Volatile var flushedTx = 0L
    @Volatile var flushedActiveMs = 0L
    @Volatile var flushedConns = 0L

    fun noteConn(ok: Boolean, connectMs: Long, server: String?, err: String?) {
        if (ok) { connOk.incrementAndGet(); if (connectMs > 0) connMsSum.addAndGet(connectMs) } else { connFail.incrementAndGet(); lastError = err; lastErrorAt = System.currentTimeMillis() }
        if (server != null) { lastServer = server; tunnelConns.incrementAndGet() }
    }

    /** Средняя задержка установления соединения, мс. */
    fun avgConnectMs(): Long { val n = connOk.get(); return if (n > 0) connMsSum.get() / n else 0L }

    @Volatile
    var rxSpeedBps: Long = 0L

    @Volatile
    var txSpeedBps: Long = 0L

    @Volatile
    var strategy: AntiDpiStrategy = AntiDpiStrategy.MICRO_SHATTER_SNI

    private var lastSampleMs: Long = 0L
    private var lastRx: Long = rxBytes.get()
    private var lastTx: Long = txBytes.get()

    fun updateSpeed() {
        val now = System.currentTimeMillis()
        val curRx = rxBytes.get()
        val curTx = txBytes.get()
        if (lastSampleMs == 0L) {
            lastSampleMs = now
            lastSampleRx = curRx
            lastSampleTx = curTx
            return
        }
        val elapsed = (now - lastSampleMs).coerceAtLeast(1L)
        if (elapsed >= 450L) {
            val rBps = ((curRx - lastSampleRx).coerceAtLeast(0L) * 1000L) / elapsed
            val tBps = ((curTx - lastSampleTx).coerceAtLeast(0L) * 1000L) / elapsed
            rxSpeedBps = rBps
            txSpeedBps = tBps
            if (curRx != lastSampleRx || curTx != lastSampleTx) {
                // v4.2.14: трафик шёл — засчитываем интервал как активность (не больше 3 с за выборку)
                activeMs.addAndGet(elapsed.coerceAtMost(3_000L))
                if (firstActivityAt == 0L) firstActivityAt = now
                lastActivityAt = now
            }
            lastSampleRx = curRx
            lastSampleTx = curTx
            lastSampleMs = now
        }
    }

    private var lastSampleRx: Long = rxBytes.get()
    private var lastSampleTx: Long = txBytes.get()
}

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
    val activeCountryName: String = "Литва (Vilnius LT-Baltic #1)",
    val remoteServerIpPort: String = "185.150.189.201:443",
    val pingMs: Int = 18,
    val loadedAssetPackMb: Int = 0,
    val browsecTunnelModeActive: Boolean = true,
    val currentStrategyLabel: String = "Micro-SNI (Исходный)",
    val autoHealStatusNote: String = "🟢 Активен • Исходный режим",
    val currentStrategyName: String = currentStrategyLabel,
    val watchdogStatusText: String = autoHealStatusNote
)

/**
 * Полный список из 22 стран (Литва, Латвия, Эстония, Польша, Германия, Нидерланды, Финляндия и др.)
 */
val ALL_VPN_COUNTRIES_LIST: List<VpnServerNodeInfo> = listOf(
    VpnServerNodeInfo("LT", "🇱🇹", "Литва", "Vilnius LT-Baltic #1", "185.150.189.201", 443, "185.150.189.0/24", "https://1.1.1.1/dns-query"),
    VpnServerNodeInfo("LV", "🇱🇻", "Латвия", "Riga LV-Baltic #1", "91.198.156.42", 443, "91.198.156.0/24", "https://1.1.1.1/dns-query"),
    VpnServerNodeInfo("EE", "🇪🇪", "Эстония", "Tallinn EE-Baltic #1", "185.197.105.78", 443, "185.197.105.0/24", "https://1.1.1.1/dns-query"),
    VpnServerNodeInfo("PL", "🇵🇱", "Польша", "Warsaw PL-Central #1", "185.238.72.19", 443, "185.238.72.0/24", "https://1.1.1.1/dns-query"),
    VpnServerNodeInfo("DE", "🇩🇪", "Германия", "Frankfurt DE-Core #1", "185.220.101.45", 443, "185.220.101.0/24", "https://1.1.1.1/dns-query"),
    VpnServerNodeInfo("NL", "🇳🇱", "Нидерланды", "Amsterdam NL-Core #2", "94.142.241.111", 443, "94.142.241.0/24", "https://1.1.1.1/dns-query"),
    VpnServerNodeInfo("FI", "🇫🇮", "Финляндия", "Helsinki FI-Nordic #1", "95.216.195.133", 443, "95.216.195.0/24", "https://1.1.1.1/dns-query"),
    VpnServerNodeInfo("SE", "🇸🇪", "Швеция", "Stockholm SE-Nordic #1", "185.213.155.94", 443, "185.213.155.0/24", "https://1.1.1.1/dns-query"),
    VpnServerNodeInfo("FR", "🇫🇷", "Франция", "Paris FR-West #1", "51.158.108.44", 443, "51.158.108.0/24", "https://1.1.1.1/dns-query"),
    VpnServerNodeInfo("UK", "🇬🇧", "Британия", "London UK-Docklands #2", "51.89.153.210", 443, "51.89.153.0/24", "https://1.1.1.1/dns-query"),
    VpnServerNodeInfo("CH", "🇨🇭", "Швейцария", "Zurich CH-Alpine #1", "185.156.175.34", 443, "185.156.175.0/24", "https://1.1.1.1/dns-query"),
    VpnServerNodeInfo("ES", "🇪🇸", "Испания", "Madrid ES-South #1", "185.253.162.55", 443, "185.253.162.0/24", "https://1.1.1.1/dns-query"),
    VpnServerNodeInfo("IT", "🇮🇹", "Италия", "Milan IT-South #1", "185.216.34.81", 443, "185.216.34.0/24", "https://1.1.1.1/dns-query"),
    VpnServerNodeInfo("CZ", "🇨🇿", "Чехия", "Prague CZ-Central #1", "185.101.215.63", 443, "185.101.215.0/24", "https://1.1.1.1/dns-query"),
    VpnServerNodeInfo("RO", "🇷🇴", "Румыния", "Bucharest RO-East #1", "89.40.181.120", 443, "89.40.181.0/24", "https://1.1.1.1/dns-query"),
    VpnServerNodeInfo("UA", "🇺🇦", "Украина", "Kyiv UA-East #1", "185.65.244.18", 443, "185.65.244.0/24", "https://1.1.1.1/dns-query"),
    VpnServerNodeInfo("US", "🇺🇸", "США", "New York US-East #4", "104.244.72.115", 443, "104.244.72.0/24", "https://8.8.8.8/resolve"),
    VpnServerNodeInfo("CA", "🇨🇦", "Канада", "Toronto CA-North #1", "158.69.211.48", 443, "158.69.211.0/24", "https://8.8.8.8/resolve"),
    VpnServerNodeInfo("TR", "🇹🇷", "Турция", "Istanbul TR-Bridge #1", "185.125.33.92", 443, "185.125.33.0/24", "https://1.1.1.1/dns-query"),
    VpnServerNodeInfo("KZ", "🇰🇿", "Казахстан", "Almaty KZ-Steppe #1", "91.215.136.44", 443, "91.215.136.0/24", "https://1.1.1.1/dns-query"),
    VpnServerNodeInfo("JP", "🇯🇵", "Япония", "Tokyo JP-Pacific #1", "45.76.211.108", 443, "45.76.211.0/24", "https://8.8.8.8/resolve"),
    VpnServerNodeInfo("SG", "🇸🇬", "Сингапур", "Singapore SG-Asia #3", "139.99.61.88", 443, "139.99.61.0/24", "https://8.8.8.8/resolve")
)

/**
 * Умный резолвер и пул резервных Anycast IP:
 * 1) Сначала запрашивает живой IP через DoH (с европейским ECS выбранной страны) + быстрый UDP DNS (1.1.1.1 / 8.8.8.8) + системный DNS.
 * 2) Возвращает упорядоченный список кандидатов (живой DoH IP + резервные чистые Anycast IP для YouTube / Gofile / Telegram),
 *    чтобы при недоступности одного IP сокет мгновенно переключался на следующий рабочий IP без `ERR_TIMED_OUT`!
 */
object FastMultiDnsResolver {
    private val cache = ConcurrentHashMap<String, String>()

    val YOUTUBE_CLEAN_ANYCAST_IPS = listOf(
        "142.250.185.174",
        "142.250.186.110",
        "172.217.16.142",
        "216.58.210.142",
        "142.251.36.46",
        "142.250.74.110",
        "142.250.107.113"
    )

    val GOFILE_FALLBACK_IPS = listOf(
        "172.67.74.152",
        "104.21.48.204",
        "104.16.132.229"
    )

    val TELEGRAM_FALLBACK_IPS = listOf(
        "149.154.167.99",
        "149.154.167.50",
        "149.154.175.50"
    )

    fun clearCache() {
        cache.clear()
    }

    fun isIpv4Literal(host: String): Boolean {
        return host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))
    }

    fun isYoutubeWebHost(host: String): Boolean {
        val h = host.lowercase()
        return h == "m.youtube.com" ||
            h == "www.youtube.com" ||
            h == "youtube.com" ||
            h == "youtu.be" ||
            h == "youtubei.googleapis.com" ||
            h == "i.ytimg.com" ||
            h == "s.ytimg.com" ||
            h == "yt3.ggpht.com" ||
            h == "redirector.googlevideo.com" ||
            h.endsWith(".youtube.com") ||
            h.endsWith(".ytimg.com") ||
            h.endsWith(".ggpht.com")
    }

    fun isCloudflareOrGofileHost(host: String): Boolean {
        val h = host.lowercase()
        return h.contains("gofile.io") ||
            h.contains("cloudflare") ||
            h.contains("rutracker.org") ||
            h.contains("discord.com") ||
            h.contains("discordapp.com")
    }

    fun isTelegramHostOrIp(host: String): Boolean {
        val h = host.trim().lowercase()
        return h.startsWith("149.154.") ||
            h.startsWith("91.108.") ||
            h.startsWith("95.161.") ||
            h.startsWith("67.198.") ||
            h == "t.me" ||
            h.endsWith(".t.me") ||
            h == "telegram.org" ||
            h.endsWith(".telegram.org") ||
            h.endsWith(".telesco.pe") ||
            h.endsWith(".cdn-telegram.org") ||
            h.endsWith(".telegram-cdn.org")
    }

    fun resolveFast(
        host: String,
        ecsSubnet: String = "185.150.189.0/24",
        dohServer: String = "1.1.1.1",
        onTraffic: ((tx: Long, rx: Long) -> Unit)? = null
    ): String {
        val clean = host.trim().lowercase()
        if (clean.isEmpty()) return host
        if (isIpv4Literal(clean)) return clean
        cache[clean]?.let { return it }

        // 1. Быстрый запрос через DoH (Google с европейским ECS выбранной страны + Cloudflare) — точно как в v3.6.0
        val dohEndpoints = listOf(
            "https://dns.google/resolve?name=$clean&type=A&edns_client_subnet=$ecsSubnet",
            "https://1.1.1.1/dns-query?name=$clean&type=A"
        )
        for (url in dohEndpoints) {
            try {
                onTraffic?.invoke(210L, 0L)
                val conn = (URL(url).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection).apply {
                    connectTimeout = 1600
                    readTimeout = 1600
                    setRequestProperty("Accept", "application/dns-json")
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    onTraffic?.invoke(0L, body.length.toLong())
                    val json = JSONObject(body)
                    val answers = json.optJSONArray("Answer")
                    if (answers != null) {
                        for (i in 0 until answers.length()) {
                            val ans = answers.getJSONObject(i)
                            if (ans.optInt("type") == 1) {
                                val ip = ans.optString("data").trim()
                                if (isIpv4Literal(ip) && ip != "127.0.0.1" && ip != "0.0.0.0" && !ip.startsWith("10.")) {
                                    cache[clean] = ip
                                    return ip
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 2. ВАЖНО (как в v3.6.0!): для YouTube НИКОГДА не обращаемся к незашифрованному UDP 53 или системному DNS провайдера,
        // чтобы ТСПУ не подсунул отравленный/блокируемый IP, вызывающий net::ERR_CONNECTION_CLOSED!
        if (isYoutubeWebHost(clean)) {
            val anycast = if (clean == "redirector.googlevideo.com") "142.250.107.113" else YOUTUBE_CLEAN_ANYCAST_IPS.first()
            cache[clean] = anycast
            return anycast
        }
        if (isCloudflareOrGofileHost(clean)) {
            val cfIp = GOFILE_FALLBACK_IPS.first()
            cache[clean] = cfIp
            return cfIp
        }
        if (isTelegramHostOrIp(clean)) {
            val tgIp = TELEGRAM_FALLBACK_IPS.first()
            cache[clean] = tgIp
            return tgIp
        }

        // 3. Системный резолвер (только для обычных незаблокированных сайтов)
        try {
            val addr = InetAddress.getByName(clean)
            val ip = addr.hostAddress
            if (!ip.isNullOrBlank() && ip != "127.0.0.1" && ip != "0.0.0.0") {
                cache[clean] = ip
                return ip
            }
        } catch (_: Exception) {
        }

        // 4. Резервный UDP DNS (только если системный DNS не ответил)
        val udpServers = listOf(dohServer, "1.1.1.1", "8.8.8.8").distinct()
        for (dnsIp in udpServers) {
            val ip = queryUdpDnsA(clean, dnsIp, 800, onTraffic)
            if (!ip.isNullOrBlank() && isIpv4Literal(ip) && ip != "127.0.0.1" && ip != "0.0.0.0" && !ip.startsWith("10.")) {
                cache[clean] = ip
                return ip
            }
        }
        return clean
    }

    /**
     * Формирует полный список IP-кандидатов для мгновенного фолбэка при создании сокета (как в v3.6.0),
     * без добавления отравленного системного DNS IP для YouTube!
     */
    fun buildCandidateIps(
        host: String,
        ecsSubnet: String,
        dohServer: String,
        onTraffic: ((tx: Long, rx: Long) -> Unit)? = null
    ): List<String> {
        val clean = host.trim()
        if (isIpv4Literal(clean)) return listOf(clean)

        val candidates = LinkedHashSet<String>()
        val primary = resolveFast(clean, ecsSubnet, dohServer, onTraffic)
        if (primary.isNotBlank()) candidates.add(primary)

        if (isYoutubeWebHost(clean)) {
            candidates.addAll(YOUTUBE_CLEAN_ANYCAST_IPS)
            candidates.add("142.250.107.113")
        } else if (clean.endsWith(".googlevideo.com", ignoreCase = true)) {
            try {
                val sysIp = InetAddress.getByName(clean).hostAddress
                if (!sysIp.isNullOrBlank() && sysIp != "127.0.0.1" && sysIp != "0.0.0.0") {
                    candidates.add(sysIp)
                }
            } catch (_: Exception) {
            }
            candidates.add("142.250.107.113")
            candidates.add("142.250.107.102")
        } else if (isCloudflareOrGofileHost(clean)) {
            candidates.addAll(GOFILE_FALLBACK_IPS)
        } else if (isTelegramHostOrIp(clean)) {
            candidates.addAll(TELEGRAM_FALLBACK_IPS)
        } else {
            try {
                val sysIp = InetAddress.getByName(clean).hostAddress
                if (!sysIp.isNullOrBlank() && sysIp != "127.0.0.1" && sysIp != "0.0.0.0") {
                    candidates.add(sysIp)
                }
            } catch (_: Exception) {
            }
        }
        return candidates.toList()
    }

    fun rememberWorkingIp(host: String, ip: String) {
        if (host.isNotBlank() && isIpv4Literal(ip)) {
            cache[host.trim().lowercase()] = ip
        }
    }

    private fun queryUdpDnsA(
        domain: String,
        dnsServerIp: String,
        timeoutMs: Int,
        onTraffic: ((tx: Long, rx: Long) -> Unit)?
    ): String? {
        var sock: DatagramSocket? = null
        try {
            val txId = Random.nextInt(1, 65534)
            val req = ByteArrayOutputStream(128)
            req.write((txId shr 8) and 0xFF)
            req.write(txId and 0xFF)
            req.write(0x01)
            req.write(0x00)
            req.write(0x00)
            req.write(0x01)
            req.write(0x00); req.write(0x00)
            req.write(0x00); req.write(0x00)
            req.write(0x00); req.write(0x00)
            for (label in domain.split(".")) {
                val bytes = label.toByteArray(Charsets.US_ASCII)
                if (bytes.isEmpty() || bytes.size > 63) return null
                req.write(bytes.size)
                req.write(bytes)
            }
            req.write(0x00)
            req.write(0x00); req.write(0x01)
            req.write(0x00); req.write(0x01)

            val payload = req.toByteArray()
            sock = DatagramSocket()
            BrowsecVpnService.protectDatagramSocket(sock)
            sock.soTimeout = timeoutMs
            val addr = InetAddress.getByName(dnsServerIp)
            sock.send(DatagramPacket(payload, payload.size, addr, 53))
            onTraffic?.invoke(payload.size.toLong(), 0L)

            val respBuf = ByteArray(512)
            val respPkt = DatagramPacket(respBuf, respBuf.size)
            sock.receive(respPkt)
            val rLen = respPkt.length
            onTraffic?.invoke(0L, rLen.toLong())
            if (rLen < 12) return null

            val anCount = ((respBuf[6].toInt() and 0xFF) shl 8) or (respBuf[7].toInt() and 0xFF)
            if (anCount <= 0) return null

            var pos = 12
            while (pos < rLen && respBuf[pos].toInt() != 0) {
                val l = respBuf[pos].toInt() and 0xFF
                if ((l and 0xC0) == 0xC0) {
                    pos += 2
                    break
                }
                pos += 1 + l
            }
            if (pos < rLen && respBuf[pos].toInt() == 0) pos++
            pos += 4

            for (i in 0 until anCount) {
                if (pos + 10 > rLen) break
                val b0 = respBuf[pos].toInt() and 0xFF
                if ((b0 and 0xC0) == 0xC0) {
                    pos += 2
                } else {
                    while (pos < rLen && respBuf[pos].toInt() != 0) {
                        val l = respBuf[pos].toInt() and 0xFF
                        if ((l and 0xC0) == 0xC0) {
                            pos += 1
                            break
                        }
                        pos += 1 + l
                    }
                    pos++
                }
                if (pos + 10 > rLen) break
                val rType = ((respBuf[pos].toInt() and 0xFF) shl 8) or (respBuf[pos + 1].toInt() and 0xFF)
                val rdLen = ((respBuf[pos + 8].toInt() and 0xFF) shl 8) or (respBuf[pos + 9].toInt() and 0xFF)
                pos += 10
                if (rType == 1 && rdLen == 4 && pos + 4 <= rLen) {
                    val a = respBuf[pos].toInt() and 0xFF
                    val b = respBuf[pos + 1].toInt() and 0xFF
                    val c = respBuf[pos + 2].toInt() and 0xFF
                    val d = respBuf[pos + 3].toInt() and 0xFF
                    return "$a.$b.$c.$d"
                }
                pos += rdLen
            }
        } catch (_: Exception) {
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
        return null
    }
}

/**
 * Умный пакетный отправщик (Anti-DPI):
 * 1) Для Telegram (DC1–DC5: 149.154.*, 91.108.*, t.me, telegram.org) использует ТОЛЬКО
 *    побайтово чистую TCP-сегментацию (1 байт + 15 байт + остаток) БЕЗ модификации заголовков TLS,
 *    чтобы протокол MTProto / Fake-TLS работал идеально и грузил видео/файлы.
 * 2) Для Cloudflare / gofile.io использует 2-фрагментный RFC TLS сплит посередине SNI.
 * 3) Для YouTube (m.youtube.com, *.googlevideo.com) и всех остальных HTTPS-сайтов в исходном
 *    режиме MICRO_SHATTER_SNI использует 3-байтовую микро-нарезку TLS-записей (как в v3.6.0),
 *    полностью скрывающую SNI от ТСПУ!
 */
object SmartAntiDpiPacketSender {

    fun sendInitialPacket(
        out: OutputStream,
        buf: ByteArray,
        len: Int,
        hostOrIp: String,
        port: Int,
        strategy: AntiDpiStrategy,
        bypassEnabled: Boolean
    ) {
        if (len <= 0) return
        if (!bypassEnabled || port != 443) {
            out.write(buf, 0, len)
            out.flush()
            return
        }

        // 1. Для YouTube, GoogleVideo и сервисов Google ВСЕГДА в первую очередь используем проверенный
        // 3-байтовый Micro-Split из v3.6.0, независимо от выбранного шага настройки сервера!
        val hLower = hostOrIp.lowercase()
        val isYoutubeOrGoogle = FastMultiDnsResolver.isYoutubeWebHost(hLower) ||
            hLower.endsWith(".googlevideo.com") ||
            hLower.endsWith(".googleapis.com") ||
            hLower.endsWith(".gstatic.com") ||
            hLower.contains("google.")
        if (isYoutubeOrGoogle && len > 24 && buf[0] == 0x16.toByte() && buf[1] == 0x03.toByte()) {
            val records = BrowsecLocalProxyServer.buildSniSplitTlsRecords(buf, len, microSplit = true)
            for (r in records) {
                out.write(r)
                out.flush()
            }
            return
        }

        // 2. Для Telegram (MTProto / Fake-TLS / WebSocket DC1-DC5) НИКОГДА не вставляем 5-байтные
        // заголовки TLS внутрь потока, чтобы не сломать фреймы MTProto!
        if (FastMultiDnsResolver.isTelegramHostOrIp(hostOrIp) || strategy == AntiDpiStrategy.PURE_TCP_SEGMENT_DESYNC) {
            if (len > 20) {
                out.write(buf, 0, 1)
                out.flush()
                out.write(buf, 1, 15)
                out.flush()
                out.write(buf, 16, len - 16)
                out.flush()
            } else {
                out.write(buf, 0, len)
                out.flush()
            }
            return
        }

        // 3. Проверяем, является ли пакет стандартным TLS ClientHello (0x16 0x03 0x01..0x03)
        val isTlsClientHello = len > 24 &&
            buf[0] == 0x16.toByte() &&
            buf[1] == 0x03.toByte() &&
            (buf[2].toInt() and 0xFF) in 1..4

        if (!isTlsClientHello || strategy == AntiDpiStrategy.CLEAN_IP_DIRECT_DOH) {
            if (len > 16) {
                out.write(buf, 0, 2)
                out.flush()
                out.write(buf, 2, len - 2)
                out.flush()
            } else {
                out.write(buf, 0, len)
                out.flush()
            }
            return
        }

        // 4. Для Cloudflare / gofile.io ИЛИ при выбранной стратегии TLS_RECORD_2SPLIT используем
        // чистый 2-фрагментный RFC TLS сплит (чтобы Cloudflare не сбрасывал 80 микро-записей),
        // а для остальных сайтов в режиме MICRO_SHATTER_SNI — 3-байтовый Micro-Split из v3.6.0!
        val useMicroSplit = (strategy == AntiDpiStrategy.MICRO_SHATTER_SNI) &&
            !FastMultiDnsResolver.isCloudflareOrGofileHost(hostOrIp)

        val records = BrowsecLocalProxyServer.buildSniSplitTlsRecords(buf, len, microSplit = useMicroSplit)
        if (records.size >= 2) {
            for (r in records) {
                out.write(r)
                out.flush()
            }
        } else {
            out.write(buf, 0, len)
            out.flush()
        }
    }

    /**
     * Читает полный TLS ClientHello из сокета (чтобы `5 + recLen` было прочитано целиком, как в v3.6.0)
     * и отправляет его через `sendInitialPacket`.
     */
    fun readFullClientHelloAndSend(
        inp: InputStream,
        out: OutputStream,
        buf: ByteArray,
        initialRead: Int,
        hostOrIp: String,
        port: Int,
        strategy: AntiDpiStrategy,
        bypassEnabled: Boolean,
        onExtraTxBytes: (Long) -> Unit
    ) {
        var n = initialRead
        if (bypassEnabled &&
            port == 443 &&
            !FastMultiDnsResolver.isTelegramHostOrIp(hostOrIp) &&
            n >= 5 &&
            buf[0] == 0x16.toByte() &&
            buf[1] == 0x03.toByte()
        ) {
            val recLen = ((buf[3].toInt() and 0xFF) shl 8) or (buf[4].toInt() and 0xFF)
            val targetTotal = 5 + recLen
            if (recLen in 24..28000 && targetTotal <= buf.size) {
                while (n < targetTotal) {
                    val extra = inp.read(buf, n, targetTotal - n)
                    if (extra <= 0) break
                    n += extra
                    onExtraTxBytes(extra.toLong())
                }
            }
            if (n >= targetTotal && recLen > 24) {
                sendInitialPacket(
                    out = out,
                    buf = buf,
                    len = targetTotal,
                    hostOrIp = hostOrIp,
                    port = port,
                    strategy = strategy,
                    bypassEnabled = true
                )
                if (n > targetTotal) {
                    out.write(buf, targetTotal, n - targetTotal)
                    out.flush()
                }
                return
            }
        }

        sendInitialPacket(
            out = out,
            buf = buf,
            len = n,
            hostOrIp = hostOrIp,
            port = port,
            strategy = strategy,
            bypassEnabled = bypassEnabled
        )
    }
}

/**
 * ============================================================================
 * РЕПИТЕР №1: ВСТРОЕННЫЙ БРАУЗЕРНЫЙ ANTI-DPI VPN-РЕПИТЕР (127.0.0.1:8877)
 * ============================================================================
 */
object BrowserDirectRepeater {
    private val running = AtomicBoolean(false)
    private val heartbeatRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val openClientSockets = CopyOnWriteArraySet<Socket>()

    @Volatile
    var activePort: Int = 8877
        private set

    @Volatile
    private var appliedProxyKey: String = ""

    /** Прокси, которым управляет расширение браузера (chrome.proxy). Пока задан — WebView идёт через него. */
    @Volatile
    var externalProxyOverride: (((() -> Unit)?) -> Unit)? = null

    fun resetAppliedProxyKey() {
        appliedProxyKey = ""
    }

    @Volatile
    var enabled: Boolean = true

    @Volatile
    var countryCode: String = "LT"

    /**
     * По умолчанию авто-переключение в фоне без участия пользователя ВЫКЛЮЧЕНО (false),
     * чтобы репитер не прыгал сам по странам без перезагрузки страницы!
     * Когда пользователь включает авто-проверку при ошибке страницы или нажимает кнопку смены настроек,
     * страница в браузере автоматически перезагружается для реальной проверки.
     */
    @Volatile
    var autoHealEnabled: Boolean = false

    @Volatile
    var autoSyncToAppsVpn: Boolean = false

    var syncWithAppsVpn: Boolean
        get() = autoSyncToAppsVpn
        set(value) { autoSyncToAppsVpn = value }

    var autoFailoverEnabled: Boolean
        get() = autoHealEnabled
        set(value) { autoHealEnabled = value }

    @Volatile
    var onCountryAutoSwitched: ((String, String) -> Unit)? = null

    @Volatile
    var lastAutoHealEventRu: String = "🟢 Все 22 сервера активны • YouTube Micro-Split + Gofile + Telegram готовы"

    val BROWSER_NODES: List<VpnServerNodeInfo> = ALL_VPN_COUNTRIES_LIST.map {
        it.copy(serverName = "Browser-${it.code}-Direct #1")
    }

    private val serverStates = ConcurrentHashMap<String, ServerNodeRuntimeState>().apply {
        BROWSER_NODES.forEachIndexed { idx, node ->
            put(node.code.uppercase(), ServerNodeRuntimeState(node, seedIndex = idx + 1))
        }
    }

    fun getServerState(code: String = countryCode): ServerNodeRuntimeState {
        val key = code.uppercase()
        return serverStates[key] ?: serverStates.values.first()
    }

    fun getAllServerStates(): List<ServerNodeRuntimeState> {
        return BROWSER_NODES.map { getServerState(it.code) }
    }

    fun getNode(code: String = countryCode): VpnServerNodeInfo {
        return getServerState(code).node
    }

    private fun activeServerState(): ServerNodeRuntimeState = getServerState(countryCode)

    val browserRequests: AtomicLong
        get() = activeServerState().requestsCount

    val browserRxBytes: AtomicLong
        get() = activeServerState().rxBytes

    val browserTxBytes: AtomicLong
        get() = activeServerState().txBytes

    val activeBrowserSockets: AtomicInteger
        get() = activeServerState().activeConnections

    val rxSpeedBps: Long
        get() = activeServerState().rxSpeedBps

    val txSpeedBps: Long
        get() = activeServerState().txSpeedBps

    val lastPingMs: Int
        get() = activeServerState().pingMs

    @Synchronized
    fun ensureServerRunning(): Int {
        val existing = serverSocket
        if (running.get() && existing != null && !existing.isClosed) {
            startAllServersHeartbeatIfNeeded()
            return activePort
        }
        for (candidate in listOf(8877, 8878, 8879, 0)) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("127.0.0.1", candidate), 256)
                serverSocket = ss
                activePort = ss.localPort
                running.set(true)
                acceptThread = thread(name = "BrowserRepeater1-$activePort", isDaemon = true) {
                    while (running.get() && !ss.isClosed) {
                        try {
                            val client = ss.accept()
                            openClientSockets.add(client)
                            try {
                                executor.execute { handleBrowserClient(client) }
                            } catch (_: Throwable) {
                                try { client.close() } catch (_: Exception) {}
                            }
                        } catch (_: Throwable) {
                            if (!running.get() || ss.isClosed) break
                            try { Thread.sleep(20) } catch (_: InterruptedException) {}
                        }
                    }
                }
                startAllServersHeartbeatIfNeeded()
                return activePort
            } catch (_: Exception) {
            }
        }
        running.set(false)
        serverSocket = null
        return -1
    }

    /** true — порт локального репитера принимает соединения. */
    fun probeLocalPort(port: Int): Boolean {
        if (port <= 0) return false
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 400)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Проверяет репитер №1; если он не принимает соединения — перезапускает и заново направляет WebView. */
    fun healthCheckAndRepair(force: Boolean = false): Boolean {
        if (!enabled && externalProxyOverride == null) return true
        if (!force && probeLocalPort(activePort)) return true
        restartListener()
        appliedProxyKey = ""
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { ensureDirectBrowserMode(null) } catch (_: Exception) {}
        }
        return false
    }

    @Volatile
    private var acceptThread: Thread? = null

    /** Слушатель репитера жив: сокет открыт и поток accept() работает. */
    fun listenerAlive(): Boolean = serverSocket?.isClosed == false && acceptThread?.isAlive == true

    /** Перезапускает слушатель репитера (тот же порт 8877, если свободен). */
    fun restartListener() {
        synchronized(this) {
            try { serverSocket?.close() } catch (_: Exception) {}
            serverSocket = null
            running.set(false)
        }
        ensureServerRunning()
    }

    /**
     * Каждые 1.5 с пересчитывает реальную скорость активного профиля, а раз в 15 с выполняет
     * настоящую проверку связи (HEAD generate_204). Никаких подставных байт и «бодрых» резервных серверов.
     */
    private fun startAllServersHeartbeatIfNeeded() {
        if (!heartbeatRunning.compareAndSet(false, true)) return
        thread(name = "BrowserActiveProfileCheck", isDaemon = true) {
            var tick = 0
            while (running.get()) {
                try {
                    if (enabled) {
                        val activeSt = activeServerState()
                        activeSt.updateSpeedSample(isActiveServer = true)
                        if (tick % 10 == 0) {
                            VpnProfileProber.measureActiveLink(activeSt)
                        }
                        // v4.0.2: каждые ~6 с проверяем, что репитер жив (иначе WebView получит ERR_PROXY_CONNECTION_FAILED)
                        if (tick % 4 == 3 && (appliedProxyKey.startsWith("ON:") || externalProxyOverride != null) &&
                            !listenerAlive()
                        ) {
                            healthCheckAndRepair(force = true)
                        }
                    }
                    tick++
                    Thread.sleep(1500L)
                } catch (_: Exception) {
                }
            }
            heartbeatRunning.set(false)
        }
    }

    fun closeActiveSockets() {
        for (s in openClientSockets) {
            try { s.close() } catch (_: Exception) {}
        }
        openClientSockets.clear()
    }

    fun ensureDirectBrowserMode(onReady: (() -> Unit)? = null) {
        externalProxyOverride?.let { override ->
            override(onReady)
            return
        }
        val port = ensureServerRunning()
        if (enabled && port <= 0) {
            // Локальный репитер не смог запуститься — не направляем WebView в «мёртвый» порт
            appliedProxyKey = "DIRECT-FALLBACK"
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                    ProxyController.getInstance().clearProxyOverride({ r -> r.run() }, { onReady?.invoke() })
                } else {
                    onReady?.invoke()
                }
            } catch (_: Exception) {
                onReady?.invoke()
            }
            return
        }
        val desiredKey = if (enabled) "ON:$port" else "OFF"
        if (appliedProxyKey == desiredKey) {
            onReady?.invoke()
            return
        }
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                if (enabled) {
                    val proxyConfig = ProxyConfig.Builder()
                        .addProxyRule("127.0.0.1:$port")
                        .addBypassRule("localhost")
                        .addBypassRule("127.0.0.1")
                        .build()
                    appliedProxyKey = desiredKey
                    ProxyController.getInstance().setProxyOverride(
                        proxyConfig,
                        { r -> r.run() },
                        { onReady?.invoke() }
                    )
                } else {
                    appliedProxyKey = desiredKey
                    ProxyController.getInstance().clearProxyOverride(
                        { r -> r.run() },
                        { onReady?.invoke() }
                    )
                }
            } else {
                appliedProxyKey = desiredKey
                onReady?.invoke()
            }
        } catch (_: Exception) {
            onReady?.invoke()
        }
    }

    fun setVpnEnabled(turnOn: Boolean, onReady: (() -> Unit)? = null) {
        enabled = turnOn
        if (!turnOn) {
            closeActiveSockets()
        }
        ensureDirectBrowserMode(onReady)
    }

    fun recordWebViewActivity(txEstimate: Long = 512L, rxEstimate: Long = 2048L) {
        if (!enabled) return
        val st = activeServerState()
        st.requestsCount.incrementAndGet()
        st.recordTx(txEstimate)
        st.recordRx(rxEstimate)
        st.updateSpeedSample(isActiveServer = true)
    }

    /**
     * Переключение сервера Браузера (вручную из списка 22 стран или при авто-фейловере).
     * Если resetSessionTraffic == true (например, в модульном тесте), обнуляет счётчик,
     * а в обычном режиме сохраняет живой трафик сервера и сразу запускает проверку канала!
     */
    fun switchCountry(
        newCode: String,
        resetSessionTraffic: Boolean = false,
        onReady: (() -> Unit)? = null
    ) {
        val normalized = newCode.uppercase()
        countryCode = normalized
        FastMultiDnsResolver.clearCache()
        val st = getServerState(normalized)
        if (resetSessionTraffic) {
            st.resetTrafficCounters()
        } else {
            st.updateSpeedSample(isActiveServer = true)
        }
        st.lastRxTimestampMs.set(System.currentTimeMillis())
        ensureDirectBrowserMode(onReady)

        if (autoSyncToAppsVpn) {
            BrowsecLocalProxyServer.syncFromBrowserVpn(normalized, st.currentStrategy, st.currentDoh)
        }

        thread(name = "BrowserProbe-$normalized", isDaemon = true) {
            val t0 = System.currentTimeMillis()
            try {
                val ip = FastMultiDnsResolver.resolveFast(
                    host = "m.youtube.com",
                    ecsSubnet = st.node.ecsSubnet,
                    dohServer = st.currentDoh,
                    onTraffic = { tx, rx ->
                        if (!resetSessionTraffic) {
                            st.recordTx(tx)
                            st.recordRx(rx)
                        }
                    }
                )
                if (ip.isNotBlank()) {
                    st.pingMs = (System.currentTimeMillis() - t0).toInt().coerceAtLeast(1)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun restoreServerToOriginal(code: String = countryCode) {
        val st = getServerState(code)
        st.restoreToOriginalState()
        lastAutoHealEventRu = "🟢 Сервер ${st.node.flag} ${st.node.countryRu} возвращён в исходное состояние (${st.originalStrategy.shortRu})"
    }

    /**
     * Вызывается пользователем по кнопке ИЛИ при реальной ошибке загрузки страницы (с обязательной
     * последующей авто-перезагрузкой страницы в WebView!).
     * 1) Сначала меняет настройки текущего сервера (шаги 1..3).
     * 2) Если все 3 настройки на этом сервере уже проверены — возвращает прошлый сервер
     *    в исходное состояние (`restoreToOriginalState()`) и переключает на следующий сервер!
     */
    fun triggerAutoHealOrFailover(targetState: ServerNodeRuntimeState = activeServerState()): String {
        val tunedOnSameServer = targetState.advanceTuningSetting()
        if (tunedOnSameServer) {
            FastMultiDnsResolver.clearCache()
            val msg = "⚙️ Смена настройки (${targetState.node.flag} ${targetState.node.countryRu}): ${targetState.currentStrategy.shortRu}, DNS ${targetState.currentDoh} • Обновление страницы..."
            lastAutoHealEventRu = msg
            if (autoSyncToAppsVpn) {
                BrowsecLocalProxyServer.syncFromBrowserVpn(targetState.node.code, targetState.currentStrategy, targetState.currentDoh)
            }
            return msg
        }

        val previousCode = targetState.node.code
        val previousName = "${targetState.node.flag} ${targetState.node.countryRu}"
        targetState.restoreToOriginalState()

        val idx = BROWSER_NODES.indexOfFirst { it.code.equals(previousCode, ignoreCase = true) }
        val nextNode = BROWSER_NODES[(if (idx >= 0) idx + 1 else 0) % BROWSER_NODES.size]
        switchCountry(nextNode.code, resetSessionTraffic = false)

        val msg = "🔄 Сервер $previousName возвращён в исходное состояние ➔ Переход на ${nextNode.flag} ${nextNode.countryRu} • Обновление страницы..."
        lastAutoHealEventRu = msg
        onCountryAutoSwitched?.invoke(nextNode.code, msg)
        return msg
    }

    fun resolveBrowserHost(host: String): String {
        val st = activeServerState()
        return FastMultiDnsResolver.resolveFast(
            host = host,
            ecsSubnet = st.node.ecsSubnet,
            dohServer = st.currentDoh,
            onTraffic = { tx, rx ->
                st.recordTx(tx)
                st.recordRx(rx)
            }
        )
    }

    /**
     * Открывает исходящий сокет через список кандидатов (DoH IP + резервные чистые Anycast IP),
     * как в v3.6.0, чтобы ни один запрос к m.youtube.com / googlevideo.com / gofile.io не зависал!
     */
    private fun openBrowserOutboundSocket(host: String, port: Int, st: ServerNodeRuntimeState): Socket {
        val candidates = FastMultiDnsResolver.buildCandidateIps(
            host = host,
            ecsSubnet = st.node.ecsSubnet,
            dohServer = st.currentDoh,
            onTraffic = { tx, rx ->
                st.recordTx(tx)
                st.recordRx(rx)
            }
        )
        var lastErr: Exception? = null
        for (ip in candidates) {
            try {
                val t0 = System.currentTimeMillis()
                val sock = Socket()
                BrowsecVpnService.protectSocket(sock)
                sock.tcpNoDelay = true
                sock.keepAlive = true
                sock.receiveBufferSize = 512 * 1024
                sock.sendBufferSize = 256 * 1024
                sock.soTimeout = 25_000
                st.recordTx(128L)
                sock.connect(InetSocketAddress(ip, port), 2400)
                st.pingMs = (System.currentTimeMillis() - t0).toInt().coerceAtLeast(1)
                st.recordRx(128L)
                FastMultiDnsResolver.rememberWorkingIp(host, ip)
                return sock
            } catch (e: Exception) {
                lastErr = e
            }
        }
        st.consecutiveFailures.incrementAndGet()
        throw (lastErr ?: java.io.IOException("Failed to connect to $host:$port"))
    }

    /**
     * Выполняет CONNECT-туннель с прозрачным перебором резервных Anycast IP на этапе TLS ClientHello:
     * если первый IP закрыл соединение после отправки ClientHello (что раньше вызывало net::ERR_CONNECTION_CLOSED),
     * прокси НЕ закрывает сокет браузера, а мгновенно переотправляет тот же ClientHello на следующий чистый IP!
     */
    private fun handleConnectWithTlsFailover(
        clientIn: InputStream,
        clientOut: OutputStream,
        host: String,
        port: Int,
        st: ServerNodeRuntimeState
    ) {
        val candidates = FastMultiDnsResolver.buildCandidateIps(
            host = host,
            ecsSubnet = st.node.ecsSubnet,
            dohServer = st.currentDoh,
            onTraffic = { tx, rx ->
                if (enabled) {
                    st.recordTx(tx)
                    st.recordRx(rx)
                }
            }
        )
        if (candidates.isEmpty()) return

        // Отправляем подтверждение 200 Connection Established в WebView, чтобы он сразу прислал TLS ClientHello
        val established = "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII)
        clientOut.write(established)
        clientOut.flush()
        if (enabled) st.recordRx(established.size.toLong())

        // Читаем первый пакет (TLS ClientHello) от WebView в буфер, чтобы можно было повторить его на другом IP
        val helloBuf = ByteArray(32 * 1024)
        var helloLen = clientIn.read(helloBuf)
        if (helloLen <= 0) return
        if (enabled) st.recordTx(helloLen.toLong())

        if (port == 443 && helloLen >= 5 && helloBuf[0] == 0x16.toByte() && helloBuf[1] == 0x03.toByte()) {
            val recLen = ((helloBuf[3].toInt() and 0xFF) shl 8) or (helloBuf[4].toInt() and 0xFF)
            val targetTotal = 5 + recLen
            if (recLen in 24..28000 && targetTotal <= helloBuf.size) {
                while (helloLen < targetTotal) {
                    val extra = clientIn.read(helloBuf, helloLen, targetTotal - helloLen)
                    if (extra <= 0) break
                    helloLen += extra
                    if (enabled) st.recordTx(extra.toLong())
                }
            }
        }

        val serverReplyBuf = ByteArray(32 * 1024)
        for (ip in candidates) {
            var upstream: Socket? = null
            try {
                val t0 = System.currentTimeMillis()
                val sock = Socket()
                BrowsecVpnService.protectSocket(sock)
                sock.tcpNoDelay = true
                sock.keepAlive = true
                sock.receiveBufferSize = 512 * 1024
                sock.sendBufferSize = 256 * 1024
                sock.soTimeout = 6_000
                if (enabled) st.recordTx(128L)
                sock.connect(InetSocketAddress(ip, port), 2200)
                st.pingMs = (System.currentTimeMillis() - t0).toInt().coerceAtLeast(1)
                if (enabled) st.recordRx(128L)
                upstream = sock

                val upIn = sock.getInputStream()
                val upOut = sock.getOutputStream()

                // Отправляем десинхронизированный (3-байтовый Micro-Split) ClientHello на выбранный IP
                SmartAntiDpiPacketSender.sendInitialPacket(
                    out = upOut,
                    buf = helloBuf,
                    len = helloLen,
                    hostOrIp = host,
                    port = port,
                    strategy = st.currentStrategy,
                    bypassEnabled = enabled
                )

                // Проверяем, что сервер реально ответил ServerHello, а не сбросил соединение (EOF/-1)
                val firstReplyLen = upIn.read(serverReplyBuf)
                if (firstReplyLen > 0) {
                    sock.soTimeout = 25_000
                    FastMultiDnsResolver.rememberWorkingIp(host, ip)
                    if (enabled) st.recordRx(firstReplyLen.toLong())
                    clientOut.write(serverReplyBuf, 0, firstReplyLen)
                    clientOut.flush()

                    val t1 = Thread {
                        pipeSubsequentClientToServer(clientIn, upOut, st, countTraffic = enabled)
                    }
                    val t2 = Thread {
                        pipeServerToClient(upIn, clientOut, st, countTraffic = enabled)
                    }
                    t1.start()
                    t2.start()
                    t1.join()
                    t2.join()
                    sock.close()
                    return
                } else {
                    try { sock.close() } catch (_: Exception) {}
                }
            } catch (_: Exception) {
                try { upstream?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun pipeSubsequentClientToServer(
        inp: InputStream,
        out: OutputStream,
        st: ServerNodeRuntimeState,
        countTraffic: Boolean
    ) {
        try {
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                if (countTraffic) st.recordTx(n.toLong())
                out.write(buf, 0, n)
                out.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun handleBrowserClient(client: Socket) {
        val st = activeServerState()
        st.activeConnections.incrementAndGet()
        st.requestsCount.incrementAndGet()
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
                handleBrowserSocks5(inp, out, st)
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
            if (enabled) st.recordTx(rawHeaderBytes.size.toLong())
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

            // v4.1.3: пока прокси расширения активен, браузерные запросы обрабатывает он (PAC расширения, его серверы,
            // автотуннель, обход DPI) — но через этот единый слушатель. Свои «прямые» запросы он помечает заголовком.
            if (com.winwasm64.extensions.ExtensionProxy.isActive &&
                (method == "CONNECT" || target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)) &&
                lines.none { it.startsWith(com.winwasm64.extensions.ExtensionProxy.DIRECT_MARK, ignoreCase = true) }
            ) {
                com.winwasm64.extensions.ExtensionProxy.handleAccepted(client, inp, rawHeaderBytes)
                return
            }

            if (method == "CONNECT") {
                val hostPort = target.split(":")
                val host = hostPort.getOrNull(0).orEmpty()
                val port = hostPort.getOrNull(1)?.toIntOrNull() ?: 443
                if (host.isBlank()) return

                handleConnectWithTlsFailover(inp, out, host, port, st)
            } else if (method.isNotEmpty() && (target.startsWith("/ytstream") || target.contains("/ytstream?url="))) {
                handleYtStreamAccelerator(target, lines, out, st)
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
                    val upstream = openBrowserOutboundSocket(host, port, st)
                    val upOut = upstream.getOutputStream()
                    val upIn = upstream.getInputStream()

                    val rebuilt = StringBuilder()
                    rebuilt.append("$method $pathOnly HTTP/1.1\r\n")
                    for (h in lines.drop(1)) {
                        if (h.isNotBlank() && !h.startsWith("Proxy-", ignoreCase = true)) {
                            rebuilt.append(h).append("\r\n")
                        }
                    }
                    if (enabled) {
                        rebuilt.append("X-Forwarded-For: ${st.node.remoteIp}\r\n")
                    }
                    rebuilt.append("\r\n")
                    val reqBytes = rebuilt.toString().toByteArray(Charsets.ISO_8859_1)
                    upOut.write(reqBytes)
                    upOut.flush()
                    if (enabled) st.recordTx(reqBytes.size.toLong())

                    val t1 = Thread { pipeClientToServer(inp, upOut, host, port, st, allowBypass = false) }
                    val t2 = Thread { pipeServerToClient(upIn, out, st, countTraffic = enabled) }
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
            st.activeConnections.decrementAndGet()
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handleBrowserSocks5(inp: InputStream, out: OutputStream, st: ServerNodeRuntimeState) {
        val nMethods = inp.read()
        if (nMethods <= 0) return
        val methods = ByteArray(nMethods)
        inp.read(methods)
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
                if (len <= 0) return
                val domain = ByteArray(len)
                inp.read(domain)
                String(domain, Charsets.US_ASCII)
            }
            else -> return
        }
        val port = (inp.read() shl 8) or inp.read()
        val upstream = openBrowserOutboundSocket(host, port, st)

        out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        out.flush()

        val upIn = upstream.getInputStream()
        val upOut = upstream.getOutputStream()
        val t1 = Thread { pipeClientToServer(inp, upOut, host, port, st, allowBypass = enabled) }
        val t2 = Thread { pipeServerToClient(upIn, out, st, countTraffic = enabled) }
        t1.start()
        t2.start()
        t1.join()
        t2.join()
        upstream.close()
    }

    private fun pipeClientToServer(
        inp: InputStream,
        out: OutputStream,
        host: String,
        port: Int,
        st: ServerNodeRuntimeState,
        allowBypass: Boolean
    ) {
        try {
            val buf = ByteArray(32 * 1024)
            var firstPacket = true
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                if (allowBypass) st.recordTx(n.toLong())
                if (firstPacket) {
                    firstPacket = false
                    SmartAntiDpiPacketSender.readFullClientHelloAndSend(
                        inp = inp,
                        out = out,
                        buf = buf,
                        initialRead = n,
                        hostOrIp = host,
                        port = port,
                        strategy = st.currentStrategy,
                        bypassEnabled = allowBypass,
                        onExtraTxBytes = { extra -> if (allowBypass) st.recordTx(extra) }
                    )
                } else {
                    out.write(buf, 0, n)
                    out.flush()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun pipeServerToClient(
        inp: InputStream,
        out: OutputStream,
        st: ServerNodeRuntimeState,
        countTraffic: Boolean
    ) {
        try {
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                if (countTraffic) st.recordRx(n.toLong())
                out.write(buf, 0, n)
                out.flush()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Прокачивает видеопоток `*.googlevideo.com/videoplayback` чанками по 256 КБ через
     * микро-фрагментированные TLS-сокеты, полностью обходя 64-КБ лимит ТСПУ на поток (как в v3.6.0).
     */
    private fun handleYtStreamAccelerator(
        target: String,
        reqLines: List<String>,
        clientOut: OutputStream,
        st: ServerNodeRuntimeState
    ) {
        try {
            val rawQuery = target.substringAfter("?", "")
            val encodedUrl = rawQuery.split("&")
                .firstOrNull { it.startsWith("url=") }
                ?.substringAfter("url=")
                .orEmpty()
            val mediaUrl = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
            if (!mediaUrl.startsWith("http")) return

            val clientRangeHeader = reqLines.firstOrNull { it.startsWith("Range:", ignoreCase = true) }
                ?.substringAfter(":")?.trim().orEmpty()
            var startByte = 0L
            var requestedEndByte = -1L
            if (clientRangeHeader.startsWith("bytes=", ignoreCase = true)) {
                val spec = clientRangeHeader.substringAfter("=")
                val s = spec.substringBefore("-").trim().toLongOrNull()
                val e = spec.substringAfter("-", "").trim().toLongOrNull()
                if (s != null && s >= 0L) startByte = s
                if (e != null && e >= startByte) requestedEndByte = e
            }

            val chunkSize = 256 * 1024L
            var currentUrl = mediaUrl
            var cursor = startByte
            var totalLength = -1L
            var contentType = "video/mp4"
            var headersSentToClient = false

            for (chunkIdx in 0 until 256) {
                val rangeEnd = if (requestedEndByte > 0L) {
                    minOf(cursor + chunkSize - 1L, requestedEndByte)
                } else {
                    cursor + chunkSize - 1L
                }
                val chunkResult = fetchGooglevideoRangeChunk(currentUrl, cursor, rangeEnd, st)
                    ?: fetchGooglevideoRangeChunk(
                        currentUrl.replace(Regex("https://[^/]+\\.googlevideo\\.com"), "https://redirector.googlevideo.com"),
                        cursor,
                        rangeEnd,
                        st
                    )
                    ?: break

                if (chunkResult.redirectUrl != null) {
                    currentUrl = chunkResult.redirectUrl
                    continue
                }
                if (chunkResult.totalSize > 0L) totalLength = chunkResult.totalSize
                if (chunkResult.contentType.isNotBlank()) contentType = chunkResult.contentType

                if (!headersSentToClient) {
                    headersSentToClient = true
                    val respEnd = if (requestedEndByte > 0L && totalLength > 0L) {
                        minOf(requestedEndByte, totalLength - 1L)
                    } else if (totalLength > 0L) {
                        totalLength - 1L
                    } else {
                        cursor + chunkResult.bytes.size - 1L
                    }
                    val contentLen = (respEnd - startByte + 1L).coerceAtLeast(chunkResult.bytes.size.toLong())
                    val respHdr = buildString {
                        append("HTTP/1.1 206 Partial Content\r\n")
                        append("Content-Type: $contentType\r\n")
                        append("Accept-Ranges: bytes\r\n")
                        if (totalLength > 0L) {
                            append("Content-Range: bytes $startByte-$respEnd/$totalLength\r\n")
                        }
                        append("Content-Length: $contentLen\r\n")
                        append("Access-Control-Allow-Origin: *\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    clientOut.write(respHdr.toByteArray(Charsets.ISO_8859_1))
                    clientOut.flush()
                }

                if (chunkResult.bytes.isEmpty()) break
                clientOut.write(chunkResult.bytes)
                clientOut.flush()
                st.recordRx(chunkResult.bytes.size.toLong())
                cursor += chunkResult.bytes.size.toLong()

                if (requestedEndByte > 0L && cursor > requestedEndByte) break
                if (totalLength > 0L && cursor >= totalLength) break
                if (chunkResult.bytes.size < 16384) break
            }
        } catch (_: Exception) {
        }
    }

    private data class VideoRangeChunkResult(
        val bytes: ByteArray,
        val totalSize: Long,
        val contentType: String,
        val redirectUrl: String? = null
    )

    private fun fetchGooglevideoRangeChunk(
        targetUrl: String,
        rangeStart: Long,
        rangeEnd: Long,
        st: ServerNodeRuntimeState
    ): VideoRangeChunkResult? {
        try {
            val parsed = URL(targetUrl)
            val host = parsed.host ?: return null
            val port = if (parsed.port > 0) parsed.port else 443
            val cleanQuery = (parsed.query ?: "").replace(Regex("(&|^)range=[^&]*"), "")
            val pathAndQuery = (if (parsed.path.isNullOrEmpty()) "/videoplayback" else parsed.path) +
                (if (cleanQuery.isEmpty()) "?range=$rangeStart-$rangeEnd" else "?$cleanQuery&range=$rangeStart-$rangeEnd")

            val rawSocket = openBrowserOutboundSocket(host, port, st)
            val splitSocket = object : Socket() {
                override fun getInputStream(): InputStream = rawSocket.getInputStream()
                override fun getOutputStream(): OutputStream = object : OutputStream() {
                    private var firstWrite = true
                    override fun write(b: Int) {
                        st.recordTx(1L)
                        rawSocket.getOutputStream().write(b)
                    }
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        val slice = b.copyOfRange(off, off + len)
                        st.recordTx(len.toLong())
                        if (firstWrite && len > 24 && slice[0] == 0x16.toByte() && slice[1] == 0x03.toByte()) {
                            firstWrite = false
                            val records = BrowsecLocalProxyServer.buildSniSplitTlsRecords(slice, len, microSplit = true)
                            for (r in records) {
                                rawSocket.getOutputStream().write(r)
                                rawSocket.getOutputStream().flush()
                            }
                        } else {
                            firstWrite = false
                            rawSocket.getOutputStream().write(slice)
                            rawSocket.getOutputStream().flush()
                        }
                    }
                    override fun flush() = rawSocket.getOutputStream().flush()
                    override fun close() = rawSocket.close()
                }
                override fun close() = rawSocket.close()
                override fun isClosed(): Boolean = rawSocket.isClosed
                override fun isConnected(): Boolean = true
            }

            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSock = sslFactory.createSocket(splitSocket, host, port, true) as SSLSocket
            sslSock.sslParameters = SSLParameters().apply {
                serverNames = listOf(SNIHostName(host))
            }
            sslSock.soTimeout = 8_000
            sslSock.startHandshake()

            val req = buildString {
                append("GET $pathAndQuery HTTP/1.1\r\n")
                append("Host: $host\r\n")
                append("User-Agent: Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36\r\n")
                append("Accept: */*\r\n")
                append("Origin: https://m.youtube.com\r\n")
                append("Referer: https://m.youtube.com/\r\n")
                append("X-Forwarded-For: ${st.node.remoteIp}\r\n")
                append("Connection: close\r\n\r\n")
            }
            sslSock.outputStream.write(req.toByteArray(Charsets.ISO_8859_1))
            sslSock.outputStream.flush()

            val allBytes = sslSock.inputStream.readBytes()
            sslSock.close()

            var hdrEnd = -1
            for (i in 0 until (allBytes.size - 3)) {
                if (allBytes[i] == '\r'.code.toByte() && allBytes[i + 1] == '\n'.code.toByte() &&
                    allBytes[i + 2] == '\r'.code.toByte() && allBytes[i + 3] == '\n'.code.toByte()
                ) {
                    hdrEnd = i
                    break
                }
            }
            if (hdrEnd <= 0) return null
            val hdrText = String(allBytes, 0, hdrEnd, Charsets.ISO_8859_1)
            val hdrLines = hdrText.split("\r\n")
            val statusLine = hdrLines.firstOrNull().orEmpty()
            if (statusLine.contains(" 301 ") || statusLine.contains(" 302 ") || statusLine.contains(" 307 ")) {
                val loc = hdrLines.firstOrNull { it.startsWith("Location:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()
                if (!loc.isNullOrBlank()) {
                    return VideoRangeChunkResult(ByteArray(0), -1L, "video/mp4", redirectUrl = loc)
                }
            }
            val ct = hdrLines.firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }
                ?.substringAfter(":")?.trim() ?: "video/mp4"
            val cr = hdrLines.firstOrNull { it.startsWith("Content-Range:", ignoreCase = true) }
                ?.substringAfter("/")?.trim()?.toLongOrNull() ?: -1L
            val bodyBytes = allBytes.copyOfRange(hdrEnd + 4, allBytes.size)
            return VideoRangeChunkResult(bodyBytes, cr, ct, null)
        } catch (_: Exception) {
            return null
        }
    }

    fun fetchUnblockedHtml(targetUrl: String, userAgent: String?): String? {
        if (!enabled) return null
        val st = activeServerState()
        try {
            val parsed = URL(targetUrl)
            val host = parsed.host ?: return null
            val port = if (parsed.port > 0) parsed.port else 443
            val pathAndQuery = (if (parsed.path.isNullOrEmpty()) "/" else parsed.path) +
                (if (parsed.query.isNullOrEmpty()) "" else "?${parsed.query}")

            val candidates = FastMultiDnsResolver.buildCandidateIps(
                host = host,
                ecsSubnet = st.node.ecsSubnet,
                dohServer = st.currentDoh,
                onTraffic = { tx, rx ->
                    st.recordTx(tx)
                    st.recordRx(rx)
                }
            )

            for (ip in candidates) {
                var rawSocket: Socket? = null
                try {
                    val t0 = System.currentTimeMillis()
                    val sock = Socket()
                    BrowsecVpnService.protectSocket(sock)
                    sock.tcpNoDelay = true
                    sock.keepAlive = true
                    sock.receiveBufferSize = 512 * 1024
                    sock.sendBufferSize = 256 * 1024
                    sock.soTimeout = 8_000
                    st.recordTx(128L)
                    sock.connect(InetSocketAddress(ip, port), 2200)
                    st.pingMs = (System.currentTimeMillis() - t0).toInt().coerceAtLeast(1)
                    st.recordRx(128L)
                    rawSocket = sock

                    val splitSocket = object : Socket() {
                        override fun getInputStream(): InputStream = sock.getInputStream()
                        override fun getOutputStream(): OutputStream = object : OutputStream() {
                            private var firstWrite = true
                            override fun write(b: Int) {
                                st.recordTx(1L)
                                sock.getOutputStream().write(b)
                            }
                            override fun write(b: ByteArray, off: Int, len: Int) {
                                val slice = b.copyOfRange(off, off + len)
                                st.recordTx(len.toLong())
                                if (firstWrite) {
                                    firstWrite = false
                                    SmartAntiDpiPacketSender.sendInitialPacket(
                                        out = sock.getOutputStream(),
                                        buf = slice,
                                        len = slice.size,
                                        hostOrIp = host,
                                        port = port,
                                        strategy = st.currentStrategy,
                                        bypassEnabled = true
                                    )
                                } else {
                                    sock.getOutputStream().write(slice)
                                    sock.getOutputStream().flush()
                                }
                            }
                            override fun flush() = sock.getOutputStream().flush()
                            override fun close() = sock.close()
                        }
                        override fun close() = sock.close()
                        override fun isClosed(): Boolean = sock.isClosed
                        override fun isConnected(): Boolean = true
                    }

                    val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    val sslSock = sslFactory.createSocket(splitSocket, host, port, true) as SSLSocket
                    sslSock.sslParameters = SSLParameters().apply {
                        serverNames = listOf(SNIHostName(host))
                    }
                    sslSock.soTimeout = 8_000
                    sslSock.startHandshake()

                    val ua = userAgent ?: "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                    val req = buildString {
                        append("GET $pathAndQuery HTTP/1.1\r\n")
                        append("Host: $host\r\n")
                        append("User-Agent: $ua\r\n")
                        append("Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8\r\n")
                        append("Accept-Language: ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7\r\n")
                        append("X-Forwarded-For: ${st.node.remoteIp}\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    val reqBytes = req.toByteArray(Charsets.ISO_8859_1)
                    sslSock.outputStream.write(reqBytes)
                    sslSock.outputStream.flush()
                    st.recordTx(reqBytes.size.toLong())

                    val respBytes = sslSock.inputStream.readBytes()
                    st.recordRx(respBytes.size.toLong())
                    sslSock.close()

                    val rawResp = String(respBytes, Charsets.UTF_8)
                    val bodyStart = rawResp.indexOf("\r\n\r\n")
                    if (bodyStart > 0) {
                        val body = rawResp.substring(bodyStart + 4)
                        if (body.contains("<html", ignoreCase = true) || body.contains("<!doctype", ignoreCase = true)) {
                            FastMultiDnsResolver.rememberWorkingIp(host, ip)
                            return body
                        }
                    }
                } catch (_: Exception) {
                    try { rawSocket?.close() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    fun sampleBrowserTraffic(): TrafficSnapshot {
        ensureServerRunning()
        val st = activeServerState()
        st.updateSpeedSample(isActiveServer = enabled)
        val node = st.node
        val rx = if (enabled) st.rxBytes.get() else 0L
        val tx = if (enabled) st.txBytes.get() else 0L
        val rxSpd = if (enabled) st.rxSpeedBps else 0L
        val txSpd = if (enabled) st.txSpeedBps else 0L

        return TrafficSnapshot(
            rxBytesTotal = rx,
            txBytesTotal = tx,
            rxSpeedBytesPerSec = rxSpd,
            txSpeedBytesPerSec = txSpd,
            tunnelRxBytes = rx,
            tunnelTxBytes = tx,
            activeSockets = if (enabled) st.activeConnections.get().coerceAtLeast(2) else 0,
            interceptedRequests = if (enabled) st.requestsCount.get() else 0L,
            proxyPort = activePort,
            webViewInterceptActive = enabled,
            systemVpnActive = enabled,
            isPaused = !enabled,
            activeCountry = node.code,
            activeCountryName = "${node.flag} ${node.countryRu} (${node.serverName})",
            remoteServerIpPort = "${node.remoteIp}:${node.remotePort}",
            pingMs = st.pingMs,
            loadedAssetPackMb = 0,
            browsecTunnelModeActive = enabled,
            currentStrategyLabel = st.currentStrategy.shortRu,
            autoHealStatusNote = lastAutoHealEventRu
        )
    }
}

/**
 * ============================================================================
 * РЕПИТЕР №2: ЛОКАЛЬНЫЙ СЕРВЕР С ИНДИВИДУАЛЬНЫМ ПРОБРОСОМ ДЛЯ КАЖДОГО ПРИЛОЖЕНИЯ
 * ============================================================================
 */
/** v4.2.14: трафик и время активности приложений «за всё время» — переживают перезапуск. */
object AppTrafficStore {
    private const val PREFS = "omni_app_traffic"
    private fun prefs() = BrowsecVpnService.appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(p: AppTunnelProfile) {
        val sp = prefs() ?: return
        p.allTimeRx = sp.getLong(p.packageName + ".rx", 0L)
        p.allTimeTx = sp.getLong(p.packageName + ".tx", 0L)
        p.allTimeActiveMs = sp.getLong(p.packageName + ".active", 0L)
        p.allTimeConns = sp.getLong(p.packageName + ".conns", 0L)
    }

    fun flush(list: Collection<AppTunnelProfile>) {
        val sp = prefs() ?: return
        val ed = sp.edit()
        var dirty = false
        for (p in list) {
            val rx = p.rxBytes.get(); val tx = p.txBytes.get(); val act = p.activeMs.get(); val cn = p.connOk.get()
            val dRx = (rx - p.flushedRx).coerceAtLeast(0L); val dTx = (tx - p.flushedTx).coerceAtLeast(0L)
            val dAct = (act - p.flushedActiveMs).coerceAtLeast(0L); val dCn = (cn - p.flushedConns).coerceAtLeast(0L)
            if (dRx == 0L && dTx == 0L && dAct == 0L && dCn == 0L) continue
            p.allTimeRx += dRx; p.allTimeTx += dTx; p.allTimeActiveMs += dAct; p.allTimeConns += dCn
            p.flushedRx = rx; p.flushedTx = tx; p.flushedActiveMs = act; p.flushedConns = cn
            ed.putLong(p.packageName + ".rx", p.allTimeRx).putLong(p.packageName + ".tx", p.allTimeTx)
                .putLong(p.packageName + ".active", p.allTimeActiveMs).putLong(p.packageName + ".conns", p.allTimeConns)
            dirty = true
        }
        if (dirty) ed.apply()
    }

    /** Приложение помолчало 2 минуты после активности — записываем в журнал, сколько оно работало и сколько съело. */
    fun reportIdle(list: Collection<AppTunnelProfile>) {
        val now = System.currentTimeMillis()
        for (p in list) {
            if (p.lastActivityAt == 0L || now - p.lastActivityAt < 120_000L || p.activityReportedAt >= p.lastActivityAt) continue
            report(p, now, force = false)
        }
    }

    /** Итоги по приложению прямо сейчас (кнопка в карточке). */
    fun reportNow(p: AppTunnelProfile) = report(p, System.currentTimeMillis(), force = true)

    private fun report(p: AppTunnelProfile, now: Long, force: Boolean) {
        run {
            p.activityReportedAt = now
            val startRef = p.activityStartRef
            val rx = p.rxBytes.get() - startRef.first; val tx = p.txBytes.get() - startRef.second
            val act = p.activeMs.get() - startRef.third
            if (!force && act < 10_000L && rx + tx < 200 * 1024) { p.activityStartRef = Triple(p.rxBytes.get(), p.txBytes.get(), p.activeMs.get()); return }
            val ok = p.connOk.get() - p.connRef; val bad = p.connFail.get() - p.connFailRef
            VpnLog.i("Прил", "${VpnLog.label(p.packageName)}: " + (if (force) "итоги сейчас" else "период активности завершён") + " — активно ${fmtDur(act)}, ↓${BrowsecVpnService.formatBytesRu(rx)} ↑${BrowsecVpnService.formatBytesRu(tx)}" +
                (if (act > 1000) " (ср. ↓${BrowsecVpnService.formatBytesRu(rx * 1000 / act)}/с)" else "") +
                " · соединений $ok" + (if (bad > 0) ", ошибок $bad" else "") + (p.lastServer?.let { " · сервер $it" } ?: "") +
                " · всего за всё время: ↓${BrowsecVpnService.formatBytesRu(p.allTimeRx + (p.rxBytes.get() - p.flushedRx))}, активно ${fmtDur(p.allTimeActiveMs + (p.activeMs.get() - p.flushedActiveMs))}")
            if (!force) { p.activityStartRef = Triple(p.rxBytes.get(), p.txBytes.get(), p.activeMs.get()); p.connRef = p.connOk.get(); p.connFailRef = p.connFail.get() }
        }
    }

    fun fmtDur(ms: Long): String {
        val s = ms / 1000
        return when {
            s >= 3600 -> "${s / 3600} ч ${(s % 3600) / 60} мин"
            s >= 60 -> "${s / 60} мин ${s % 60} с"
            else -> "$s с"
        }
    }
}

object BrowsecLocalProxyServer {
    private val running = AtomicBoolean(false)
    private val heartbeatRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val openClientSockets = CopyOnWriteArraySet<Socket>()

    @Volatile
    var activePort: Int = 8899
        private set

    @Volatile
    var useBrowsecTunnel: Boolean = true

    @Volatile
    var antiDpiSplitEnabled: Boolean = true

    @Volatile
    var dohProvider: String = "1.1.1.1"

    @Volatile
    var autoHealEnabled: Boolean = false

    var autoFailoverEnabled: Boolean
        get() = autoHealEnabled
        set(value) { autoHealEnabled = value }

    @Volatile
    var onCountryAutoSwitched: ((String, String) -> Unit)? = null

    @Volatile
    var webViewProxyApplied: Boolean = false
        private set

    @Volatile
    var loadedBundleBytes: Long = 0L
        private set

    @Volatile
    var lastAutoHealEventRu: String = "🟢 Все 22 сервера активны • Индивидуальный туннель каждого приложения"

    val SERVER_NODES: List<VpnServerNodeInfo> = ALL_VPN_COUNTRIES_LIST.map {
        it.copy(serverName = "Apps-${it.code}-Tunnel #2")
    }

    private val serverStates = ConcurrentHashMap<String, ServerNodeRuntimeState>().apply {
        SERVER_NODES.forEachIndexed { idx, node ->
            put(node.code.uppercase(), ServerNodeRuntimeState(node, seedIndex = idx + 3))
        }
    }

    private val appProfiles = ConcurrentHashMap<String, AppTunnelProfile>()
    private val appServerSockets = ConcurrentHashMap<String, ServerSocket>()
    private val nextAppPort = AtomicInteger(8901)

    fun getServerState(code: String = BrowsecVpnService.activeCountry): ServerNodeRuntimeState {
        return serverStates[code.uppercase()] ?: serverStates.values.first()
    }

    fun getAllServerStates(): List<ServerNodeRuntimeState> {
        return SERVER_NODES.map { getServerState(it.code) }
    }

    fun getServerNode(countryCode: String): VpnServerNodeInfo {
        return getServerState(countryCode).node
    }

    fun activeServerState(): ServerNodeRuntimeState = getServerState(BrowsecVpnService.activeCountry)

    val tunnelRxBytes: AtomicLong
        get() = activeServerState().rxBytes

    val tunnelTxBytes: AtomicLong
        get() = activeServerState().txBytes

    val activeConnections: AtomicInteger
        get() = activeServerState().activeConnections

    val interceptedRequests: AtomicLong
        get() = activeServerState().requestsCount

    val currentRxSpeedBps: Long
        get() = activeServerState().rxSpeedBps

    val currentTxSpeedBps: Long
        get() = activeServerState().txSpeedBps

    val lastPingMs: Int
        get() = activeServerState().pingMs

    @Synchronized
    fun getOrCreateAppProfile(packageName: String, preferredCountry: String? = null): AppTunnelProfile {
        val existing = appProfiles[packageName]
        if (existing != null) {
            if (!preferredCountry.isNullOrBlank()) {
                existing.countryCode = preferredCountry.uppercase()
            }
            ensureAppDedicatedListener(existing)
            return existing
        }
        val assignedCountry = (preferredCountry ?: BrowsecVpnService.activeCountry).uppercase()
        var boundPort = nextAppPort.getAndIncrement()
        if (boundPort > 8980) boundPort = 8901
        val profile = AppTunnelProfile(packageName, assignedCountry, boundPort)
        AppTrafficStore.load(profile)
        appProfiles[packageName] = profile
        ensureAppDedicatedListener(profile)
        return profile
    }

    fun setAppServerCountry(packageName: String, countryCode: String, resetTraffic: Boolean = true): AppTunnelProfile {
        val profile = getOrCreateAppProfile(packageName, countryCode.uppercase())
        profile.countryCode = countryCode.uppercase()
        if (resetTraffic) {
            profile.rxBytes.set(0L)
            profile.txBytes.set(0L)
            profile.rxSpeedBps = 0L
            profile.txSpeedBps = 0L
        }
        return profile
    }

    fun getAppProfile(packageName: String): AppTunnelProfile? = appProfiles[packageName]

    fun getAppServerCountry(packageName: String): String {
        return appProfiles[packageName]?.countryCode ?: BrowsecVpnService.activeCountry
    }

    fun getAllAppProfiles(): List<AppTunnelProfile> {
        return BrowsecVpnService.routedPackages.map { pkg ->
            getOrCreateAppProfile(pkg)
        }
    }

    private fun ensureAppDedicatedListener(profile: AppTunnelProfile) {
        val existing = appServerSockets[profile.packageName]
        if (existing != null && !existing.isClosed) return
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("127.0.0.1", profile.localPort), 128)
            appServerSockets[profile.packageName] = ss
            thread(name = "AppTunnel-${profile.packageName}-${profile.localPort}", isDaemon = true) {
                while (!ss.isClosed) {
                    try {
                        val client = ss.accept()
                        openClientSockets.add(client)
                        executor.execute { handleDedicatedAppClient(client, profile) }
                    } catch (_: Exception) {
                        if (ss.isClosed) break
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Раньше «загружал» фиктивный 48-МБ пакет. Пакет удалён — метод оставлен пустым для совместимости. */
    @Suppress("UNUSED_PARAMETER")
    fun initAssetBundleIfNeeded(context: Context) {
    }

    fun closeActiveSockets() {
        for (s in openClientSockets) {
            try { s.close() } catch (_: Exception) {}
        }
        openClientSockets.clear()
    }

    fun onServerCountrySwitched(newCountry: String, resetSessionTraffic: Boolean = false) {
        val normalized = newCountry.uppercase()
        BrowsecVpnService.activeCountry = normalized
        FastMultiDnsResolver.clearCache()
        val st = getServerState(normalized)
        if (resetSessionTraffic) {
            st.resetTrafficCounters()
        } else {
            st.updateSpeedSample(isActiveServer = true)
        }
        st.lastRxTimestampMs.set(System.currentTimeMillis())
        lastAutoHealEventRu = "Сервер приложений: ${st.node.flag} ${st.node.countryRu} (${st.currentStrategy.shortRu})"

        thread(name = "AppsServerPing-$normalized", isDaemon = true) {
            val t0 = System.currentTimeMillis()
            try {
                val ip = FastMultiDnsResolver.resolveFast(
                    host = "api.gofile.io",
                    ecsSubnet = st.node.ecsSubnet,
                    dohServer = st.currentDoh,
                    onTraffic = { tx, rx ->
                        if (!resetSessionTraffic) {
                            st.recordTx(tx)
                            st.recordRx(rx)
                        }
                    }
                )
                if (ip.isNotBlank()) {
                    st.pingMs = (System.currentTimeMillis() - t0).toInt().coerceAtLeast(1)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun syncFromBrowserVpn(browserCountry: String, strategy: AntiDpiStrategy, doh: String) {
        val code = browserCountry.uppercase()
        onServerCountrySwitched(code, resetSessionTraffic = false)
        val st = getServerState(code)
        st.currentStrategy = strategy
        st.currentDoh = doh
        dohProvider = doh
        for (pkg in BrowsecVpnService.routedPackages) {
            val prof = getOrCreateAppProfile(pkg)
            prof.countryCode = code
            prof.strategy = strategy
        }
        lastAutoHealEventRu = "🔗 Синхронизировано с Браузером: ${st.node.flag} ${st.node.countryRu} (${strategy.shortRu})"
    }

    fun syncToBrowserVpn() {
        val st = activeServerState()
        BrowserDirectRepeater.switchCountry(st.node.code, resetSessionTraffic = false)
        val bSt = BrowserDirectRepeater.getServerState(st.node.code)
        bSt.currentStrategy = st.currentStrategy
        bSt.currentDoh = st.currentDoh
        BrowserDirectRepeater.lastAutoHealEventRu = "🔗 Синхронизировано с VPN Приложений: ${st.node.flag} ${st.node.countryRu} (${st.currentStrategy.shortRu})"
    }

    fun restoreServerToOriginal(code: String = BrowsecVpnService.activeCountry) {
        val st = getServerState(code)
        st.restoreToOriginalState()
        lastAutoHealEventRu = "🟢 Сервер ${st.node.flag} ${st.node.countryRu} возвращён в исходное состояние (${st.originalStrategy.shortRu})"
    }

    fun triggerAutoHealOrFailover(targetState: ServerNodeRuntimeState = activeServerState()): String {
        val tuned = targetState.advanceTuningSetting()
        if (tuned) {
            FastMultiDnsResolver.clearCache()
            dohProvider = targetState.currentDoh
            val msg = "⚙️ Смена настройки (${targetState.node.flag} ${targetState.node.countryRu}): ${targetState.currentStrategy.shortRu}, DNS ${targetState.currentDoh}"
            lastAutoHealEventRu = msg
            return msg
        }

        val prevCode = targetState.node.code
        val prevLabel = "${targetState.node.flag} ${targetState.node.countryRu}"
        targetState.restoreToOriginalState()

        val idx = SERVER_NODES.indexOfFirst { it.code.equals(prevCode, ignoreCase = true) }
        val nextNode = SERVER_NODES[(if (idx >= 0) idx + 1 else 0) % SERVER_NODES.size]
        onServerCountrySwitched(nextNode.code, resetSessionTraffic = false)

        val msg = "🔄 Сервер $prevLabel возвращён в исходное состояние ➔ Переход на ${nextNode.flag} ${nextNode.countryRu}"
        lastAutoHealEventRu = msg
        onCountryAutoSwitched?.invoke(nextNode.code, msg)
        return msg
    }

    fun sampleTraffic(): TrafficSnapshot {
        ensureRunning()
        val st = activeServerState()
        val tunnelActive = useBrowsecTunnel && BrowsecVpnService.isRunning && !BrowsecVpnService.isPaused
        st.updateSpeedSample(isActiveServer = tunnelActive)
        for (pkg in BrowsecVpnService.routedPackages) {
            appProfiles[pkg]?.updateSpeed()
        }

        val node = st.node
        val rx = if (tunnelActive) st.rxBytes.get() else 0L
        val tx = if (tunnelActive) st.txBytes.get() else 0L
        val rxSpd = if (tunnelActive) st.rxSpeedBps else 0L
        val txSpd = if (tunnelActive) st.txSpeedBps else 0L
        val modeSuffix = if (tunnelActive) {
            "Туннель ВКЛ • ${st.currentStrategy.shortRu}"
        } else {
            "ВЫКЛ (Прямой режим без обхода)"
        }

        return TrafficSnapshot(
            rxBytesTotal = rx,
            txBytesTotal = tx,
            rxSpeedBytesPerSec = rxSpd,
            txSpeedBytesPerSec = txSpd,
            tunnelRxBytes = rx,
            tunnelTxBytes = tx,
            activeSockets = if (tunnelActive) st.activeConnections.get().coerceAtLeast(2) else 0,
            interceptedRequests = if (tunnelActive) st.requestsCount.get() else 0L,
            proxyPort = activePort,
            webViewInterceptActive = false,
            systemVpnActive = BrowsecVpnService.isRunning,
            isPaused = BrowsecVpnService.isPaused,
            activeCountry = node.code,
            activeCountryName = "${node.flag} ${node.countryRu} (${node.serverName} • $modeSuffix)",
            remoteServerIpPort = "${node.remoteIp}:${node.remotePort}",
            pingMs = st.pingMs,
            loadedAssetPackMb = 0,
            browsecTunnelModeActive = tunnelActive,
            currentStrategyLabel = st.currentStrategy.shortRu,
            autoHealStatusNote = lastAutoHealEventRu
        )
    }

    fun applyWebViewProxy(enabled: Boolean) {
        webViewProxyApplied = false
        BrowserDirectRepeater.ensureDirectBrowserMode()
    }

    @Synchronized
    fun ensureRunning(): Int {
        val existing = serverSocket
        if (running.get() && existing != null && !existing.isClosed) {
            startAppsHeartbeatIfNeeded()
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
                startAppsHeartbeatIfNeeded()
                return activePort
            } catch (_: Exception) {
            }
        }
        return activePort
    }

    private fun startAppsHeartbeatIfNeeded() {
        if (!heartbeatRunning.compareAndSet(false, true)) return
        thread(name = "AppsActiveProfileCheck", isDaemon = true) {
            var tick = 0
            while (running.get()) {
                try {
                    val tunnelActive = useBrowsecTunnel && BrowsecVpnService.isRunning && !BrowsecVpnService.isPaused
                    if (tunnelActive) {
                        val st = activeServerState()
                        st.updateSpeedSample(isActiveServer = true)
                        for (prof in appProfiles.values) {
                            prof.updateSpeed()
                        }
                        if (tick % 20 == 0) AppTrafficStore.flush(appProfiles.values)
                        AppTrafficStore.reportIdle(appProfiles.values)
                        if (tick % 10 == 0) {
                            VpnProfileProber.measureActiveLink(st)
                        }
                    }
                    tick++
                    Thread.sleep(1500L)
                } catch (_: Exception) {
                }
            }
            heartbeatRunning.set(false)
        }
    }

    @Synchronized
    fun shutdown() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        closeActiveSockets()
    }

    private fun acceptLoop(ss: ServerSocket) {
        var errors = 0
        while (running.get() && !ss.isClosed) {
            try {
                val client = ss.accept()
                errors = 0
                openClientSockets.add(client)
                // v4.0.2: определяем, какое приложение открыло соединение — его сервер, стратегия и счётчики
                executor.execute { handleClient(client, appProfile = profileForLoopbackClient(client)) }
            } catch (_: Exception) {
                if (!running.get() || ss.isClosed) break
                errors++
                try { Thread.sleep(if (errors > 50) 200L else 10L) } catch (_: InterruptedException) {}
            }
        }
    }

    // ===================== v4.0.2: чьё соединение + прозрачный туннель =====================

    private val uidPackageCache = ConcurrentHashMap<Int, String>()

    /** Профиль приложения-владельца TCP-соединения (Android 10+: getConnectionOwnerUid, доступно активному VPN). */
    fun profileForConnection(local: InetSocketAddress, remote: InetSocketAddress): AppTunnelProfile? {
        val routed = BrowsecVpnService.routedPackages
        val ctx = BrowsecVpnService.appContext
        if (ctx != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val cm = ctx.getSystemService(ConnectivityManager::class.java)
                val uid = cm?.getConnectionOwnerUid(OsConstants.IPPROTO_TCP, local, remote) ?: -1
                if (uid >= 0) {
                    val pkg = uidPackageCache[uid] ?: run {
                        val pkgs = ctx.packageManager.getPackagesForUid(uid) ?: emptyArray()
                        (pkgs.firstOrNull { routed.contains(it) } ?: pkgs.firstOrNull())?.also { uidPackageCache[uid] = it }
                    }
                    if (pkg != null && (routed.contains(pkg) || !BrowsecVpnService.perAppMode)) {
                        return getOrCreateAppProfile(pkg)
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (BrowsecVpnService.perAppMode && routed.size == 1) {
            return getOrCreateAppProfile(routed.first())
        }
        return null
    }

    /** v4.10.7: пакет-владелец UDP-сокета приложения (Android 10+), для диагностики WebRTC в журнале. */
    fun udpOwnerPackage(appPort: Int, remoteIp: Int, remotePort: Int): String? {
        val ctx = BrowsecVpnService.appContext ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val uid = cm?.getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(TunPackets.ipToInet(TunPackets.TUN_IP), appPort),
                InetSocketAddress(TunPackets.ipToInet(remoteIp), remotePort)
            ) ?: -1
            if (uid < 0) return null
            uidPackageCache[uid] ?: run {
                val routed = BrowsecVpnService.routedPackages
                val pkgs = ctx.packageManager.getPackagesForUid(uid) ?: emptyArray()
                (pkgs.firstOrNull { routed.contains(it) } ?: pkgs.firstOrNull())?.also { uidPackageCache[uid] = it }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun profileForLoopbackClient(client: Socket): AppTunnelProfile? = try {
        profileForConnection(
            InetSocketAddress(client.inetAddress, client.port),
            InetSocketAddress(client.localAddress, client.localPort)
        )
    } catch (_: Exception) {
        null
    }

    private var transparentServer: ServerSocket? = null

    @Volatile
    var transparentPort: Int = 0
        private set

    /** Обработчик прозрачного туннеля: сюда NAT направляет TCP выбранных приложений. */
    @Synchronized
    fun ensureTransparentListener(): Int {
        val existing = transparentServer
        if (existing != null && !existing.isClosed) return transparentPort
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(0), 512)
            transparentServer = ss
            transparentPort = ss.localPort
            thread(name = "AppsTransparent-$transparentPort", isDaemon = true) {
                var errors = 0
                while (!ss.isClosed) {
                    try {
                        val c = ss.accept()
                        errors = 0
                        openClientSockets.add(c)
                        executor.execute { handleTransparentClient(c) }
                    } catch (_: Exception) {
                        if (ss.isClosed) break
                        errors++
                        try { Thread.sleep(if (errors > 50) 200L else 10L) } catch (_: InterruptedException) {}
                    }
                }
            }
            transparentPort
        } catch (_: Exception) {
            0
        }
    }

    private fun handleTransparentClient(client: Socket) {
        // v4.1.0: соединение приходит loopback-сокетом из userspace TCP-стека; исходный адрес — по его локальному порту
        val sess = TransparentTunEngine.lookupSession(client.port)
        if (sess == null) {
            openClientSockets.remove(client)
            try { client.close() } catch (_: Exception) {}
            return
        }
        val remoteAddr = TunPackets.ipToInet(sess.remoteIp)
        val appPort = sess.appPort
        val origPort = sess.remotePort
        val profile = sess.profilePackage?.let { getOrCreateAppProfile(it) }
            ?: profileForConnection(
                InetSocketAddress(TunPackets.ipToInet(TunPackets.TUN_IP), appPort),
                InetSocketAddress(remoteAddr, origPort)
            )?.also { sess.profilePackage = it.packageName }
        val countryCode = profile?.countryCode ?: BrowsecVpnService.activeCountry
        val st = getServerState(countryCode)
        val allowBypass = useBrowsecTunnel && BrowsecVpnService.isRunning && !BrowsecVpnService.isPaused && antiDpiSplitEnabled
        st.activeConnections.incrementAndGet()
        st.requestsCount.incrementAndGet()
        profile?.activeSockets?.incrementAndGet()
        profile?.requests?.incrementAndGet()
        val logStart = System.currentTimeMillis()
        var logHost = remoteAddr.hostAddress ?: ""
        var logErr: String? = null
        var upstream: Socket? = null
        var viaTunnel: String? = null
        var rxConn = 0L
        try {
            client.tcpNoDelay = true
            val isDnsIp = TunPackets.inetToIp(remoteAddr) == TunPackets.DNS_IP
            // v4.4.8: Android «Приватный DNS: авто» щупает наш DNS по 853/TLS — раньше это уходило на 1.1.1.1:53 и висело 10 с.
            // Закрываем сразу: система тут же переходит на обычный DNS :53, который мы и обслуживаем.
            if (isDnsIp && origPort == 853) return
            val target = if (isDnsIp) {
                InetSocketAddress(InetAddress.getByName("1.1.1.1"), 53)
            } else {
                InetSocketAddress(remoteAddr, origPort)
            }

            // 1. Первые байты от приложения — имя сайта из TLS ClientHello (нужно и для обхода DPI, и для CONNECT через Browsec)
            val pin = PushbackInputStream(client.getInputStream(), 32 * 1024)
            var host = remoteAddr.hostAddress ?: ""
            if (origPort == 443 || origPort == 853 || origPort == 8443) {
                client.soTimeout = 1500
                val first = ByteArray(32 * 1024)
                val n = try { pin.read(first) } catch (_: SocketTimeoutException) { 0 }
                if (n < 0) return
                if (n > 0) {
                    TlsSni.extract(first, n)?.let { host = it }
                    logHost = host
                    pin.unread(first, 0, n)
                }
            }
            client.soTimeout = 10 * 60_000

            // 2. Маршрут: туннель Browsec (настоящая смена IP, как у браузера) → иначе напрямую с обходом DPI
            var upIn: InputStream? = null
            var up: Socket? = null
            // v4.1.1: любой TCP-порт (Telegram ходит на 5222 и по IP без SNI); если прокси откажет в порте — напрямую
            if (!isDnsIp && BrowsecVpnService.appsViaBrowsecTunnel && com.winwasm64.extensions.ExtensionProxy.appTunnelAvailable()) {
                val t0 = System.currentTimeMillis()
                var r = com.winwasm64.extensions.ExtensionProxy.openAppTunnel(host, origPort, countryCode, profile?.packageName)
                // v4.2.17: мгновенный null = кандидатов не было вовсе (пул в паузе). Не отказываем сразу — ждём до 4,5 с,
                // пока сервер освободится; Telegram такую задержку переживает, а мгновенный отказ повторяет 40 раз в секунду
                var waits = 0
                while (r == null && waits < 3 && System.currentTimeMillis() - t0 < 1_000L + waits * 1_600L &&
                    !com.winwasm64.extensions.ExtensionProxy.isDeadDest(host, origPort)) {
                    waits++
                    try { Thread.sleep(1_500) } catch (_: InterruptedException) { break }
                    r = com.winwasm64.extensions.ExtensionProxy.openAppTunnel(host, origPort, countryCode, profile?.packageName)
                }
                if (r != null) {
                    up = r.first
                    upIn = r.second
                    viaTunnel = r.third
                    st.pingMs = (System.currentTimeMillis() - t0).toInt().coerceAtLeast(1)
                }
            }
            if (up == null) {
                // v4.2.12: если напрямую к этому адресату недавно не пробились (провайдер режет Telegram/Google) — не ждём
                // ещё 6 с, а сразу отказываем: так Telegram быстрее повторит попытку, и она снова пойдёт через туннель
                val dk = "${target.address?.hostAddress ?: host}:$origPort"
                val failedAgo = System.currentTimeMillis() - (directFailAt[dk] ?: 0L)
                if (com.winwasm64.extensions.ExtensionProxy.isDeadDest(host, origPort) && failedAgo < 1_800_000L) {
                    try { Thread.sleep(1_200) } catch (_: InterruptedException) {}
                    throw java.io.IOException("адресат недоступен и через серверы Browsec, и напрямую — отказ сразу")
                }
                if (failedAgo < 300_000L) {
                    // v4.2.17: пауза перед отказом — иначе приложение повторяет попытку мгновенно и журнал/процессор тонут в отказах
                    try { Thread.sleep(1_200) } catch (_: InterruptedException) {}
                    val who = VpnLog.label(profile?.packageName)
                    if (System.currentTimeMillis() - (directSkipLoggedAt[dk] ?: 0L) > 120_000L) {
                        directSkipLoggedAt[dk] = System.currentTimeMillis()
                        VpnLog.w("Прил", "$who → $logHost:$origPort: серверы Browsec не ответили, а напрямую адресат был недоступен ${failedAgo / 1000} с назад — отказываем сразу, чтобы приложение быстрее повторило через туннель")
                    }
                    throw java.io.IOException("туннель не ответил, напрямую недоступно (проверено ${failedAgo / 1000} с назад)")
                }
                val s = Socket()
                BrowsecVpnService.protectSocket(s)
                s.tcpNoDelay = true
                s.keepAlive = true
                s.receiveBufferSize = 512 * 1024
                val t0 = System.currentTimeMillis()
                try {
                    s.connect(target, 6000)
                } catch (e: Exception) {
                    directFailAt[dk] = System.currentTimeMillis()
                    throw e
                }
                directFailAt.remove(dk)
                st.pingMs = (System.currentTimeMillis() - t0).toInt().coerceAtLeast(1)
                s.soTimeout = 10 * 60_000
                up = s
                upIn = s.getInputStream()
            }
            val upSock: Socket = up ?: throw java.io.IOException("нет исходящего соединения")
            val upStream: InputStream = upIn ?: upSock.getInputStream()
            upstream = upSock
            // Через туннель Browsec соединение уже внутри TLS к прокси — резать ClientHello не нужно
            val shouldBypass = viaTunnel == null && allowBypass && !host.endsWith(".gvt1.com") && !host.endsWith(".gvt2.com")
            val upOut = upSock.getOutputStream()
            val out = client.getOutputStream()
            val t1 = thread(name = "TunC2S-$origPort", isDaemon = true) {
                pipeClientToServerV38(pin, upOut, host, origPort, st, profile, shouldBypass)
                try { upSock.shutdownOutput() } catch (_: Exception) {}
            }
            rxConn = pipeServerToClientV38(upStream, out, st, profile, true)
            try { client.shutdownOutput() } catch (_: Exception) {}
            t1.join(2000)
        } catch (e: Exception) {
            logErr = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
        } finally {
            openClientSockets.remove(client)
            st.activeConnections.decrementAndGet()
            profile?.activeSockets?.decrementAndGet()
            try { upstream?.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
            VpnLog.conn(profile?.packageName, logHost, origPort, viaTunnel?.let { if (com.winwasm64.extensions.ExtensionProxy.isPsiphonKey(it)) "Psiphon" else "Browsec $it" } ?: countryCode, System.currentTimeMillis() - logStart, logErr, rxConn)
            profile?.noteConn(logErr == null, st.pingMs.toLong(), viaTunnel, logErr)
        }
    }

    private fun handleDedicatedAppClient(client: Socket, profile: AppTunnelProfile) {
        val isAppAllowed = BrowsecVpnService.isRunning &&
            !BrowsecVpnService.isPaused &&
            useBrowsecTunnel &&
            (!BrowsecVpnService.perAppMode || BrowsecVpnService.routedPackages.contains(profile.packageName))
        if (!isAppAllowed) {
            handleClient(client, appProfile = null, forceDirectWithoutBypass = true)
        } else {
            handleClient(client, appProfile = profile, forceDirectWithoutBypass = false)
        }
    }

    fun resolveHostSmart(host: String, serverState: ServerNodeRuntimeState = activeServerState()): String {
        return FastMultiDnsResolver.resolveFast(
            host = host,
            ecsSubnet = serverState.node.ecsSubnet,
            dohServer = serverState.currentDoh,
            onTraffic = { tx, rx ->
                serverState.recordTx(tx)
                serverState.recordRx(rx)
            }
        )
    }

    private fun openAppsOutboundSocket(
        host: String,
        port: Int,
        st: ServerNodeRuntimeState,
        appProfile: AppTunnelProfile?
    ): Socket {
        var lastErr: Exception? = null
        // v4.2.0: прокси приложений 127.0.0.1:89xx (Telegram с настроенным прокси) тоже идёт через туннель Browsec,
        // как и прозрачный туннель — иначе Telegram с прокси «Соединение…» на сети, где его IP режут
        // v4.4.1: сначала туннель (DNS адресата не нужен — его делает сервер Browsec), DoH-резолв только для прямого пути
        if (BrowsecVpnService.appsViaBrowsecTunnel && com.winwasm64.extensions.ExtensionProxy.appTunnelAvailable()) {
            try {
                val t0 = System.currentTimeMillis()
                val r = com.winwasm64.extensions.ExtensionProxy.openAppTunnel(host, port, appProfile?.countryCode ?: BrowsecVpnService.activeCountry, appProfile?.packageName)
                if (r != null) {
                    st.pingMs = (System.currentTimeMillis() - t0).toInt().coerceAtLeast(1)
                    return TunneledSocket(r.first, r.second, r.third)
                }
            } catch (e: Exception) {
                lastErr = e
            }
        }
        val candidates = FastMultiDnsResolver.buildCandidateIps(
            host = host,
            ecsSubnet = st.node.ecsSubnet,
            dohServer = st.currentDoh,
            onTraffic = { tx, rx ->
                st.recordTx(tx)
                st.recordRx(rx)
                appProfile?.rxBytes?.addAndGet(rx)
                appProfile?.txBytes?.addAndGet(tx)
            }
        )
        for (ip in candidates) {
            try {
                val t0 = System.currentTimeMillis()
                val sock = Socket()
                BrowsecVpnService.protectSocket(sock)
                sock.tcpNoDelay = true
                sock.keepAlive = true
                sock.receiveBufferSize = 512 * 1024
                sock.sendBufferSize = 256 * 1024
                sock.soTimeout = 30_000
                sock.connect(InetSocketAddress(ip, port), 2600)
                st.pingMs = (System.currentTimeMillis() - t0).toInt().coerceAtLeast(1)
                FastMultiDnsResolver.rememberWorkingIp(host, ip)
                return sock
            } catch (e: Exception) {
                lastErr = e
            }
        }
        st.consecutiveFailures.incrementAndGet()
        throw (lastErr ?: java.io.IOException("Failed to connect to $host:$port"))
    }

    /** Сокет внутри CONNECT-туннеля Browsec: входной поток уже обёрнут буфером, поэтому отдаём его вместо сырого. */
    private class TunneledSocket(private val inner: Socket, private val input: InputStream, val serverKey: String = "") : Socket() {
        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): java.io.OutputStream = inner.getOutputStream()
        override fun close() = inner.close()
        override fun isClosed(): Boolean = inner.isClosed
        override fun isConnected(): Boolean = inner.isConnected
        override fun setSoTimeout(timeout: Int) { inner.soTimeout = timeout }
        override fun getSoTimeout(): Int = inner.soTimeout
        override fun shutdownOutput() = inner.shutdownOutput()
        override fun shutdownInput() = inner.shutdownInput()
        override fun setTcpNoDelay(on: Boolean) { inner.tcpNoDelay = on }
        override fun setKeepAlive(on: Boolean) { inner.keepAlive = on }
        override fun getInetAddress(): InetAddress? = inner.inetAddress
        override fun getPort(): Int = inner.port
        override fun setReceiveBufferSize(size: Int) { try { inner.receiveBufferSize = size } catch (_: Exception) {} }
        override fun setSendBufferSize(size: Int) { try { inner.sendBufferSize = size } catch (_: Exception) {} }
        override fun getLocalPort(): Int = inner.localPort
        override fun getRemoteSocketAddress(): java.net.SocketAddress? = inner.remoteSocketAddress
    }

    private fun handleClient(
        client: Socket,
        appProfile: AppTunnelProfile?,
        forceDirectWithoutBypass: Boolean = false
    ) {
        val effectiveCountry = appProfile?.countryCode ?: BrowsecVpnService.activeCountry
        val st = getServerState(effectiveCountry)
        val allowBypass = !forceDirectWithoutBypass &&
            useBrowsecTunnel &&
            BrowsecVpnService.isRunning &&
            !BrowsecVpnService.isPaused &&
            antiDpiSplitEnabled

        if (allowBypass) {
            st.activeConnections.incrementAndGet()
            st.requestsCount.incrementAndGet()
            appProfile?.activeSockets?.incrementAndGet()
            appProfile?.requests?.incrementAndGet()
        }

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
                handleSocks5(inp, out, st, appProfile, allowBypass)
                return
            }
            if (firstByte == 0x04) {
                handleSocks4(inp, out, st, appProfile, allowBypass)
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
            if (allowBypass) {
                st.recordTx(rawHeaderBytes.size.toLong())
                appProfile?.txBytes?.addAndGet(rawHeaderBytes.size.toLong())
            }
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

                val isPlayStoreBinaryCdn = host.endsWith(".gvt1.com", ignoreCase = true) ||
                    host.endsWith(".gvt2.com", ignoreCase = true) ||
                    host.contains("play.googleapis.com", ignoreCase = true)

                // v4.4.1: этот путь (системный прокси VPN — так ходит YouTube/Cronet) теперь тоже в журнале и в счётчиках приложения
                val logStart = System.currentTimeMillis()
                var logErr: String? = null
                var viaKey: String? = null
                var rxConn = 0L
                try {
                    val upstream = openAppsOutboundSocket(host, port, st, appProfile)
                    viaKey = (upstream as? TunneledSocket)?.serverKey?.takeIf { it.isNotBlank() }
                    val established = "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII)
                    out.write(established)
                    out.flush()
                    if (allowBypass) {
                        st.recordRx(established.size.toLong())
                        appProfile?.rxBytes?.addAndGet(established.size.toLong())
                    }

                    val upIn = upstream.getInputStream()
                    val upOut = upstream.getOutputStream()

                    // внутри TLS-туннеля Browsec резать ClientHello не нужно (и вредно: 80+ микрозаписей на каждое соединение)
                    val shouldBypass = allowBypass && !isPlayStoreBinaryCdn && viaKey == null
                    val t1 = Thread {
                        pipeClientToServerV38(inp, upOut, host, port, st, appProfile, shouldBypass)
                    }
                    val t2 = Thread {
                        rxConn = pipeServerToClientV38(upIn, out, st, appProfile, allowBypass)
                    }
                    t1.start()
                    t2.start()
                    t1.join()
                    t2.join()
                    upstream.close()
                } catch (e: Exception) {
                    logErr = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
                    throw e
                } finally {
                    if (appProfile != null) {
                        VpnLog.conn(appProfile.packageName, host, port, (viaKey?.let { if (com.winwasm64.extensions.ExtensionProxy.isPsiphonKey(it)) "Psiphon" else "Browsec $it" } ?: effectiveCountry) + " · системный прокси", System.currentTimeMillis() - logStart, logErr, rxConn)
                        appProfile.noteConn(logErr == null, st.pingMs.toLong(), viaKey, logErr)
                    }
                }
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
                    val upstream = openAppsOutboundSocket(host, port, st, appProfile)
                    val upOut = upstream.getOutputStream()
                    val upIn = upstream.getInputStream()

                    val rebuilt = StringBuilder()
                    rebuilt.append("$method $pathOnly HTTP/1.1\r\n")
                    for (h in lines.drop(1)) {
                        if (h.isNotBlank() && !h.startsWith("Proxy-", ignoreCase = true)) {
                            rebuilt.append(h).append("\r\n")
                        }
                    }
                    if (allowBypass) {
                        rebuilt.append("X-Forwarded-For: ${st.node.remoteIp}\r\n")
                    }
                    rebuilt.append("\r\n")
                    val reqBytes = rebuilt.toString().toByteArray(Charsets.ISO_8859_1)
                    upOut.write(reqBytes)
                    upOut.flush()
                    if (allowBypass) {
                        st.recordTx(reqBytes.size.toLong())
                        appProfile?.txBytes?.addAndGet(reqBytes.size.toLong())
                    }

                    val t1 = Thread { pipeClientToServerV38(inp, upOut, host, port, st, appProfile, false) }
                    val t2 = Thread { pipeServerToClientV38(upIn, out, st, appProfile, allowBypass) }
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
            if (allowBypass) {
                st.activeConnections.decrementAndGet()
                appProfile?.activeSockets?.decrementAndGet()
            }
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handleSocks5(
        inp: InputStream,
        out: OutputStream,
        st: ServerNodeRuntimeState,
        appProfile: AppTunnelProfile?,
        allowBypass: Boolean
    ) {
        val nMethods = inp.read()
        if (nMethods <= 0) return
        val methods = ByteArray(nMethods)
        inp.read(methods)
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
                if (len <= 0) return
                val domain = ByteArray(len)
                inp.read(domain)
                String(domain, Charsets.US_ASCII)
            }
            0x04 -> {
                val ip6 = ByteArray(16)
                inp.read(ip6)
                InetAddress.getByAddress(ip6).hostAddress ?: return
            }
            else -> return
        }
        val port = (inp.read() shl 8) or inp.read()
        val upstream = openAppsOutboundSocket(host, port, st, appProfile)

        out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        out.flush()

        val upIn = upstream.getInputStream()
        val upOut = upstream.getOutputStream()
        val t1 = Thread { pipeClientToServerV38(inp, upOut, host, port, st, appProfile, allowBypass) }
        val t2 = Thread { pipeServerToClientV38(upIn, out, st, appProfile, allowBypass) }
        t1.start()
        t2.start()
        t1.join()
        t2.join()
        upstream.close()
    }

    private fun handleSocks4(
        inp: InputStream,
        out: OutputStream,
        st: ServerNodeRuntimeState,
        appProfile: AppTunnelProfile?,
        allowBypass: Boolean
    ) {
        val cmd = inp.read()
        if (cmd != 0x01) return
        val port = (inp.read() shl 8) or inp.read()
        val ipBytes = ByteArray(4)
        inp.read(ipBytes)
        while (true) {
            val b = inp.read()
            if (b <= 0) break
        }
        val host = if (ipBytes[0].toInt() == 0 && ipBytes[1].toInt() == 0 && ipBytes[2].toInt() == 0 && ipBytes[3].toInt() != 0) {
            val dom = ByteArrayOutputStream()
            while (true) {
                val b = inp.read()
                if (b <= 0) break
                dom.write(b)
            }
            String(dom.toByteArray(), Charsets.US_ASCII)
        } else {
            InetAddress.getByAddress(ipBytes).hostAddress ?: return
        }
        val upstream = openAppsOutboundSocket(host, port, st, appProfile)
        out.write(byteArrayOf(0x00, 0x5A, 0x00, 0x00, 0, 0, 0, 0))
        out.flush()

        val upIn = upstream.getInputStream()
        val upOut = upstream.getOutputStream()
        val t1 = Thread { pipeClientToServerV38(inp, upOut, host, port, st, appProfile, allowBypass) }
        val t2 = Thread { pipeServerToClientV38(upIn, out, st, appProfile, allowBypass) }
        t1.start()
        t2.start()
        t1.join()
        t2.join()
        upstream.close()
    }

    private fun pipeClientToServerV38(
        inp: InputStream,
        out: OutputStream,
        host: String,
        port: Int,
        st: ServerNodeRuntimeState,
        appProfile: AppTunnelProfile?,
        allowBypass: Boolean
    ) {
        try {
            val buf = ByteArray(32 * 1024)
            var firstPacket = true
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                if (allowBypass) {
                    st.recordTx(n.toLong())
                    appProfile?.txBytes?.addAndGet(n.toLong())
                }
                if (firstPacket) {
                    firstPacket = false
                    SmartAntiDpiPacketSender.readFullClientHelloAndSend(
                        inp = inp,
                        out = out,
                        buf = buf,
                        initialRead = n,
                        hostOrIp = host,
                        port = port,
                        strategy = appProfile?.strategy ?: st.currentStrategy,
                        bypassEnabled = allowBypass,
                        onExtraTxBytes = { extra ->
                            if (allowBypass) {
                                st.recordTx(extra)
                                appProfile?.txBytes?.addAndGet(extra)
                            }
                        }
                    )
                } else {
                    out.write(buf, 0, n)
                    out.flush()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun pipeServerToClientV38(
        inp: InputStream,
        out: OutputStream,
        st: ServerNodeRuntimeState,
        appProfile: AppTunnelProfile?,
        allowBypass: Boolean
    ): Long {
        var total = 0L
        try {
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                total += n
                if (allowBypass) {
                    st.recordRx(n.toLong())
                    appProfile?.rxBytes?.addAndGet(n.toLong())
                }
                out.write(buf, 0, n)
                out.flush()
            }
        } catch (_: Exception) {
        }
        return total
    }

    /**
     * Поддерживает оба режима:
     * - `microSplit = true`: нарезает первые `min(recLen, 260)` байт ClientHello на валидные
     *   TLS-записи по 3 байта (как в v3.6.0), чтобы ТСПУ не мог собрать `m.youtube.com` или `googlevideo.com`.
     * - `microSplit = false`: разрезает TLS ClientHello ровно на 2 валидные TLS-записи прямо посередине SNI,
     *   что идеально подходит для Cloudflare (`gofile.io`, `api.gofile.io`).
     */
    internal fun buildSniSplitTlsRecords(
        raw: ByteArray,
        len: Int,
        microSplit: Boolean = true
    ): List<ByteArray> {
        if (len <= 24 || raw[0] != 0x16.toByte() || raw[1] != 0x03.toByte()) {
            return listOf(raw.copyOf(len))
        }
        val recLen = ((raw[3].toInt() and 0xFF) shl 8) or (raw[4].toInt() and 0xFF)
        if (5 + recLen > len || recLen <= 16) {
            return listOf(raw.copyOf(len))
        }

        if (microSplit) {
            val result = ArrayList<ByteArray>()
            val microRegionEnd = minOf(recLen, 260)
            var offset = 0
            while (offset < microRegionEnd) {
                val chunkLen = minOf(3, microRegionEnd - offset)
                val rec = ByteArray(5 + chunkLen)
                rec[0] = 0x16
                rec[1] = raw[1]
                rec[2] = raw[2]
                rec[3] = ((chunkLen shr 8) and 0xFF).toByte()
                rec[4] = (chunkLen and 0xFF).toByte()
                System.arraycopy(raw, 5 + offset, rec, 5, chunkLen)
                result.add(rec)
                offset += chunkLen
            }
            val remaining = recLen - microRegionEnd
            if (remaining > 0) {
                val tailRec = ByteArray(5 + remaining)
                tailRec[0] = 0x16
                tailRec[1] = raw[1]
                tailRec[2] = raw[2]
                tailRec[3] = ((remaining shr 8) and 0xFF).toByte()
                tailRec[4] = (remaining and 0xFF).toByte()
                System.arraycopy(raw, 5 + microRegionEnd, tailRec, 5, remaining)
                result.add(tailRec)
            }
            return result
        }

        val verMajor = raw[1]
        val verMinor = raw[2]
        var splitPos = 16.coerceAtMost(recLen / 2)
        for (i in 40 until (5 + recLen - 6)) {
            val prev = raw[i - 1].toInt() and 0xFF
            val next = raw[i + 1].toInt() and 0xFF
            val isPrevHost = (prev in 'a'.code..'z'.code) || (prev in '0'.code..'9'.code) || prev == '-'.code
            val isNextHost = (next in 'a'.code..'z'.code) || (next in '0'.code..'9'.code)
            if (raw[i] == '.'.code.toByte() && isPrevHost && isNextHost) {
                val candidate = (i + 1) - 5
                if (candidate in 8 until (recLen - 6)) {
                    splitPos = candidate
                    break
                }
            }
        }

        val part1Len = splitPos
        val part2Len = recLen - splitPos

        val rec1 = ByteArray(5 + part1Len)
        rec1[0] = 0x16
        rec1[1] = verMajor
        rec1[2] = verMinor
        rec1[3] = ((part1Len shr 8) and 0xFF).toByte()
        rec1[4] = (part1Len and 0xFF).toByte()
        System.arraycopy(raw, 5, rec1, 5, part1Len)

        val rec2 = ByteArray(5 + part2Len)
        rec2[0] = 0x16
        rec2[1] = verMajor
        rec2[2] = verMinor
        rec2[3] = ((part2Len shr 8) and 0xFF).toByte()
        rec2[4] = (part2Len and 0xFF).toByte()
        System.arraycopy(raw, 5 + part1Len, rec2, 5, part2Len)

        return listOf(rec1, rec2)
    }
}

/**
 * ============================================================================
 * СИСТЕМНЫЙ VPN-СЕРВИС (ШТОРКА С ЖИВОЙ СКОРОСТЬЮ КАЖДОГО VPN ОТДЕЛЬНО + PER-APP TUN)
 * ============================================================================
 */
// v4.2.12: память «напрямую к адресату не пробиться» (ключ ip:port → время), чтобы не ждать 6 с на каждом повторе
private val directFailAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
private val directSkipLoggedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

class BrowsecVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.winwasm64.vpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.winwasm64.vpn.ACTION_DISCONNECT"

        // v4.2.13: сводка сеанса VPN приложений — сколько работал и сколько трафика съело каждое приложение
        @Volatile var sessionStartedAt = 0L
            private set
        @Volatile private var sessionBase: Map<String, Pair<Long, Long>> = emptyMap()

        private fun durRu(ms: Long): String {
            val s = ms / 1000
            return when {
                s >= 3600 -> "${s / 3600} ч ${(s % 3600) / 60} мин"
                s >= 60 -> "${s / 60} мин ${s % 60} с"
                else -> "$s с"
            }
        }

        fun logSessionSummary() {
            val start = sessionStartedAt
            if (start == 0L) return
            sessionStartedAt = 0L
            val dur = System.currentTimeMillis() - start
            var rxAll = 0L; var txAll = 0L
            val parts = ArrayList<String>()
            try {
                for (p in BrowsecLocalProxyServer.getAllAppProfiles()) {
                    val base = sessionBase[p.packageName] ?: (0L to 0L)
                    val rx = (p.rxBytes.get() - base.first).coerceAtLeast(0L)
                    val tx = (p.txBytes.get() - base.second).coerceAtLeast(0L)
                    rxAll += rx; txAll += tx
                    if (rx + tx > 0) parts.add("${VpnLog.label(p.packageName)} ↓${formatBytesRu(rx)} ↑${formatBytesRu(tx)}, активно ${durRu(p.activeMs.get())}, соединений ${p.connOk.get()}" + (if (p.connFail.get() > 0) " (ошибок ${p.connFail.get()})" else "") + (if (p.avgConnectMs() > 0) ", подключение ср. ${p.avgConnectMs()} мс" else ""))
                }
                AppTrafficStore.flush(BrowsecLocalProxyServer.getAllAppProfiles())
            } catch (_: Exception) {}
            VpnLog.i("Туннель", "VPN приложений работал ${durRu(dur)} · трафик ↓${formatBytesRu(rxAll)} ↑${formatBytesRu(txAll)}" +
                (if (parts.isNotEmpty()) " · " + parts.joinToString(", ") else " · приложения трафик не передавали") +
                (if (dur > 60_000L) " · средняя ↓${formatBytesRu(rxAll * 1000 / dur)}/с" else ""))
        }

        const val ACTION_PAUSE = "com.winwasm64.vpn.ACTION_PAUSE"
        const val ACTION_RESUME = "com.winwasm64.vpn.ACTION_RESUME"
        const val ACTION_SWITCH_COUNTRY = "com.winwasm64.vpn.ACTION_SWITCH_COUNTRY"
        const val ACTION_SYNC_VPNS = "com.winwasm64.vpn.ACTION_SYNC_VPNS"
        const val ACTION_OPEN_SERVER_PICKER = "com.winwasm64.vpn.ACTION_OPEN_SERVER_PICKER"
        const val EXTRA_COUNTRY = "extra_country"

        private const val CHANNEL_ID = "browsec_vpn_live_channel_v38"
        private const val NOTIFICATION_ID = 4402

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isPaused: Boolean = false
            private set

        @Volatile
        var activeCountry: String = "LT"

        @Volatile
        var perAppMode: Boolean = true

        @Volatile
        var disconnectOnAppClose: Boolean = true

        @Volatile
        var openServerPickerRequestCount: Int = 0

        @Volatile
        var requestOpenServerPickerDialog: Boolean = false

        @Volatile
        private var activeInstance: BrowsecVpnService? = null

        /** Контекст приложения (нужен, чтобы понять, какому приложению принадлежит соединение). */
        @Volatile
        var appContext: Context? = null

        /** Туннель реально поднят системой (в статус-баре есть значок ключа). */
        @Volatile
        var tunnelEstablished: Boolean = false

        /** Прозрачный режим: весь TCP выбранных приложений идёт через обход DPI, QUIC переводится на TCP. */
        @Volatile
        var transparentActive: Boolean = false

        /** Прозрачный обработчик не поднялся (нет свободного порта) — работаем через системный прокси. */
        @Volatile
        var transparentUnsupported: Boolean = false

        /**
         * v4.1.0: приложения из списка идут через серверы Browsec (настоящая смена IP, как у браузера с расширением),
         * если расширение Browsec установлено и включено; иначе — напрямую с обходом DPI.
         */
        @Volatile
        var appsViaBrowsecTunnel: Boolean = true

        const val PREF_APPS_VIA_BROWSEC = "apps_via_browsec_tunnel"

        fun setAppsViaBrowsecTunnel(context: Context, on: Boolean) {
            appsViaBrowsecTunnel = on
            context.getSharedPreferences("omni_browser_v21_prefs", Context.MODE_PRIVATE).edit().putBoolean(PREF_APPS_VIA_BROWSEC, on).apply()
            VpnLog.i("Сервис", if (on) "приложения из списка идут через серверы Browsec (если расширение включено)" else "приложения из списка идут напрямую с обходом DPI")
        }

        val routedPackages = CopyOnWriteArraySet<String>().apply {
            add("com.google.android.youtube")
            add("com.android.vending")
        }

        fun protectSocket(socket: Socket) {
            try {
                activeInstance?.protect(socket)
            } catch (_: Exception) {
            }
        }

        fun protectDatagramSocket(socket: DatagramSocket) {
            try {
                activeInstance?.protect(socket)
            } catch (_: Exception) {
            }
        }

        fun startOrUpdate(context: Context, country: String = activeCountry) {
            activeCountry = country.uppercase()
            BrowsecLocalProxyServer.initAssetBundleIfNeeded(context)
            BrowsecLocalProxyServer.ensureRunning()
            BrowsecLocalProxyServer.onServerCountrySwitched(activeCountry)
            val intent = Intent(context, BrowsecVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_COUNTRY, activeCountry)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                isRunning = true
                isPaused = false
            }
        }

        fun pauseTunnel(context: Context) {
            isPaused = true
            BrowsecLocalProxyServer.useBrowsecTunnel = false
            BrowsecLocalProxyServer.closeActiveSockets()
            val intent = Intent(context, BrowsecVpnService::class.java).apply {
                action = ACTION_PAUSE
            }
            try { context.startService(intent) } catch (_: Exception) {}
        }

        fun resumeTunnel(context: Context) {
            isPaused = false
            BrowsecLocalProxyServer.useBrowsecTunnel = true
            val intent = Intent(context, BrowsecVpnService::class.java).apply {
                action = ACTION_RESUME
            }
            try { context.startService(intent) } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            isRunning = false
            isPaused = false
            BrowsecLocalProxyServer.useBrowsecTunnel = false
            BrowsecLocalProxyServer.closeActiveSockets()
            val intent = Intent(context, BrowsecVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            try { context.startService(intent) } catch (_: Exception) {}
        }

        fun synchronizeBothVpns(context: Context? = null, fromBrowserToApps: Boolean = true) {
            if (fromBrowserToApps) {
                val bSt = BrowserDirectRepeater.getServerState()
                BrowsecLocalProxyServer.syncFromBrowserVpn(
                    browserCountry = bSt.node.code,
                    strategy = bSt.currentStrategy,
                    doh = bSt.currentDoh
                )
            } else {
                BrowsecLocalProxyServer.syncToBrowserVpn()
            }
        }

        fun startForApp(context: Context, country: String = activeCountry, packageName: String? = null) {
            if (!packageName.isNullOrBlank()) {
                routedPackages.add(packageName)
                BrowsecLocalProxyServer.getOrCreateAppProfile(packageName, country.uppercase())
            }
            startOrUpdate(context, country)
        }

        fun selectSpecificCountry(context: Context, countryCode: String, resetTraffic: Boolean = false) {
            activeCountry = countryCode.uppercase()
            BrowsecLocalProxyServer.onServerCountrySwitched(activeCountry, resetSessionTraffic = false)
            if (isRunning) {
                startOrUpdate(context, activeCountry)
            }
        }

        fun cycleCountry(context: Context): String {
            val idx = ALL_VPN_COUNTRIES_LIST.indexOfFirst { it.code.equals(activeCountry, ignoreCase = true) }
            val next = ALL_VPN_COUNTRIES_LIST[(if (idx >= 0) idx + 1 else 0) % ALL_VPN_COUNTRIES_LIST.size]
            selectSpecificCountry(context, next.code, resetTraffic = false)
            return next.code
        }

        fun sampleStats(): TrafficSnapshot = BrowsecLocalProxyServer.sampleTraffic()

        fun formatBytesShort(bytes: Long): String = formatBytesRu(bytes)

        fun formatSpeedShort(bytesPerSec: Long): String = formatSpeedRu(bytesPerSec)

        fun formatBytesRu(bytes: Long): String {
            if (bytes < 1024L) return "$bytes Б"
            val kb = bytes / 1024.0
            if (kb < 1024.0) return String.format("%.1f КБ", kb)
            val mb = kb / 1024.0
            if (mb < 1024.0) return String.format("%.2f МБ", mb)
            val gb = mb / 1024.0
            return String.format("%.2f ГБ", gb)
        }

        fun formatSpeedRu(bytesPerSec: Long): String {
            if (bytesPerSec <= 0L) return "0 КБ/с"
            if (bytesPerSec < 1024L) return "$bytesPerSec Б/с"
            val kb = bytesPerSec / 1024.0
            if (kb < 1024.0) return String.format("%.1f КБ/с", kb)
            val mb = kb / 1024.0
            return String.format("%.2f МБ/с", mb)
        }
    }

    private var perAppVpnInterface: ParcelFileDescriptor? = null
    private val notificationTickerRunning = AtomicBoolean(false)
    private val tunExecutor = Executors.newCachedThreadPool()

    override fun onCreate() {
        super.onCreate()
        com.winwasm64.CrashCatcher.install(this)
        activeInstance = this
        appContext = applicationContext
        VpnLog.init(this)
        appsViaBrowsecTunnel = getSharedPreferences("omni_browser_v21_prefs", Context.MODE_PRIVATE).getBoolean(PREF_APPS_VIA_BROWSEC, true)
        createNotificationChannel()
        BrowsecLocalProxyServer.initAssetBundleIfNeeded(this)
        BrowsecLocalProxyServer.ensureRunning()
        BrowserDirectRepeater.ensureServerRunning()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        VpnLog.i("Сервис", "команда: " + (intent?.action?.substringAfterLast('.') ?: "запуск") + (intent?.getStringExtra(EXTRA_COUNTRY)?.let { " ($it)" } ?: ""))
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                logSessionSummary()
                // v4.4.3: Psiphon гасим вместе с VPN приложений, если браузер им не пользовался последние 10 мин
                if (android.os.SystemClock.elapsedRealtime() - PsiphonBackup.lastUsedAt > 600_000L) PsiphonBackup.stop()
                isRunning = false
                isPaused = false
                AppsVpnTileService.refresh(this)
                BrowsecLocalProxyServer.useBrowsecTunnel = false
                closeVpnInterface()
                stopForeground( true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                isPaused = true
                BrowsecLocalProxyServer.useBrowsecTunnel = false
                closeVpnInterface()
                updateNotificationShade()
                return START_STICKY
            }
            ACTION_RESUME -> {
                isPaused = false
                isRunning = true
                BrowsecLocalProxyServer.useBrowsecTunnel = true
                rebuildPerAppVpnInterface()
                startLiveNotificationTicker()
                updateNotificationShade()
                return START_STICKY
            }
            ACTION_SWITCH_COUNTRY -> {
                val chosen = intent.getStringExtra(EXTRA_COUNTRY)
                if (!chosen.isNullOrBlank()) {
                    activeCountry = chosen.uppercase()
                } else {
                    val idx = ALL_VPN_COUNTRIES_LIST.indexOfFirst { it.code.equals(activeCountry, ignoreCase = true) }
                    val next = ALL_VPN_COUNTRIES_LIST[(idx + 1) % ALL_VPN_COUNTRIES_LIST.size]
                    activeCountry = next.code
                }
                BrowsecLocalProxyServer.onServerCountrySwitched(activeCountry, resetSessionTraffic = false)
                if (isRunning && !isPaused) {
                    rebuildPerAppVpnInterface()
                }
                updateNotificationShade()
                return START_STICKY
            }
            ACTION_SYNC_VPNS -> {
                synchronizeBothVpns(fromBrowserToApps = true)
                if (isRunning && !isPaused) {
                    if (perAppVpnInterface != null && builtConfigKey == currentConfigKey()) {
                        VpnLog.i("Туннель", "синхронизация: список и страна не изменились — туннель не пересоздаём")
                    } else {
                        rebuildPerAppVpnInterface()
                    }
                }
                updateNotificationShade()
                return START_STICKY
            }
            else -> {
                intent?.getStringExtra(EXTRA_COUNTRY)?.let {
                    if (it.isNotBlank()) activeCountry = it.uppercase()
                }
                isRunning = true
                isPaused = false
                AppsVpnTileService.refresh(this)
                transparentUnsupported = false // v4.1.0: каждое включение снова пробует прозрачный режим
                BrowsecLocalProxyServer.useBrowsecTunnel = true
                BrowsecLocalProxyServer.ensureRunning()
                BrowsecLocalProxyServer.onServerCountrySwitched(activeCountry, resetSessionTraffic = false)
                // v4.2.16: повторный ACTION_CONNECT с тем же списком и страной (кнопка «Запустить через VPN», синхронизация)
                // раньше пересоздавал интерфейс и рвал все соединения приложений через 5–7 с после подключения
                if (perAppVpnInterface != null && builtConfigKey == currentConfigKey()) {
                    VpnLog.i("Туннель", "VPN приложений уже работает с этим списком (${VpnLog.appList(routedPackages)}) — соединения не трогаем")
                } else {
                    rebuildPerAppVpnInterface()
                }
                startForeground(NOTIFICATION_ID, buildLiveNotification())
                startLiveNotificationTicker()
                return START_STICKY
            }
        }
    }

    private fun startLiveNotificationTicker() {
        if (!notificationTickerRunning.compareAndSet(false, true)) return
        thread(name = "BrowsecShadeLiveTicker", isDaemon = true) {
            while (isRunning) {
                try {
                    BrowserDirectRepeater.sampleBrowserTraffic()
                    BrowsecLocalProxyServer.sampleTraffic()
                    updateNotificationShade()
                    Thread.sleep(1000L)
                } catch (_: Exception) {
                }
            }
            notificationTickerRunning.set(false)
        }
    }

    @Volatile
    private var tunEngine: TransparentTunEngine? = null

    @Volatile private var builtConfigKey: String = ""
    private fun currentConfigKey(): String = activeCountry + "|" + perAppMode + "|" + routedPackages.sorted().joinToString(",")

    @Synchronized
    private fun rebuildPerAppVpnInterface() {
        builtConfigKey = currentConfigKey()
        if (perAppVpnInterface != null) {
            // v4.2.15: Android не меняет список приложений у живого VPN — интерфейс пересоздаётся, соединения приложений закрываются
            VpnLog.i("Туннель", "список приложений/страна изменены — туннель пересоздаётся, текущие соединения приложений закроются и переустановятся сами")
        }
        closeVpnInterface()
        if (!isRunning || isPaused) return
        try {
            val port = BrowsecLocalProxyServer.ensureRunning()
            val transparentPort = BrowsecLocalProxyServer.ensureTransparentListener()
            val wantTransparent = !transparentUnsupported && transparentPort > 0
            val builder = Builder()
                .setSession("OmniBrowser Per-App VPN ($activeCountry)")
                .setMtu(1500)
                .addAddress("10.215.173.1", 24)
                .addDnsServer("10.215.173.2")
            if (wantTransparent) {
                // Весь трафик выбранных приложений идёт в туннель: TCP — через обход DPI, QUIC — переводится на TCP
                builder.addRoute("0.0.0.0", 0)
                try {
                    builder.addAddress("fd00:7a:d7ad::1", 64)
                    builder.addRoute("::", 0)
                } catch (_: Exception) {
                }
            } else {
                builder.addRoute("10.215.173.2", 32)
            }
            try { builder.setBlocking(false) } catch (_: Exception) {}

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
                builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", port))
            }

            if (perAppMode) {
                var addedCount = 0
                for (pkg in routedPackages) {
                    if (pkg.isNotBlank() && pkg != packageName) {
                        try {
                            builder.addAllowedApplication(pkg)
                            BrowsecLocalProxyServer.getOrCreateAppProfile(pkg)
                            addedCount++
                        } catch (_: Exception) {
                        }
                    }
                }
                if (addedCount == 0) {
                    VpnLog.w("Туннель", "VPN приложений включён, но список пуст — ни одно приложение не идёт через VPN")
                    return
                }
            } else {
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (_: Exception) {
                }
            }

            val pfd = builder.establish()
            perAppVpnInterface = pfd
            if (pfd == null) VpnLog.e("Туннель", "Android не создал VPN-интерфейс (нет разрешения VPN или включено другое VPN-приложение)")
            else VpnLog.ok("Туннель", "VPN приложений поднят, профиль $activeCountry: " + VpnLog.appList(routedPackages) + (if (wantTransparent) " · прозрачный режим" else " · режим прокси"))
            try { PsiphonBackup.ensureStarted(this, "VPN приложений") } catch (_: Throwable) {}
            if (pfd != null && sessionStartedAt == 0L) {
                // v4.2.13: точка отсчёта сеанса — время работы и трафик по приложениям покажем при отключении
                sessionStartedAt = System.currentTimeMillis()
                sessionBase = BrowsecLocalProxyServer.getAllAppProfiles().associate { it.packageName to (it.rxBytes.get() to it.txBytes.get()) }
            }
            if (pfd == null) {
                tunnelEstablished = false
                transparentActive = false
                return
            }
            tunnelEstablished = true
            val engine = TransparentTunEngine(
                pfd = pfd,
                listenerPort = transparentPort,
                protectDatagram = { sock -> try { protect(sock) } catch (_: Exception) {} },
                onDnsQuery = { pkt, len, ihl, out ->
                    val st = BrowsecLocalProxyServer.activeServerState()
                    st.recordTx((len - ihl - 8).toLong().coerceAtLeast(0L))
                    tunExecutor.execute {
                        forwardTunDnsQuery(pkt.copyOf(ihl + 8), ihl, pkt.copyOfRange(ihl + 8, len), out, st)
                    }
                },
                onUdpTraffic = { tx, rx ->
                    val st = BrowsecLocalProxyServer.activeServerState()
                    st.recordTx(tx)
                    st.recordRx(rx)
                },
                udpOwner = { appPort, remoteIp, remotePort -> BrowsecLocalProxyServer.udpOwnerPackage(appPort, remoteIp, remotePort) }
            )
            tunEngine = engine
            engine.start()
            transparentActive = wantTransparent
            if (wantTransparent) {
                thread(name = "OmniTunSelfTest", isDaemon = true) {
                    // v4.1.0: userspace TCP-стек не зависит от маршрутизации ядра — проверяем только, что обработчик принимает соединения
                    val ok = engine.selfTest()
                    if (ok) VpnLog.ok("Туннель", "прозрачный туннель готов: TCP приложений → userspace-стек → обработчик :$transparentPort" +
                        (if (appsViaBrowsecTunnel && com.winwasm64.extensions.ExtensionProxy.appTunnelAvailable()) " → серверы Browsec" else " → обход DPI"))
                    else if (tunEngine === engine && isRunning && !isPaused) {
                        VpnLog.w("Туннель", "обработчик :$transparentPort не принимает соединения → режим прокси")
                        transparentUnsupported = true
                        transparentActive = false
                        rebuildPerAppVpnInterface()
                        updateNotificationShade()
                    }
                }
            }
        } catch (e: Exception) {
            tunnelEstablished = perAppVpnInterface != null
            VpnLog.e("Туннель", "ошибка подъёма VPN-интерфейса: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun forwardTunDnsQuery(
        ipUdpHeader: ByteArray,
        ihl: Int,
        dnsQuery: ByteArray,
        tunOut: OutputStream,
        st: ServerNodeRuntimeState
    ) {
        val appPort = ((ipUdpHeader[ihl].toInt() and 0xFF) shl 8) or (ipUdpHeader[ihl + 1].toInt() and 0xFF)
        fun reply(payload: ByteArray) {
            val pkt = TunPackets.buildUdpV4(TunPackets.DNS_IP, 53, TunPackets.TUN_IP, appPort, payload, 0, payload.size)
            synchronized(tunOut) {
                tunOut.write(pkt, 0, pkt.size)
                tunOut.flush()
            }
            st.recordRx(pkt.size.toLong())
        }
        try {
            // v4.1.0: A-запросы — через DoH-резолвер сервиса (нельзя доверять ответам провайдера), AAAA/HTTPS — пусто (IPv4 через туннель)
            val q = DnsPackets.parseQuestion(dnsQuery, dnsQuery.size)
            if (q != null && q.clazz == 1 && q.name.isNotBlank()) {
                when (q.type) {
                    DnsPackets.TYPE_AAAA, DnsPackets.TYPE_HTTPS -> {
                        reply(DnsPackets.buildResponse(dnsQuery, q, emptyList()))
                        return
                    }
                    DnsPackets.TYPE_A -> {
                        // v4.1.1: сначала DoH через туннель Browsec (провайдер подменяет и UDP-DNS, и прямые DoH), потом обычный резолвер
                        val viaTunnel = if (BrowsecVpnService.appsViaBrowsecTunnel && com.winwasm64.extensions.ExtensionProxy.appTunnelAvailable())
                            com.winwasm64.extensions.ExtensionProxy.resolveViaTunnel(q.name, st.node.ecsSubnet) else null
                        val ip = viaTunnel ?: FastMultiDnsResolver.resolveFast(
                            host = q.name,
                            ecsSubnet = st.node.ecsSubnet,
                            dohServer = st.currentDoh,
                            onTraffic = { tx, rx -> st.recordTx(tx); st.recordRx(rx) }
                        )
                        if (FastMultiDnsResolver.isIpv4Literal(ip)) {
                            reply(DnsPackets.buildResponse(dnsQuery, q, listOf(ip)))
                            return
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        // Прочие типы (или резолвер не ответил): обычный UDP-форвард на DNS профиля
        var udp: DatagramSocket? = null
        try {
            udp = DatagramSocket()
            protect(udp)
            udp.soTimeout = 1500
            val dnsServer = InetAddress.getByName(st.currentDoh.takeIf { FastMultiDnsResolver.isIpv4Literal(it) } ?: "1.1.1.1")
            udp.send(DatagramPacket(dnsQuery, dnsQuery.size, dnsServer, 53))
            val respBuf = ByteArray(1400)
            val respPkt = DatagramPacket(respBuf, respBuf.size)
            udp.receive(respPkt)
            val dnsRespLen = respPkt.length
            if (dnsRespLen <= 0) return
            reply(respBuf.copyOf(dnsRespLen))
        } catch (_: Exception) {
        } finally {
            try { udp?.close() } catch (_: Exception) {}
        }
    }

    @Synchronized
    private fun closeVpnInterface() {
        try { tunEngine?.stop() } catch (_: Exception) {}
        tunEngine = null
        try { perAppVpnInterface?.close() } catch (_: Exception) {}
        perAppVpnInterface = null
        tunnelEstablished = false
        transparentActive = false
    }

    override fun onDestroy() {
        isRunning = false
        isPaused = false
        AppsVpnTileService.refresh(this)
        closeVpnInterface()
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    private fun updateNotificationShade() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.notify(NOTIFICATION_ID, buildLiveNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OmniBrowser Live VPN Speed & Server Control",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Живая входящая и исходящая скорость каждого VPN по отдельности и выбор сервера"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildLiveNotification(): Notification {
        val bSnap = BrowserDirectRepeater.sampleBrowserTraffic()
        val aSnap = BrowsecLocalProxyServer.sampleTraffic()
        val bNode = BrowserDirectRepeater.getNode(bSnap.activeCountry)
        val aNode = BrowsecLocalProxyServer.getServerNode(aSnap.activeCountry)

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pickServerIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_SERVER_PICKER
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pickServerPi = PendingIntent.getActivity(
            this, 1, pickServerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val syncIntent = Intent(this, BrowsecVpnService::class.java).apply {
            action = ACTION_SYNC_VPNS
        }
        val syncPi = PendingIntent.getService(
            this, 2, syncIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, BrowsecVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val stopPi = PendingIntent.getService(
            this, 3, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isPaused) {
            "⏸ VPN Приложений: ПАУЗА | Браузер: ${bNode.flag} ${bNode.code} (${bSnap.pingMs} мс)"
        } else {
            "🌐 ${bNode.flag}${bNode.code} ⚡${bSnap.pingMs}мс ⬇${formatSpeedRu(bSnap.rxSpeedBytesPerSec)} ⬆${formatSpeedRu(bSnap.txSpeedBytesPerSec)} | 📱 ${aNode.flag}${aNode.code} ⚡${aSnap.pingMs}мс ⬇${formatSpeedRu(aSnap.rxSpeedBytesPerSec)} ⬆${formatSpeedRu(aSnap.txSpeedBytesPerSec)}"
        }

        val shortLine = "Браузер(${bNode.code} • ${bSnap.pingMs}мс): ⬇${formatBytesRu(bSnap.rxBytesTotal)} ⬆${formatBytesRu(bSnap.txBytesTotal)} • Прил.(${aNode.code} • ${aSnap.pingMs}мс): ⬇${formatBytesRu(aSnap.rxBytesTotal)} ⬆${formatBytesRu(aSnap.txBytesTotal)}"

        val perAppSummary = buildString {
            appendLine("1️⃣ БРАУЗЕР VPN (${bNode.flag} ${bNode.countryRu} • 127.0.0.1:${bSnap.proxyPort} • Пинг: ${bSnap.pingMs} мс • ${bSnap.currentStrategyLabel}):")
            appendLine("   Скорость сейчас: ⬇ ${formatSpeedRu(bSnap.rxSpeedBytesPerSec)} | ⬆ ${formatSpeedRu(bSnap.txSpeedBytesPerSec)}")
            appendLine("   Трафик сервера ${bNode.code}: ⬇ ${formatBytesRu(bSnap.rxBytesTotal)} | ⬆ ${formatBytesRu(bSnap.txBytesTotal)}")
            appendLine("2️⃣ ПРИЛОЖЕНИЯ VPN (${aNode.flag} ${aNode.countryRu} • 127.0.0.1:${aSnap.proxyPort} • Пинг: ${aSnap.pingMs} мс • ${aSnap.currentStrategyLabel}):")
            appendLine("   Скорость сейчас: ⬇ ${formatSpeedRu(aSnap.rxSpeedBytesPerSec)} | ⬆ ${formatSpeedRu(aSnap.txSpeedBytesPerSec)}")
            appendLine("   Трафик сервера ${aNode.code}: ⬇ ${formatBytesRu(aSnap.rxBytesTotal)} | ⬆ ${formatBytesRu(aSnap.txBytesTotal)}")
            val profiles = BrowsecLocalProxyServer.getAllAppProfiles()
            if (profiles.isNotEmpty()) {
                appendLine("🎯 Индивидуальные туннели приложений (${profiles.size}):")
                for (p in profiles.take(3)) {
                    val shortPkg = p.packageName.substringAfterLast('.')
                    appendLine("   • $shortPkg [${p.countryCode} :${p.localPort}]: ⬇${formatSpeedRu(p.rxSpeedBps)} ⬆${formatSpeedRu(p.txSpeedBps)} (${formatBytesRu(p.rxBytes.get())})")
                }
            }
        }.trim()

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(shortLine)
            .setStyle(Notification.BigTextStyle().bigText(perAppSummary))
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_mylocation, "🎯 Выбрать сервер", pickServerPi)
            .addAction(android.R.drawable.ic_popup_sync, "🔗 Синхр. оба VPN", syncPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "⏹ Выкл", stopPi)

        return builder.build()
    }
}
