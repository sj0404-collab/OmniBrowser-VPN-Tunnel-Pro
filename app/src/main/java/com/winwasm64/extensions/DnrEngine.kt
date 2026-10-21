package com.winwasm64.extensions

import org.json.JSONArray
import org.json.JSONObject

/**
 * Настоящий движок chrome.declarativeNetRequest для блокировки запросов в WebView.
 * Поддерживает: block / allow / allowAllRequests / redirect(extensionPath),
 * urlFilter (||, |, ^, *), regexFilter, requestDomains / excludedRequestDomains,
 * initiatorDomains / excludedInitiatorDomains, resourceTypes / excludedResourceTypes,
 * requestMethods / excludedRequestMethods, domainType (firstParty / thirdParty), priority.
 * Не поддерживается WebView: modifyHeaders, upgradeScheme, redirect на внешний URL.
 */
class DnrRule(
    val extId: String,
    val id: Int,
    val priority: Int,
    val action: String,
    val redirectExtensionPath: String?,
    private val urlFilter: String?,
    private val regexFilter: String?,
    private val caseSensitive: Boolean,
    private val requestDomains: Array<String>?,
    private val excludedRequestDomains: Array<String>?,
    private val initiatorDomains: Array<String>?,
    private val excludedInitiatorDomains: Array<String>?,
    private val resourceTypes: Array<String>?,
    private val excludedResourceTypes: Array<String>?,
    private val methods: Array<String>?,
    private val excludedMethods: Array<String>?,
    private val domainType: String?
) {
    private val compiled: Regex? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        try {
            when {
                regexFilter != null -> if (caseSensitive) Regex(regexFilter) else Regex(regexFilter, RegexOption.IGNORE_CASE)
                urlFilter != null -> Regex(DnrEngine.urlFilterToRegex(if (caseSensitive) urlFilter else urlFilter.lowercase()))
                else -> null
            }
        } catch (_: Exception) {
            INVALID
        }
    }

    val actionRank: Int
        get() = when (action) {
            "allow" -> 5
            "allowAllRequests" -> 4
            "block" -> 3
            "upgradeScheme" -> 2
            "redirect" -> 1
            else -> 0
        }

    fun indexDomains(): List<String> {
        requestDomains?.let { return it.toList() }
        val f = urlFilter ?: return emptyList()
        if (!f.startsWith("||")) return emptyList()
        val end = f.indexOfAny(charArrayOf('^', '/', '*', '|', ':', '?'), 2).let { if (it < 0) f.length else it }
        val d = f.substring(2, end).lowercase()
        return if (d.contains('.') && !d.startsWith('.') && !d.endsWith('.')) listOf(d) else emptyList()
    }

    /** Токен для индексации: целое слово из [a-z0-9%], ограниченное в фильтре разделителями. */
    fun indexToken(): String? {
        val f = (urlFilter ?: return null).lowercase()
        var best: String? = null
        var i = 0
        while (i < f.length) {
            if (!DnrEngine.isTokenChar(f[i])) {
                i++
                continue
            }
            val start = i
            while (i < f.length && DnrEngine.isTokenChar(f[i])) i++
            val before = if (start == 0) null else f[start - 1]
            val after = if (i >= f.length) null else f[i]
            val boundedLeft = before != null && before != '*'
            val boundedRight = after != null && after != '*'
            val tok = f.substring(start, i)
            if (boundedLeft && boundedRight && tok.length >= 3 && (best == null || tok.length > best.length)) best = tok
        }
        return best
    }

    fun matches(req: DnrRequest): Boolean {
        val type = req.resourceType
        if (resourceTypes != null) {
            if (type == null) {
                if (resourceTypes.size == 1 && resourceTypes[0] == "main_frame") return false
            } else if (type !in resourceTypes) {
                return false
            }
        } else if (type == "main_frame") {
            return false
        }
        if (excludedResourceTypes != null && type != null && type in excludedResourceTypes) return false
        if (methods != null && req.method !in methods) return false
        if (excludedMethods != null && req.method in excludedMethods) return false
        if (requestDomains != null && !DnrEngine.hostMatchesAny(req.host, requestDomains)) return false
        if (excludedRequestDomains != null && DnrEngine.hostMatchesAny(req.host, excludedRequestDomains)) return false
        val initiator = req.initiatorHost
        if (initiatorDomains != null && (initiator == null || !DnrEngine.hostMatchesAny(initiator, initiatorDomains))) return false
        if (excludedInitiatorDomains != null && initiator != null && DnrEngine.hostMatchesAny(initiator, excludedInitiatorDomains)) return false
        if (domainType != null && initiator != null) {
            val third = DnrEngine.registrableDomain(req.host) != DnrEngine.registrableDomain(initiator)
            if (domainType == "thirdParty" && !third) return false
            if (domainType == "firstParty" && third) return false
        }
        val re = compiled ?: return true
        if (re === INVALID) return false
        return re.containsMatchIn(if (caseSensitive || regexFilter != null) req.url else req.urlLower)
    }

    companion object {
        private val INVALID = Regex("(?!)")
    }
}

