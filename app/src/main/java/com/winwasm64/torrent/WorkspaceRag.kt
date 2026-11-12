package com.winwasm64.torrent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v4.74.0: RAG по рабочей папке агента — по-настоящему, без интернета и без демо.
 *
 * Индекс строится по файлам workspace (тексты, код, разметка, заметки): файл режется на куски
 * ~700 символов, для каждого считается мешок слов, из всего корпуса — IDF. Поиск — BM25.
 * Индекс лежит на диске (filesDir/rag/index.json) и обновляется по кнопке или сам, когда файлы
 * изменились (сравниваем размеры и время правки).
 *
 * Инструменты агента: rag_search (поиск по своим файлам) и rag_reindex (перестроить индекс).
 * Кроме того, лучшие куски автоматически подмешиваются в контекст вопроса в чате.
 */
object WorkspaceRag {

    private const val CHUNK = 700
    private const val OVERLAP = 120
    private const val MAX_FILES = 400
    private const val TEXT_EXT = "txt md markdown kt java js ts json xml html htm css csv yml yaml toml ini log py sh gradle kts sql rst tex"

    data class Chunk(val path: String, val text: String, val tf: Map<String, Int>, val len: Int)

    internal data class Index(
        val chunks: List<Chunk>,
        val df: Map<String, Int>,
        val avgLen: Double,
        val stamp: String
    )

    @Volatile private var index: Index? = null

    private fun indexFile(ctx: Context): File = File(ctx.filesDir, "rag/index.json").also { it.parentFile?.mkdirs() }

