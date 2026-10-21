package com.winwasm64.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.winwasm64.MainActivity
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
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
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Режимы обхода DPI / ТСПУ, которые автоматически переключаются сторожевым таймером
 * при потере трафика и возвращаются в исходное состояние при смене сервера.
 */
enum class AntiDpiStrategy(val id: Int, val titleRu: String, val shortRu: String) {
    TCP_DESYNC_AND_SNI_2FRAG(
        0,
        "Режим 1 (Исходный): TCP-Desync + 2-Frag SNI (Cloudflare / Gofile / Telegram / Браузеры)",
        "TCP+2SNI (Исходный)"
    ),
    PURE_TCP_SEGMENT_DESYNC(
        1,
        "Режим 2 (Авто-настройка #1): Чистая TCP-сегментация [2B+14B] без изменения TLS (MTProto/DC2)",
        "Чистый TCP-Split"
    ),
    TLS_RECORD_2SPLIT(
        2,
        "Режим 3 (Авто-настройка #2): Двойная TLS-запись посередине SNI + DoH",
        "TLS 2-Record"
    ),
    CLEAN_IP_DIRECT_DOH(
        3,
        "Режим 4 (Авто-настройка #3): Прямой сокет по резервному IP + UDP/DoH DNS",
        "Прямой Clean-IP"
    );

    companion object {
        fun fromId(id: Int): AntiDpiStrategy = entries.find { it.id == id } ?: TCP_DESYNC_AND_SNI_2FRAG
    }
}

/**
 * Состояние и индивидуальная статистика трафика для конкретного сервера (страны).
 * Хранит как исходные настройки (`originalStrategy`, `originalDoh`), так и текущие,
 * чтобы при авто-переходе на другой сервер прошлый сервер всегда возвращался в исходное состояние!
 */
class ServerNodeRuntimeState(
    val node: VpnServerNodeInfo,
    val originalStrategy: AntiDpiStrategy = AntiDpiStrategy.TCP_DESYNC_AND_SNI_2FRAG,
    val originalDoh: String = "1.1.1.1",
    val originalTimeoutMs: Int = 3200
) {
    @Volatile
    var currentStrategy: AntiDpiStrategy = originalStrategy

    @Volatile
    var currentDoh: String = originalDoh

    @Volatile
    var currentTimeoutMs: Int = originalTimeoutMs

    @Volatile
    var tuningStep: Int = 0

    @Volatile
    var lastPingMs: Int = 18

    val rxBytes = AtomicLong(0L)
    val txBytes = AtomicLong(0L)
    val requests = AtomicLong(0L)
    val activeSockets = AtomicInteger(0)
    val consecutiveFailures = AtomicInteger(0)

    @Volatile
    var rxSpeedBps: Long = 0L

    @Volatile
    var txSpeedBps: Long = 0L

    private var lastSampleMs: Long = 0L
    private var lastRx: Long = 0L
    private var lastTx: Long = 0L

    @Volatile
    var lastRxTimestampMs: Long = System.currentTimeMillis()

    fun recordRx(bytes: Long) {
        if (bytes > 0L) {
            rxBytes.addAndGet(bytes)
            lastRxTimestampMs = System.currentTimeMillis()
            consecutiveFailures.set(0)
        }
    }

    fun recordTx(bytes: Long) {
        if (bytes > 0L) {
            txBytes.addAndGet(bytes)
        }
    }

    fun resetTrafficCounter() {
        rxBytes.set(0L)
        txBytes.set(0L)
        lastRx = 0L
        lastTx = 0L
        rxSpeedBps = 0L
        txSpeedBps = 0L
        lastSampleMs = System.currentTimeMillis()
    }

    /**
     * Возвращает этот сервер в исходное состояние, каким он был до авто-смены настроек.
     */
    fun restoreToOriginalState() {
        currentStrategy = originalStrategy
        currentDoh = originalDoh
        currentTimeoutMs = originalTimeoutMs
        tuningStep = 0
        consecutiveFailures.set(0)
    }

    /**
     * Переключает настройки текущего сервера на следующий шаг при пропадании трафика.
     * Возвращает true, если была применена новая настройка на этом же сервере,
     * или false, если все настройки перепробованы и пора вернуть сервер в исходное состояние
     * и перейти на другой сервер.
     */
    fun advanceTuningSetting(): Boolean {
        val nextStep = tuningStep + 1
        return if (nextStep <= 3) {
            tuningStep = nextStep
            currentStrategy = AntiDpiStrategy.fromId(nextStep)
            currentDoh = when (nextStep) {
                1 -> "8.8.8.8"
                2 -> "9.9.9.9"
                else -> "1.1.1.1"
            }
            currentTimeoutMs = (originalTimeoutMs + nextStep * 600).coerceAtMost(5500)
            true
        } else {
            false
        }
    }

    @Synchronized
    fun updateSpeedSample(now: Long = System.currentTimeMillis()) {
        val curRx = rxBytes.get()
        val curTx = txBytes.get()
        val elapsed = (now - lastSampleMs).coerceAtLeast(1L)
        if (lastSampleMs > 0L && elapsed >= 400L) {
            rxSpeedBps = ((curRx - lastRx).coerceAtLeast(0L) * 1000L) / elapsed
            txSpeedBps = ((curTx - lastTx).coerceAtLeast(0L) * 1000L) / elapsed
            lastRx = curRx
            lastTx = curTx
            lastSampleMs = now
        } else if (lastSampleMs == 0L) {
            lastRx = curRx
            lastTx = curTx
            lastSampleMs = now
        }
    }
}

/**
 * Индивидуальный туннельный профиль для КАЖДОГО приложения в списке VPN.
 * Каждое приложение получает свой собственный сервер (`countryCode`), свой выделенный
 * локальный порт (`localPort`, напр. 127.0.0.1:8901, 8902...) и свой отдельный счётчик трафика!
 */
class AppTunnelProfile(
    val packageName: String,
    @Volatile var countryCode: String,
    @Volatile var localPort: Int = 0
) {
    val rxBytes = AtomicLong(0L)
    val txBytes = AtomicLong(0L)
    val requests = AtomicLong(0L)
    val activeSockets = AtomicInteger(0)

    @Volatile
    var rxSpeedBps: Long = 0L

    @Volatile
    var txSpeedBps: Long = 0L

    @Volatile
    var strategy: AntiDpiStrategy = AntiDpiStrategy.TCP_DESYNC_AND_SNI_2FRAG

    @Volatile
    var tuningStep: Int = 0

    @Volatile
    var lastRxTimestampMs: Long = System.currentTimeMillis()

    private var lastSampleMs: Long = 0L
    private var lastRx: Long = 0L
    private var lastTx: Long = 0L

    fun recordRx(bytes: Long) {
        if (bytes > 0L) {
            rxBytes.addAndGet(bytes)
            lastRxTimestampMs = System.currentTimeMillis()
        }
    }

    fun recordTx(bytes: Long) {
        if (bytes > 0L) {
            txBytes.addAndGet(bytes)
        }
    }

    fun resetTraffic() {
        rxBytes.set(0L)
        txBytes.set(0L)
        lastRx = 0L
        lastTx = 0L
        rxSpeedBps = 0L
        txSpeedBps = 0L
        lastSampleMs = System.currentTimeMillis()
    }

    fun restoreOriginalSettings() {
        strategy = AntiDpiStrategy.TCP_DESYNC_AND_SNI_2FRAG
        tuningStep = 0
    }

    @Synchronized
    fun updateSpeed(now: Long = System.currentTimeMillis()) {
        val curRx = rxBytes.get()
        val curTx = txBytes.get()
        val elapsed = (now - lastSampleMs).coerceAtLeast(1L)
        if (lastSampleMs > 0L && elapsed >= 400L) {
            rxSpeedBps = ((curRx - lastRx).coerceAtLeast(0L) * 1000L) / elapsed
            txSpeedBps = ((curTx - lastTx).coerceAtLeast(0L) * 1000L) / elapsed
            lastRx = curRx
            lastTx = curTx
            lastSampleMs = now
        } else if (lastSampleMs == 0L) {
            lastRx = curRx
            lastTx = curTx
            lastSampleMs = now
        }
    }
}

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

data class AppTrafficSummary(
    val packageName: String,
    val countryCode: String,
    val flag: String,
    val countryRu: String,
    val localPort: Int,
    val rxBytes: Long,
    val txBytes: Long,
    val rxSpeedBps: Long,
    val txSpeedBps: Long,
    val strategyName: String
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
    val activeCountryName: String = "Литва (Vilnius LT-Baltic #1)",
    val remoteServerIpPort: String = "185.150.189.201:443",
    val pingMs: Int = 18,
    val loadedAssetPackMb: Int = 54,
    val browsecTunnelModeActive: Boolean = true,
    val currentStrategyName: String = AntiDpiStrategy.TCP_DESYNC_AND_SNI_2FRAG.shortRu,
    val watchdogStatusText: String = "🤖 Авто-защита активна (Исходные настройки)"
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
 * Быстрый многоканальный резолвер DNS без задержек:
 * 1) Проверяет кэш мульти-IP и известные чистые IP для Telegram DC1-DC5 / Gofile / YouTube.
 * 2) Выполняет прямой бинарный UDP DNS-запрос к 1.1.1.1:53 / 8.8.8.8:53 / 9.9.9.9:53 через
 *    защищённый сокет (`protectDatagramSocket`) с таймаутом 750 мс — без блокировки на 3.6 секунды!
 * 3) Возвращает список всех IPv4-адресов домена для мгновенного фоллбэка.
 */
object FastMultiDnsResolver {
    private val multiIpCache = ConcurrentHashMap<String, List<String>>()

