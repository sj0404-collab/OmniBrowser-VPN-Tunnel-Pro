package com.winwasm64.vpn

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.winwasm64.extensions.ExtensionProxy
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Настоящий VPN через серверы Browsec — так же, как расширение Browsec в Lemur/Chrome:
 * список стран берём с API Browsec, трафик идёт через HTTPS-прокси выбранной страны (TLS + CONNECT).
 * Через него ходят браузер (маршрут в ExtensionProxy) и приложения из белого списка (прозрачный туннель).
 * Бесплатные страны работают без входа в аккаунт; премиум-страны требуют Browsec Premium.
 */
object BrowsecNative {
    private const val TAG = "OmniBrowsec"
    private const val PREFS = "omni_browsec_native"
    private val API_BASES = listOf("https://browsec.com/api/v1", "https://e4.bmtr.org/api/v1", "https://e5.bmtr.org/api/v1")
    private const val SPEED_HOST = "speed.cloudflare.com"
    private const val SPEED_BYTES = 300_000
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    class Server(val host: String, val port: Int) {
        val key: String get() = "$host:$port"
    }

    class Country(val code: String, val free: List<Server>, val premium: List<Server>) {
        val isFree: Boolean get() = free.isNotEmpty()
        val flag: String get() = flagOf(code)
        val nameRu: String get() = countryNameRu(code)
        fun servers(): List<Server> = if (free.isNotEmpty()) free else premium
    }

    class Metrics {
        @Volatile var pingMs: Int = -1
        @Volatile var speedBps: Long = -1
        @Volatile var bestServer: String? = null
        @Volatile var error: String? = null
        @Volatile var testing: Boolean = false
        @Volatile var measuredAt: Long = 0L
    }

