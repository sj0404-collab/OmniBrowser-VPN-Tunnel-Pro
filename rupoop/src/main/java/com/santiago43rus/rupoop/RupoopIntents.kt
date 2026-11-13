package com.santiago43rus.rupoop

/**
 * v4.89.0: небольшие «намерения» из нашего интерфейса в Rupoop.
 * Например, из галереи фото → «🎬 Видео»: ссылку на поиск мы больше не держим своим экраном,
 * а просим Rupoop искать внутри его собственного интерфейса (там же, где YouTube).
 */
object RupoopIntents {

    @Volatile
    var pendingQuery: String? = null
        private set

    fun search(query: String) {
        val q = query.trim()
        pendingQuery = q.takeIf { q.isNotBlank() }
    }

    fun consumeQuery(): String? {
        val q = pendingQuery
        pendingQuery = null
        return q
    }
}
