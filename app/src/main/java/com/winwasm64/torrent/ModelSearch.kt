package com.winwasm64.torrent

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * v4.62.0: «добавь поиск gguf, mnn, tflite и другие форматы — и не кучу воды, а описание,
 * сами модели, для чего они подходят и чтобы можно было скачать. Только учти, что не только
 * Hugging Face, но и другие сайты, в том числе GitHub».
 *
 * Источники (все — параллельно, все отдают настоящие файлы с настоящим размером):
 *  • Hugging Face — крупнейшее хранилище моделей: `/api/models` + список файлов `/tree/main`;
 *  • GitHub — репозитории под нужный формат: описание репозитория + файлы моделей из Releases;
 *  • Civitai — картинки-модели (Checkpoint/LoRA, .safetensors) с прямой ссылкой на скачивание.
 *
 * По каждой модели показываем без воды: формат, реальный размер, «для чего подходит»,
 * откуда скачано (и насколько популярно), короткое описание и «Скачать» — прямую ссылку.
 */
object ModelSearch {

    /** Форматы моделей и чем их запускают. */
    val FORMATS = linkedMapOf(
        "gguf" to "llama.cpp · Ollama · LM Studio",
        "tflite" to "Android · LiteRT/TFLite",
        "mnn" to "MNN · мобильные",
        "onnx" to "ONNX Runtime",
        "safetensors" to "PyTorch · Transformers",
        "mlmodel" to "Core ML · iPhone/Mac",
        "mlpackage" to "Core ML · iPhone/Mac",
        "ncnn" to "NCNN · мобильные",
        "param" to "NCNN · мобильные",
        "pt" to "PyTorch",
        "pth" to "PyTorch",
        "bin" to "PyTorch / GGML",
        "mlx" to "MLX · Apple Silicon",
        "task" to "Android · LiteRT Task"
    )

    private val MODEL_EXT = setOf(
        "gguf", "tflite", "mnn", "onnx", "safetensors", "mlmodel", "mlpackage",
        "ncnn", "param", "pt", "pth", "bin", "mlx", "task"
    )

    /** Тип задачи → «для чего подходит». */
    private val PURPOSE = mapOf(
        "text-generation" to "чат и генерация текста",
        "text2text-generation" to "пересказ и генерация текста",
        "summarization" to "краткое изложение текста",
        "translation" to "перевод текста",
        "image-text-to-text" to "зрение + чат (понимает картинки)",
        "text-to-image" to "генерация картинок",
        "image-generation" to "генерация картинок",
        "text-to-speech" to "синтез речи",
        "automatic-speech-recognition" to "речь → текст",
        "audio-classification" to "распознавание звуков",
        "audio-source-separation" to "разделение звука на дорожки",
        "image-classification" to "классификация картинок",
        "object-detection" to "поиск объектов на фото",
        "image-segmentation" to "выделение объектов на фото",
        "image-to-image" to "преобразование картинок",
        "feature-extraction" to "эмбеддинги: поиск, RAG",
        "sentence-similarity" to "сравнение смысла текстов",
        "text-embedding" to "эмбеддинги: поиск, RAG",
        "depth-estimation" to "глубина по фото",
        "text-to-video" to "генерация видео",
        "image-to-video" to "оживление фото",
        "question-answering" to "ответы на вопросы по тексту",
        "fill-mask" to "заполнение пропусков в тексте",
        "token-classification" to "разметка текста (NER)",
        "reranking" to "переранжирование поиска",
        "upscale" to "увеличение картинок",
        "speech-recognition" to "речь → текст",
        "text-gen" to "чат и генерация текста"
    )

    data class Found(
        val id: String,
        val title: String,
        val file: String,
        val format: String,
        val sizeBytes: Long,
        val purpose: String,
        val popularity: Int,
        val popularLabel: String,
        val source: String,
        val pageUrl: String,
        val downloadUrl: String,
        val snippet: String
    ) {
        val sizeLabel: String get() = if (sizeBytes > 0) BitTorrentEngine.formatSize(sizeBytes) else "размер неизвестен"
    }

