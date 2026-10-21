package com.winwasm64.vpn

import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Честная проверка профилей обхода блокировок.
 *
 * Вместо подставных пингов и «бодрящих» байт каждый профиль проверяется по-настоящему:
 * DNS-запрос с подсетью (ECS) этого профиля + реальное TCP-подключение к YouTube:443.
 * Проверка НИЧЕГО не переключает и не меняет настройки — только обновляет показатели.
 */
object VpnProfileProber {

    data class ProbeResult(
        val code: String,
        val ok: Boolean,
        val connectMs: Int,
        val resolvedIp: String?
    )

    private val fullProbeRunning = AtomicBoolean(false)

    @Volatile
    var lastFullProbeAtMs: Long = 0L
        private set

    val isFullProbeRunning: Boolean
        get() = fullProbeRunning.get()

    /** Реальная проверка связи активного профиля: HEAD generate_204, задержка без подгонки. */
    fun measureActiveLink(st: ServerNodeRuntimeState) {
        val t0 = SystemClock.elapsedRealtime()
        var ok = false
        try {
            val conn = (URL("https://connectivitycheck.gstatic.com/generate_204")
                .openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "HEAD"
                useCaches = false
            }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..399) {
                st.pingMs = (SystemClock.elapsedRealtime() - t0).toInt().coerceAtLeast(1)
                ok = true
            }
        } catch (_: Exception) {
        }
        st.lastProbeOk = ok
        st.lastProbeAtMs = System.currentTimeMillis()
    }

    /** Проверяет один профиль: DNS (с ECS профиля) + TCP-подключение к одному из полученных IP. */
    fun probeNode(node: VpnServerNodeInfo, dohServer: String): ProbeResult {
        val candidates = try {
            FastMultiDnsResolver.buildCandidateIps(
                host = "www.youtube.com",
                ecsSubnet = node.ecsSubnet,
                dohServer = dohServer
            )
        } catch (_: Exception) {
            emptyList()
        }
        for (ip in candidates.take(3)) {
            try {
                Socket().use { s ->
                    BrowsecVpnService.protectSocket(s)
                    val t0 = SystemClock.elapsedRealtime()
                    s.connect(InetSocketAddress(ip, 443), 2500)
                    val ms = (SystemClock.elapsedRealtime() - t0).toInt().coerceAtLeast(1)
                    return ProbeResult(node.code, true, ms, ip)
                }
            } catch (_: Exception) {
            }
        }
        return ProbeResult(node.code, false, 0, null)
    }

    /** Записывает результат проверки в оба набора состояний (браузер и приложения). */
    fun applyResult(r: ProbeResult) {
        val now = System.currentTimeMillis()
        val states = listOf(
            BrowserDirectRepeater.getServerState(r.code),
            BrowsecLocalProxyServer.getServerState(r.code)
        )
        for (st in states) {
            st.lastProbeOk = r.ok
            st.lastProbeAtMs = now
            if (r.ok) {
                st.pingMs = r.connectMs
                if (st.tuningStep == 0) {
                    st.statusNoteRu = "Проверен: ответ за ${r.connectMs} мс • ${st.currentStrategy.shortRu}"
                }
            } else if (st.tuningStep == 0) {
                st.statusNoteRu = "Нет ответа при проверке"
            }
        }
    }

    /** Проверяет все 22 профиля параллельно (6 потоков): при запуске и по кнопке «Проверить все». */
    fun probeAllAsync(
        onProgress: ((ProbeResult) -> Unit)? = null,
        onDone: (() -> Unit)? = null
    ) {
        if (!fullProbeRunning.compareAndSet(false, true)) return
        thread(name = "VpnProfileProber", isDaemon = true) {
            val pool = Executors.newFixedThreadPool(6)
            try {
                val futures = ALL_VPN_COUNTRIES_LIST.map { node ->
                    val doh = BrowserDirectRepeater.getServerState(node.code).currentDoh
                    pool.submit<ProbeResult> { probeNode(node, doh) }
                }
                for (f in futures) {
                    val r = try {
                        f.get(12, TimeUnit.SECONDS)
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    applyResult(r)
                    onProgress?.invoke(r)
                }
                lastFullProbeAtMs = System.currentTimeMillis()
            } finally {
                pool.shutdownNow()
                fullProbeRunning.set(false)
                onDone?.invoke()
            }
        }
    }
}
