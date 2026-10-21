package com.winwasm64.extensions

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

data class ContentScriptEntry(
    val id: String,
    val matches: List<String>,
    val excludeMatches: List<String> = emptyList(),
    val includeGlobs: List<String> = emptyList(),
    val excludeGlobs: List<String> = emptyList(),
    val js: List<String> = emptyList(),
    val css: List<String> = emptyList(),
    val runAt: String = "document_idle",
    val allFrames: Boolean = false,
    val matchAboutBlank: Boolean = false,
    val world: String = "ISOLATED"
)

data class DnrRulesetRef(val id: String, val path: String, val enabledByDefault: Boolean)

data class ExtensionManifest(
    val manifestVersion: Int,
    val name: String,
    val shortName: String,
    val version: String,
    val description: String,
    val defaultLocale: String?,
    val iconPath: String?,
    val popupPath: String?,
    val actionTitle: String,
    val optionsPath: String?,
    val backgroundPage: String?,
    val backgroundScripts: List<String>,
    val serviceWorker: String?,
    val serviceWorkerIsModule: Boolean,
    val contentScripts: List<ContentScriptEntry>,
    val permissions: List<String>,
    val hostPermissions: List<String>,
    val webAccessibleResources: List<String>,
    val dnrRulesets: List<DnrRulesetRef>,
    /** Исходный manifest.json (с подставленными переводами) — отдаётся в chrome.runtime.getManifest(). */
    val rawJson: String,
    /** Сообщения выбранной локали (ключи в нижнем регистре) в виде JSON для chrome.i18n. */
    val messagesJson: String,
    val uiLocale: String
) {
    val hasBackground: Boolean get() = backgroundPage != null || backgroundScripts.isNotEmpty() || serviceWorker != null
}

object ManifestParser {
    fun readJsonText(file: File): String {
        if (!file.isFile) throw IOException("Нет файла ${file.name}")
        var text = file.readText(Charsets.UTF_8)
        if (text.startsWith("\uFEFF")) text = text.substring(1)
        return text
    }

    fun parse(dir: File, uiLanguage: String = Locale.getDefault().language, uiCountry: String = Locale.getDefault().country): ExtensionManifest {
        val manifestFile = File(dir, "manifest.json")
        val json = JSONObject(readJsonText(manifestFile))
        val mv = json.optInt("manifest_version", 2)
        if (mv != 2 && mv != 3) throw IOException("Неподдерживаемая версия манифеста: $mv")
        val defaultLocale = json.optString("default_locale", "").ifBlank { null }
        val (messages, chosenLocale) = loadMessages(dir, defaultLocale, uiLanguage, uiCountry)

        fun t(s: String): String = translate(s, messages)

        val action = json.optJSONObject("action") ?: json.optJSONObject("browser_action") ?: json.optJSONObject("page_action")
        val popup = action?.optString("default_popup", "")?.trim()?.trimStart('/')?.ifBlank { null }
        val actionTitle = t(action?.optString("default_title", "") ?: "")

        val options = json.optJSONObject("options_ui")?.optString("page", "")?.ifBlank { null }
            ?: json.optString("options_page", "").ifBlank { null }

        val bg = json.optJSONObject("background")
        val bgPage = bg?.optString("page", "")?.trim()?.trimStart('/')?.ifBlank { null }
        val bgScripts = stringList(bg?.optJSONArray("scripts")).map { it.trimStart('/') }
        val sw = bg?.optString("service_worker", "")?.trim()?.trimStart('/')?.ifBlank { null }
        val swModule = bg?.optString("type", "") == "module"

        val contentScripts = mutableListOf<ContentScriptEntry>()
        json.optJSONArray("content_scripts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val cs = arr.optJSONObject(i) ?: continue
                val matches = stringList(cs.optJSONArray("matches"))
                if (matches.isEmpty()) continue
                contentScripts.add(
                    ContentScriptEntry(
                        id = "manifest_$i",
                        matches = matches,
                        excludeMatches = stringList(cs.optJSONArray("exclude_matches")),
                        includeGlobs = stringList(cs.optJSONArray("include_globs")),
                        excludeGlobs = stringList(cs.optJSONArray("exclude_globs")),
                        js = stringList(cs.optJSONArray("js")).map { it.trimStart('/') },
                        css = stringList(cs.optJSONArray("css")).map { it.trimStart('/') },
                        runAt = cs.optString("run_at", "document_idle").ifBlank { "document_idle" },
                        allFrames = cs.optBoolean("all_frames", false),
                        matchAboutBlank = cs.optBoolean("match_about_blank", false),
                        world = cs.optString("world", "ISOLATED").ifBlank { "ISOLATED" }
                    )
                )
            }
        }

        val permissions = mutableListOf<String>()
        val hostPermissions = mutableListOf<String>()
        for (p in stringList(json.optJSONArray("permissions")) + stringList(json.optJSONArray("optional_permissions"))) {
            if (p.contains("://") || p == "<all_urls>") hostPermissions.add(p) else permissions.add(p)
        }
        hostPermissions.addAll(stringList(json.optJSONArray("host_permissions")))
        hostPermissions.addAll(stringList(json.optJSONArray("optional_host_permissions")))