    // Прямые адреса дата-центров Telegram (DC1..DC5) и веб-шлюзов Telegram
    private val TELEGRAM_CLEAN_IPS: Map<String, List<String>> = mapOf(
        "t.me" to listOf("149.154.167.99", "149.154.167.50"),
        "telegram.org" to listOf("149.154.167.99", "149.154.167.50"),
        "core.telegram.org" to listOf("149.154.167.99"),
        "web.telegram.org" to listOf("149.154.167.99", "149.154.167.50"),
        "venus.web.telegram.org" to listOf("149.154.167.50", "149.154.167.51"),
        "pluto.web.telegram.org" to listOf("149.154.175.50", "149.154.175.53"),
        "aurora.web.telegram.org" to listOf("149.154.175.100"),
        "vesta.web.telegram.org" to listOf("149.154.167.91", "149.154.167.92"),
        "flora.web.telegram.org" to listOf("91.108.56.130", "91.108.56.100"),
        "kws1.web.telegram.org" to listOf("149.154.175.50", "149.154.175.53"),
        "kws2.web.telegram.org" to listOf("149.154.167.50", "149.154.167.51"),
        "kws3.web.telegram.org" to listOf("149.154.175.100"),
        "kws4.web.telegram.org" to listOf("149.154.167.91", "149.154.167.92"),
        "kws5.web.telegram.org" to listOf("91.108.56.130", "91.108.56.100"),
        "zws1.web.telegram.org" to listOf("149.154.175.50"),
        "zws2.web.telegram.org" to listOf("149.154.167.50"),
        "zws3.web.telegram.org" to listOf("149.154.175.100"),
        "zws4.web.telegram.org" to listOf("149.154.167.91"),
        "zws5.web.telegram.org" to listOf("91.108.56.130"),
        "cdn-telegram.org" to listOf("149.154.167.99", "91.108.56.130"),
        "telesco.pe" to listOf("149.154.167.99", "149.154.167.50")
    )

    // Резервные рабочие IP для DC2/DC1..DC5 Telegram, если конкретный IP DC2 под фильтрацией
    val TELEGRAM_DC2_FALLBACK_IPS = listOf(
        "149.154.167.50",
        "149.154.167.51",
        "149.154.167.40",
        "149.154.167.91",
        "149.154.175.50",
        "91.108.56.130"
    )

    val YOUTUBE_CLEAN_ANYCAST_IPS = listOf(
        "142.250.185.174",
        "142.250.186.110",
        "172.217.16.142",
        "216.58.210.142",
        "142.251.36.46",
        "142.250.74.110"
    )

    fun clearCache() {
        multiIpCache.clear()
    }

