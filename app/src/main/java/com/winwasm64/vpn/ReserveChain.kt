package com.winwasm64.vpn

/**
 * v4.30.0: цепочка резервных каналов и порядок перебора.
 *
 * Идея: основной канал (Browsec/свой релей) — первый. Если он упал или упёрся в лимит,
 * браузер должен уйти на следующий живой источник, а не показывать «нет интернета».
 *
 * Порядок резервов:
 *   1. Psiphon — локальный HTTP-прокси 127.0.0.1:порт (он уже есть в приложении);
 *   2. публичные HTTP-прокси из [ReservePool], сначала самые быстрые;
 *   3. публичные SOCKS5 из [ReservePool].
 *
 * Логика состояния (кого пробовать, кого остудить) вынесена в [State] без Android —
 * её видно тестами. Сам объект [ReserveChain] — тонкая обёртка с блокировкой и журналом.
 */

/** Один резервный выход. */
data class ReserveUpstream(
    val kind: ReserveChain.Kind,
    val host: String,
    val port: Int,
    val label: String
)

object ReserveChain {

    enum class Kind { PSIPHON, HTTP, SOCKS5 }

    /** Сколько держать источник «в отстое» после провала. */
    const val COOLDOWN_MS = 10 * 60_000L
    /** Сколько провалов подряд нужно, чтобы увести источник в отстой. */
    const val FAILS_TO_COOLDOWN = 2

    /**
     * Чистая логика перебора: порядок, счётчики провалов, остывание, восстановление.
     * Время передаётся снаружи — так это тестируется без часов и без Android.
     */
    class State(private val cooldownMs: Long = COOLDOWN_MS) {
        private var list: List<ReserveUpstream> = emptyList()
        private var cursor: Int = 0
        private val fails = HashMap<String, Int>()
        private val cooldownUntil = HashMap<String, Long>()

        fun keyOf(u: ReserveUpstream) = "${u.kind}|${u.host}:${u.port}"

        fun setList(newList: List<ReserveUpstream>) {
            val oldKeys = list.map { keyOf(it) }.toSet()
            list = newList
            // оставляем счётчики только для тех, кто ещё в списке
            fails.keys.retainAll { it in newList.map(::keyOf).toSet() }
            cooldownUntil.keys.retainAll { it in newList.map(::keyOf).toSet() }
            cursor = if (newList.isEmpty()) 0 else cursor.coerceIn(0, newList.size - 1)
            if (oldKeys.isEmpty()) cursor = 0
        }

        fun size(): Int = list.size

        /** Текущий источник: первый по порядку с курсора, который не в отстое. */
        fun active(now: Long): ReserveUpstream? {
            if (list.isEmpty()) return null
            for (step in list.indices) {
                val i = (cursor + step) % list.size
                val u = list[i]
                val until = cooldownUntil[keyOf(u)] ?: 0L
                if (until <= now) {
                    cursor = i
                    return u
                }
            }
            return null // все в отстое — ждём
        }

        fun reportSuccess(u: ReserveUpstream, now: Long) {
            fails.remove(keyOf(u))
            cooldownUntil.remove(keyOf(u))
        }

        /** Провал: считаем и, если хватит, уводим в отстой и сдвигаемся дальше. */
        fun reportFailure(u: ReserveUpstream, now: Long): ReserveUpstream? {
            val k = keyOf(u)
            val n = (fails[k] ?: 0) + 1
            fails[k] = n
            if (n >= FAILS_TO_COOLDOWN) {
                cooldownUntil[k] = now + cooldownMs
                fails[k] = 0
                if (list.isNotEmpty()) cursor = (cursor + 1) % list.size
            }
            return active(now)
        }

        fun snapshot(now: Long): String {
            if (list.isEmpty()) return "резервов нет — нажмите «Обновить пул прокси»"
            val cur = active(now)
            val cooled = cooldownUntil.count { it.value > now }
            val sb = StringBuilder()
            sb.append(if (cur == null) "все резервы в отстое" else "резерв: ${cur.label}")
            sb.append(" · источников ${list.size}")
            if (cooled > 0) sb.append(" · в отстое $cooled")
            return sb.toString()
        }
    }

    private val lock = Any()
    private val state = State()

    /** Автоматический перебор включён. Меняется из настроек. */
    @Volatile
    var autoEnabled: Boolean = true

    /**
     * v4.30.1: ручной режим «сначала резерв».
     * По умолчанию false — резерв обязан оставаться запасным путём. Включать, только когда
     * основной канал заведомо упёрся в лимит и надо срочно выйти другим путём.
     */
    @Volatile
    var preferReserve: Boolean = false

    /** Последний известный порт локального HTTP-прокси Psiphon (0 — не поднят). */
    @Volatile
    var psiphonHttpPort: Int = 0

    /** v4.35.0: SOCKS-порт Psiphon — второй путь в тот же туннель, если HTTP-порт не отвечает. */
    @Volatile
    var psiphonSocksPort: Int = 0

    /** Пул публичных прокси в порядке предпочтения. */
    @Volatile
    private var pool: List<ReservePool.Proxy> = emptyList()

    /** Учесть скачанный/загруженный пул. */
    fun applyPool(list: List<ReservePool.Proxy>) {
        synchronized(lock) {
            // Psiphon всегда первым: он «свой», с ним нет проблем доверия
            val ordered = ArrayList<ReserveUpstream>()
            if (psiphonHttpPort > 0) {
                ordered.add(ReserveUpstream(Kind.PSIPHON, "127.0.0.1", psiphonHttpPort, "Psiphon (локальный прокси)"))
            }
            if (psiphonSocksPort > 0) {
                ordered.add(ReserveUpstream(Kind.SOCKS5, "127.0.0.1", psiphonSocksPort, "Psiphon (SOCKS5)"))
            }
            for (p in list) {
                ordered.add(
                    ReserveUpstream(
                        kind = if (p.kind == ReservePool.Kind.SOCKS5) Kind.SOCKS5 else Kind.HTTP,
                        host = p.host,
                        port = p.port,
                        label = "${p.host}:${p.port} (${p.kind.name.lowercase()})"
                    )
                )
            }
            pool = list
            state.setList(ordered)
        }
    }

    fun poolSize(): Int = pool.size

    /** Вызывается, когда Psiphon поднял локальный прокси (HTTP или SOCKS5). */
    fun setPsiphonPort(port: Int, socks: Boolean = false) {
        if (socks) psiphonSocksPort = port else psiphonHttpPort = port
        if (pool.isNotEmpty() || port > 0) applyPool(pool)
    }

    fun active(): ReserveUpstream? =
        if (!autoEnabled && !preferReserve) null else synchronized(lock) { state.active(now()) }

    fun reportSuccess(u: ReserveUpstream) {
        synchronized(lock) { state.reportSuccess(u, now()) }
        VpnLog.i("Резерв", "канал «${u.label}» работает — держим его")
    }

    fun reportFailure(u: ReserveUpstream, why: String) {
        val next = synchronized(lock) { state.reportFailure(u, now()) }
        VpnLog.w("Резерв", "канал «${u.label}» не сработал ($why) → следующий: ${next?.label ?: "нет"}")
    }

    fun reset() {
        synchronized(lock) { state.setList(emptyList()); applyPool(pool) }
        VpnLog.i("Резерв", "перебор сброшен")
    }

    fun statusLine(): String = synchronized(lock) { state.snapshot(now()) }

    private fun now() = System.currentTimeMillis()
}