    private fun tokens(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in s.lowercase()) {
            if (ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.') sb.append(ch)
            else {
                if (sb.length >= 2) out.add(sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.length >= 2) out.add(sb.toString())
        return out
    }

    /** Простой стемминг для русских/английских окончаний — чтобы «файлы» и «файл» совпадали. */
    private fun stem(w: String): String {
        if (w.length <= 4) return w
        for (suf in listOf("ами", "ями", "ов", "ев", "ый", "ий", "ая", "ое", "ые", "ing", "tion", "s")) {
            if (w.endsWith(suf) && w.length - suf.length >= 3) return w.dropLast(suf.length)
        }
        return w
    }

    private fun fingerprint(ctx: Context): String {
        val root = AgentWorkspace.root(ctx)
        val sb = StringBuilder()
        root.walkTopDown().take(MAX_FILES).forEach { f ->
            if (f.isFile && f.length() < 2_000_000) sb.append(f.path).append(':').append(f.length()).append(':').append(f.lastModified()).append(';')
        }
        return sb.toString().hashCode().toString()
    }

    @Synchronized
    internal fun ensureIndex(ctx: Context): Index? {
        val fp = fingerprint(ctx)
        index?.let { if (it.stamp == fp) return it }
        // пробуем прочитать с диска
        val cached = loadFromDisk(ctx)
        if (cached != null && cached.stamp == fp) {
            index = cached
            return cached
        }
        return rebuild(ctx)
    }

    @Synchronized
    internal fun rebuild(ctx: Context): Index? {
        val root = AgentWorkspace.root(ctx)
        val chunks = ArrayList<Chunk>()
        var files = 0
        root.walkTopDown().forEach { f ->
            if (files >= MAX_FILES || chunks.size > 4000) return@forEach
            if (!f.isFile || f.length() == 0L || f.length() > 2_000_000) return@forEach
            val ext = f.extension.lowercase()
            if (ext.isNotBlank() && ext !in TEXT_EXT.split(' ')) {
                // нетекстовые пропускаем, но имя файла запоминаем отдельным «куском»
                chunks.add(chunkOf(ctx, f, "файл ${f.name} (${f.length()} Б)"))
                files++
                return@forEach
            }
            val text = try {
                f.readText()
            } catch (_: Throwable) {
                ""
            }
            if (text.isBlank()) return@forEach
            files++
            var pos = 0
            while (pos < text.length) {
                val end = minOf(pos + CHUNK, text.length)
                chunks.add(chunkOf(ctx, f, text.substring(pos, end)))
                if (end == text.length) break
                pos += CHUNK - OVERLAP
            }
        }
        if (chunks.isEmpty()) return null
        val df = HashMap<String, Int>()
        var totalLen = 0
        for (c in chunks) {
            totalLen += c.len
            for (w in c.tf.keys) df[w] = (df[w] ?: 0) + 1
        }
        val idx = Index(chunks, df, totalLen.toDouble() / chunks.size, fingerprint(ctx))
        index = idx
        saveToDisk(ctx, idx)
        return idx
    }

    private fun chunkOf(ctx: Context, f: File, text: String): Chunk {
        val root = AgentWorkspace.root(ctx).toPath()
        val rel = try {
            root.relativize(f.toPath()).toString().replace('\\', '/')
        } catch (_: Throwable) {
            f.name
        }
        val tf = HashMap<String, Int>()
        val toks = tokens(text)
        for (t in toks) {
            val s = stem(t)
            tf[s] = (tf[s] ?: 0) + 1
        }
        return Chunk(rel, text, tf, toks.size)
    }

    /** BM25-поиск: возвращает «путь · фрагмент» для самых релевантных кусков. */
    fun search(ctx: Context, query: String, k: Int = 4): List<Pair<String, String>> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val idx = ensureIndex(ctx) ?: return emptyList()
        val qToks = tokens(q).map { stem(it) }.distinct()
        if (qToks.isEmpty()) return emptyList()
        val n = idx.chunks.size
        val k1 = 1.4
        val b = 0.75
        val scored = ArrayList<Triple<Double, String, String>>()
        for (c in idx.chunks) {
            var score = 0.0
            for (w in qToks) {
                val f = c.tf[w] ?: continue
                val df = idx.df[w] ?: 0
                if (df == 0) continue
                val idf = Math.log(1.0 + (n - df + 0.5) / (df + 0.5))
                score += idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * c.len / idx.avgLen))
            }
            if (score > 0) scored.add(Triple(score, c.path, c.text))
        }
        return scored.sortedByDescending { it.first }.take(k).map { it.second to it.third }
    }

    /** Готовый блок для системного промпта: что нашлось в собственных файлах агента. */
    fun contextFor(ctx: Context, query: String, k: Int = 3, perChunk: Int = 600): String {
        val found = search(ctx, query, k)
        if (found.isEmpty()) return ""
        return buildString {
            append("\n\nФрагменты из рабочей папки агента (RAG, ищи опору здесь):\n")
            for ((path, text) in found) {
                append("• ").append(path).append(": ").append(text.replace(Regex("\\s+"), " ").take(perChunk)).append('\n')
            }
        }
    }

    fun stats(ctx: Context): String {
        val idx = ensureIndex(ctx) ?: return "индекса нет — в workspace пока нет файлов"
        return "файлов/кусков: ${idx.chunks.size} · уникальных слов: ${idx.df.size} · обновлён ${java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
    }

    /* ------------------------------ диск ------------------------------ */

    private fun saveToDisk(ctx: Context, idx: Index) {
        try {
            val arr = JSONArray()
            for (c in idx.chunks) {
                val tf = JSONObject()
                for ((w, v) in c.tf) tf.put(w, v)
                arr.put(JSONObject().put("p", c.path).put("t", c.text).put("l", c.len).put("tf", tf))
            }
            val dfJ = JSONObject()
            for ((w, v) in idx.df) dfJ.put(w, v)
            val root = JSONObject()
                .put("stamp", idx.stamp)
                .put("avg", idx.avgLen)
                .put("df", dfJ)
                .put("chunks", arr)
            indexFile(ctx).writeText(root.toString())
        } catch (_: Throwable) {
        }
    }

    private fun loadFromDisk(ctx: Context): Index? = try {
        val f = indexFile(ctx)
        if (!f.isFile || f.length() < 10) null else {
            val o = JSONObject(f.readText())
            val arr = o.optJSONArray("chunks") ?: JSONArray()
            val chunks = ArrayList<Chunk>()
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val tfJ = c.optJSONObject("tf") ?: JSONObject()
                val tf = HashMap<String, Int>()
                val keys = tfJ.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    tf[k] = tfJ.optInt(k)
                }
                chunks.add(Chunk(c.optString("p"), c.optString("t"), tf, c.optInt("l")))
            }
            val dfJ = o.optJSONObject("df") ?: JSONObject()
            val df = HashMap<String, Int>()
            val keys = dfJ.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                df[k] = dfJ.optInt(k)
            }
            if (chunks.isEmpty()) null
            else Index(chunks, df, o.optDouble("avg", 100.0), o.optString("stamp"))
        }
    } catch (_: Throwable) {
        null
    }
}