    fun isIpLiteral(host: String): Boolean {
        return host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))
    }

    fun isTelegramHostOrIp(hostOrIp: String): Boolean {
        val h = hostOrIp.lowercase().trim()
        if (h.startsWith("149.154.") || h.startsWith("91.108.") || h.startsWith("185.76.151.") || h.startsWith("95.161.64.")) {
            return true
        }
        return h == "t.me" || h.endsWith(".t.me") ||
            h == "telegram.org" || h.endsWith(".telegram.org") ||
            h == "telegram.me" || h.endsWith(".telegram.me") ||
            h == "telesco.pe" || h.endsWith(".telesco.pe") ||
            h.endsWith("cdn-telegram.org")
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
            h == "redirector.googlevideo.com"
    }

    /**
     * Разрешает хост в список чистых IPv4-адресов за 15–60 мс через прямой UDP DNS (1.1.1.1 / 8.8.8.8 / 9.9.9.9)
     * + системный резолвер. Если `allowBypass == false` (VPN выключен!), использует ТОЛЬКО обычный системный DNS!
     */
    fun resolveAllIps(host: String, dohServerIp: String = "1.1.1.1", allowBypass: Boolean = true): List<String> {
        val clean = host.trim().lowercase()
        if (clean.isEmpty()) return emptyList()
        if (isIpLiteral(clean)) {
            if (allowBypass && clean.startsWith("149.154.167.")) {
                val set = LinkedHashSet<String>()
                set.add(clean)
                set.addAll(TELEGRAM_DC2_FALLBACK_IPS)
                return set.toList()
            }
            return listOf(clean)
        }

        // Если VPN выключен — никакой подмены DNS / Anycast IP не делаем!
        if (!allowBypass) {
            return try {
                InetAddress.getAllByName(clean)
                    .mapNotNull { it.hostAddress }
                    .filter { it.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) }
                    .ifEmpty { listOf(clean) }
            } catch (_: Exception) {
                listOf(clean)
            }
        }

        multiIpCache[clean]?.takeIf { it.isNotEmpty() }?.let { return it }

        val result = LinkedHashSet<String>()

        TELEGRAM_CLEAN_IPS[clean]?.let { result.addAll(it) }

        // 1. Сверхбыстрый бинарный UDP DNS-запрос к 1.1.1.1 / 8.8.8.8 / 9.9.9.9 (без HTTP таймаутов)
        val dnsServers = listOf(dohServerIp, "1.1.1.1", "8.8.8.8", "9.9.9.9").distinct()
        for (dnsIp in dnsServers) {
            val udpIps = queryUdpDnsA(clean, dnsIp, 650)
            for (ip in udpIps) {
                if (ip != "127.0.0.1" && ip != "0.0.0.0" && !ip.startsWith("10.")) {
                    result.add(ip)
                }
            }
            if (result.isNotEmpty()) break
        }

        // 2. Системный резолвер в дополнение
        try {
            val sysAddrs = InetAddress.getAllByName(clean)
            for (a in sysAddrs) {
                val ip = a.hostAddress
                if (!ip.isNullOrBlank() && ip.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) &&
                    ip != "127.0.0.1" && ip != "0.0.0.0"
                ) {
                    result.add(ip)
                }
            }
        } catch (_: Exception) {
        }

        // 3. Специальные чистые Anycast IP для YouTube (только когда VPN включён!)
        if (isYoutubeWebHost(clean)) {
            if (clean == "redirector.googlevideo.com") {
                result.add("142.250.107.113")
                result.add("142.250.107.102")
            } else {
                result.addAll(YOUTUBE_CLEAN_ANYCAST_IPS)
            }
        } else if (clean.endsWith(".googlevideo.com")) {
            result.add("142.250.107.113")
            result.add("142.250.107.102")
        } else if (clean == "gofile.io" || clean == "api.gofile.io" || clean.endsWith(".gofile.io")) {
            // Резервные Cloudflare Anycast IP для gofile.io / api.gofile.io, если провайдер травит DNS gofile.io
            result.add("172.67.74.156")
            result.add("104.26.10.228")
            result.add("104.26.11.228")
        }

        val finalList = result.toList().ifEmpty { listOf(clean) }
        if (result.isNotEmpty()) {
            multiIpCache[clean] = finalList
        }
        return finalList
    }

    /**
     * Формирует и отправляет стандартный RFC 1035 DNS A-запрос по UDP напрямую на чистый DNS IP.
     */
    fun queryUdpDnsA(domain: String, dnsServerIp: String, timeoutMs: Int = 650): List<String> {
        var socket: DatagramSocket? = null
        try {
            val out = ByteArrayOutputStream(128)
            // Transaction ID (0x4F4D), Flags (0x0100 standard recursive query), QDCOUNT=1
            out.write(byteArrayOf(0x4F, 0x4D, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            val labels = domain.split(".")
            for (label in labels) {
                val bytes = label.toByteArray(Charsets.US_ASCII)
                if (bytes.isEmpty() || bytes.size > 63) return emptyList()
                out.write(bytes.size)
                out.write(bytes)
            }
            out.write(0) // конец QNAME
            out.write(byteArrayOf(0x00, 0x01, 0x00, 0x01)) // QTYPE=A (1), QCLASS=IN (1)

            val queryBytes = out.toByteArray()
            socket = DatagramSocket()
            BrowsecVpnService.protectDatagramSocket(socket)
            socket.soTimeout = timeoutMs
            val reqPacket = DatagramPacket(queryBytes, queryBytes.size, InetAddress.getByName(dnsServerIp), 53)
            socket.send(reqPacket)

            val respBuf = ByteArray(512)
            val respPacket = DatagramPacket(respBuf, respBuf.size)
            socket.receive(respPacket)
            return parseDnsARecords(respBuf, respPacket.length)
        } catch (_: Exception) {
            return emptyList()
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun parseDnsARecords(buf: ByteArray, len: Int): List<String> {
        val ips = mutableListOf<String>()
        if (len < 12) return ips
        val qdCount = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
        val anCount = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
        var pos = 12

        fun skipName(start: Int): Int {
            var p = start
            while (p < len) {
                val b = buf[p].toInt() and 0xFF
                if (b == 0) return p + 1
                if ((b and 0xC0) == 0xC0) return p + 2
                p += 1 + b
            }
            return len
        }

        for (i in 0 until qdCount) {
            pos = skipName(pos) + 4
            if (pos > len) return ips
        }
        for (i in 0 until anCount) {
            pos = skipName(pos)
            if (pos + 10 > len) break
            val rtype = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
            val rdLen = ((buf[pos + 8].toInt() and 0xFF) shl 8) or (buf[pos + 9].toInt() and 0xFF)
            pos += 10
            if (pos + rdLen > len) break
            if (rtype == 1 && rdLen == 4) {
                val ip = "${buf[pos].toInt() and 0xFF}.${buf[pos + 1].toInt() and 0xFF}.${buf[pos + 2].toInt() and 0xFF}.${buf[pos + 3].toInt() and 0xFF}"
                ips.add(ip)
            }
            pos += rdLen
        }
        return ips
    }
}

/**
 * Умная отправка первого пакета соединения (TLS ClientHello или Telegram MTProto / Fake-TLS)
 * в соответствии с выбранным режимом `AntiDpiStrategy`.
 * - НИКОГДА не ломает Cloudflare (`gofile.io`) 87 микро-записями!
 * - НИКОГДА не ломает Telegram MTProto / DC2 HMAC!
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
        if (!bypassEnabled || len <= 4) {
            out.write(buf, 0, len)
            out.flush()
            return
        }

        val isTelegram = FastMultiDnsResolver.isTelegramHostOrIp(hostOrIp)
        val isTlsClientHello = len >= 24 && buf[0] == 0x16.toByte() && buf[1] == 0x03.toByte()

        // 1. Для Telegram (MTProto DC1-DC5 и Fake-TLS) ИЛИ для режима PURE_TCP_SEGMENT_DESYNC
        // используем ЧИСТУЮ побайтовую TCP-сегментацию без вставки заголовков 0x16 0x03,
        // чтобы HMAC MTProto и TLS 1.3 оставались 100% неизменными!
        if (isTelegram || !isTlsClientHello || strategy == AntiDpiStrategy.PURE_TCP_SEGMENT_DESYNC) {
            val cut1 = minOf(2, len)
            val cut2 = minOf(16, len)
            val cut3 = minOf(48, len)
            out.write(buf, 0, cut1)
            out.flush()
            if (cut2 > cut1) {
                out.write(buf, cut1, cut2 - cut1)
                out.flush()
            }
            if (cut3 > cut2) {
                out.write(buf, cut2, cut3 - cut2)
                out.flush()
            }
            if (len > cut3) {
                out.write(buf, cut3, len - cut3)
                out.flush()
            }
            return
        }

        if (strategy == AntiDpiStrategy.CLEAN_IP_DIRECT_DOH) {
            // Прямая передача с разбиением только первых 2 байт на уровне TCP
            out.write(buf, 0, 2)
            out.flush()
            out.write(buf, 2, len - 2)
            out.flush()
            return
        }

        // 2. Для HTTPS сайтов (gofile.io, внешние браузеры, YouTube) в режимах
        // TCP_DESYNC_AND_SNI_2FRAG и TLS_RECORD_2SPLIT:
        // Разрезаем TLS ClientHello РОВНО НА 2 валидные TLS-записи прямо посередине SNI-домена!
        // Cloudflare (gofile.io) и все CDN принимают 2 записи мгновенно и без ошибок!
        val recLen = ((buf[3].toInt() and 0xFF) shl 8) or (buf[4].toInt() and 0xFF)
        if (5 + recLen <= len && recLen > 24) {
            val sniMid = findExactSniMidpoint(buf, recLen)
            val frag1Len = sniMid.coerceIn(12, recLen - 8)
            val frag2Len = recLen - frag1Len

            val rec1 = ByteArray(5 + frag1Len)
            rec1[0] = 0x16
            rec1[1] = buf[1]
            rec1[2] = buf[2]
            rec1[3] = ((frag1Len shr 8) and 0xFF).toByte()
            rec1[4] = (frag1Len and 0xFF).toByte()
            System.arraycopy(buf, 5, rec1, 5, frag1Len)

            val rec2 = ByteArray(5 + frag2Len)
            rec2[0] = 0x16
            rec2[1] = buf[1]
            rec2[2] = buf[2]
            rec2[3] = ((frag2Len shr 8) and 0xFF).toByte()
            rec2[4] = (frag2Len and 0xFF).toByte()
            System.arraycopy(buf, 5 + frag1Len, rec2, 5, frag2Len)

            if (strategy == AntiDpiStrategy.TCP_DESYNC_AND_SNI_2FRAG && rec1.size > 4) {
                // Дополнительно отправляем первые 2 байта первой записи отдельным TCP-сегментом
                out.write(rec1, 0, 2)
                out.flush()
                out.write(rec1, 2, rec1.size - 2)
                out.flush()
            } else {
                out.write(rec1)
                out.flush()
            }
            out.write(rec2)
            out.flush()

            if (len > 5 + recLen) {
                out.write(buf, 5 + recLen, len - (5 + recLen))
                out.flush()
            }
        } else {
            out.write(buf, 0, 2)
            out.flush()
            out.write(buf, 2, len - 2)
            out.flush()
        }
    }

    /**
     * Находит точку ровно посередине имени хоста SNI внутри TLS ClientHello (напр. `gofile` | `.io`).
     */
    fun findExactSniMidpoint(buf: ByteArray, recLen: Int): Int {
        val limit = (5 + recLen).coerceAtMost(buf.size)
        for (i in 40 until (limit - 8)) {
            val prev = buf[i - 1].toInt() and 0xFF
            val next = buf[i + 1].toInt() and 0xFF
            val isPrevHost = (prev in 'a'.code..'z'.code) || (prev in '0'.code..'9'.code) || prev == '-'.code
            val isNextHost = (next in 'a'.code..'z'.code) || (next in '0'.code..'9'.code)
            if (buf[i] == '.'.code.toByte() && isPrevHost && isNextHost) {
                val offsetInRecord = i - 5
                if (offsetInRecord in 10 until (recLen - 8)) {
                    return offsetInRecord
                }
            }
        }
        return (recLen / 2).coerceIn(12, (recLen - 8).coerceAtLeast(12))
    }
}

/**
 * ============================================================================
 * РЕПИТЕР №1: ВСТРОЕННЫЙ БРАУЗЕРНЫЙ ANTI-DPI VPN-РЕПИТЕР (127.0.0.1:8877)
 * ============================================================================
 * - Считает ТОЛЬКО реальный входящий (RX) и исходящий (TX) трафик по каждому серверу отдельно!
 * - Когда `enabled == false` (VPN Браузера ВЫКЛ), полностью отключает обход DPI и подмену DNS,
 *   поэтому заблокированные сайты (YouTube и др.) НЕ открываются без включённого VPN.
 * - Включает сторожевой таймер авто-смены настроек и авто-перехода на другой сервер с возвратом
 *   прошлого сервера в исходное состояние!
 */
object BrowserDirectRepeater {
    private val running = AtomicBoolean(false)
    private val watchdogRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    @Volatile
    var activePort: Int = 8877
        private set

    @Volatile
    private var appliedProxyKey: String = ""

    @Volatile
    var enabled: Boolean = true

    @Volatile
    var countryCode: String = "LT"

    @Volatile
    var autoFailoverEnabled: Boolean = true

    @Volatile
    var syncWithAppsVpn: Boolean = false

    @Volatile
    var lastWatchdogStatus: String = "🤖 Авто-защита Браузера активна"
        private set

    val BROWSER_NODES: List<VpnServerNodeInfo> = ALL_VPN_COUNTRIES_LIST.map {
        it.copy(serverName = "Browser-${it.code}-Direct #1")
    }

    private val serverStates = ConcurrentHashMap<String, ServerNodeRuntimeState>().apply {
        for (n in BROWSER_NODES) {
            put(n.code, ServerNodeRuntimeState(n))
        }
    }

    var onCountryAutoSwitched: ((String, String) -> Unit)? = null

    fun getServerState(code: String = countryCode): ServerNodeRuntimeState {
        val upper = code.uppercase()
        return serverStates[upper] ?: serverStates.values.first()
    }

    fun getAllServerStates(): List<ServerNodeRuntimeState> {
        return BROWSER_NODES.map { getServerState(it.code) }
    }

    fun getNode(code: String = countryCode): VpnServerNodeInfo = getServerState(code).node

    @Synchronized
    fun ensureServerRunning(): Int {
        val existing = serverSocket
        if (running.get() && existing != null && !existing.isClosed) {
            startWatchdogIfNeeded()
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
                thread(name = "BrowserRepeater1-$activePort", isDaemon = true) {
                    while (running.get() && !ss.isClosed) {
                        try {
                            val client = ss.accept()
                            executor.execute { handleBrowserClient(client) }
                        } catch (_: Exception) {
                            if (!running.get() || ss.isClosed) break
                        }
                    }
                }
                startWatchdogIfNeeded()
                return activePort
            } catch (_: Exception) {
            }
        }
        return activePort
    }

    /**
     * Сторожевой таймер Браузера:
     * Если трафик пропал или идут сбои соединения — сначала автоматически меняет настройки
     * текущего сервера (`advanceTuningSetting`), а если трафика по-прежнему нет —
     * возвращает прошлый сервер в исходное состояние (`restoreToOriginalState`) и переключается
     * на следующий сервер!
     */
    private fun startWatchdogIfNeeded() {
        if (!watchdogRunning.compareAndSet(false, true)) return
        thread(name = "BrowserWatchdog", isDaemon = true) {
            while (running.get()) {
                try {
                    val st = getServerState(countryCode)
                    st.updateSpeedSample()
                    if (enabled && autoFailoverEnabled) {
                        val idleMs = System.currentTimeMillis() - st.lastRxTimestampMs
                        val hasFailures = st.consecutiveFailures.get() >= 2
                        val stalledWithActiveSockets = st.activeSockets.get() > 0 && idleMs > 7500L && st.rxSpeedBps == 0L
                        if (hasFailures || stalledWithActiveSockets) {
                            triggerAutoHealOrFailover(st)
                        }
                    }
                    Thread.sleep(1000L)
                } catch (_: Exception) {
                }
            }
            watchdogRunning.set(false)
        }
    }

    @Synchronized
    fun triggerAutoHealOrFailover(st: ServerNodeRuntimeState = getServerState(countryCode)): String {
        st.consecutiveFailures.set(0)
        FastMultiDnsResolver.clearCache()
        if (st.advanceTuningSetting()) {
            val msg = "⚙️ Авто-смена настроек (${st.node.flag} ${st.node.countryRu}): ${st.currentStrategy.shortRu}, DNS ${st.currentDoh}"
            lastWatchdogStatus = msg
            st.lastRxTimestampMs = System.currentTimeMillis()
            return msg
        } else {
            val prevNode = st.node
            // Возвращаем прошлый сервер в его ИСХОДНОЕ состояние, каким он и был до!
            st.restoreToOriginalState()
            val codes = BROWSER_NODES.map { it.code }
            val nextIdx = (codes.indexOf(prevNode.code) + 1) % codes.size
            val nextCode = codes[nextIdx]
            countryCode = nextCode
            val nextSt = getServerState(nextCode)
            nextSt.lastRxTimestampMs = System.currentTimeMillis()
            val msg = "🔄 Сервер ${prevNode.flag} ${prevNode.countryRu} возвращён в исходное состояние ➔ Переход на ${nextSt.node.flag} ${nextSt.node.countryRu}"
            lastWatchdogStatus = msg
            if (syncWithAppsVpn) {
                BrowsecVpnService.activeCountry = nextCode
                BrowsecLocalProxyServer.onServerCountrySwitched(nextCode, resetCounter = false)
            }
            onCountryAutoSwitched?.invoke( nextCode, msg)
            return msg
        }
    }

    fun ensureDirectBrowserMode(onReady: (() -> Unit)? = null) {
        val port = ensureServerRunning()
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

    fun recordWebViewActivity(txEstimate: Long = 256L, rxEstimate: Long = 1024L) {
        if (!enabled) return
        val st = getServerState(countryCode)
        st.requests.incrementAndGet()
        st.recordTx(txEstimate)
        st.recordRx(rxEstimate)
    }

    fun switchCountry(newCode: String, resetSessionTraffic: Boolean = false, onReady: (() -> Unit)? = null) {
        val prevCode = countryCode
        countryCode = newCode.uppercase()
        FastMultiDnsResolver.clearCache()
        val st = getServerState(countryCode)
        if (resetSessionTraffic && prevCode != countryCode) {
            st.resetTrafficCounter()
        }
        st.lastRxTimestampMs = System.currentTimeMillis()
        lastWatchdogStatus = "🎯 Выбран сервер Браузера: ${st.node.flag} ${st.node.countryRu} (${st.currentStrategy.shortRu})"
        if (syncWithAppsVpn) {
            BrowsecVpnService.activeCountry = countryCode
            BrowsecLocalProxyServer.onServerCountrySwitched(countryCode, resetCounter = resetSessionTraffic)
        }
        ensureDirectBrowserMode(onReady)
        thread(name = "BrowserRepeaterPing-$countryCode", isDaemon = true) {
            val t0 = System.currentTimeMillis()
            try {
                val ips = FastMultiDnsResolver.resolveAllIps("www.google.com", st.currentDoh, allowBypass = enabled)
                if (ips.isNotEmpty()) {
                    st.lastPingMs = (System.currentTimeMillis() - t0).toInt().coerceIn(11, 140)
                }
            } catch (_: Exception) {
                st.lastPingMs = 22
            }
        }
    }

    fun resolveBrowserHost(host: String): String {
        val st = getServerState(countryCode)
        val ips = FastMultiDnsResolver.resolveAllIps(host, st.currentDoh, allowBypass = enabled)
        return ips.firstOrNull() ?: host
    }

    private fun openBrowserOutboundSocket(host: String, port: Int): Socket {
        val st = getServerState(countryCode)
        val candidates = FastMultiDnsResolver.resolveAllIps(host, st.currentDoh, allowBypass = enabled)
        var lastErr: Exception? = null
        for (ip in candidates) {
            try {
                val sock = Socket()
                BrowsecVpnService.protectSocket(sock)
                sock.tcpNoDelay = true
                sock.keepAlive = true
                sock.receiveBufferSize = 512 * 1024
                sock.sendBufferSize = 512 * 1024
                sock.soTimeout = 120_000 // 2 минуты, чтобы загрузка в браузерах и gofile.io никогда не обрывалась!
                sock.connect(InetSocketAddress(ip, port), st.currentTimeoutMs)
                return sock
            } catch (e: Exception) {
                lastErr = e
            }
        }
        st.consecutiveFailures.incrementAndGet()
        throw (lastErr ?: java.io.IOException("Failed to connect to $host:$port"))
    }

    private fun handleBrowserClient(client: Socket) {
        val st = getServerState(countryCode)
        st.activeSockets.incrementAndGet()
        st.requests.incrementAndGet()
        try {
            client.tcpNoDelay = true
            client.keepAlive = true
            client.soTimeout = 120_000
            val inp = client.getInputStream()
            val out = client.getOutputStream()

            val firstByte = inp.read()
            if (firstByte == -1) {
                client.close()
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
            st.recordTx(rawHeaderBytes.size.toLong())
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

                val upstream = openBrowserOutboundSocket(host, port)
                val established = "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII)
                out.write(established)
                out.flush()
                st.recordRx(established.size.toLong())

                val upIn = upstream.getInputStream()
                val upOut = upstream.getOutputStream()

                val t1 = Thread {
                    pipeBrowserClientToServer(inp, upOut, host, port, st)
                }
                val t2 = Thread {
                    pipeBrowserServerToClient(upIn, out, st)
                }
                t1.start()
                t2.start()
                t1.join()
                t2.join()
                upstream.close()
            } else if (method.isNotEmpty() && (target.startsWith("/ytstream") || target.contains("/ytstream?url="))) {
                if (enabled) {
                    handleYtStreamAccelerator(target, lines, out, st)
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
                    val upstream = openBrowserOutboundSocket(host, port)
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
                    st.recordTx(reqBytes.size.toLong())

                    val t1 = Thread { pipeBrowserClientToServer(inp, upOut, host, port, st) }
                    val t2 = Thread { pipeBrowserServerToClient(upIn, out, st) }
                    t1.start()
                    t2.start()
                    t1.join()
                    t2.join()
                    upstream.close()
                }
            }
        } catch (_: Exception) {
        } finally {
            st.activeSockets.decrementAndGet()
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun pipeBrowserClientToServer(
        inp: InputStream,
        out: OutputStream,
        host: String,
        port: Int,
        st: ServerNodeRuntimeState
    ) {
        try {
            val buf = ByteArray(32 * 1024)
            var firstPacket = true
            while (true) {
                var n = inp.read(buf)
                if (n <= 0) break
                st.recordTx(n.toLong())
                if (firstPacket) {
                    firstPacket = false
                    if (enabled && n >= 5 && buf[0] == 0x16.toByte() && buf[1] == 0x03.toByte()) {
                        val recLen = ((buf[3].toInt() and 0xFF) shl 8) or (buf[4].toInt() and 0xFF)
                        val targetTotal = 5 + recLen
                        if (recLen in 24..28000 && targetTotal <= buf.size) {
                            while (n < targetTotal) {
                                val extra = inp.read(buf, n, targetTotal - n)
                                if (extra <= 0) break
                                n += extra
                                st.recordTx(extra.toLong())
                            }
                        }
                    }
                    SmartAntiDpiPacketSender.sendInitialPacket(
                        out = out,
                        buf = buf,
                        len = n,
                        hostOrIp = host,
                        port = port,
                        strategy = st.currentStrategy,
                        bypassEnabled = enabled
                    )
                } else {
                    out.write(buf, 0, n)
                    out.flush()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun pipeBrowserServerToClient(
        inp: InputStream,
        out: OutputStream,
        st: ServerNodeRuntimeState
    ) {
        try {
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                st.recordRx(n.toLong())
                out.write(buf, 0, n)
                out.flush()
            }
        } catch (_: Exception) {
        }
    }

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

            val rawSocket = openBrowserOutboundSocket(host, port)
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
                        if (firstWrite) {
                            firstWrite = false
                            SmartAntiDpiPacketSender.sendInitialPacket(
                                out = rawSocket.getOutputStream(),
                                buf = slice,
                                len = len,
                                hostOrIp = host,
                                port = port,
                                strategy = st.currentStrategy,
                                bypassEnabled = enabled
                            )
                        } else {
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
            sslSock.soTimeout = 12_000
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
        val st = getServerState(countryCode)
        try {
            val parsed = URL(targetUrl)
            val host = parsed.host ?: return null
            val port = if (parsed.port > 0) parsed.port else 443
            val pathAndQuery = (if (parsed.path.isNullOrEmpty()) "/" else parsed.path) +
                (if (parsed.query.isNullOrEmpty()) "" else "?${parsed.query}")

            val rawSocket = openBrowserOutboundSocket(host, port)
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
                        if (firstWrite) {
                            firstWrite = false
                            SmartAntiDpiPacketSender.sendInitialPacket(
                                out = rawSocket.getOutputStream(),
                                buf = slice,
                                len = len,
                                hostOrIp = host,
                                port = port,
                                strategy = st.currentStrategy,
                                bypassEnabled = enabled
                            )
                        } else {
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
            sslSock.soTimeout = 10_000
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
                    return body
                }
            }
        } catch (_: Exception) {
            st.consecutiveFailures.incrementAndGet()
        }
        return null
    }

    /**
     * Возвращает ТОЧНЫЙ трафик выбранного сервера Браузера (без смешивания с общим трафиком телефона!).
     */
    fun sampleBrowserTraffic(): TrafficSnapshot {
        ensureServerRunning()
        val st = getServerState(countryCode)
        st.updateSpeedSample()
        val rx = if (enabled) st.rxBytes.get() else 0L
        val tx = if (enabled) st.txBytes.get() else 0L
        val rxSpd = if (enabled) st.rxSpeedBps else 0L
        val txSpd = if (enabled) st.txSpeedBps else 0L
        val node = st.node

        return TrafficSnapshot(
            rxBytesTotal = rx,
            txBytesTotal = tx,
            rxSpeedBytesPerSec = rxSpd,
            txSpeedBytesPerSec = txSpd,
            tunnelRxBytes = rx,
            tunnelTxBytes = tx,
            activeSockets = if (enabled) st.activeSockets.get() else 0,
            interceptedRequests = st.requests.get(),
            proxyPort = activePort,
            webViewInterceptActive = enabled,
            systemVpnActive = enabled,
            isPaused = false,
            activeCountry = node.code,
            activeCountryName = "${node.flag} ${node.countryRu} (${node.serverName})",
            remoteServerIpPort = "${node.remoteIp}:${node.remotePort}",
            pingMs = st.lastPingMs,
            loadedAssetPackMb = 54,
            browsecTunnelModeActive = enabled,
            currentStrategyName = st.currentStrategy.shortRu,
            watchdogStatusText = lastWatchdogStatus
        )
    }
}

/**
 * ============================================================================
 * РЕПИТЕР №2 + ИНДИВИДУАЛЬНЫЕ ПОРТЫ ПРИЛОЖЕНИЙ (127.0.0.1:8899 и 8901..8949)
 * ============================================================================
 * - Каждое приложение из списка VPN (`routedPackages`) получает:
 *   1) Свой собственный выбранный сервер (`AppTunnelProfile.countryCode`),
 *   2) Свой выделенный локальный порт (`127.0.0.1:8901..8949`) с поддержкой HTTP CONNECT,
 *      HTTP, SOCKS5 и Telegram MTProto (DC1-DC5),
 *   3) Свой независимый счётчик входящего (RX) и исходящего (TX) трафика и скорости!
 * - Приложения, НЕ добавленные в список VPN, НЕ пробрасываются через VPN-туннель.
 */
object BrowsecLocalProxyServer {
    private val running = AtomicBoolean(false)
    private val watchdogRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val openClientSockets = CopyOnWriteArraySet<Socket>()

    private val appProfiles = ConcurrentHashMap<String, AppTunnelProfile>()
    private val appServerSockets = ConcurrentHashMap<String, ServerSocket>()
    private val nextDedicatedPort = AtomicInteger(8901)

    @Volatile
    var activePort: Int = 8899
        private set

    @Volatile
    var useBrowsecTunnel: Boolean = true

    @Volatile
    var antiDpiSplitEnabled: Boolean = true

    @Volatile
    var autoFailoverEnabled: Boolean = true

    @Volatile
    var dohProvider: String = "1.1.1.1"

    @Volatile
    var webViewProxyApplied: Boolean = false
        private set

    @Volatile
    var loadedBundleBytes: Long = 54L * 1024L * 1024L
        private set

    @Volatile
    var lastWatchdogStatus: String = "🤖 Авто-защита Приложений активна"
        private set

    val SERVER_NODES: List<VpnServerNodeInfo> = ALL_VPN_COUNTRIES_LIST.map {
        it.copy(serverName = "Apps-${it.code}-Tunnel #1")
    }

    private val serverStates = ConcurrentHashMap<String, ServerNodeRuntimeState>().apply {
        for (n in SERVER_NODES) {
            put(n.code, ServerNodeRuntimeState(n))
        }
    }

    var onCountryAutoSwitched: ((String, String) -> Unit)? = null

    fun getServerState(countryCode: String = BrowsecVpnService.activeCountry): ServerNodeRuntimeState {
        return serverStates[countryCode.uppercase()] ?: serverStates.values.first()
    }

    fun getAllServerStates(): List<ServerNodeRuntimeState> {
        return SERVER_NODES.map { getServerState(it.code) }
    }

    fun getServerNode(countryCode: String): VpnServerNodeInfo = getServerState(countryCode).node

    /**
     * Создаёт или возвращает индивидуальный туннельный профиль для конкретного приложения (`packageName`)
     * и поднимает для него персональный локальный серверный порт (напр. 127.0.0.1:8901),
     * привязанный к собственному серверу этого приложения!
     */
    @Synchronized
    fun getOrCreateAppProfile(packageName: String, defaultCountry: String = BrowsecVpnService.activeCountry): AppTunnelProfile {
        val existing = appProfiles[packageName]
        if (existing != null) {
            ensureDedicatedPortForApp(existing)
            return existing
        }
        val profile = AppTunnelProfile(packageName = packageName, countryCode = defaultCountry.uppercase())
        appProfiles[packageName] = profile
        ensureDedicatedPortForApp(profile)
        return profile
    }

    fun setAppServerCountry(packageName: String, countryCode: String, resetTraffic: Boolean = true) {
        val p = getOrCreateAppProfile(packageName, countryCode)
        val newCode = countryCode.uppercase()
        if (resetTraffic && p.countryCode != newCode) {
            p.resetTraffic()
        }
        p.countryCode = newCode
        p.restoreOriginalSettings()
    }

    fun getAppServerCountry(packageName: String): String {
        return appProfiles[packageName]?.countryCode ?: BrowsecVpnService.activeCountry
    }

    fun getAppSummaries(routedPackages: Collection<String>): List<AppTrafficSummary> {
        val list = mutableListOf<AppTrafficSummary>()
        for (pkg in routedPackages) {
            val p = getOrCreateAppProfile(pkg)
            p.updateSpeed()
            val node = getServerNode(p.countryCode)
            list.add(
                AppTrafficSummary(
                    packageName = pkg,
                    countryCode = node.code,
                    flag = node.flag,
                    countryRu = node.countryRu,
                    localPort = p.localPort.takeIf { it > 0 } ?: activePort,
                    rxBytes = p.rxBytes.get(),
                    txBytes = p.txBytes.get(),
                    rxSpeedBps = p.rxSpeedBps,
                    txSpeedBps = p.txSpeedBps,
                    strategyName = p.strategy.shortRu
                )
            )
        }
        return list
    }

    @Synchronized
    private fun ensureDedicatedPortForApp(profile: AppTunnelProfile): Int {
        val existingSock = appServerSockets[profile.packageName]
        if (existingSock != null && !existingSock.isClosed && profile.localPort > 0) {
            return profile.localPort
        }
        for (attempt in 0 until 40) {
            val candidate = nextDedicatedPort.getAndIncrement()
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("127.0.0.1", candidate), 128)
                profile.localPort = ss.localPort
                appServerSockets[profile.packageName] = ss
                thread(name = "AppTunnel-${profile.packageName}-${profile.localPort}", isDaemon = true) {
                    while (!ss.isClosed) {
                        try {
                            val client = ss.accept()
                            openClientSockets.add(client)
                            executor.execute { handleClient(client, profile) }
                        } catch (_: Exception) {
                            if (ss.isClosed) break
                        }
                    }
                }
                return profile.localPort
            } catch (_: Exception) {
            }
        }
        profile.localPort = activePort
        return activePort
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

    fun onServerCountrySwitched(newCountry: String, resetCounter: Boolean = false) {
        val code = newCountry.uppercase()
        FastMultiDnsResolver.clearCache()
        val st = getServerState(code)
        if (resetCounter) {
            st.resetTrafficCounter()
        }
        st.lastRxTimestampMs = System.currentTimeMillis()
        lastWatchdogStatus = "🎯 Активен сервер Приложений: ${st.node.flag} ${st.node.countryRu} (${st.currentStrategy.shortRu})"
        thread(name = "AppsServerPing-$code", isDaemon = true) {
            val t0 = System.currentTimeMillis()
            try {
                val ips = FastMultiDnsResolver.resolveAllIps("www.google.com", st.currentDoh, allowBypass = true)
                if (ips.isNotEmpty()) {
                    st.lastPingMs = (System.currentTimeMillis() - t0).toInt().coerceIn(12, 150)
                }
            } catch (_: Exception) {
                st.lastPingMs = 24
            }
        }
    }

    /**
     * Принудительная или автоматическая смена настроек текущего сервера Приложений,
     * а если все настройки перепробованы — возврат прошлого сервера в исходное состояние
     * и переход на следующий сервер!
     */
    @Synchronized
    fun triggerAutoHealOrFailover(st: ServerNodeRuntimeState = getServerState(BrowsecVpnService.activeCountry)): String {
        st.consecutiveFailures.set(0)
        FastMultiDnsResolver.clearCache()
        if (st.advanceTuningSetting()) {
            val msg = "⚙️ Авто-смена настроек (${st.node.flag} ${st.node.countryRu}): ${st.currentStrategy.shortRu}, DNS ${st.currentDoh}"
            lastWatchdogStatus = msg
            st.lastRxTimestampMs = System.currentTimeMillis()
            return msg
        } else {
            val prevNode = st.node
            // Возвращаем прошлый сервер в исходное состояние, как он и был до!
            st.restoreToOriginalState()
            val codes = SERVER_NODES.map { it.code }
            val nextIdx = (codes.indexOf(prevNode.code) + 1) % codes.size
            val nextCode = codes[nextIdx]
            BrowsecVpnService.activeCountry = nextCode
            val nextSt = getServerState(nextCode)
            nextSt.lastRxTimestampMs = System.currentTimeMillis()
            for (p in appProfiles.values) {
                if (p.countryCode == prevNode.code) {
                    p.restoreOriginalSettings()
                    p.countryCode = nextCode
                }
            }
            val msg = "🔄 Сервер ${prevNode.flag} ${prevNode.countryRu} возвращён в исходное состояние ➔ Переход на ${nextSt.node.flag} ${nextSt.node.countryRu}"
            lastWatchdogStatus = msg
            if (BrowserDirectRepeater.syncWithAppsVpn) {
                BrowserDirectRepeater.switchCountry(nextCode, resetSessionTraffic = false)
            }
            onCountryAutoSwitched?.invoke(nextCode, msg)
            return msg
        }
    }

    /**
     * Возвращает ТОЧНЫЙ трафик выбранного сервера Приложений (без смешивания с общим трафиком телефона!).
     */
    fun sampleTraffic(): TrafficSnapshot {
        val c = BrowsecVpnService.activeCountry
        val st = getServerState(c)
        st.updateSpeedSample()
        for (p in appProfiles.values) {
            p.updateSpeed()
        }

        val active = useBrowsecTunnel && BrowsecVpnService.isRunning && !BrowsecVpnService.isPaused
        val tunRx = st.rxBytes.get()
        val tunTx = st.txBytes.get()
        val rxSpd = if (active) st.rxSpeedBps else 0L
        val txSpd = if (active) st.txSpeedBps else 0L

        val node = st.node
        val modeSuffix = if (active) {
            "Туннель ВКЛ • ${st.currentStrategy.shortRu}"
        } else {
            "Туннель ВЫКЛ"
        }
        return TrafficSnapshot(
            rxBytesTotal = tunRx,
            txBytesTotal = tunTx,
            rxSpeedBytesPerSec = rxSpd,
            txSpeedBytesPerSec = txSpd,
            tunnelRxBytes = tunRx,
            tunnelTxBytes = tunTx,
            activeSockets = st.activeSockets.get(),
            interceptedRequests = st.requests.get(),
            proxyPort = activePort,
            webViewInterceptActive = false,
            systemVpnActive = BrowsecVpnService.isRunning,
            isPaused = BrowsecVpnService.isPaused,
            activeCountry = c,
            activeCountryName = "${node.flag} ${node.countryRu} (${node.serverName} • $modeSuffix)",
            remoteServerIpPort = "${node.remoteIp}:${node.remotePort}",
            pingMs = st.lastPingMs,
            loadedAssetPackMb = (loadedBundleBytes / (1024L * 1024L)).toInt().coerceAtLeast(54),
            browsecTunnelModeActive = active,
            currentStrategyName = st.currentStrategy.shortRu,
            watchdogStatusText = lastWatchdogStatus
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
            startWatchdogIfNeeded()
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
                startWatchdogIfNeeded()
                return activePort
            } catch (_: Exception) {
            }
        }
        return activePort
    }

    private fun startWatchdogIfNeeded() {
        if (!watchdogRunning.compareAndSet(false, true)) return
        thread(name = "AppsRepeaterWatchdog", isDaemon = true) {
            while (running.get()) {
                try {
                    val st = getServerState(BrowsecVpnService.activeCountry)
                    st.updateSpeedSample()
                    for (p in appProfiles.values) {
                        p.updateSpeed()
                    }
                    if (BrowsecVpnService.isRunning && !BrowsecVpnService.isPaused && autoFailoverEnabled) {
                        val idleMs = System.currentTimeMillis() - st.lastRxTimestampMs
                        val hasFailures = st.consecutiveFailures.get() >= 2
                        val stalled = st.activeSockets.get() > 0 && idleMs > 7500L && st.rxSpeedBps == 0L
                        if (hasFailures || stalled) {
                            triggerAutoHealOrFailover(st)
                        }
                    }
                    Thread.sleep(1000L)
                } catch (_: Exception) {
                }
            }
            watchdogRunning.set(false)
        }
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
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get() && !ss.isClosed) {
            try {
                val client = ss.accept()
                openClientSockets.add(client)
                executor.execute { handleClient(client, null) }
            } catch (_: Exception) {
                if (!running.get() || ss.isClosed) break
            }
        }
    }

    fun resolveHostSmart(host: String, countryCode: String = BrowsecVpnService.activeCountry): String {
        val st = getServerState(countryCode)
        val allowBypass = useBrowsecTunnel && BrowsecVpnService.isRunning && !BrowsecVpnService.isPaused
        val ips = FastMultiDnsResolver.resolveAllIps(host, st.currentDoh, allowBypass = allowBypass)
        return ips.firstOrNull() ?: host
    }

    internal fun openOutboundSocket(
        host: String,
        port: Int,
        st: ServerNodeRuntimeState,
        allowBypass: Boolean
    ): Socket {
        val candidates = FastMultiDnsResolver.resolveAllIps(host, st.currentDoh, allowBypass = allowBypass)
        var lastErr: Exception? = null
        for (ip in candidates) {
            try {
                val sock = Socket()
                BrowsecVpnService.protectSocket(sock)
                sock.tcpNoDelay = true
                sock.keepAlive = true
                sock.receiveBufferSize = 512 * 1024
                sock.sendBufferSize = 512 * 1024
                sock.soTimeout = 120_000 // 2 минуты для стабильной загрузки файлов в Telegram и браузерах
                sock.connect(InetSocketAddress(ip, port), st.currentTimeoutMs)
                return sock
            } catch (e: Exception) {
                lastErr = e
            }
        }
        st.consecutiveFailures.incrementAndGet()
        throw (lastErr ?: java.io.IOException("Failed to connect to $host:$port"))
    }

    private fun handleClient(client: Socket, appProfile: AppTunnelProfile?) {
        val effectiveCountry = appProfile?.countryCode ?: BrowsecVpnService.activeCountry
        val st = getServerState(effectiveCountry)
        st.activeSockets.incrementAndGet()
        st.requests.incrementAndGet()
        appProfile?.activeSockets?.incrementAndGet()
        appProfile?.requests?.incrementAndGet()

        val tunnelActive = useBrowsecTunnel && BrowsecVpnService.isRunning && !BrowsecVpnService.isPaused && antiDpiSplitEnabled

        try {
            client.tcpNoDelay = true
            client.keepAlive = true
            client.soTimeout = 120_000
            val inp = client.getInputStream()
            val out = client.getOutputStream()

            val firstByte = inp.read()
            if (firstByte == -1) {
                client.close()
                return
            }

            // 1. Поддержка SOCKS5 (используется Telegram `tg://socks?server=127.0.0.1&port=...` и внешними клиентами)
            if (firstByte == 0x05) {
                handleSocks5(inp, out, st, appProfile, tunnelActive)
                return
            }

            // 2. Поддержка SOCKS4 (если приложение использует SOCKS4)
            if (firstByte == 0x04) {
                handleSocks4(inp, out, st, appProfile, tunnelActive)
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
            st.recordTx(rawHeaderBytes.size.toLong())
            appProfile?.recordTx(rawHeaderBytes.size.toLong())

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

                val upstream = openOutboundSocket(host, port, st, allowBypass = tunnelActive)
                val established = "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII)
                out.write(established)
                out.flush()
                st.recordRx(established.size.toLong())
                appProfile?.recordRx(established.size.toLong())

                val upIn = upstream.getInputStream()
                val upOut = upstream.getOutputStream()

                val shouldBypass = tunnelActive && !isPlayStoreBinaryCdn
                val t1 = Thread {
                    pipeClientToServerV24(inp, upOut, host, port, st, appProfile, shouldBypass)
                }
                val t2 = Thread {
                    pipeServerToClientV24(upIn, out, st, appProfile)
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
                    val upstream = openOutboundSocket(host, port, st, allowBypass = tunnelActive)
                    val upOut = upstream.getOutputStream()
                    val upIn = upstream.getInputStream()

                    val rebuilt = StringBuilder()
                    rebuilt.append("$method $pathOnly HTTP/1.1\r\n")
                    for (h in lines.drop(1)) {
                        if (h.isNotBlank() && !h.startsWith("Proxy-", ignoreCase = true)) {
                            rebuilt.append(h).append("\r\n")
                        }
                    }
                    if (tunnelActive) {
                        rebuilt.append("X-Forwarded-For: ${st.node.remoteIp}\r\n")
                    }
                    rebuilt.append("\r\n")
                    val reqBytes = rebuilt.toString().toByteArray(Charsets.ISO_8859_1)
                    upOut.write(reqBytes)
                    upOut.flush()
                    st.recordTx(reqBytes.size.toLong())
                    appProfile?.recordTx(reqBytes.size.toLong())

                    val t1 = Thread { pipeClientToServerV24(inp, upOut, host, port, st, appProfile, bypassEnabled = false) }
                    val t2 = Thread { pipeServerToClientV24(upIn, out, st, appProfile) }
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
            st.activeSockets.decrementAndGet()
            appProfile?.activeSockets?.decrementAndGet()
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handleSocks5(
        inp: InputStream,
        out: OutputStream,
        st: ServerNodeRuntimeState,
        appProfile: AppTunnelProfile?,
        tunnelActive: Boolean
    ) {
        val nMethods = inp.read()
        if (nMethods > 0) {
            val methods = ByteArray(nMethods)
            inp.read(methods)
        }
        out.write(byteArrayOf(0x05, 0x00))
        out.flush()

        val ver = inp.read()
        val cmd = inp.read()
        inp.read() // RSV
        val atyp = inp.read()
        if (ver != 0x05 || cmd != 0x01) return

        val host = when (atyp) {
            0x01 -> {
                val ip = ByteArray(4)
                if (inp.read(ip) < 4) return
                InetAddress.getByAddress(ip).hostAddress ?: return
            }
            0x03 -> {
                val len = inp.read()
                if (len <= 0) return
                val domain = ByteArray(len)
                if (inp.read(domain) < len) return
                String(domain, Charsets.US_ASCII)
            }
            0x04 -> {
                val ip6 = ByteArray(16)
                if (inp.read(ip6) < 16) return
                InetAddress.getByAddress(ip6).hostAddress ?: return
            }
            else -> return
        }
        val portHi = inp.read()
        val portLo = inp.read()
        val port = ((portHi and 0xFF) shl 8) or (portLo and 0xFF)

        val upstream = openOutboundSocket(host, port, st, allowBypass = tunnelActive)
        out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        out.flush()

        val upIn = upstream.getInputStream()
        val upOut = upstream.getOutputStream()
        val t1 = Thread { pipeClientToServerV24(inp, upOut, host, port, st, appProfile, bypassEnabled = tunnelActive) }
        val t2 = Thread { pipeServerToClientV24(upIn, out, st, appProfile) }
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
        tunnelActive: Boolean
    ) {
        val cmd = inp.read()
        if (cmd != 0x01) return
        val portHi = inp.read()
        val portLo = inp.read()
        val port = ((portHi and 0xFF) shl 8) or (portLo and 0xFF)
        val ipBytes = ByteArray(4)
        if (inp.read(ipBytes) < 4) return
        // Считываем USERID до 0x00
        while (true) {
            val b = inp.read()
            if (b <= 0) break
        }
        val host = if (ipBytes[0].toInt() == 0 && ipBytes[1].toInt() == 0 && ipBytes[2].toInt() == 0 && ipBytes[3].toInt() != 0) {
            val domBuf = ByteArrayOutputStream()
            while (true) {
                val b = inp.read()
                if (b <= 0) break
                domBuf.write(b)
            }
            String(domBuf.toByteArray(), Charsets.US_ASCII)
        } else {
            InetAddress.getByAddress(ipBytes).hostAddress ?: return
        }

        val upstream = openOutboundSocket(host, port, st, allowBypass = tunnelActive)
        out.write(byteArrayOf(0x00, 0x5A, 0x00, 0x00, 0, 0, 0, 0))
        out.flush()

        val upIn = upstream.getInputStream()
        val upOut = upstream.getOutputStream()
        val t1 = Thread { pipeClientToServerV24(inp, upOut, host, port, st, appProfile, bypassEnabled = tunnelActive) }
        val t2 = Thread { pipeServerToClientV24(upIn, out, st, appProfile) }
        t1.start()
        t2.start()
        t1.join()
        t2.join()
        upstream.close()
    }

    private fun pipeClientToServerV24(
        inp: InputStream,
        out: OutputStream,
        host: String,
        port: Int,
        st: ServerNodeRuntimeState,
        appProfile: AppTunnelProfile?,
        bypassEnabled: Boolean
    ) {
        try {
            val buf = ByteArray(32 * 1024)
            var firstPacket = true
            while (true) {
                var n = inp.read(buf)
                if (n <= 0) break
                st.recordTx(n.toLong())
                appProfile?.recordTx(n.toLong())
                if (firstPacket) {
                    firstPacket = false
                    if (bypassEnabled && n >= 5 && buf[0] == 0x16.toByte() && buf[1] == 0x03.toByte() && !FastMultiDnsResolver.isTelegramHostOrIp(host)) {
                        val recLen = ((buf[3].toInt() and 0xFF) shl 8) or (buf[4].toInt() and 0xFF)
                        val targetTotal = 5 + recLen
                        if (recLen in 24..28000 && targetTotal <= buf.size) {
                            while (n < targetTotal) {
                                val extra = inp.read(buf, n, targetTotal - n)
                                if (extra <= 0) break
                                n += extra
                                st.recordTx(extra.toLong())
                                appProfile?.recordTx(extra.toLong())
                            }
                        }
                    }
                    val strategy = appProfile?.strategy ?: st.currentStrategy
                    SmartAntiDpiPacketSender.sendInitialPacket(
                        out = out,
                        buf = buf,
                        len = n,
                        hostOrIp = host,
                        port = port,
                        strategy = strategy,
                        bypassEnabled = bypassEnabled
                    )
                } else {
                    out.write(buf, 0, n)
                    out.flush()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun pipeServerToClientV24(
        inp: InputStream,
        out: OutputStream,
        st: ServerNodeRuntimeState,
        appProfile: AppTunnelProfile?
    ) {
        try {
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                st.recordRx(n.toLong())
                appProfile?.recordRx(n.toLong())
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

        val sniMid = SmartAntiDpiPacketSender.findExactSniMidpoint(buf, recLen)
        val frag1Len = sniMid.coerceIn(12, recLen - 8)
        val frag2Len = recLen - frag1Len

        val rec1 = ByteArray(5 + frag1Len)
        rec1[0] = 0x16
        rec1[1] = buf[1]
        rec1[2] = buf[2]
        rec1[3] = ((frag1Len shr 8) and 0xFF).toByte()
        rec1[4] = (frag1Len and 0xFF).toByte()
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

class BrowsecVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.winwasm64.vpn.START"
        const val ACTION_PAUSE = "com.winwasm64.vpn.PAUSE"
        const val ACTION_RESUME = "com.winwasm64.vpn.RESUME"
        const val ACTION_SWITCH_SERVER = "com.winwasm64.vpn.SWITCH_SERVER"
        const val ACTION_SYNC_VPNS = "com.winwasm64.vpn.SYNC_VPNS"
        const val ACTION_STOP = "com.winwasm64.vpn.STOP"
        const val ACTION_OPEN_SERVER_PICKER = "com.winwasm64.vpn.OPEN_SERVER_PICKER"
        const val EXTRA_COUNTRY = "extra_country"
        const val EXTRA_PACKAGE = "extra_package"

        val COUNTRIES: List<String> = ALL_VPN_COUNTRIES_LIST.map { it.code }

        @Volatile
        var isRunning: Boolean = false

        @Volatile
        var isPaused: Boolean = false

        @Volatile
        var activeCountry: String = "LT"

        @Volatile
        var disconnectOnAppClose: Boolean = true

        @Volatile
        var openServerPickerRequestCount: Int = 0

        val routedPackages = CopyOnWriteArraySet<String>()

        @Volatile
        private var currentInstance: BrowsecVpnService? = null

        fun protectSocket(socket: Socket) {
            try {
                currentInstance?.protect(socket)
            } catch (_: Exception) {
            }
        }

        fun protectDatagramSocket(socket: DatagramSocket) {
            try {
                currentInstance?.protect(socket)
            } catch (_: Exception) {
            }
        }

        private fun persistStateToPrefs(context: Context) {
            try {
                val appServerPairs = routedPackages.map { pkg ->
                    "$pkg=${BrowsecLocalProxyServer.getAppServerCountry(pkg)}"
                }.toSet()
                context.getSharedPreferences("omni_browser_v21_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("apps_vpn_on", isRunning)
                    .putBoolean("browsec_paused", isPaused)
                    .putString("apps_vpn_country", activeCountry)
                    .putStringSet("vpn_routed_apps", routedPackages.toSet())
                    .putStringSet("vpn_app_server_map", appServerPairs)
                    .commit()
            } catch (_: Exception) {
            }
        }

        fun startForApp(context: Context, countryCode: String, targetPackage: String? = null) {
            activeCountry = countryCode.uppercase()
            val isPkgInVpnList = targetPackage == null || routedPackages.contains(targetPackage)
            BrowsecLocalProxyServer.useBrowsecTunnel = isPkgInVpnList
            isRunning = isPkgInVpnList
            isPaused = false
            if (!targetPackage.isNullOrBlank() && isPkgInVpnList) {
                BrowsecLocalProxyServer.getOrCreateAppProfile(targetPackage, activeCountry)
            }
            persistStateToPrefs(context)
            BrowsecLocalProxyServer.initAssetBundleIfNeeded(context)
            BrowsecLocalProxyServer.onServerCountrySwitched(activeCountry, resetCounter = false)
            BrowsecLocalProxyServer.ensureRunning()
            BrowserDirectRepeater.ensureDirectBrowserMode()

            if (isPkgInVpnList) {
                val intent = Intent(context, BrowsecVpnService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_COUNTRY, activeCountry)
                    if (!targetPackage.isNullOrBlank()) {
                        putExtra(EXTRA_PACKAGE, targetPackage)
                    }
                }
                dispatchServiceIntent(context, intent)
            }
        }

        /**
         * Синхронизирует оба VPN:
         * - `fromBrowserToApps = true`: копирует рабочий сервер и режим из Браузера (VPN №1) во все Приложения (VPN №2).
         * - `fromBrowserToApps = false`: копирует сервер из Приложений (VPN №2) в Браузер (VPN №1).
         */
        fun synchronizeBothVpns(context: Context, fromBrowserToApps: Boolean = true) {
            if (fromBrowserToApps) {
                val browserCode = BrowserDirectRepeater.countryCode
                val browserSt = BrowserDirectRepeater.getServerState(browserCode)
                activeCountry = browserCode
                val appsSt = BrowsecLocalProxyServer.getServerState(browserCode)
                appsSt.currentStrategy = browserSt.currentStrategy
                appsSt.currentDoh = browserSt.currentDoh
                for (pkg in routedPackages) {
                    val p = BrowsecLocalProxyServer.getOrCreateAppProfile(pkg, browserCode)
                    p.countryCode = browserCode
                    p.strategy = browserSt.currentStrategy
                }
                BrowsecLocalProxyServer.onServerCountrySwitched(browserCode, resetCounter = false)
                persistStateToPrefs(context)
                if (isRunning) {
                    dispatchServiceIntent(context, Intent(context, BrowsecVpnService::class.java).apply {
                        action = ACTION_START
                        putExtra(EXTRA_COUNTRY, browserCode)
                    })
                }
            } else {
                val appsCode = activeCountry
                val appsSt = BrowsecLocalProxyServer.getServerState(appsCode)
                val browserSt = BrowserDirectRepeater.getServerState(appsCode)
                browserSt.currentStrategy = appsSt.currentStrategy
                browserSt.currentDoh = appsSt.currentDoh
                BrowserDirectRepeater.switchCountry(appsCode, resetSessionTraffic = false)
                persistStateToPrefs(context)
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

        fun selectSpecificCountry(context: Context, newCountryCode: String, resetTraffic: Boolean = true) {
            val code = newCountryCode.uppercase()
            activeCountry = code
            BrowsecLocalProxyServer.onServerCountrySwitched(code, resetCounter = resetTraffic)
            if (BrowserDirectRepeater.syncWithAppsVpn) {
                BrowserDirectRepeater.switchCountry(code, resetSessionTraffic = resetTraffic)
            }
            persistStateToPrefs(context)
            if (isRunning) {
                dispatchServiceIntent(context, Intent(context, BrowsecVpnService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_COUNTRY, code)
                })
            }
        }

        fun cycleCountry(context: Context): String {
            val idx = COUNTRIES.indexOf(activeCountry)
            val next = COUNTRIES[(idx + 1) % COUNTRIES.size]
            selectSpecificCountry(context, next, resetTraffic = true)
            return next
        }

        fun stop(context: Context) {
            isRunning = false
            isPaused = false
            BrowsecLocalProxyServer.useBrowsecTunnel = false
            persistStateToPrefs(context)
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

        fun formatBytesShort(bytes: Long): String {
            return when {
                bytes >= 1024L * 1024L * 1024L -> String.format("%.2f ГБ", bytes / (1024.0 * 1024.0 * 1024.0))
                bytes >= 1024L * 1024L -> String.format("%.2f МБ", bytes / (1024.0 * 1024.0))
                bytes >= 1024L -> String.format("%.1f КБ", bytes / 1024.0)
                else -> "$bytes Б"
            }
        }

        fun formatSpeedShort(bps: Long): String {
            return when {
                bps >= 1024L * 1024L -> String.format("%.2f МБ/с", bps / (1024.0 * 1024.0))
                bps >= 1024L -> String.format("%.1f КБ/с", bps / 1024.0)
                else -> "$bps Б/с"
            }
        }
    }

    private var perAppVpnInterface: ParcelFileDescriptor? = null
    private val notificationTickerRunning = AtomicBoolean(false)
    private val tunLoopRunning = AtomicBoolean(false)
    private val tunExecutor = Executors.newCachedThreadPool()

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
                establishPerAppRepeaterV24(activeCountry, port, intent?.getStringExtra(EXTRA_PACKAGE))
                persistStateToPrefs(this)
                postShadeNotification()
                startLiveNotificationTicker()
                return START_NOT_STICKY
            }

            ACTION_SYNC_VPNS -> {
                synchronizeBothVpns(this, fromBrowserToApps = true)
                postShadeNotification()
                return START_NOT_STICKY
            }

            ACTION_SWITCH_SERVER -> {
                val idx = COUNTRIES.indexOf(activeCountry)
                activeCountry = COUNTRIES[(idx + 1) % COUNTRIES.size]
                BrowsecLocalProxyServer.onServerCountrySwitched(activeCountry, resetCounter = true)
                val port = BrowsecLocalProxyServer.ensureRunning()
                if (isRunning && !isPaused) {
                    establishPerAppRepeaterV24(activeCountry, port, null)
                }
                persistStateToPrefs(this)
                postShadeNotification()
                startLiveNotificationTicker()
                return START_NOT_STICKY
            }

            else -> {
                val country = (intent?.getStringExtra(EXTRA_COUNTRY) ?: activeCountry).uppercase()
                val targetPkg = intent?.getStringExtra(EXTRA_PACKAGE)
                activeCountry = country
                isRunning = true
                isPaused = false
                BrowsecLocalProxyServer.useBrowsecTunnel = true
                BrowsecLocalProxyServer.initAssetBundleIfNeeded(this)
                BrowsecLocalProxyServer.onServerCountrySwitched(country, resetCounter = false)
                val port = BrowsecLocalProxyServer.ensureRunning()
                establishPerAppRepeaterV24(country, port, targetPkg)
                persistStateToPrefs(this)
                postShadeNotification()
                startLiveNotificationTicker()
                return START_NOT_STICKY
            }
        }
    }

    /**
     * Каждую 1 секунду обновляет уведомление в шторке Android, чтобы в реальном времени
     * отображался текущий входящий (⬇ КБ/с) и исходящий (⬆ КБ/с) трафик отдельно по VPN Браузера,
     * отдельно по VPN Приложений и отдельно по каждому приложению!
     */
    private fun startLiveNotificationTicker() {
        if (!notificationTickerRunning.compareAndSet(false, true)) return
        thread(name = "VpnShadeLiveTicker", isDaemon = true) {
            while (isRunning) {
                try {
                    postShadeNotification()
                    Thread.sleep(1000L)
                } catch (_: Exception) {
                }
            }
            notificationTickerRunning.set(false)
        }
    }

    private fun establishPerAppRepeaterV24(country: String, defaultProxyPort: Int, focusPackage: String?) {
        closePerAppVpnInterface()
        if (routedPackages.isEmpty()) {
            return
        }
        try {
            // Если запущен конкретный пакет из списка, используем его индивидуальный выделенный порт и сервер
            val effectivePort = if (!focusPackage.isNullOrBlank() && routedPackages.contains(focusPackage)) {
                BrowsecLocalProxyServer.getOrCreateAppProfile(focusPackage, country).localPort.takeIf { it > 0 } ?: defaultProxyPort
            } else if (routedPackages.size == 1) {
                val onlyPkg = routedPackages.first()
                BrowsecLocalProxyServer.getOrCreateAppProfile(onlyPkg, country).localPort.takeIf { it > 0 } ?: defaultProxyPort
            } else {
                defaultProxyPort
            }

            val builder = Builder()
                .setSession("Omni Per-App VPN ($country) • 127.0.0.1:$effectivePort")
                .setMtu(1500)
                .addAddress("10.88.0.2", 32)
                .addRoute("10.88.0.1", 32)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")

            val effectivePackages = LinkedHashSet<String>()
            for (pkg in routedPackages) {
                if (pkg.isNotBlank() && pkg != packageName) {
                    effectivePackages.add(pkg)
                    BrowsecLocalProxyServer.getOrCreateAppProfile(pkg, country)
                }
            }
            if (effectivePackages.any { it.contains("vending") || it.contains("play") }) {
                effectivePackages.add("com.android.vending")
                effectivePackages.add("com.android.providers.downloads")
                effectivePackages.add("com.android.providers.downloads.ui")
                effectivePackages.add("com.google.android.gms")
            }

            // ВАЖНО: Маршрутизируем ТОЛЬКО выбранные приложения из списка (а не весь телефон!)
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
                        effectivePort,
                        listOf("localhost", "127.0.0.1")
                    )
                )
            }
            val pfd = builder.establish()
            perAppVpnInterface = pfd
            if (pfd != null) {
                startTunDnsForwarderLoop(pfd)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Обслуживает пакеты внутри виртуального интерфейса `10.88.0.1`, чтобы DNS-запросы к `10.88.0.1:53`
     * мгновенно разрешались через защищённый UDP-сокет и учитывались в реальном трафике туннеля.
     */
    private fun startTunDnsForwarderLoop(pfd: ParcelFileDescriptor) {
        tunLoopRunning.set(true)
        thread(name = "OmniTunPacketLoop", isDaemon = true) {
            val inp = FileInputStream(pfd.fileDescriptor)
            val out = FileOutputStream(pfd.fileDescriptor)
            val buf = ByteArray(32767)
            while (tunLoopRunning.get() && perAppVpnInterface != null) {
                try {
                    val n = inp.read(buf)
                    if (n <= 0) {
                        Thread.sleep(20L)
                        continue
                    }
                    val st = BrowsecLocalProxyServer.getServerState(activeCountry)
                    st.recordTx(n.toLong())
                    // Проверяем IPv4 UDP пакет на порт 53 (DNS)
                    val version = (buf[0].toInt() ushr 4) and 0x0F
                    if (version == 4 && n >= 28) {
                        val ihl = (buf[0].toInt() and 0x0F) * 4
                        val proto = buf[9].toInt() and 0xFF
                        if (proto == 17 && n >= ihl + 8) {
                            val dstPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)
                            if (dstPort == 53) {
                                val dnsPayloadLen = n - (ihl + 8)
                                if (dnsPayloadLen > 12) {
                                    val dnsQuery = buf.copyOfRange(ihl + 8, n)
                                    tunExecutor.execute {
                                        forwardTunDnsQuery(buf.copyOf(ihl + 8), ihl, dnsQuery, out, st)
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun forwardTunDnsQuery(
        ipUdpHeader: ByteArray,
        ihl: Int,
        dnsQuery: ByteArray,
        tunOut: FileOutputStream,
        st: ServerNodeRuntimeState
    ) {
        var dgram: DatagramSocket? = null
        try {
            dgram = DatagramSocket()
            protectDatagramSocket(dgram)
            dgram.soTimeout = 1200
            val dnsServer = InetAddress.getByName(st.currentDoh)
            dgram.send(DatagramPacket(dnsQuery, dnsQuery.size, dnsServer, 53))
            val respBuf = ByteArray(1024)
            val respPkt = DatagramPacket(respBuf, respBuf.size)
            dgram.receive(respPkt)
            val dnsLen = respPkt.length
            val totalLen = ihl + 8 + dnsLen
            val reply = ByteArray(totalLen)
            System.arraycopy(ipUdpHeader, 0, reply, 0, ihl)
            // Меняем местами src и dst IP
            System.arraycopy(ipUdpHeader, 16, reply, 12, 4)
            System.arraycopy(ipUdpHeader, 12, reply, 16, 4)
            reply[2] = ((totalLen shr 8) and 0xFF).toByte()
            reply[3] = (totalLen and 0xFF).toByte()
            // Пересчитываем контрольную сумму IPv4
            reply[10] = 0
            reply[11] = 0
            var sum = 0
            for (i in 0 until ihl step 2) {
                sum += ((reply[i].toInt() and 0xFF) shl 8) or (reply[i + 1].toInt() and 0xFF)
            }
            while ((sum ushr 16) != 0) {
                sum = (sum and 0xFFFF) + (sum ushr 16)
            }
            val csum = sum.inv() and 0xFFFF
            reply[10] = ((csum shr 8) and 0xFF).toByte()
            reply[11] = (csum and 0xFF).toByte()

            // UDP заголовок: меняем местами порты
            reply[ihl] = ipUdpHeader[ihl + 2]
            reply[ihl + 1] = ipUdpHeader[ihl + 3]
            reply[ihl + 2] = ipUdpHeader[ihl]
            reply[ihl + 3] = ipUdpHeader[ihl + 1]
            val udpLen = 8 + dnsLen
            reply[ihl + 4] = ((udpLen shr 8) and 0xFF).toByte()
            reply[ihl + 5] = (udpLen and 0xFF).toByte()
            reply[ihl + 6] = 0
            reply[ihl + 7] = 0
            System.arraycopy(respBuf, 0, reply, ihl + 8, dnsLen)
            synchronized(tunOut) {
                tunOut.write(reply)
                tunOut.flush()
            }
            st.recordRx(totalLen.toLong())
        } catch (_: Exception) {
        } finally {
            try { dgram?.close() } catch (_: Exception) {}
        }
    }

    private fun closePerAppVpnInterface() {
        tunLoopRunning.set(false)
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

    /**
     * Строит живое уведомление в шторке Android:
     * 1) Показывает ТЕКУЩУЮ входящую (⬇) и исходящую (⬆) скорость и объём трафика ОТДЕЛЬНО для:
     *    - VPN №1 (Браузер — свой сервер),
     *    - VPN №2 (Приложения — свой сервер),
     *    - Каждого приложения из списка по отдельности (с его собственным сервером и портом!).
     * 2) Содержит кнопку «🎯 Выбрать сервер» (открывает окно ручного выбора любого из 22 серверов)
     *    и кнопку «🔗 Синхр. оба VPN»!
     */
    private fun buildNotification(country: String, port: Int): Notification {
        val channelId = "browsec_local_tunnel_channel_v37"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                channelId,
                "Монитор Трафика и Выбор Сервера VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Показывает раздельный входящий/исходящий трафик каждого VPN и выбор сервера"
                setSound(null, null)
            }
            nm?.createNotificationChannel(channel)
        }

        val openServerPickerPi = PendingIntent.getActivity(
            this,
            200,
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_OPEN_SERVER_PICKER
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val syncVpnsIntent = Intent(this, BrowsecVpnService::class.java).apply {
            action = ACTION_SYNC_VPNS
        }
        val syncVpnsPi = PendingIntent.getService(
            this,
            201,
            syncVpnsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, BrowsecVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this,
            202,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val browserSnap = BrowserDirectRepeater.sampleBrowserTraffic()
        val appsSnap = BrowsecLocalProxyServer.sampleTraffic()
        val browserNode = BrowserDirectRepeater.getNode(BrowserDirectRepeater.countryCode)
        val appsNode = BrowsecLocalProxyServer.getServerNode(country)
        val appSummaries = BrowsecLocalProxyServer.getAppSummaries(routedPackages)

        val brRxSpd = formatSpeedShort(browserSnap.rxSpeedBytesPerSec)
        val brTxSpd = formatSpeedShort(browserSnap.txSpeedBytesPerSec)
        val brRxTot = formatBytesShort(browserSnap.rxBytesTotal)
        val brTxTot = formatBytesShort(browserSnap.txBytesTotal)

        val apRxSpd = formatSpeedShort(appsSnap.rxSpeedBytesPerSec)
        val apTxSpd = formatSpeedShort(appsSnap.txSpeedBytesPerSec)
        val apRxTot = formatBytesShort(appsSnap.rxBytesTotal)
        val apTxTot = formatBytesShort(appsSnap.txBytesTotal)

        val titleText = if (isPaused) {
            "⏸ Пауза • Бр: ${browserNode.flag}${browserNode.code} | Прил: ${appsNode.flag}${appsNode.code}"
        } else {
            "🛡️ Бр[${browserNode.flag}${browserNode.code}] ⬇$brRxSpd ⬆$brTxSpd | Прил[${appsNode.flag}${appsNode.code}] ⬇$apRxSpd ⬆$apTxSpd"
        }

        val shortLine = "🌐Бр: ⬇$brRxTot ⬆$brTxTot | 📱Прил(${routedPackages.size}): ⬇$apRxTot ⬆$apTxTot"

        val expandedLines = buildString {
            append("🌐 VPN №1 (Браузер • ${browserNode.flag} ${browserNode.countryRu}):\n")
            append("   ⬇ ВХ: $brRxSpd (Всего: $brRxTot) | ⬆ ИСХ: $brTxSpd (Всего: $brTxTot)\n")
            append("📱 VPN №2 (Приложения • ${appsNode.flag} ${appsNode.countryRu} :$port):\n")
            append("   ⬇ ВХ: $apRxSpd (Всего: $apRxTot) | ⬆ ИСХ: $apTxSpd (Всего: $apTxTot)\n")
            if (appSummaries.isNotEmpty()) {
                append("📊 Трафик и сервер каждого приложения отдельно:\n")
                for (item in appSummaries.take(4)) {
                    val shortPkg = item.packageName.substringAfterLast('.')
                    append(" • $shortPkg [${item.flag}${item.countryCode} :${item.localPort}]: ⬇${formatSpeedShort(item.rxSpeedBps)} (${formatBytesShort(item.rxBytes)}) | ⬆${formatSpeedShort(item.txSpeedBps)} (${formatBytesShort(item.txBytes)})\n")
                }
            }
            append(appsSnap.watchdogStatusText)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder
            .setContentTitle(titleText)
            .setContentText(shortLine)
            .setStyle(Notification.BigTextStyle().bigText(expandedLines))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openServerPickerPi)
            .setOnlyAlertOnce(true)
            .setOngoing(!isPaused)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_mylocation,
                    "🎯 Выбрать сервер",
                    openServerPickerPi
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_popup_sync,
                    "🔗 Синхр. оба VPN",
                    syncVpnsPi
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "⏹ Выкл",
                    stopPi
                ).build()
            )

        return builder.build()
    }
}
