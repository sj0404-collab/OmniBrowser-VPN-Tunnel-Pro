package com.winwasm64.torrent

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * v4.63.0: «написал Cyrillic ocr — нашёл только 6 моделей, а их гораздо больше».
 *
 * Причина была не в источниках, а в моём отборе файлов:
 *  • брал всего 6 моделей на источник (даже когда их нашлось 18–20);
 *  • не заходил в подпапки репозитория, а веса часто лежат там;
 *  • не знал форматов PaddleOCR (.pdmodel/.pdiparams) — а кириллические OCR-модели
 *    PaddlePaddle хранятся именно так, поэтому они молча отбрасывались.
 *
 * Что теперь:
 *  • по запросу ищется несколько формулировок (вся фраза, каждое слово, синонимы OCR/речи/картинок)
 *    — порядок слов не важен, «Cyrillic ocr» и «ocr cyrillic» дают одно и то же;
 *  • файлы ищутся в корне и в подпапках; учитываются десятки форматов весов;
 *  • источники: Hugging Face, GitHub (Releases И файлы, лежащие в репозитории), Civitai;
 *  • карточка по делу: формат · настоящий размер · «для чего подходит» · чем запускают · описание.
 */
object ModelSearch {

    /** Форматы весов и чем их запускают. */
    val FORMATS = linkedMapOf(
        "gguf" to "llama.cpp · Ollama · LM Studio",
        "ggml" to "llama.cpp (старый)",
        "tflite" to "Android · LiteRT/TFLite",
        "task" to "Android · LiteRT Task",
        "litert" to "Android · LiteRT",
        "mnn" to "MNN · мобильные",
        "onnx" to "ONNX Runtime",
        "safetensors" to "PyTorch · Transformers",
        "mlmodel" to "Core ML · iPhone/Mac",
        "mlpackage" to "Core ML · iPhone/Mac",
        "ncnn" to "NCNN · мобильные",
        "param" to "NCNN · мобильные",
        "pt" to "PyTorch",
        "pth" to "PyTorch",
        "ckpt" to "PyTorch / Lightning",
        "bin" to "PyTorch / GGML",
        "mlx" to "MLX · Apple Silicon",
        // v4.63.0: PaddleOCR / Tesseract — на них держится распознавание кириллицы
        "pdmodel" to "PaddleOCR / Paddle Inference",
        "pdiparams" to "PaddleOCR / Paddle Inference",
        "pdparams" to "PaddlePaddle (обучение)",
        "traineddata" to "Tesseract OCR"
    )

    private val MODEL_EXT = FORMATS.keys

    /** Тип задачи → «для чего подходит». */
    private val PURPOSE = mapOf(
        "text-generation" to "чат и генерация текста",
        "text2text-generation" to "пересказ и генерация текста",
        "summarization" to "краткое изложение текста",
        "translation" to "перевод текста",
        "image-text-to-text" to "зрение + чат (понимает картинки)",
        "image-to-text" to "картинка → текст (OCR, подписи)",
        "ocr" to "распознавание текста (OCR)",
        "document-question-answering" to "вопросы по документам",
        "image-feature-extraction" to "признаки картинок (поиск, детекция)",
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
        "text-gen" to "чат и генерация текста",
        "vlm" to "зрение + текст"
    )

