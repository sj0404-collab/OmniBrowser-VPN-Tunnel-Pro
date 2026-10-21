package com.winwasm64.extensions

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class InstalledExtension(
    val id: String,
    val dir: File,
    val manifest: ExtensionManifest,
    val enabled: Boolean,
    val source: String,
    val installedAt: Long
)

/** Каталог проверенных расширений Chrome Web Store (ставятся настоящие пакеты с серверов Google). */
data class CatalogExtension(val id: String, val name: String, val summary: String, val note: String)

/**
 * Хранилище установленных расширений: файлы, реестр, chrome.storage, правила DNR,
 * динамически зарегистрированные контент-скрипты. Все записи на диск — атомарные.
 */
object ExtensionStore {
    const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    val CATALOG: List<CatalogExtension> = listOf(
        CatalogExtension("ddkjiahejlhfcafbddmgiahcphecmpfh", "uBlock Origin Lite", "Блокировщик рекламы и трекеров (MV3)", "Сетевые правила работают полностью"),
        CatalogExtension("eimadpbcbfnmbkopoojfekhnkhdbieeh", "Dark Reader", "Тёмная тема для любых сайтов", "Контент-скрипт + фоновая страница"),
        CatalogExtension("mnjggcdmjocbbbhaepdhchncahnbgone", "SponsorBlock", "Пропуск спонсорских вставок на YouTube", "Работает на youtube.com"),
        CatalogExtension("gebbhagfogifgggkldgodflihgfeippi", "Return YouTube Dislike", "Возвращает счётчик дизлайков на YouTube", "Работает на youtube.com"),
        CatalogExtension("nffaoalbilbmmfgbnbgppjihopabppdk", "Video Speed Controller", "Скорость любого HTML5-видео", "Только контент-скрипт"),
        CatalogExtension("edibdbjcniadpccecjdfdjjppcpchdlm", "I still don't care about cookies", "Убирает баннеры согласия на cookies", "Контент-скрипты + правила"),
        CatalogExtension("bgnkhhnnamicmpeenaelnjfhikgbkllg", "AdGuard AdBlocker", "Блокировщик рекламы AdGuard (MV3)", "Сетевые правила работают полностью")
    )

    private lateinit var appContext: Context
    private lateinit var root: File
    private lateinit var dataRoot: File
    private val lock = Any()
    private var registry = JSONObject()
    @Volatile
    var installed: List<InstalledExtension> = emptyList()
        private set

    private val storageCache = ConcurrentHashMap<String, JSONObject>()
    private val sessionRules = ConcurrentHashMap<String, JSONArray>()
    private val actionState = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    val isInitialized: Boolean get() = this::root.isInitialized