data class DnrRequest(
    val url: String,
    val urlLower: String,
    val host: String,
    val method: String,
    val resourceType: String?,
    val initiatorHost: String?
)

class DnrIndex {
    private val byDomain = HashMap<String, MutableList<DnrRule>>()
    private val byToken = HashMap<String, MutableList<DnrRule>>()
    private val generic = ArrayList<DnrRule>()
    var size: Int = 0
        private set

    fun add(rule: DnrRule) {
        size++
        val domains = rule.indexDomains()
        if (domains.isNotEmpty()) {
            for (d in domains) byDomain.getOrPut(d) { ArrayList(2) }.add(rule)
            return
        }
        val tok = rule.indexToken()
        if (tok != null) byToken.getOrPut(tok) { ArrayList(2) }.add(rule) else generic.add(rule)
    }

    /** Лучшее совпадение: наивысший priority, при равенстве allow > allowAllRequests > block > redirect. */
    fun bestMatch(req: DnrRequest): DnrRule? {
        var best: DnrRule? = null
        fun consider(r: DnrRule) {
            val b = best
            if (b != null && (r.priority < b.priority || (r.priority == b.priority && r.actionRank <= b.actionRank))) return
            if (r.matches(req)) best = r
        }
        var h = req.host
        while (h.isNotEmpty()) {
            byDomain[h]?.forEach { consider(it) }
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
        if (byToken.isNotEmpty()) {
            val seen = HashSet<String>()
            val u = req.urlLower
            var i = 0
            while (i < u.length) {
                if (!DnrEngine.isTokenChar(u[i])) {
                    i++
                    continue
                }
                val s = i
                while (i < u.length && DnrEngine.isTokenChar(u[i])) i++
                if (i - s >= 3) {
                    val tok = u.substring(s, i)
                    if (seen.add(tok)) byToken[tok]?.forEach { consider(it) }
                }
            }
        }
        for (r in generic) consider(r)
        return best
    }
}

object DnrEngine {
    fun isTokenChar(c: Char): Boolean = (c in 'a'..'z') || (c in '0'..'9') || c == '%'