    /**
     * Синонимы по частым темам: чтобы «Cyrillic ocr» находил и trocr, и PaddleOCR, и tesseract,
     * а не только модели, в названии которых ровно эти два слова.
     */
    private val SYNONYMS = mapOf(
        "ocr" to listOf("ocr", "text-recognition", "textrecognition", "image-to-text", "tesseract", "paddleocr", "trocr", "easyocr", "surya", "doctr", "handwritten"),
        "кириллица" to listOf("cyrillic", "russian", "ru", "кириллица"),
        "cyrillic" to listOf("cyrillic", "russian", "ru", "кириллица"),
        "русский" to listOf("russian", "ru", "cyrillic"),
        "russian" to listOf("russian", "cyrillic", "ru"),
        "handwritten" to listOf("handwritten", "рукописный", "htr"),
        "речь" to listOf("speech", "asr", "whisper", "аудио"),
        "speech" to listOf("speech", "asr", "whisper"),
        "перевод" to listOf("translation", "nmt", "translate"),
        "translation" to listOf("translation", "nmt", "translate"),
        "chat" to listOf("chat", "instruct", "text-generation"),
        "чат" to listOf("chat", "instruct", "text-generation"),
        "картинки" to listOf("image", "diffusion", "text-to-image", "sdxl"),
        "image" to listOf("image", "vision", "diffusion"),
        "детекция" to listOf("detection", "yolo", "object-detection"),
        "detection" to listOf("detection", "yolo", "object-detection"),
        "эмбеддинги" to listOf("embedding", "embeddings", "feature-extraction", "bge", "e5"),
        "embedding" to listOf("embedding", "embeddings", "feature-extraction", "bge", "e5")
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
     * [formats] — если задано, оставляем только эти форматы.
     */
    fun search(query: String, formats: List<String> = emptyList(), limit: Int = 32, timeoutMs: Int = 15_000): List<Found> {
        var term = query.trim()
        if (term.isBlank() && formats.isEmpty()) return emptyList()
        val wanted = mutableListOf<String>()
        for (f in MODEL_EXT) {
            if (Regex("(^|[^a-z0-9])" + Regex.escape(f) + "($|[^a-z0-9])", RegexOption.IGNORE_CASE).containsMatchIn(term)) {
                wanted.add(f)
                term = term.replace(Regex(Regex.escape(f), RegexOption.IGNORE_CASE), " ").trim()
            }
        }
        wanted.addAll(formats.map { it.lowercase() })
        if (term.isBlank()) term = query.trim()
        val tokens = tokensOf(term)

        val out = java.util.Collections.synchronizedList(mutableListOf<Found>())
        val jobs = listOf<() -> Unit>(
            { out.addAll(huggingFace(term, tokens, wanted.distinct(), limit, timeoutMs)) },
            { out.addAll(gitHub(term, tokens, wanted.distinct(), limit, timeoutMs)) },
            { if (wanted.isEmpty() || "safetensors" in wanted) out.addAll(civitai(term, timeoutMs)) }
        )
        val threads = jobs.mapIndexed { idx, job ->
            Thread({ try { job() } catch (_: Throwable) {} }, "Model-$idx").also { it.isDaemon = true; it.start() }
        }
        threads.forEach { it.join((timeoutMs + 10_000).toLong()) }

        // v4.63.0: чем больше слов запроса совпало с названием/тегами модели, тем выше карточка
        fun tokenHits(f: Found): Int {
            val hay = (f.id + " " + f.title + " " + f.file + " " + f.purpose + " " + f.snippet).lowercase()
            return tokens.count { hay.contains(it) }
        }
        return out.toList()
            .filter { it.downloadUrl.isNotBlank() }
            .distinctBy { it.downloadUrl }
            .sortedWith(
                compareByDescending<Found> { tokenHits(it) }
                    .thenByDescending { it.popularity }
                    .thenByDescending { it.sizeBytes }
            )
            .let { list ->
                // по 3 файла на модель (разные квантования), но не больше трёх карточек одного названия
                val perModel = mutableMapOf<String, Int>()
                list.filter { f ->
                    val key = f.source + "|" + f.title
                    val n = perModel[key] ?: 0
                    perModel[key] = n + 1
                    n < 3
                }
            }
            .take(limit)
    }

    /* ------------------------------- Hugging Face ------------------------------- */

    private fun huggingFace(term: String, tokens: List<String>, formats: List<String>, limit: Int, timeoutMs: Int): List<Found> {
        val models = java.util.Collections.synchronizedMap(linkedMapOf<String, JSONObject>())
        val searches = linkedSetOf<String>()
        searches.add(term)
        // каждое слово отдельно: порядок слов в запросе больше не важен
        tokens.forEach { searches.add(it) }
        // синонимы из словаря — «ocr» ищет и tesseract, и trocr, и paddleocr
        tokens.forEach { t -> SYNONYMS[t]?.take(4)?.forEach { searches.add(it) } }
        val queries = mutableListOf<String>()
        for (s in searches.take(6)) {
            queries.add(
                "https://huggingface.co/api/models?search=${enc(s)}&limit=40&sort=downloads&direction=-1&full=true"
            )
        }
        for (f in formats.take(3)) {
            queries.add(
                "https://huggingface.co/api/models?search=${enc(term)}&filter=${enc(f)}&limit=30&sort=downloads&direction=-1&full=true"
            )
        }
        // теги-задачи тоже дают попадания (например, всё, что помечено как OCR)
        for (t in tokens.take(2)) {
            if (t in SYNONYMS) queries.add(
                "https://huggingface.co/api/models?filter=${enc(t)}&limit=30&sort=downloads&direction=-1&full=true"
            )
        }

        runParallel(queries, 6, (timeoutMs + 3000).toLong()) { url ->
            val body = httpGet(url, timeoutMs) ?: return@runParallel
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
        }

        // v4.63.0: было «top 12», теперь берём заметно больше — моделей действительно много
        val ranked = models.values
            .sortedWith(
                compareByDescending<JSONObject> { hits(it, tokens) }
                    .thenByDescending { it.optInt("downloads", 0) }
            )
            .take(22)
        if (ranked.isEmpty()) return emptyList()

        val found = java.util.Collections.synchronizedList(mutableListOf<Found>())
        val queue = java.util.concurrent.LinkedBlockingQueue(ranked)
        val workers = (1..6).map {
            Thread({
                while (true) {
                    val model = queue.poll() ?: break
                    try { found.addAll(hfFiles(model, formats, tokens, timeoutMs)) } catch (_: Throwable) {}
                }
            }, "HFFiles").also { it.isDaemon = true; it.start() }
        }
        workers.forEach { it.join((timeoutMs + 8000).toLong()) }

        val sorted = found.toList()
            .sortedWith(
                compareByDescending<Found> { f -> tokens.count { (f.id + f.file + f.purpose).lowercase().contains(it) } }
                    .thenByDescending { it.popularity }
            )
            .take(limit)
        // описания — параллельно, чтобы не растягивать поиск
        val needDocs = sorted.take(6)
        val docs = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())
        runParallel(needDocs.map { it.id }.distinct(), 4, (timeoutMs + 5000).toLong()) { id ->
            docs[id] = hfSnippet(id, tokens, timeoutMs)
        }
        return sorted.map { if (docs[it.id].isNullOrBlank()) it else it.copy(snippet = docs[it.id] ?: "") }
    }

