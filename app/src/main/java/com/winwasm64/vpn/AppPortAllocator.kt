package com.winwasm64.vpn

/**
 * v4.32.0: устойчивый порт локального прокси для приложения.
 *
 * Что было: порт выдавался счётчиком (8901, 8902, …) заново при каждом старте процесса.
 * Telegram в настройках оставался со старым портом, слушателя на нём уже не было —
 * со стороны это выглядело как «прокси то включался, то сам себя менял».
 *
 * Что стало: порт выводится из имени пакета, то есть один и тот же пакет всегда получает
 * один и тот же порт (пока тот свободен), и настройка Telegram продолжает работать.
 */
object AppPortAllocator {

    const val BASE = 8901
    const val SPAN = 79 // 8901..8979

    /** Порт, «принадлежащий» пакету. Одинаковый при каждом запуске. */
    fun preferred(packageName: String): Int {
        val h = if (packageName.isEmpty()) 0 else Math.abs(packageName.hashCode())
        return BASE + (h % SPAN)
    }

    /**
     * Итоговый порт: предпочтительный для пакета, иначе ближайший свободный по кругу.
     * [taken] — порты, уже занятые другими приложениями внутри процесса.
     * [free] — проверка «порт можно занять» (в тестах не нужна).
     */
    fun pick(packageName: String, taken: Set<Int>, free: (Int) -> Boolean = { true }): Int {
        val preferred = preferred(packageName)
        fun ok(p: Int) = p !in taken && free(p)
        if (ok(preferred)) return preferred
        for (step in 1 until SPAN) {
            val p = BASE + ((preferred - BASE + step) % SPAN)
            if (ok(p)) return p
        }
        return preferred
    }
}