    /** Поток ответа сервера Browsec: считает принятые байты (живой трафик). */
    class CountingInput(inp: InputStream) : FilterInputStream(inp) {
        override fun read(): Int {
            val b = super.read()
            if (b >= 0) bytesDown.incrementAndGet()
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) bytesDown.addAndGet(n.toLong())
            return n
        }
    }

    /** Поток к серверу Browsec: считает отправленные байты. */
    class CountingOutput(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: Int) {
            out.write(b)
            bytesUp.incrementAndGet()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            if (len > 0) bytesUp.addAndGet(len.toLong())
        }
    }

    // ---------------- состояние (наблюдается из Compose и панелей) ----------------
    var enabled by mutableStateOf(false)
        private set
    var activeCode by mutableStateOf("nl")
        private set
    var countries by mutableStateOf<List<Country>>(emptyList())
        private set
    var version by mutableIntStateOf(0)
        private set
    var statusText by mutableStateOf("")
        private set
    var ruDirect by mutableStateOf(true)
        private set
    var bubbleWanted by mutableStateOf(false)
        private set

    val metrics = ConcurrentHashMap<String, Metrics>()
    private val serverFails = ConcurrentHashMap<String, Long>()
    val bytesUp = AtomicLong()
    val bytesDown = AtomicLong()
    @Volatile var rateUp: Long = 0
        private set
    @Volatile var rateDown: Long = 0
        private set

    /** Вызываются на главном потоке: раз в секунду и при любых изменениях (уведомление, плавающая кнопка, панели). */
    val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** Защита сокетов от петли через VPN (ставит BrowsecVpnService). */
    @Volatile var socketProtector: ((Socket) -> Unit)? = null

    private val io = Executors.newCachedThreadPool { r -> Thread(r, "omni-browsec").apply { isDaemon = true } }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var appContext: Context? = null
    private var tickerStarted = false
    private var lastUp = 0L
    private var lastDown = 0L
    @Volatile private var refreshing = false

    /** Запасной список (ответ API на 22.10.2026), если API Browsec недоступен при первом запуске. */
    private val FALLBACK_FREE = mapOf(
        "de" to listOf("de57.rapidstaticserve.cc:443", "de62.staticvaultcdn.xyz:443", "de67.staticvaultcdn.org:443"),
        "lt" to listOf("lt40.staticvaultcdn.org:443", "lt36.staticvaultcdn.org:443", "lt44.staticvaultcdn.xyz:443"),
        "lv" to listOf("lv193.staticvaultcdn.org:443", "lv197.rapidstaticserve.cc:443", "lv117.staticvaultcdn.org:443"),
        "nl" to listOf("nl139.rapidstaticserve.cc:443", "nl138.rapidstaticserve.cc:443", "nl124.staticvaultcdn.xyz:443"),
        "sg" to listOf("sg9.rapidcdn.xyz:6886", "sg1.bd-assets.com:3987", "sg3.cdnflow.net:23803"),
        "uk" to listOf("uk25.contentnode.net:16927", "uk35.datafrenzy.org:24399", "uk30.speedycdn.xyz:19881"),
        "us" to listOf("us35.speedycdn.xyz:4603", "us30.rapidcdn.xyz:23226", "us23.contentnode.net:19600")
    )

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val p = prefs()
        enabled = p.getBoolean("enabled", false)
        activeCode = p.getString("active", "nl") ?: "nl"
        ruDirect = p.getBoolean("ru_direct", true)
        bubbleWanted = p.getBoolean("bubble", false)
        p.getString("servers_json", null)?.let { j ->
            try {
                val list = parseServers(j)
                if (list.any { it.isFree }) countries = list
            } catch (_: Exception) {
            }
        }
        if (countries.isEmpty()) countries = fallbackCountries()
        startTicker()
        if (System.currentTimeMillis() - p.getLong("servers_at", 0L) > 6 * 3600_000L) refreshServers(null)
    }

    private fun prefs(): SharedPreferences = appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun clientId(): String {
        val p = prefs()
        p.getString("cid", null)?.let { return it }
        val id = UUID.randomUUID().toString()
        p.edit().putString("cid", id).apply()
        return id
    }

    fun fallbackCountries(): List<Country> = FALLBACK_FREE.map { (code, list) ->
        Country(code, list.map { Server(it.substringBefore(':'), it.substringAfter(':').toInt()) }, emptyList())
    }.sortedBy { it.nameRu }

    fun parseServers(json: String): List<Country> {
        val root = JSONObject(json)
        val cs = root.optJSONObject("countries") ?: return emptyList()
        val out = ArrayList<Country>()
        val it = cs.keys()
        while (it.hasNext()) {
            val code = it.next()
            val o = cs.optJSONObject(code) ?: continue
            out.add(Country(code.lowercase(Locale.ROOT), parseList(o.optJSONArray("servers")), parseList(o.optJSONArray("premium_servers"))))
        }
        return out.sortedWith(compareBy<Country>({ !it.isFree }, { it.nameRu }))
    }

    private fun parseList(a: JSONArray?): List<Server> {
        if (a == null) return emptyList()
        val out = ArrayList<Server>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val host = o.optString("host", "").trim()
            val port = o.optInt("port", 0)
            if (host.isNotEmpty() && port in 1..65535) out.add(Server(host, port))
        }
        return out
    }

    /** Свежий список стран с API Browsec (основной адрес и зеркала, как в самом расширении). */
    fun refreshServers(onDone: ((Boolean) -> Unit)?) {
        if (refreshing || appContext == null) return
        refreshing = true
        io.execute {
            var ok = false
            val cid = try { clientId() } catch (_: Exception) { UUID.randomUUID().toString() }
            for (base in API_BASES) {
                var c: HttpURLConnection? = null
                try {
                    c = URL("$base/servers?stdomains=1&cid=$cid").openConnection() as HttpURLConnection
                    c.connectTimeout = 8000
                    c.readTimeout = 10000
                    c.setRequestProperty("X-Browsec-Version", "4.0.0")
                    c.setRequestProperty("Cache-Control", "no-cache")
                    c.setRequestProperty("User-Agent", UA)
                    if (c.responseCode == 200) {
                        val body = c.inputStream.bufferedReader().use { r -> r.readText() }
                        val list = parseServers(body)
                        if (list.any { it.isFree }) {
                            prefs().edit().putString("servers_json", body).putLong("servers_at", System.currentTimeMillis()).apply()
                            main.post {
                                countries = list
                                if (enabled) applyBrowserRoute()
                            }
                            ok = true
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "servers $base: ${e.message}")
                } finally {
                    try { c?.disconnect() } catch (_: Exception) {}
                }
            }
            refreshing = false
            main.post {
                statusText = if (ok) "" else "API Browsec недоступен — показан сохранённый список"
                bump()
                onDone?.invoke(ok)
            }
        }
    }

    // ---------------- режим и выбор страны ----------------
    fun activeCountry(): Country? = countries.firstOrNull { it.code == activeCode && it.isFree } ?: countries.firstOrNull { it.isFree }

    fun applyEnabled(on: Boolean) {
        enabled = on
        appContext?.let { prefs().edit().putBoolean("enabled", on).apply() }
        applyBrowserRoute()
        bump()
    }

    /** Выбор страны: сразу для браузера и для новых соединений приложений из белого списка. */
    fun select(code: String): Boolean {
        val c = countries.firstOrNull { it.code == code } ?: return false
        if (!c.isFree) {
            statusText = "${c.nameRu}: только для Browsec Premium"
            bump()
            return false
        }
        activeCode = code
        statusText = ""
        appContext?.let { prefs().edit().putString("active", code).apply() }
        if (!enabled) applyEnabled(true) else {
            applyBrowserRoute()
            bump()
        }
        val m = metrics[code]
        if (m == null || m.pingMs < 0) measure(code, false)
        return true
    }

    fun applyRuDirect(on: Boolean) {
        ruDirect = on
        appContext?.let { prefs().edit().putBoolean("ru_direct", on).apply() }
        applyBrowserRoute()
        bump()
    }

    fun applyBubbleWanted(on: Boolean) {
        bubbleWanted = on
        appContext?.let { prefs().edit().putBoolean("bubble", on).apply() }
        bump()
    }

    /** Браузер: при включённом Browsec — маршрут через нашу цепочку в ExtensionProxy, иначе возвращаем прежний. */
    fun applyBrowserRoute() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { applyBrowserRoute() }
            return
        }
        try {
            if (!ExtensionProxy.isInitialized) return
            val c = activeCountry()
            if (enabled && c != null) ExtensionProxy.activateNative("Browsec · ${c.nameRu}", bypassList()) else ExtensionProxy.deactivateNative()
        } catch (e: Exception) {
            Log.w(TAG, "route", e)
        }
    }

    fun bypassList(): List<String> {
        val l = arrayListOf("<local>", "localhost", "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16")
        if (ruDirect) l.addAll(listOf(".ru", ".su", ".xn--p1ai", ".рф"))
        return l
    }

    /** Туннель приложений: идти ли этому хосту через Browsec (российские сайты — напрямую, если так выбрано). */
    fun shouldTunnel(host: String): Boolean {
        if (!enabled) return false
        if (!ruDirect) return true
        val h = host.lowercase(Locale.ROOT).trimEnd('.')
        return !(h.endsWith(".ru") || h.endsWith(".su") || h.endsWith(".xn--p1ai") || h.endsWith(".рф"))
    }

    fun needsPing(): Boolean {
        val now = System.currentTimeMillis()
        return countries.filter { it.isFree }.any { c -> val m = metrics[c.code]; m == null || (!m.testing && now - m.measuredAt > 3 * 60_000L) }
    }

    // ---------------- туннель ----------------
    /** TLS к серверу Browsec выбранной страны и CONNECT host:port. Возвращает сокет и поток, который считает принятые байты. */
    fun openTunnel(host: String, port: Int): Pair<Socket, InputStream> {
        val c = activeCountry() ?: throw IOException("Browsec: список стран пуст")
        var last: Exception? = null
        for (s in orderedServers(c)) {
            try {
                val t = connectVia(s, host, port, 12_000)
                serverFails.remove(s.key)
                return t
            } catch (e: Exception) {
                last = e
                serverFails[s.key] = SystemClock.elapsedRealtime()
            }
        }
        throw IOException("Browsec ${c.code.uppercase(Locale.ROOT)}: серверы недоступны (${last?.message})")
    }

    private fun orderedServers(c: Country): List<Server> {
        val best = metrics[c.code]?.bestServer
        val now = SystemClock.elapsedRealtime()
        return c.servers().sortedWith(compareBy<Server>({ (serverFails[it.key] ?: -1_000_000L) > now - 60_000L }, { it.key != best }))
    }

    private fun connectVia(s: Server, host: String, port: Int, timeout: Int): Pair<Socket, InputStream> {
        val raw = Socket()
        try {
            socketProtector?.invoke(raw)
            raw.connect(InetSocketAddress(s.host, s.port), timeout)
            raw.soTimeout = timeout
            raw.tcpNoDelay = true
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, s.host, s.port, true) as SSLSocket
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(s.host, ssl.session)) {
                ssl.close()
                throw IOException("сертификат ${s.host} не совпадает")
            }
            val target = if (host.contains(':') && !host.startsWith("[")) "[$host]:$port" else "$host:$port"
            val req = "CONNECT $target HTTP/1.1\r\nHost: $target\r\nUser-Agent: $UA\r\nProxy-Connection: keep-alive\r\n\r\n"
            val out = ssl.outputStream
            out.write(req.toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val bin = BufferedInputStream(ssl.inputStream, 16 * 1024)
            val head = readHead(bin)
            if (head == null) {
                ssl.close()
                throw IOException("${s.host} закрыл соединение")
            }
            val code = head.substringBefore("\r\n").split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            if (code != 200) {
                ssl.close()
                throw IOException("${s.host}: HTTP $code")
            }
            ssl.soTimeout = 0
            return ssl to CountingInput(bin)
        } catch (e: Exception) {
            try { raw.close() } catch (_: Exception) {}
            throw e
        }
    }

    private fun readHead(inp: InputStream): String? {
        val sb = StringBuilder()
        var state = 0
        while (sb.length < 16 * 1024) {
            val b = inp.read()
            if (b < 0) return null
            val ch = b.toChar()
            sb.append(ch)
            state = when {
                ch == '\r' && (state == 0 || state == 2) -> state + 1
                ch == '\n' && (state == 1 || state == 3) -> state + 1
                else -> 0
            }
            if (state == 4) return sb.toString()
        }
        return null
    }

    fun addUp(n: Int) {
        if (n > 0) bytesUp.addAndGet(n.toLong())
    }

    // ---------------- проверка стран до подключения ----------------
    fun measureAll(withSpeed: Boolean) {
        countries.filter { it.isFree }.forEach { measure(it.code, withSpeed) }
    }

    fun measure(code: String, withSpeed: Boolean) {
        val c = countries.firstOrNull { it.code == code } ?: return
        val m = metrics.getOrPut(code) { Metrics() }
        if (m.testing) return
        m.testing = true
        bump()
        io.execute {
            try {
                var best: Server? = null
                var bestMs = Int.MAX_VALUE
                for (s in c.servers().take(3)) {
                    val ms = tcpPing(s)
                    if (ms in 0 until bestMs) {
                        bestMs = ms
                        best = s
                    }
                }
                if (best == null) {
                    m.pingMs = -1
                    m.speedBps = -1
                    m.error = "нет ответа"
                } else {
                    m.pingMs = bestMs
                    m.bestServer = best.key
                    m.error = null
                    if (withSpeed) m.speedBps = try { speedTest(best) } catch (_: Exception) { -1L }
                }
                m.measuredAt = System.currentTimeMillis()
            } catch (e: Exception) {
                m.error = e.message ?: "ошибка"
            } finally {
                m.testing = false
                main.post { bump() }
            }
        }
    }

    private fun tcpPing(s: Server): Int {
        val sock = Socket()
        return try {
            socketProtector?.invoke(sock)
            val t0 = SystemClock.elapsedRealtime()
            sock.connect(InetSocketAddress(s.host, s.port), 4000)
            (SystemClock.elapsedRealtime() - t0).toInt().coerceAtLeast(1)
        } catch (_: Exception) {
            -1
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    /** Скачиваем 300 КБ через сам прокси страны — реальная скорость, которую получите после подключения. */
    private fun speedTest(s: Server): Long {
        val (sock, _) = connectVia(s, SPEED_HOST, 443, 8000)
        try {
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(sock, SPEED_HOST, 443, true) as SSLSocket
            tls.soTimeout = 10_000
            tls.startHandshake()
            val req = "GET /__down?bytes=$SPEED_BYTES HTTP/1.1\r\nHost: $SPEED_HOST\r\nUser-Agent: $UA\r\nAccept: */*\r\nConnection: close\r\n\r\n"
            val t0 = SystemClock.elapsedRealtime()
            tls.outputStream.write(req.toByteArray(Charsets.ISO_8859_1))
            tls.outputStream.flush()
            val buf = ByteArray(32 * 1024)
            var total = 0L
            val inp = tls.inputStream
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                total += n
                if (SystemClock.elapsedRealtime() - t0 > 9000) break
            }
            val dt = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
            try { tls.close() } catch (_: Exception) {}
            return if (total < 20_000) -1L else total * 1000L / dt
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    // ---------------- живой трафик ----------------
    private fun startTicker() {
        if (tickerStarted) return
        tickerStarted = true
        main.post(object : Runnable {
            override fun run() {
                val up = bytesUp.get()
                val down = bytesDown.get()
                rateUp = (up - lastUp).coerceAtLeast(0L)
                rateDown = (down - lastDown).coerceAtLeast(0L)
                lastUp = up
                lastDown = down
                if (rateUp > 0 || rateDown > 0) version++
                for (l in listeners) {
                    try { l() } catch (_: Exception) {}
                }
                main.postDelayed(this, 1000L)
            }
        })
    }

    private fun bump() {
        version++
        for (l in listeners) {
            try { l() } catch (_: Exception) {}
        }
    }

    // ---------------- форматирование ----------------
    fun formatBytes(b: Long): String = when {
        b < 1024L -> "$b Б"
        b < 1024L * 1024L -> String.format(Locale.US, "%.1f КБ", b / 1024.0)
        b < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f МБ", b / 1048576.0)
        else -> String.format(Locale.US, "%.2f ГБ", b / 1073741824.0)
    }

    fun formatRate(bps: Long): String = formatBytes(bps) + "/с"

    /** Коротко для плавающей кнопки: 0, 85К, 1.2М. */
    fun formatRateShort(bps: Long): String = when {
        bps < 1024L -> "$bps"
        bps < 1024L * 1024L -> "${bps / 1024L}К"
        else -> String.format(Locale.US, "%.1fМ", bps / 1048576.0)
    }

    fun flagOf(code: String): String {
        val cc = (if (code.equals("uk", true)) "gb" else code).uppercase(Locale.ROOT)
        if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) return "🌐"
        val first = 0x1F1E6 + (cc[0] - 'A')
        val second = 0x1F1E6 + (cc[1] - 'A')
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    fun countryNameRu(code: String): String = try {
        val cc = if (code.equals("uk", true)) "GB" else code.uppercase(Locale.ROOT)
        Locale("", cc).getDisplayCountry(Locale("ru")).ifBlank { code.uppercase(Locale.ROOT) }
    } catch (_: Exception) {
        code.uppercase(Locale.ROOT)
    }
}