    private fun hits(model: JSONObject, tokens: List<String>): Int {
        val hay = ((model.optString("id").ifBlank { model.optString("modelId") }) + " " +
            (model.optJSONArray("tags")?.let { a -> (0 until a.length()).joinToString(" ") { a.optString(it) } } ?: "") + " " +
            model.optString("pipeline_tag")).lowercase()
        return tokens.count { hay.contains(it) }
    }

    private fun hfFiles(model: JSONObject, formats: List<String>, tokens: List<String>, timeoutMs: Int): List<Found> {
        val id = model.optString("id").ifBlank { model.optString("modelId") }
        if (id.isBlank()) return emptyList()
        val tags = mutableListOf<String>()
        model.optJSONArray("tags")?.let { a -> for (i in 0 until a.length()) tags.add(a.optString(i)) }
        val purpose = purposeOf(model, tags)
        val downloads = model.optInt("downloads", 0)

        val entries = ArrayList<JSONObject>()
        val root = fetchTree(id, "", timeoutMs)
        entries.addAll(root)
        // v4.63.0: веса часто лежат в подпапках — заходим внутрь (не глубже двух уровней)
        val dirs = root.filter { it.optString("type") == "directory" }
            .map { it.optString("path") }
            .filter { d -> d.isNotBlank() && !d.startsWith(".git") && !d.contains(".eval_results") }
            .take(5)
        val sub = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
        runParallel(dirs, 5, (timeoutMs + 4000).toLong()) { d -> sub.addAll(fetchTree(id, d, timeoutMs)) }
        entries.addAll(sub)

        val out = mutableListOf<Found>()
        for (f in entries) {
            if (f.optString("type") != "file") continue
            val path = f.optString("path")
            if (path.isBlank()) continue
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext !in MODEL_EXT) continue
            if (formats.isNotEmpty() && ext !in formats) continue
            val size = f.optJSONObject("lfs")?.optLong("size", 0L) ?: f.optLong("size", 0L)
            // 200 КБ: мелкие файлы — это конфиги и «пустышки», а не веса
            if (size < 200_000L) continue
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
        // до трёх файлов на модель: обычно это разные квантования/варианты
        return out.sortedByDescending { it.sizeBytes }.take(3)
    }