    /**
     * Поиск моделей по всем источникам. Блокирующий — вызывать из фонового потока.
     * [formats] — если задано, оставляем только эти форматы (иначе — любые из [MODEL_EXT]).
     */
    fun search(query: String, formats: List<String> = emptyList(), limit: Int = 24, timeoutMs: Int = 12_000): List<Found> {
        var term = query.trim()
        if (term.isBlank()) return emptyList()
        val wanted = mutableListOf<String>()
        for (f in MODEL_EXT) {
            if (Regex("(^|[^a-z0-9])" + f + "($|[^a-z0-9])", RegexOption.IGNORE_CASE).containsMatchIn(term)) {
                wanted.add(f)
                term = term.replace(Regex(f, RegexOption.IGNORE_CASE), " ").trim()
            }
        }
        wanted.addAll(formats.map { it.lowercase() })
        if (term.isBlank()) term = query.trim()

        val out = java.util.Collections.synchronizedList(mutableListOf<Found>())
        val jobs = listOf<() -> Unit>(
            { out.addAll(huggingFace(term, wanted.distinct(), limit, timeoutMs)) },
            { out.addAll(gitHub(term, wanted.distinct(), limit, timeoutMs)) },
            // Civitai — только если формат не ограничен чем-то узким (там .safetensors картинок-моделей)
            { if (wanted.isEmpty() || "safetensors" in wanted) out.addAll(civitai(term, limit, timeoutMs)) }
        )
        val threads = jobs.mapIndexed { idx, job ->
            Thread({ try { job() } catch (_: Throwable) {} }, "Model-$idx").also { it.isDaemon = true; it.start() }
        }
        threads.forEach { it.join((timeoutMs + 8_000).toLong()) }

        return out.toList()
            .filter { it.downloadUrl.isNotBlank() }
            .distinctBy { it.downloadUrl }
            .sortedWith(
                compareByDescending<Found> { it.popularity }
                    .thenByDescending { it.sizeBytes }
            )
            .let { list ->
                // по одной-две карточки на модель от каждого источника, чтобы не было «воды» из дублей
                val perModel = mutableMapOf<String, Int>()
                list.filter { f ->
                    val key = f.source + "|" + f.title
                    val n = perModel[key] ?: 0
                    perModel[key] = n + 1
                    n < 2
                }
            }
            .take(limit)
    }

    /* ------------------------------- Hugging Face ------------------------------- */

    private fun huggingFace(term: String, formats: List<String>, limit: Int, timeoutMs: Int): List<Found> {
        val models = java.util.Collections.synchronizedMap(linkedMapOf<String, JSONObject>())
        val urls = mutableListOf(
            "https://huggingface.co/api/models?search=${enc(term)}&limit=20&sort=downloads&direction=-1&full=true"
        )
        for (f in formats.take(3)) {
            urls.add(
                "https://huggingface.co/api/models?search=${enc(term)}&filter=${enc(f)}&limit=15&sort=downloads&direction=-1&full=true"
            )
        }
        val threads = urls.map { url ->
            Thread({
                val body = httpGet(url, timeoutMs) ?: return@Thread
                try {
                    val arr = JSONArray(body)
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optString("id").ifBlank { o.optString("modelId") }
                        if (id.isBlank()) continue
                        val prev = models[id]
                        if (prev == null || o.optInt("downloads", 0) > prev.optInt("downloads", 0)) models[id] = o
                    }
                } catch (_: Throwable) {
                }
            }, "HFFind").also { it.isDaemon = true; it.start() }
        }
        threads.forEach { it.join((timeoutMs + 2000).toLong()) }