    fun init(context: Context) {
        if (isInitialized) return
        appContext = context.applicationContext
        root = File(appContext.filesDir, "chrome_ext").apply { mkdirs() }
        dataRoot = File(appContext.filesDir, "chrome_ext_data").apply { mkdirs() }
        registry = try {
            JSONObject(File(root, "registry.json").takeIf { it.isFile }?.readText() ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        reload()
    }

    fun reload() {
        val list = ArrayList<InstalledExtension>()
        synchronized(lock) {
            val keys = registry.keys().asSequence().toList()
            for (id in keys) {
                val meta = registry.optJSONObject(id) ?: continue
                val dir = File(root, id)
                if (!File(dir, "manifest.json").isFile) continue
                try {
                    val manifest = ManifestParser.parse(dir)
                    list.add(
                        InstalledExtension(
                            id = id,
                            dir = dir,
                            manifest = manifest,
                            enabled = meta.optBoolean("enabled", true),
                            source = meta.optString("source", "store"),
                            installedAt = meta.optLong("installedAt", 0L)
                        )
                    )
                } catch (_: Exception) {
                }
            }
        }
        installed = list.sortedBy { it.manifest.name.lowercase() }
    }

    fun get(id: String): InstalledExtension? = installed.firstOrNull { it.id == id }
    fun enabledExtensions(): List<InstalledExtension> = installed.filter { it.enabled }
    fun isEnabled(id: String): Boolean = installed.any { it.id == id && it.enabled }

    private fun saveRegistry() {
        atomicWrite(File(root, "registry.json"), registry.toString())
    }

    fun setEnabled(id: String, enabled: Boolean) {
        synchronized(lock) {
            registry.optJSONObject(id)?.put("enabled", enabled)
            saveRegistry()
        }
        reload()
    }

    fun uninstall(id: String) {
        synchronized(lock) {
            registry.remove(id)
            saveRegistry()
        }
        File(root, id).deleteRecursively()
        File(dataRoot, id).deleteRecursively()
        storageCache.keys.filter { it.startsWith("$id/") }.forEach { storageCache.remove(it) }
        sessionRules.remove(id)
        actionState.remove(id)
        reload()
    }

    /** Ожидающее событие runtime.onInstalled ("install"/"update") — выдаётся один раз. */
    fun takePendingInstallEvent(id: String): Pair<String, String?>? = synchronized(lock) {
        val meta = registry.optJSONObject(id) ?: return null
        val ev = meta.optString("pendingEvent", "")
        if (ev.isBlank()) return null
        val prev = meta.optString("previousVersion", "").ifBlank { null }
        meta.remove("pendingEvent")
        saveRegistry()
        ev to prev
    }

    // ---------------- Установка ----------------

    fun downloadCrx(id: String): ByteArray {
        var current = ChromeWebStore.crxDownloadUrl(id)
        for (attempt in 0 until 8) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 90_000
                setRequestProperty("User-Agent", DESKTOP_UA)
                setRequestProperty("Accept", "*/*")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("Пустое перенаправление от сервера Google")
                    current = URL(URL(current), loc).toString()
                    continue
                }
                if (code == 204) {
                    throw IOException("Google не выдал пакет (HTTP 204): расширение не найдено, снято с публикации или недоступно")
                }
                if (code != 200) throw IOException("HTTP $code при загрузке пакета расширения")
                val limit = 120L * 1024 * 1024
                conn.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > limit) throw IOException("Пакет больше 120 МБ")
                        out.write(buf, 0, n)
                    }
                    return out.toByteArray()
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Слишком много перенаправлений при загрузке пакета")
    }

    /** Установка из байтов CRX/ZIP. knownId — ID из магазина (если известен). */
    fun installBytes(bytes: ByteArray, knownId: String?, source: String): InstalledExtension {
        val tmp = File(root, ".tmp_${System.nanoTime()}")
        try {
            CrxFormat.unpack(bytes, tmp)
            val base = findManifestDir(tmp) ?: throw IOException("В пакете нет manifest.json")
            val manifest = ManifestParser.parse(base)
            val id = knownId ?: idFromManifestKey(base) ?: CrxFormat.extensionIdFromBytes(bytes)
            val dest = File(root, id)
            val previousVersion = synchronized(lock) { registry.optJSONObject(id)?.optString("version", "") }
            dest.deleteRecursively()
            if (!base.renameTo(dest)) {
                base.copyRecursively(dest, overwrite = true)
            }
            synchronized(lock) {
                val meta = JSONObject()
                    .put("enabled", true)
                    .put("source", source)
                    .put("installedAt", System.currentTimeMillis())
                    .put("version", manifest.version)
                    .put("pendingEvent", if (previousVersion.isNullOrBlank()) "install" else "update")
                if (!previousVersion.isNullOrBlank()) meta.put("previousVersion", previousVersion)
                registry.put(id, meta)
                saveRegistry()
            }
            reload()
            return get(id) ?: throw IOException("Не удалось прочитать установленное расширение")
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun findManifestDir(dir: File): File? {
        if (File(dir, "manifest.json").isFile) return dir
        val children = dir.listFiles()?.filter { it.isDirectory }.orEmpty()
        return children.firstOrNull { File(it, "manifest.json").isFile }
    }

    private fun idFromManifestKey(dir: File): String? = try {
        val key = JSONObject(ManifestParser.readJsonText(File(dir, "manifest.json"))).optString("key", "")
        if (key.isBlank()) null else CrxFormat.extensionIdFromPublicKey(Base64.decode(key, Base64.DEFAULT))
    } catch (_: Exception) {
        null
    }

    // ---------------- Файлы расширения ----------------

    /** Безопасное разрешение пути внутри каталога расширения (без выхода через ..). */
    fun resolveFile(ext: InstalledExtension, rawPath: String): File? {
        val clean = rawPath.substringBefore('?').substringBefore('#').trimStart('/')
        if (clean.isEmpty()) return null
        val decoded = try {
            java.net.URLDecoder.decode(clean.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            clean
        }
        val base = ext.dir.canonicalFile
        val f = File(base, decoded).canonicalFile
        if (!f.path.startsWith(base.path + File.separator)) return null
        return if (f.isFile) f else null
    }

    fun readText(ext: InstalledExtension, path: String): String? = resolveFile(ext, path)?.let {
        try {
            var t = it.readText(Charsets.UTF_8)
            if (t.startsWith("\uFEFF")) t = t.substring(1)
            t
        } catch (_: Exception) {
            null
        }
    }

    // ---------------- chrome.storage ----------------

    private fun storageFile(id: String, area: String) = File(File(dataRoot, id).apply { mkdirs() }, "storage_$area.json")

    fun storageArea(id: String, area: String): JSONObject {
        val key = "$id/$area"
        return storageCache.getOrPut(key) {
            if (area == "session" || area == "managed") {
                JSONObject()
            } else {
                try {
                    JSONObject(storageFile(id, area).takeIf { it.isFile }?.readText() ?: "{}")
                } catch (_: Exception) {
                    JSONObject()
                }
            }
        }
    }

    fun persistStorage(id: String, area: String) {
        if (area == "session" || area == "managed") return
        val obj = storageArea(id, area)
        val text = synchronized(obj) { obj.toString() }
        atomicWrite(storageFile(id, area), text)
    }

    // ---------------- DNR: динамические/сессионные правила и наборы ----------------

    private fun dataFile(id: String, name: String) = File(File(dataRoot, id).apply { mkdirs() }, name)

    fun dynamicRules(id: String): JSONArray = try {
        JSONArray(dataFile(id, "dnr_dynamic.json").takeIf { it.isFile }?.readText() ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }

    fun saveDynamicRules(id: String, rules: JSONArray) = atomicWrite(dataFile(id, "dnr_dynamic.json"), rules.toString())

    fun sessionRules(id: String): JSONArray = sessionRules.getOrPut(id) { JSONArray() }
    fun setSessionRules(id: String, rules: JSONArray) {
        sessionRules[id] = rules
    }

    /** null — используются наборы, включённые в манифесте по умолчанию. */
    fun enabledRulesets(ext: InstalledExtension): Set<String> {
        val f = dataFile(ext.id, "dnr_rulesets.json")
        if (f.isFile) {
            try {
                return ManifestParser.stringList(JSONArray(f.readText())).toSet()
            } catch (_: Exception) {
            }
        }
        return ext.manifest.dnrRulesets.filter { it.enabledByDefault }.map { it.id }.toSet()
    }

    fun saveEnabledRulesets(id: String, ids: Set<String>) = atomicWrite(dataFile(id, "dnr_rulesets.json"), JSONArray(ids.toList()).toString())

    fun disabledStaticRules(id: String): JSONObject = try {
        JSONObject(dataFile(id, "dnr_disabled.json").takeIf { it.isFile }?.readText() ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    fun saveDisabledStaticRules(id: String, obj: JSONObject) = atomicWrite(dataFile(id, "dnr_disabled.json"), obj.toString())

    // ---------------- chrome.scripting.registerContentScripts ----------------

    fun registeredScripts(id: String): JSONArray = try {
        JSONArray(dataFile(id, "registered_scripts.json").takeIf { it.isFile }?.readText() ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }

    fun saveRegisteredScripts(id: String, arr: JSONArray) = atomicWrite(dataFile(id, "registered_scripts.json"), arr.toString())

    // ---------------- chrome.action (значок, текст бейджа, popup) ----------------

    fun setActionValue(id: String, key: String, tabId: Int?, value: String) {
        val map = actionState.getOrPut(id) { ConcurrentHashMap() }
        map[if (tabId != null) "$key@$tabId" else key] = value
    }

    fun getActionValue(id: String, key: String, tabId: Int?): String? {
        val map = actionState[id] ?: return null
        return (if (tabId != null) map["$key@$tabId"] else null) ?: map[key]
    }

    fun atomicWrite(file: File, text: String) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }
}