    private fun fetchTree(id: String, dir: String, timeoutMs: Int): List<JSONObject> {
        val url = "https://huggingface.co/api/models/${pathEnc(id)}/tree/main" +
            (if (dir.isBlank()) "" else "/" + encodePath(dir))
        val body = httpGet(url, timeoutMs) ?: return emptyList()
        val arr = try { JSONArray(body) } catch (_: Throwable) { return emptyList() }
        val out = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val e = JSONObject(o.toString())
            // в подпапке путь иногда приходит без префикса — приводим к полному
            if (dir.isNotBlank()) {
                val p = e.optString("path")
                if (!p.startsWith("$dir/")) e.put("path", "$dir/$p")
            }
            out.add(e)
        }
        return out
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

    /** Первый смысловой абзац карточки модели — без YAML и разметки. */
    private fun hfSnippet(id: String, tokens: List<String>, timeoutMs: Int): String {
        val body = httpGet("https://huggingface.co/$id/raw/main/README.md", timeoutMs) ?: return ""
        return cleanText(body, 220, tokens)
    }

    /* ---------------------------------- GitHub ---------------------------------- */

    private fun gitHub(term: String, tokens: List<String>, formats: List<String>, limit: Int, timeoutMs: Int): List<Found> {
        val exts = if (formats.isNotEmpty()) formats.take(3) else listOf("onnx", "tflite", "pdiparams", "gguf", "pt", "mnn")
        val repos = java.util.Collections.synchronizedMap(linkedMapOf<String, JSONObject>())
        val queries = linkedSetOf<String>()
        queries.add("${enc(term)}+in:name,description,readme")
        tokens.take(3).forEach { queries.add("${enc(it)}+in:name,description,readme") }
        for (e in exts.take(2)) queries.add("${enc(term)}+${enc(e)}+in:name,description,readme")

        runParallel(queries.take(5).toList(), 5, (timeoutMs + 3000).toLong()) { q ->
            val body = httpGet(
                "https://api.github.com/search/repositories?q=$q&sort=stars&order=desc&per_page=10",
                timeoutMs
            ) ?: return@runParallel
            try {
                val items = JSONObject(body).optJSONArray("items") ?: return@runParallel
                for (i in 0 until items.length()) {
                    val o = items.optJSONObject(i) ?: continue
                    val full = o.optString("full_name")
                    if (full.isBlank() || o.optBoolean("archived")) continue
                    val prev = repos[full]
                    if (prev == null || o.optInt("stargazers_count", 0) > prev.optInt("stargazers_count", 0)) repos[full] = o
                }
            } catch (_: Throwable) {
            }
        }

        val top = repos.values
            .sortedWith(
                compareByDescending<JSONObject> { o ->
                    tokens.count { (o.optString("full_name") + " " + o.optString("description")).lowercase().contains(it) }
                }.thenByDescending { it.optInt("stargazers_count", 0) }
            )
            .take(10)
        if (top.isEmpty()) return emptyList()

        val found = java.util.Collections.synchronizedList(mutableListOf<Found>())
        val queue = java.util.concurrent.LinkedBlockingQueue(top)
        val workers = (1..4).map {
            Thread({
                while (true) {
                    val repo = queue.poll() ?: break
                    try {
                        found.addAll(ghReleaseAssets(repo, formats, timeoutMs))
                        // v4.63.0: многие репозитории держат .onnx/.tflite прямо в дереве, без Releases
                        found.addAll(ghRepoFiles(repo, formats, timeoutMs))
                    } catch (_: Throwable) {
                    }
                }
            }, "GHAssets").also { it.isDaemon = true; it.start() }
        }
        workers.forEach { it.join((timeoutMs + 9000).toLong()) }
        return found.toList()
    }

