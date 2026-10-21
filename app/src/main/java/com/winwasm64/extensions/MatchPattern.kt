package com.winwasm64.extensions

/**
 * Шаблоны адресов Chrome (match patterns) -> регулярные выражения,
 * совместимые и с java.util.regex, и с JavaScript RegExp.
 *
 * Правила Chrome:
 *  - "<all_urls>" — http, https, file, ftp;
 *  - схема "*" — только http и https;
 *  - хост "*" — любой; "*.example.com" — example.com и все поддомены;
 *  - путь сравнивается с путём + query (без #фрагмента), "*" — любые символы.
 */
object MatchPattern {
    private const val SPECIALS = "\\^$.|?+()[]{}/"

    fun toRegexSource(pattern: String): String? {
        val p = pattern.trim()
        if (p.isEmpty()) return null
        if (p == "<all_urls>") return "^(?:https?|file|ftp):\\/\\/.*$"
        val schemeSep = p.indexOf("://")
        if (schemeSep <= 0) return null
        val scheme = p.substring(0, schemeSep).lowercase()
        val rest = p.substring(schemeSep + 3)
        val schemeRe = when (scheme) {
            "*" -> "https?"
            "http", "https", "file", "ftp", "ws", "wss", "urn" -> scheme
            else -> return null
        }
        val slash = rest.indexOf('/')
        if (slash < 0) return null
        val host = rest.substring(0, slash).lowercase()
        val path = rest.substring(slash)
        val hostRe = when {
            scheme == "file" -> ""
            host == "*" -> "[^\\/]*"
            host.startsWith("*.") -> "(?:[^\\/]*\\.)?" + hostWithPort(host.substring(2))
            host.contains('*') -> return null
            host.isEmpty() -> return null
            else -> hostWithPort(host)
        }
        val pathRe = StringBuilder()
        for (c in path) {
            when {
                c == '*' -> pathRe.append(".*")
                SPECIALS.indexOf(c) >= 0 -> pathRe.append('\\').append(c)
                else -> pathRe.append(c)
            }
        }
        return "^$schemeRe:\\/\\/$hostRe$pathRe$"
    }

    private fun hostWithPort(host: String): String {
        val colon = host.lastIndexOf(':')
        return if (colon > 0 && host.substring(colon + 1).all { it.isDigit() || it == '*' }) {
            val port = host.substring(colon + 1)
            escape(host.substring(0, colon)) + if (port == "*") "(?::\\d+)?" else ":" + port
        } else {
            escape(host) + "(?::\\d+)?"
        }
    }

    /** Glob (include_globs / exclude_globs): "*" — любые символы, "?" — один символ. */
    fun globToRegexSource(glob: String): String {
        val sb = StringBuilder("^")
        for (c in glob) {
            when {
                c == '*' -> sb.append(".*")
                c == '?' -> sb.append('.')
                SPECIALS.indexOf(c) >= 0 -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        return sb.append('$').toString()
    }

    fun escape(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            if (SPECIALS.indexOf(c) >= 0 || c == '*') sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    /** Адрес без #фрагмента; для схемы сравнение без учёта регистра. */
    fun normalizeUrl(url: String): String {
        val hash = url.indexOf('#')
        val u = if (hash >= 0) url.substring(0, hash) else url
        val sep = u.indexOf("://")
        if (sep <= 0) return u
        val afterScheme = sep + 3
        val slash = u.indexOf('/', afterScheme)
        return if (slash < 0) {
            u.substring(0, afterScheme).lowercase() + u.substring(afterScheme).lowercase() + "/"
        } else {
            u.substring(0, afterScheme).lowercase() + u.substring(afterScheme, slash).lowercase() + u.substring(slash)
        }
    }

    fun matches(pattern: String, url: String): Boolean {
        val src = toRegexSource(pattern) ?: return false
        return try {
            Regex(src).matches(normalizeUrl(url))
        } catch (_: Exception) {
            false
        }
    }

    fun matchesAny(patterns: List<String>, url: String): Boolean = patterns.any { matches(it, url) }
}
