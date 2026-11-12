package com.winwasm64.torrent

import android.content.Context
import android.net.Uri
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v4.72.0: рабочая папка агента.
 *
 * Пользователь просил «вкладку workspace», где лежит всё, что создал агент: папки, файлы, вложенные
 * папки, и чтобы агент работал с ними напрямую в любой момент, а он сам мог создать папку, положить
 * туда файл и попросить в текущем чате работать именно там.
 *
 * Всё лежит открыто, в обычной папке телефона:
 *   Download/OmniBrowser/agent/workspace/<любые папки и файлы>
 * Если внешняя папка недоступна (нет разрешения) — используется приватная папка приложения,
 * путь всё равно виден в интерфейсе.
 */
object AgentWorkspace {

    private const val PREFS = "omni_agent_ws"
    private const val KEY_CURRENT = "current_dir"

    /** Предел на чтение файла инструментом — чтобы модель не задохнулась. */
    const val READ_LIMIT = 20_000

    fun root(ctx: Context): File {
        val pub = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "${AppDownloads.SUBDIR}/agent/workspace"
        )
        val dir = try {
            if (!pub.exists()) pub.mkdirs()
            if (pub.isDirectory && pub.canWrite()) pub else null
        } catch (_: Throwable) {
            null
        } ?: File(ctx.filesDir, "agent/workspace").also { it.mkdirs() }
        dir.mkdirs()
        return dir
    }

    /** Папка, выбранная «работать здесь» (относительный путь, "" = корень workspace). */
    fun current(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CURRENT, "").orEmpty()

    fun setCurrent(ctx: Context, rel: String) {
        val safe = sanitize(rel)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CURRENT, safe).apply()
    }

    /** Текущая папка целиком (создаётся при необходимости). */
    fun currentDir(ctx: Context): File {
        val dir = File(root(ctx), current(ctx)).also { it.mkdirs() }
        return if (dir.isDirectory) dir else root(ctx)
    }

    fun displayPath(ctx: Context): String = "Download/${AppDownloads.SUBDIR}/agent/workspace" +
        (if (current(ctx).isBlank()) "" else "/" + current(ctx))

    data class Entry(val name: String, val isDir: Boolean, val size: Long, val rel: String)

    /** Содержимое текущей папки: сначала папки, потом файлы, по алфавиту. */
    fun list(ctx: Context): List<Entry> {
        val dir = currentDir(ctx)
        val base = current(ctx)
        val files = dir.listFiles() ?: return emptyList()
        return files.map {
            Entry(
                name = it.name,
                isDir = it.isDirectory,
                size = if (it.isFile) it.length() else 0L,
                rel = if (base.isBlank()) it.name else "$base/${it.name}"
            )
        }.sortedWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    fun file(ctx: Context, rel: String): File? = try {
        val root = root(ctx)
        val f = File(root, sanitize(rel))
        val canonical = f.canonicalFile
        if (canonical.path.startsWith(root.canonicalFile.path)) canonical else null
    } catch (_: Throwable) {
        null
    }

    fun mkdir(ctx: Context, rel: String): File? {
        val f = file(ctx, rel) ?: return null
        return if (f.mkdirs() || f.isDirectory) f else null
    }

    fun write(ctx: Context, rel: String, text: String): File? = try {
        val f = file(ctx, rel) ?: return null
        f.parentFile?.mkdirs()
        f.writeText(text)
        f
    } catch (_: Throwable) {
        null
    }

    fun delete(ctx: Context, rel: String): Boolean = try {
        file(ctx, rel)?.deleteRecursively() ?: false
    } catch (_: Throwable) {
        false
    }

    fun readText(ctx: Context, rel: String, limit: Int = READ_LIMIT): String? = try {
        val f = file(ctx, rel)
        when {
            f == null || !f.isFile -> null
            f.length() > 4_000_000 -> "файл слишком большой (${f.length() / 1024} КБ) — скачайте его отдельно"
            else -> f.readText().take(limit)
        }
    } catch (t: Throwable) {
        "не удалось прочитать: " + (t.message ?: "ошибка")
    }

    /** Импорт файла, выбранного системным выбором, в текущую папку. */
    fun import(ctx: Context, uri: Uri, nameHint: String? = null): File? = try {
        val name = (nameHint?.takeIf { it.isNotBlank() } ?: queryName(ctx, uri) ?: "file_${System.currentTimeMillis()}")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val dst = File(currentDir(ctx), name)
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            dst.outputStream().use { out -> input.copyTo(out) }
        }
        dst
    } catch (_: Throwable) {
        null
    }

    private fun queryName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Throwable) {
        null
    }

    /** ZIP текущей папки (для кнопки «Скачать в ZIP»). */
    fun zip(ctx: Context): File? = try {
        val dir = currentDir(ctx)
        val out = File(ctx.cacheDir, "workspace_${System.currentTimeMillis()}.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            val base = dir.toPath()
            dir.walkTopDown().forEach { f ->
                val name = base.relativize(f.toPath()).toString().replace('\\', '/')
                if (f.isDirectory) {
                    zos.putNextEntry(ZipEntry("$name/"))
                    zos.closeEntry()
                } else {
                    zos.putNextEntry(ZipEntry(name))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        out
    } catch (_: Throwable) {
        null
    }

    /** Дерево для системного промпта и для инструмента ws_list(recursive=true). */
    fun tree(ctx: Context, maxEntries: Int = 200): String {
        val dir = currentDir(ctx)
        val base = dir.toPath()
        val sb = StringBuilder()
        var n = 0
        dir.walkTopDown().forEach { f ->
            if (n >= maxEntries) return@forEach
            val rel = base.relativize(f.toPath()).toString().replace('\\', '/')
            if (rel.isEmpty()) return@forEach
            sb.append(if (f.isDirectory) "$rel/\n" else "$rel (${f.length()} Б)\n")
            n++
        }
        return if (sb.isEmpty()) "папка пуста" else sb.toString()
    }

    /** Имя файла заметок агента по проекту. */
    fun notesFile(ctx: Context): File = File(root(ctx), "NOTES.md").also { it.parentFile?.mkdirs() }

    fun notes(ctx: Context): String = try {
        notesFile(ctx).takeIf { it.isFile }?.readText().orEmpty()
    } catch (_: Throwable) {
        ""
    }

    fun saveNotes(ctx: Context, text: String): Boolean = try {
        notesFile(ctx).writeText(text)
        true
    } catch (_: Throwable) {
        false
    }

    fun appendNote(ctx: Context, text: String): Boolean = try {
        notesFile(ctx).appendText("\n- " + text.trim() + "\n")
        true
    } catch (_: Throwable) {
        false
    }

    private fun sanitize(rel: String): String =
        rel.trim().trimStart('/').replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }
            .joinToString("/")
}

/**
 * v4.72.0: доступ к GitHub по токену из файла-секрета (а не из кода).
 *
 * Токен ищется по порядку:
 *   1. Download/OmniBrowser/secrets/github.token
 *   2. files/secrets/github.token (приватная папка приложения)
 *   3. Download/OmniBrowser/agent/workspace/.secrets/github.token
 * Файл — обычный текст: первая строка токен (`ghp_…` / `github_pat_…`).
 */
object GitHubBridge {

    private const val API = "https://api.github.com"

    fun token(ctx: Context): String {
        val candidates = listOf(
            File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "${AppDownloads.SUBDIR}/secrets"), "github.token"),
            File(ctx.filesDir, "secrets/github.token"),
            File(AgentWorkspace.root(ctx), ".secrets/github.token")
        )
        for (f in candidates) {
            try {
                if (f.isFile) {
                    val t = f.readText().trim().lines().firstOrNull().orEmpty().trim()
                    if (t.length > 20) return t
                }
            } catch (_: Throwable) {
            }
        }
        return ctx.getSharedPreferences("omni_agent_ws", Context.MODE_PRIVATE)
            .getString("github_token", "").orEmpty()
    }

    fun saveToken(ctx: Context, token: String) {
        ctx.getSharedPreferences("omni_agent_ws", Context.MODE_PRIVATE).edit()
            .putString("github_token", token.trim()).apply()
        try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "${AppDownloads.SUBDIR}/secrets"
            )
            dir.mkdirs()
            File(dir, "github.token").writeText(token.trim())
        } catch (_: Throwable) {
            // нет прав на общую папку — останется только в приватном хранилище
        }
    }

    fun configured(ctx: Context): Boolean = token(ctx).isNotBlank()

    private fun call(ctx: Context, method: String, path: String, body: String? = null, raw: Boolean = false): String {
        val t = token(ctx)
        if (t.isBlank()) return "нет токена GitHub: положите его в Download/OmniBrowser/secrets/github.token"
        val (code, text) = com.winwasm64.ui.browser.NetDirect.requestText(
            if (path.startsWith("http")) path else API + path,
            mapOf(
                "Authorization" to "Bearer $t",
                "Accept" to if (raw) "application/vnd.github.raw" else "application/vnd.github+json",
                "X-GitHub-Api-Version" to "2022-11-28",
                "User-Agent" to "OmniBrowser-Agent"
            ),
            method = method,
            body = body,
            timeoutMs = 30_000
        )
        return if (code in 200..299) text else "GitHub ответил $code: " + text.take(400)
    }

    fun readFile(ctx: Context, repo: String, path: String, ref: String = ""): String =
        call(ctx, "GET", "/repos/$repo/contents/$path" + (if (ref.isNotBlank()) "?ref=$ref" else ""), raw = true)

    fun listDir(ctx: Context, repo: String, path: String = ""): String {
        val json = call(ctx, "GET", "/repos/$repo/contents/$path")
        return try {
            val arr = org.json.JSONArray(json)
            buildString {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    append(if (o.optString("type") == "dir") "📁 " else "📄 ")
                    append(o.optString("path"))
                    if (o.optString("type") != "dir") append(" (${o.optLong("size")} Б)")
                    append('\n')
                }
            }.ifBlank { "пусто" }
        } catch (_: Throwable) {
            json
        }
    }

    /** Создать/перезаписать файл в репозитории (коммит в указанную ветку). */
    fun writeFile(ctx: Context, repo: String, path: String, content: String, message: String, branch: String = "main"): String {
        var sha = ""
        val existing = call(ctx, "GET", "/repos/$repo/contents/$path?ref=$branch")
        try {
            sha = JSONObject(existing).optString("sha")
        } catch (_: Throwable) {
        }
        val body = JSONObject()
            .put("message", message.ifBlank { "обновление из OmniBrowser" })
            .put("content", android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP))
            .put("branch", branch)
        if (sha.isNotBlank()) body.put("sha", sha)
        val res = call(ctx, "PUT", "/repos/$repo/contents/$path", body.toString())
        return if (res.contains("\"content\"")) "✓ записано: $repo/$path (ветка $branch)" else res
    }

    fun workflowStatus(ctx: Context, repo: String): String {
        val json = call(ctx, "GET", "/repos/$repo/actions/runs?per_page=5")
        return try {
            val runs = JSONObject(json).optJSONArray("workflow_runs") ?: return "нет данных"
            buildString {
                for (i in 0 until runs.length()) {
                    val r = runs.optJSONObject(i) ?: continue
                    append("#").append(r.optLong("run_number"))
                    append(" ").append(r.optString("name").take(28))
                    append(" · ").append(r.optString("status"))
                    if (r.optString("conclusion").isNotBlank()) append("/").append(r.optString("conclusion"))
                    append(" · ").append(r.optString("head_branch"))
                    append('\n')
                }
            }.ifBlank { "прогонов нет" }
        } catch (_: Throwable) {
            json.take(400)
        }
    }

    fun searchCode(ctx: Context, repo: String, query: String): String {
        val url = "$API/search/code?q=" + java.net.URLEncoder.encode("$query repo:$repo", "UTF-8")
        val json = call(ctx, "GET", url, raw = true)
        return try {
            val items = JSONObject(json).optJSONArray("items") ?: return json.take(400)
            buildString {
                for (i in 0 until minOf(items.length(), 15)) {
                    append(items.optJSONObject(i)?.optString("path").orEmpty()).append('\n')
                }
            }.ifBlank { "ничего не найдено" }
        } catch (_: Throwable) {
            json.take(400)
        }
    }
}