        val war = mutableListOf<String>()
        json.optJSONArray("web_accessible_resources")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.opt(i)
                when (item) {
                    is String -> war.add(item.trimStart('/'))
                    is JSONObject -> war.addAll(stringList(item.optJSONArray("resources")).map { it.trimStart('/') })
                }
            }
        }

        val rulesets = mutableListOf<DnrRulesetRef>()
        json.optJSONObject("declarative_net_request")?.optJSONArray("rule_resources")?.let { arr ->
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val path = r.optString("path", "").trimStart('/')
                if (path.isBlank()) continue
                rulesets.add(DnrRulesetRef(r.optString("id", "ruleset_$i"), path, r.optBoolean("enabled", false)))
            }
        }

        val name = t(json.optString("name", "")).ifBlank { dir.name }
        val shortName = t(json.optString("short_name", "")).ifBlank { name }
        // Переводим поля, которые видит пользователь, прямо в отдаваемом манифесте.
        json.put("name", name)
        if (json.has("description")) json.put("description", t(json.optString("description", "")))

        return ExtensionManifest(
            manifestVersion = mv,
            name = name,
            shortName = shortName,
            version = json.optString("version", "0"),
            description = t(json.optString("description", "")),
            defaultLocale = defaultLocale,
            iconPath = pickIcon(json, action),
            popupPath = popup,
            actionTitle = actionTitle,
            optionsPath = options?.trimStart('/'),
            backgroundPage = bgPage,
            backgroundScripts = bgScripts,
            serviceWorker = sw,
            serviceWorkerIsModule = swModule,
            contentScripts = contentScripts,
            permissions = permissions,
            hostPermissions = hostPermissions,
            webAccessibleResources = war,
            dnrRulesets = rulesets,
            rawJson = json.toString(),
            messagesJson = messages.toString(),
            uiLocale = chosenLocale ?: uiLanguage
        )
    }

    private fun pickIcon(json: JSONObject, action: JSONObject?): String? {
        val candidates = mutableListOf<Pair<Int, String>>()
        fun collect(obj: JSONObject?) {
            if (obj == null) return
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val size = k.toIntOrNull() ?: continue
                val v = obj.optString(k, "")
                if (v.isNotBlank()) candidates.add(size to v.trimStart('/'))
            }
        }
        collect(json.optJSONObject("icons"))
        action?.opt("default_icon")?.let { di ->
            when (di) {
                is String -> if (di.isNotBlank()) candidates.add(32 to di.trimStart('/'))
                is JSONObject -> collect(di)
            }
        }
        if (candidates.isEmpty()) return null
        // Самая маленькая иконка не меньше 48 px (чётко на экране), иначе — самая большая из имеющихся.
        val atLeast48 = candidates.filter { it.first >= 48 }.minByOrNull { it.first }
        return (atLeast48 ?: candidates.maxByOrNull { it.first })?.second
    }

    /** Подставляет __MSG_name__ из таблицы сообщений. */
    fun translate(s: String, messages: JSONObject): String {
        if (!s.contains("__MSG_")) return s
        return Regex("__MSG_([A-Za-z0-9_@]+)__").replace(s) { m ->
            val key = m.groupValues[1].lowercase()
            messages.optJSONObject(key)?.optString("message", "") ?: ""
        }
    }

    /** Загружает messages.json: сначала язык интерфейса, затем default_locale. Ключи — в нижнем регистре. */
    fun loadMessages(dir: File, defaultLocale: String?, lang: String, country: String): Pair<JSONObject, String?> {
        val locales = File(dir, "_locales")
        val result = JSONObject()
        if (!locales.isDirectory) return result to null
        val order = linkedSetOf<String>()
        if (country.isNotBlank()) order.add("${lang}_$country")
        order.add(lang)
        if (defaultLocale != null) order.add(defaultLocale)
        order.add("en")
        var chosen: String? = null
        // Сначала самый общий (default), поверх — более точный язык.
        // asReversed(): только Kotlin-функция; LinkedHashSet.reversed() из Android 15 упал бы на старых Android.
        for (loc in order.toList().asReversed()) {
            val f = File(locales, "$loc/messages.json")
            if (!f.isFile) continue
            try {
                val obj = JSONObject(readJsonText(f))
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val entry = obj.optJSONObject(k) ?: continue
                    val normalized = JSONObject()
                    normalized.put("message", entry.optString("message", ""))
                    entry.optJSONObject("placeholders")?.let { ph ->
                        val phOut = JSONObject()
                        val pk = ph.keys()
                        while (pk.hasNext()) {
                            val name = pk.next()
                            val content = ph.optJSONObject(name)?.optString("content", "") ?: ""
                            phOut.put(name.lowercase(), JSONObject().put("content", content))
                        }
                        normalized.put("placeholders", phOut)
                    }
                    result.put(k.lowercase(), normalized)
                }
                chosen = loc
            } catch (_: Exception) {
            }
        }
        return result to chosen
    }

    fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.isNotBlank()) out.add(s)
        }
        return out
    }
}