    /** Веса из Releases репозитория. */
    private fun ghReleaseAssets(repo: JSONObject, formats: List<String>, timeoutMs: Int): List<Found> {
        val full = repo.optString("full_name")
        if (full.isBlank()) return emptyList()
        val stars = repo.optInt("stargazers_count", 0)
        val descr = repo.optString("description").ifBlank { "репозиторий с моделями" }
        val body = httpGet("https://api.github.com/repos/${pathEnc(full)}/releases?per_page=3", timeoutMs)
            ?: return emptyList()
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
                if (size < 200_000L) continue
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

    /** Веса, лежащие прямо в репозитории (raw-ссылка). */
    private fun ghRepoFiles(repo: JSONObject, formats: List<String>, timeoutMs: Int): List<Found> {
        val full = repo.optString("full_name")
        if (full.isBlank()) return emptyList()
        val branches = listOf(repo.optString("default_branch").ifBlank { "HEAD" })
        val stars = repo.optInt("stargazers_count", 0)
        val descr = repo.optString("description").ifBlank { "репозиторий с моделями" }
        val out = mutableListOf<Found>()
        for (br in branches) {
            val body = httpGet(
                "https://api.github.com/repos/${pathEnc(full)}/git/trees/${enc(br)}?recursive=1",
                timeoutMs
            ) ?: continue
            val root = try { JSONObject(body) } catch (_: Throwable) { continue }
            val tree = root.optJSONArray("tree") ?: continue
            for (i in 0 until tree.length()) {
                val t = tree.optJSONObject(i) ?: continue
                val path = t.optString("path")
                val ext = path.substringAfterLast('.', "").lowercase()
                if (ext !in MODEL_EXT) continue
                if (formats.isNotEmpty() && ext !in formats) continue
                val size = t.optLong("size", 0L)
                if (size < 500_000L) continue
                out.add(
                    Found(
                        id = full,
                        title = full.substringAfter('/') + " · " + path.substringAfterLast('/').take(40),
                        file = path,
                        format = ext.uppercase(),
                        sizeBytes = size,
                        purpose = "${FORMATS[ext] ?: "модель ИИ"} · ${descr.take(90)}",
                        popularity = stars,
                        popularLabel = "⭐ $stars",
                        source = "GitHub",
                        pageUrl = repo.optString("html_url").ifBlank { "https://github.com/$full" },
                        downloadUrl = "https://raw.githubusercontent.com/$full/$br/" + encodePath(path),
                        snippet = ""
                    )
                )
            }
        }
        return out.sortedByDescending { it.sizeBytes }.take(3)
    }

    /* --------------------------------- Civitai --------------------------------- */

    private fun civitai(term: String, timeoutMs: Int): List<Found> {
        val body = httpGet(
            "https://civitai.com/api/v1/models?query=${enc(term)}&limit=8&types=Checkpoint,LORA",
            timeoutMs
        ) ?: return emptyList()
        val items = try { JSONObject(body).optJSONArray("items") } catch (_: Throwable) { return emptyList() }
        val out = mutableListOf<Found>()
        for (i in 0 until items.length()) {
            val m = items.optJSONObject(i) ?: continue
            val name = m.optString("name")
            val type = m.optString("type")
            val downloads = m.optJSONObject("stats")?.optInt("downloadCount", 0) ?: 0
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
        return out.sortedByDescending { it.popularity }.take(5)
    }

    /* -------------------------------- служебное -------------------------------- */

    private fun tokensOf(term: String): List<String> = term.lowercase()
        .split(Regex("""[^a-zа-яё0-9]+"""))
        .filter { it.length >= 2 && it !in setOf("the", "and", "for", "model", "models", "модель", "модели") }
        .distinct()

    /** Параллельный обход списка задач с ограничением по времени. */
    private fun <T> runParallel(items: List<T>, workers: Int, joinMs: Long, job: (T) -> Unit) {
        if (items.isEmpty()) return
        val queue = java.util.concurrent.LinkedBlockingQueue(items)
        val threads = (1..workers).map { idx ->
            Thread({
                while (true) {
                    val item = queue.poll() ?: break
                    try { job(item) } catch (_: Throwable) {}
                }
            }, "Par-$idx").also { it.isDaemon = true; it.start() }
        }
        threads.forEach { it.join(joinMs) }
    }

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
    private fun cleanText(raw: String, max: Int, tokens: List<String>): String {
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
        val sentences = text.split(". ").filter { it.length > 25 }
        val best = sentences.firstOrNull { s -> tokens.any { s.contains(it, ignoreCase = true) } }
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
                setRequestProperty("User-Agent", "OmniBrowser/4.63 (Android)")
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