    /** urlFilter (синтаксис DNR/ABP) -> регулярное выражение. */
    fun urlFilterToRegex(filter: String): String {
        var f = filter
        val sb = StringBuilder()
        var anchoredEnd = false
        when {
            f.startsWith("||") -> {
                sb.append("^[a-z][a-z0-9+.-]*:\\/\\/(?:[^\\/?#]*\\.)?")
                f = f.substring(2)
            }
            f.startsWith("|") -> {
                sb.append('^')
                f = f.substring(1)
            }
        }
        if (f.endsWith("|") && !f.endsWith("\\|")) {
            anchoredEnd = true
            f = f.substring(0, f.length - 1)
        }
        for (c in f) {
            when (c) {
                '*' -> sb.append(".*")
                '^' -> sb.append("(?:[^a-zA-Z0-9_.%-]|$)")
                '\\', '.', '+', '?', '(', ')', '[', ']', '{', '}', '$', '/', '|' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        if (anchoredEnd) sb.append('$')
        return sb.toString()
    }

    fun hostMatchesAny(host: String, domains: Array<String>): Boolean {
        for (d in domains) {
            if (host == d || (host.length > d.length && host.endsWith(d) && host[host.length - d.length - 1] == '.')) return true
        }
        return false
    }

    /** Упрощённый «регистрируемый домен»: последние 2 метки (3 — для вида co.uk / com.ru). */
    fun registrableDomain(host: String): String {
        val parts = host.split('.')
        if (parts.size <= 2) return host
        val sld = parts[parts.size - 2]
        val tld = parts[parts.size - 1]
        return if (tld.length == 2 && sld.length <= 3 && sld in setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go")) {
            parts.takeLast(3).joinToString(".")
        } else {
            parts.takeLast(2).joinToString(".")
        }
    }

    private fun arr(o: JSONObject, key: String, lower: Boolean = true): Array<String>? {
        val a: JSONArray = o.optJSONArray(key) ?: return null
        if (a.length() == 0) return null
        return Array(a.length()) { i -> a.optString(i, "").let { if (lower) it.lowercase() else it } }
    }

    /** Разбор одного правила; неподдерживаемые действия возвращают null. */
    fun parseRule(extId: String, o: JSONObject): DnrRule? {
        val action = o.optJSONObject("action") ?: return null
        val type = action.optString("type", "")
        if (type !in setOf("block", "allow", "allowAllRequests", "redirect")) return null
        val redirectPath = if (type == "redirect") {
            action.optJSONObject("redirect")?.optString("extensionPath", "")?.ifBlank { null } ?: return null
        } else {
            null
        }
        val c = o.optJSONObject("condition") ?: JSONObject()
        val urlFilter = c.optString("urlFilter", "").ifBlank { null }
        val regexFilter = c.optString("regexFilter", "").ifBlank { null }
        return DnrRule(
            extId = extId,
            id = o.optInt("id", 0),
            priority = o.optInt("priority", 1),
            action = type,
            redirectExtensionPath = redirectPath,
            urlFilter = urlFilter,
            regexFilter = regexFilter,
            caseSensitive = c.optBoolean("isUrlFilterCaseSensitive", false),
            requestDomains = arr(c, "requestDomains") ?: arr(c, "domains"),
            excludedRequestDomains = arr(c, "excludedRequestDomains") ?: arr(c, "excludedDomains"),
            initiatorDomains = arr(c, "initiatorDomains"),
            excludedInitiatorDomains = arr(c, "excludedInitiatorDomains"),
            resourceTypes = arr(c, "resourceTypes"),
            excludedResourceTypes = arr(c, "excludedResourceTypes"),
            methods = arr(c, "requestMethods")?.map { it.uppercase() }?.toTypedArray(),
            excludedMethods = arr(c, "excludedRequestMethods")?.map { it.uppercase() }?.toTypedArray(),
            domainType = c.optString("domainType", "").ifBlank { null }
        )
    }

    /** Разбор файла набора правил (JSON-массив). Возвращает (правила, число пропущенных). */
    fun parseRuleset(extId: String, jsonText: String, disabledIds: Set<Int> = emptySet()): Pair<List<DnrRule>, Int> {
        val arr = JSONArray(jsonText)
        val out = ArrayList<DnrRule>(arr.length())
        var skipped = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) {
                skipped++
                continue
            }
            if (o.optInt("id", 0) in disabledIds) continue
            val r = parseRule(extId, o)
            if (r == null) skipped++ else out.add(r)
        }
        return out to skipped
    }

    /** Тип ресурса по заголовку Accept и расширению файла (WebView не сообщает тип напрямую). */
    fun guessResourceType(isMainFrame: Boolean, accept: String?, path: String?): String? {
        if (isMainFrame) return "main_frame"
        val a = accept?.lowercase() ?: ""
        val p = path?.lowercase() ?: ""
        return when {
            a.startsWith("text/html") -> "sub_frame"
            a.startsWith("text/css") -> "stylesheet"
            a.startsWith("image/") -> "image"
            p.endsWith(".js") || p.endsWith(".mjs") -> "script"
            p.endsWith(".css") -> "stylesheet"
            p.endsWith(".png") || p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".gif") ||
                p.endsWith(".webp") || p.endsWith(".svg") || p.endsWith(".ico") || p.endsWith(".avif") -> "image"
            p.endsWith(".woff") || p.endsWith(".woff2") || p.endsWith(".ttf") || p.endsWith(".otf") -> "font"
            p.endsWith(".mp4") || p.endsWith(".webm") || p.endsWith(".m3u8") || p.endsWith(".mp3") ||
                p.endsWith(".m4a") || p.endsWith(".ts") -> "media"
            a.contains("application/json") || a.contains("text/plain") -> "xmlhttprequest"
            else -> null
        }
    }
}