        val top = models.values.sortedByDescending { it.optInt("downloads", 0) }.take(12)
        if (top.isEmpty()) return emptyList()
        val found = java.util.Collections.synchronizedList(mutableListOf<Found>())
        val queue = java.util.concurrent.LinkedBlockingQueue(top.toList())
        val workers = (1..4).map {
            Thread({
                while (true) {
                    val model = queue.poll() ?: break
                    try {
                        found.addAll(hfFiles(model, formats, timeoutMs))
                    } catch (_: Throwable) {
                    }
                }
            }, "HFFiles").also { it.isDaemon = true; it.start() }
        }
        workers.forEach { it.join((timeoutMs + 6000).toLong()) }

        val sorted = found.toList().sortedByDescending { it.popularity }.take(6)
        // описание читаем только у первых трёх: остальным хватает назначения и размера
        return sorted.mapIndexed { idx, f ->
            if (idx < 3) f.copy(snippet = hfSnippet(f.id, term, timeoutMs)) else f
        }
    }

    private fun hfFiles(model: JSONObject, formats: List<String>, timeoutMs: Int): List<Found> {
        val id = model.optString("id").ifBlank { model.optString("modelId") }
        if (id.isBlank()) return emptyList()
        val body = httpGet("https://huggingface.co/api/models/${pathEnc(id)}/tree/main", timeoutMs) ?: return emptyList()
        val arr = try { JSONArray(body) } catch (_: Throwable) { return emptyList() }
        val tags = mutableListOf<String>()
        model.optJSONArray("tags")?.let { a -> for (i in 0 until a.length()) tags.add(a.optString(i)) }
        val purpose = purposeOf(model, tags)
        val downloads = model.optInt("downloads", 0)
        val out = mutableListOf<Found>()
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            if (f.optString("type") != "file") continue
            val path = f.optString("path")
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext !in MODEL_EXT) continue
            if (formats.isNotEmpty() && ext !in formats) continue
            val size = f.optJSONObject("lfs")?.optLong("size", 0L) ?: f.optLong("size", 0L)
            if (size < 1_000_000L) continue
            val url = "https://huggingface.co/$id/resolve/main/" + encodePath(path)
            out.add(
                Found(
                    id = id,
                    title = id.substringAfter('/'),
                    file = path,
                    format = ext.uppercase(),
                    sizeBytes = size,
                    purpose = purpose,
                    popularity = downloads,
                    popularLabel = downloadsLabel(downloads),
                    source = "Hugging Face",
                    pageUrl = "https://huggingface.co/$id",
                    downloadUrl = "$url?download=true",
                    snippet = ""
                )
            )
        }
        return out.sortedByDescending { it.sizeBytes }.take(3)
    }

    private fun purposeOf(model: JSONObject, tags: List<String>): String {
        val pipeline = model.optString("pipeline_tag")
        val task = if (pipeline.isNotBlank() && PURPOSE.containsKey(pipeline)) {
            PURPOSE[pipeline].orEmpty()
        } else {
            tags.firstNotNullOfOrNull { PURPOSE[it] } ?: ""
        }
        val fmt = tags.firstOrNull { it in MODEL_EXT }
        val runner = fmt?.let { FORMATS[it] } ?: ""
        val mods = tags.filter { it == "vision" || it == "audio" || it == "multimodal" || it == "on-device" || it == "quantized" }
            .map {
                when (it) {
                    "vision" -> "зрение"
                    "audio" -> "звук"
                    "multimodal" -> "мультимодальная"
                    "on-device" -> "для телефона"
                    else -> "квантованная"
                }
            }.distinct().take(2)
        return listOf(task.ifBlank { "модель ИИ" }, mods.joinToString(", "), runner)
            .filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** Первый абзац карточки модели — коротко, без YAML и разметки. */
    private fun hfSnippet(id: String, term: String, timeoutMs: Int): String {
        val body = httpGet("https://huggingface.co/$id/raw/main/README.md", timeoutMs) ?: return ""
        return cleanText(body, 200, term)
    }

    /* ---------------------------------- GitHub ---------------------------------- */

    private fun gitHub(term: String, formats: List<String>, limit: Int, timeoutMs: Int): List<Found> {
        val exts = if (formats.isNotEmpty()) formats.take(3) else listOf("gguf", "tflite", "onnx", "mnn")
        val repos = java.util.Collections.synchronizedMap(linkedMapOf<String, JSONObject>())
        val queries = mutableListOf<String>()
        queries.add("${enc(term)}+model+in:name,description")
        for (e in exts) queries.add("${enc(term)}+${enc(e)}+in:name,description,readme")

        val threads = queries.take(4).map { q ->
            Thread({
                val body = httpGet(
                    "https://api.github.com/search/repositories?q=$q&sort=stars&order=desc&per_page=6",
                    timeoutMs
                ) ?: return@Thread
                try {
                    val items = JSONObject(body).optJSONArray("items") ?: return@Thread
                    for (i in 0 until items.length()) {
                        val o = items.optJSONObject(i) ?: continue
                        val full = o.optString("full_name")
                        if (full.isBlank() || o.optBoolean("archived")) continue
                        val prev = repos[full]
                        if (prev == null || o.optInt("stargazers_count", 0) > prev.optInt("stargazers_count", 0)) repos[full] = o
                    }
                } catch (_: Throwable) {
                }
            }, "GHFind").also { it.isDaemon = true; it.start() }
        }
        threads.forEach { it.join((timeoutMs + 2000).toLong()) }

        val top = repos.values.sortedByDescending { it.optInt("stargazers_count", 0) }.take(8)
        if (top.isEmpty()) return emptyList()
        val found = java.util.Collections.synchronizedList(mutableListOf<Found>())
        val queue = java.util.concurrent.LinkedBlockingQueue(top.toList())
        val workers = (1..3).map {
            Thread({
                while (true) {
                    val repo = queue.poll() ?: break
                    try { found.addAll(ghAssets(repo, formats, timeoutMs)) } catch (_: Throwable) {}
                }
            }, "GHAssets").also { it.isDaemon = true; it.start() }
        }
        workers.forEach { it.join((timeoutMs + 6000).toLong()) }
        return found.toList()
    }

    /** Файлы моделей из Releases репозитория (сам код модели не качаем — только веса). */
    private fun ghAssets(repo: JSONObject, formats: List<String>, timeoutMs: Int): List<Found> {
        val full = repo.optString("full_name")
        if (full.isBlank()) return emptyList()
        val stars = repo.optInt("stargazers_count", 0)
        val descr = repo.optString("description").ifBlank { "репозиторий с моделями" }
        val body = httpGet("https://api.github.com/repos/${pathEnc(full)}/releases?per_page=3", timeoutMs) ?: return emptyList()
        val releases = try { JSONArray(body) } catch (_: Throwable) { return emptyList() }
        val out = mutableListOf<Found>()
        for (i in 0 until releases.length()) {
            val rel = releases.optJSONObject(i) ?: continue
            val assets = rel.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.optJSONObject(j) ?: continue
                val name = a.optString("name")
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in MODEL_EXT) continue
                if (formats.isNotEmpty() && ext !in formats) continue
                val size = a.optLong("size", 0L)
                if (size < 500_000L) continue
                out.add(
                    Found(
                        id = full,
                        title = full.substringAfter('/') + " · " + name.substringBeforeLast('.').take(40),
                        file = name,
                        format = ext.uppercase(),
                        sizeBytes = size,
                        purpose = "${FORMATS[ext] ?: "модель ИИ"} · ${descr.take(90)}",
                        popularity = stars,
                        popularLabel = "⭐ $stars",
                        source = "GitHub",
                        pageUrl = repo.optString("html_url").ifBlank { "https://github.com/$full" },
                        downloadUrl = a.optString("browser_download_url"),
                        snippet = ""
                    )
                )
            }
        }
        return out.sortedByDescending { it.sizeBytes }.take(2)
    }

    /* --------------------------------- Civitai --------------------------------- */

    private fun civitai(term: String, limit: Int, timeoutMs: Int): List<Found> {
        val body = httpGet(
            "https://civitai.com/api/v1/models?query=${enc(term)}&limit=6&types=Checkpoint,LORA",
            timeoutMs
        ) ?: return emptyList()
        val items = try { JSONObject(body).optJSONArray("items") } catch (_: Throwable) { return emptyList() }
        val out = mutableListOf<Found>()
        for (i in 0 until items.length()) {
            val m = items.optJSONObject(i) ?: continue
            val name = m.optString("name")
            val type = m.optString("type")
            val stats = m.optJSONObject("stats")
            val downloads = stats?.optInt("downloadCount", 0) ?: 0
            val versions = m.optJSONArray("modelVersions") ?: continue
            if (versions.length() == 0) continue
            val v = versions.optJSONObject(0) ?: continue
            val baseModel = v.optString("baseModel")
            val base = if (baseModel.isNotBlank()) " · база $baseModel" else ""
            val purpose = (if (type.equals("LORA", true)) "стиль/детали для генерации картинок" else "генерация картинок") + base
            val descr = stripHtml(m.optString("description")).take(160)
            val files = v.optJSONArray("files") ?: continue
            for (j in 0 until files.length()) {
                val f = files.optJSONObject(j) ?: continue
                val fileName = f.optString("name")
                val ext = fileName.substringAfterLast('.', "").lowercase()
                if (ext !in MODEL_EXT) continue
                val url = f.optString("downloadUrl")
                if (url.isBlank()) continue
                out.add(
                    Found(
                        id = "civitai-" + m.optInt("id", 0),
                        title = name,
                        file = fileName,
                        format = ext.uppercase(),
                        sizeBytes = (f.optDouble("sizeKB", 0.0) * 1024).toLong(),
                        purpose = purpose,
                        popularity = downloads,
                        popularLabel = downloadsLabel(downloads),
                        source = "Civitai",
                        pageUrl = "https://civitai.com/models/" + m.optInt("id", 0),
                        downloadUrl = url,
                        snippet = descr
                    )
                )
            }
        }
        return out.sortedByDescending { it.popularity }.take(4)
    }

    /* -------------------------------- служебное -------------------------------- */

    private fun downloadsLabel(n: Int): String = when {
        n >= 1_000_000 -> "⬇ ${n / 1_000_000} млн"
        n >= 1_000 -> "⬇ ${n / 1_000} тыс."
        else -> "⬇ $n"
    }

    private fun stripHtml(html: String): String = html
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Убираем YAML-заголовок карточки и markdown-разметку, оставляем смысл. */
    private fun cleanText(raw: String, max: Int, term: String): String {
        val body = if (raw.startsWith("---")) {
            val end = raw.indexOf("\n---", 3)
            if (end > 0) raw.substring(end + 4) else raw
        } else raw
        val text = body
            .replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), " ")
            .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("[#>*_`|<]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.isBlank()) return ""
        // если первый абзац — «общие слова», берём предложение, где есть сам запрос
        val sentences = text.split(". ").filter { it.length > 25 }
        val best = sentences.firstOrNull { it.contains(term, ignoreCase = true) }
            ?: sentences.firstOrNull()
            ?: text
        return best.take(max).trim()
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun encodePath(path: String): String = path.split('/').joinToString("/") {
        URLEncoder.encode(it, "UTF-8").replace("+", "%20")
    }

    private fun pathEnc(id: String): String = id.split('/').joinToString("/") { enc(it) }

    private fun httpGet(url: String, timeoutMs: Int): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "OmniBrowser/4.62 (Android)")
                setRequestProperty("Accept", "application/json, text/plain, */*")
            }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return null
            }
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Throwable) {
            null
        }
    }
}
