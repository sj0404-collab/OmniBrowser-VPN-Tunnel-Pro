package com.winwasm64.torrent

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

/**
 * v4.52.0: «Видео» — один поиск, все категории сразу, переключение мгновенное.
 *
 * Изменения по сравнению с 4.51.0:
 *  • категория больше не уходит в запрос — один ответ приносит видео, плейлисты, каналы и эфиры,
 *    а переключение категорий/сортировки/длительности фильтрует УЖЕ СОБРАННОЕ (без сети);
 *  • на каждом чипе категории видно количество найденного («🎬 Видео 34»);
 *  • список сам дозагружается токеном продолжения, пока в выбранной категории не наберётся достаточно;
 *  • «⬇ Скачать» умеет не только YouTube: у VK/RuTube перехватывается прямой mp4 из плеера
 *    (эти площадки отдают его и без входа), а если поток только HLS — честно отправляем в браузер.
 */
class VideoSearchActivity : Activity() {

    private var query = ""
    private var channelName = ""
    private var channelQuery = ""
    private var source = "YouTube"

    private var category = "Все"
    private var sortMode = 0
    private var duration = "Любая"

    private val allItems = mutableListOf<YouTubeSearch.Item>()
    private var continuation: String? = null
    private var loading = false
    // v4.53.0: если по точному названию нашлось мало — пробуем упоминания/ассоциации
    private var variantIndex = 0
    private var extraTerms: List<String> = emptyList()
    // v4.54.0: глубина — сколько страниц YouTube забирать за один заход и сколько раз
    // прокручивать выдачу RuTube/VK (1…8), задаёт человек
    private var depth = 3
    private var scrapePass = 0

    private lateinit var status: TextView
    private lateinit var captureStatus: TextView
    private lateinit var resultsBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var playerBox: LinearLayout
    private lateinit var player: WebView
    private lateinit var scrapeWeb: WebView
    private lateinit var captureWeb: WebView
    private lateinit var catRow: LinearLayout
    private lateinit var sortRow: LinearLayout

    private val thumbCache = HashMap<String, Bitmap?>()
    private val grabbed = Collections.synchronizedMap(LinkedHashMap<String, String>())
    private var grabSession = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        query = intent.getStringExtra("query").orEmpty()
        channelName = intent.getStringExtra("channel").orEmpty()
        channelQuery = intent.getStringExtra("channelQuery").orEmpty()
        source = normalizeSource(intent.getStringExtra("source").orEmpty())
        depth = SearchSettings.depth(this, "video")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E0F12"))
        }

        root.addView(TextView(this).apply {
            text = if (channelName.isNotBlank()) "📺 $channelName" else "🎬 Видео"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(12, 12, 12, 2)
        })
        root.addView(TextView(this).apply {
            text = "«${query.ifBlank { "—" }}»" + if (channelName.isNotBlank()) " · только этот канал" else ""
            setTextColor(Color.parseColor("#9AA0A6"))
            textSize = 11f
            setPadding(12, 0, 12, 4)
        })

        // площадки
        val srcScroll = HorizontalScrollView(this)
        val srcRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("YouTube", "RuTube", "VK").forEach { name ->
            srcRow.addView(chip(name, source == name) {
                source = name
                reload()
            })
        }
        srcScroll.addView(srcRow)
        root.addView(srcScroll)

        // категории (со счётчиками)
        val catScroll = HorizontalScrollView(this)
        catRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        catScroll.addView(catRow)
        root.addView(catScroll)

        // сортировка и длительность
        val sortScroll = HorizontalScrollView(this)
        sortRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sortScroll.addView(sortRow)
        root.addView(sortScroll)

        status = TextView(this).apply {
            text = "Ищу…"
            setTextColor(Color.parseColor("#FFC107"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(12, 6, 12, 2)
        }
        root.addView(status)

        captureStatus = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#8AB4F8"))
            textSize = 10f
            setPadding(12, 0, 12, 4)
        }
        root.addView(captureStatus)

        // плеер
        playerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(8, 0, 8, 6)
        }
        playerBox.addView(TextView(this).apply {
            text = "▶ Смотрю внутри приложения"
            setTextColor(Color.WHITE)
            textSize = 11f
        })
        player = newWeb()
        player.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 560)
        playerBox.addView(player)
        playerBox.addView(Button(this).apply {
            text = "✖ Закрыть плеер"
            textSize = 11f
            setOnClickListener {
                player.stopLoading()
                player.loadUrl("about:blank")
                playerBox.visibility = View.GONE
            }
        })
        root.addView(playerBox)

        // служебные WebView
        scrapeWeb = newWeb().apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            visibility = View.GONE
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.postDelayed({ scrapePage() }, 1200)
                    view?.postDelayed({ scrapePage() }, 3000)
                }
            }
        }
        root.addView(scrapeWeb)

        captureWeb = newWeb().apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            visibility = View.GONE
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val u = request?.url?.toString().orEmpty()
                    if (u.contains("googlevideo.com") && u.contains("videoplayback")) {
                        val itag = Regex("[?&]itag=(\\d+)").find(u)?.groupValues?.get(1).orEmpty()
                        grabbed["yt" + itag.ifBlank { "x" }] = u
                    } else if (u.contains(".mp4", true) && !u.startsWith("data:")) {
                        grabbed["mp4"] = u
                    } else if (u.contains(".m3u8", true)) {
                        grabbed["hls"] = u
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
        }
        root.addView(captureWeb)

        scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setOnScrollChangeListener { _, _, top, _, _ ->
                val child = getChildAt(0) ?: return@setOnScrollChangeListener
                if (child.height - (top + height) < 320) loadMore()
            }
        }
        resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(resultsBox)
        root.addView(scroll)

        root.addView(Button(this).apply {
            text = "⬇ Показать ещё"
            textSize = 11f
            setOnClickListener { loadMore(manual = true) }
        })

        setContentView(root)
        rebuildChips()

        if (query.startsWith("http")) {
            playInline(query)
            status.text = "Открыл ссылку во встроенном плеере"
        } else {
            reload()
        }
    }

    /* ===================== чипы и локальная фильтрация ===================== */

    private fun normalizeSource(raw: String): String = when {
        raw.contains("VK", true) -> "VK"
        raw.contains("RuTube", true) -> "RuTube"
        else -> "YouTube"
    }

    /** Пункты выбранной категории — из уже собранного. */
    private fun filtered(): List<YouTubeSearch.Item> {
        val base = when (category) {
            "🎬 Видео" -> allItems.filter { it.kind == YouTubeSearch.KIND_VIDEO && it.extra != "live" }
            "🎞 Плейлисты" -> allItems.filter { it.kind == YouTubeSearch.KIND_PLAYLIST }
            "📺 Каналы" -> allItems.filter { it.kind == YouTubeSearch.KIND_CHANNEL }
            "🔴 Трансляции" -> allItems.filter { it.extra == "live" }
            else -> allItems
        }
        val byDuration = when (duration) {
            "До 4 мин" -> base.filter { it.kind != YouTubeSearch.KIND_VIDEO || it.lengthSec in 1..240 }
            "4–20 мин" -> base.filter { it.kind != YouTubeSearch.KIND_VIDEO || it.lengthSec in 241..1200 }
            "20+ мин" -> base.filter { it.kind != YouTubeSearch.KIND_VIDEO || it.lengthSec > 1200 }
            else -> base
        }
        return when (sortMode) {
            1 -> byDuration.sortedBy { it.daysAgo }
            2 -> byDuration.sortedByDescending { it.views }
            3 -> byDuration.sortedByDescending { it.daysAgo }
            else -> byDuration
        }
    }

    private fun rebuildChips() {
        catRow.removeAllViews()
        val categories = listOf("Все", "🎬 Видео", "🎞 Плейлисты", "📺 Каналы", "🔴 Трансляции")
        for (name in categories) {
            val count = when (name) {
                "Все" -> allItems.size
                "🎬 Видео" -> allItems.count { it.kind == YouTubeSearch.KIND_VIDEO && it.extra != "live" }
                "🎞 Плейлисты" -> allItems.count { it.kind == YouTubeSearch.KIND_PLAYLIST }
                "📺 Каналы" -> allItems.count { it.kind == YouTubeSearch.KIND_CHANNEL }
                else -> allItems.count { it.extra == "live" }
            }
            catRow.addView(chip("$name" + if (count > 0) " $count" else "", category == name) {
                category = name
                rebuildChips()
                renderList()   // без сети — данные уже есть
            })
        }

        sortRow.removeAllViews()
        listOf("По релевантности", "Новые", "Популярные", "Старые").forEachIndexed { idx, label ->
            sortRow.addView(chip(label, sortMode == idx) {
                sortMode = idx
                rebuildChips()
                renderList()
            })
        }
        listOf("Любая", "До 4 мин", "4–20 мин", "20+ мин").forEach { label ->
            sortRow.addView(chip(label, duration == label) {
                duration = label
                rebuildChips()
                renderList()
            })
        }
    }

    private fun chip(label: String, selected: Boolean, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 10f
            setPadding(dp(8), 0, dp(8), 0)
            setTextColor(if (selected) Color.WHITE else Color.parseColor("#C7CBD1"))
            setBackgroundColor(Color.parseColor(if (selected) "#2B4A8B" else "#1E2026"))
            setOnClickListener { action() }
        }

    /* ===================== загрузка данных ===================== */

    private fun reload() {
        allItems.clear()
        continuation = null
        loading = false
        variantIndex = 0
        extraTerms = if (source == "YouTube") {
            try {
                QueryExpander.variants(query, 3).drop(1)
            } catch (_: Throwable) {
                emptyList()
            }
        } else {
            emptyList()
        }
        resultsBox.removeAllViews()
        rebuildChips()
        if (source == "YouTube") {
            loadMore(manual = true)
        } else {
            val term = VideoChannels.scopedQuery(
                query,
                VideoChannels.Channel(channelName.ifBlank { "все" }, channelQuery.ifBlank { channelName })
            ).ifBlank { query }
            val url = when (source) {
                "RuTube" -> "https://rutube.ru/search/?query=" + Uri.encode(term)
                else -> "https://m.vk.com/video?q=" + Uri.encode(term)
            }
            status.text = "Ищу на $source…"
            scrapePass = 0
            scrapeWeb.loadUrl(url)
        }
    }

    private fun loadMore(manual: Boolean = false) {
        if (source != "YouTube") {
            if (manual) {
                scrapeWeb.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight);", null)
                scrapeWeb.postDelayed({ scrapePage() }, 2000)
            }
            return
        }
        if (loading) return
        if (!manual && continuation == null) return
        loading = true
        status.text = if (allItems.isEmpty()) "Ищу на YouTube…" else "Догружаю…"
        val base = if (channelName.isBlank()) query else "$query $channelQuery".trim()
        val term = if (variantIndex == 0 || variantIndex > extraTerms.size) base
        else extraTerms[variantIndex - 1]
        val next = continuation
        Thread({
            val page = try {
                YouTubeSearch.search(term, "Все", 0, "Любая", next)
            } catch (_: Throwable) {
                YouTubeSearch.Page(emptyList(), null)
            }
            runOnUiThread {
                loading = false
                continuation = page.continuation
                // по глубине добираем первые страницы сразу, чтобы категории было чем наполнить
                if (allItems.size < depth * 8 && page.continuation != null) {
                    allItems.addAll(page.items.filter { new -> allItems.none { it.id == new.id && it.kind == new.kind } })
                    rebuildChips()
                    renderList()
                    loading = false
                    loadMore(manual = true)
                    return@runOnUiThread
                }
                allItems.addAll(page.items.filter { new -> allItems.none { it.id == new.id && it.kind == new.kind } })
                rebuildChips()
                renderList()
                // v4.55.0: ИИ-куратор убирает из выдачи посторонние ролики (если включён)
                if (AiCurator.isEnabled(this) && allItems.size > 6) {
                    val snapshotTitles = allItems.map { it.title }
                    Thread({
                        val keep = AiCurator.keepIndices(this, query.ifBlank { channelName }, snapshotTitles, 30)
                        if (keep != null) runOnUiThread {
                            val kept = allItems.filterIndexed { idx, _ -> idx in keep || idx >= 30 }
                            allItems.clear()
                            allItems.addAll(kept)
                            rebuildChips()
                            renderList()
                        }
                    }, "AiVideoFilter").also { it.isDaemon = true; it.start() }
                }
                status.text = (if (variantIndex > 0) "Упоминания по «${extraTerms.getOrNull(variantIndex - 1).orEmpty()}» · " else "") +
                    "Найдено: ${allItems.size} · $category" +
                    (if (duration != "Любая") " · $duration" else "") +
                    (if (continuation == null) " · это всё" else " · листайте — догружу")
                // если по точному запросу почти ничего — идём по ассоциациям
                if (continuation == null && allItems.size < 8 && variantIndex <= extraTerms.size) {
                    variantIndex++
                    loadMore(manual = true)
                    return@runOnUiThread
                }
                // добираем автоматически, если в выбранной категории пока пусто
                if (filtered().isEmpty() && continuation != null) loadMore()
            }
        }, "YTSearch").also { it.isDaemon = true; it.start() }
    }

    private fun renderList() {
        resultsBox.removeAllViews()
        val items = filtered()
        if (items.isEmpty()) {
            status.text = if (allItems.isEmpty()) "Ничего не нашлось по «$query»"
            else "В категории «$category» пусто — переключитесь на «Все»"
            return
        }
        for (item in items) addRow(item)
        status.text = "Показано: ${items.size} · $category" +
            (if (duration != "Любая") " · $duration" else "") +
            (if (continuation != null) " · есть ещё" else "")
    }

    /* ===================== скрапинг RuTube / VK ===================== */

    private fun scrapePage() {
        // несколько проходов (глубина): каждый следующий прокручивает выдачу ниже
        if (scrapePass > 0) {
            scrapeWeb.evaluateJavascript(
                "window.scrollBy(0, Math.round(window.innerHeight*0.9));",
                null
            )
        }
        val js = """
            (function() {
              function clean(t){ return t ? t.replace(/\s+/g,' ').trim() : ''; }
              function isVideoLink(h){
                // RuTube: /video/<hex-id>/   VK: /video-123_456 или /video123_456
                return /rutube\.ru\/video\/[0-9a-f]{8,}/i.test(h) ||
                       /vk(video)?\.(com|ru)\/video-?\d+_\d+/i.test(h) ||
                       /vk\.com\/clip-?\d+_\d+/i.test(h);
              }
              function badTitle(t){
                if (!t || t.length < 5) return true;
                if (/^club\d+$/i.test(t)) return true;
                if (/^(на главную|поиск|видео|музыка|клипы|главная|ещё|показать все|все видео|vk видео)/i.test(t)) return true;
                if (/^[\d\s:.,]+$/.test(t)) return true;
                if (/^(short|reel|shorts)$/i.test(t)) return true;
                return false;
              }
              var out=[], nodes=document.querySelectorAll('a[href]');
              for (var i=0;i<nodes.length;i++){
                var a=nodes[i], h=a.href||'';
                if(!isVideoLink(h)) continue;
                var t = clean(a.getAttribute('aria-label')||a.getAttribute('title')||'');
                if(badTitle(t)){
                  var c=a.closest('li,article,div[class*="video"],div[class*="item"]');
                  var h2=c?c.querySelector('h3,h2,[class*=title]'):null;
                  if(h2) t=clean(h2.innerText||h2.getAttribute('aria-label')||h2.getAttribute('title'));
                }
                if(badTitle(t)){
                  var img=a.querySelector('img[alt]')||(c?a.closest('div,li').querySelector('img[alt]'):null);
                  if(img) t=clean(img.getAttribute('alt'));
                }
                if(badTitle(t)) continue;
                var src=/rutube/i.test(h)?'RuTube':'VK';
                out.push(src+'|'+t.replace(/\|/g,' ')+'|'+h);
              }
              var seen={},res=[];
              for(var j=0;j<out.length;j++){var k=out[j];if(seen[k])continue;seen[k]=1;res.push(k);}
              return res.slice(0,150).join('\n');
            })();
        """.trimIndent()
        scrapeWeb.evaluateJavascript(js) { raw ->
            val text = raw?.removePrefix("\"")?.removeSuffix("\"")
                ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.replace("\\\\", "\\") ?: ""
            val lines = text.split("\n").map { it.trim() }.filter { it.count { c -> c == '|' } >= 2 }
            if (lines.isEmpty()) {
                status.text = "На $source ничего не нашлось — попробуйте другой запрос"
                return@evaluateJavascript
            }
            for (line in lines) {
                val parts = line.split('|')
                val item = YouTubeSearch.Item(
                    id = parts[2].substringAfterLast('/').trimEnd('/'),
                    kind = YouTubeSearch.KIND_VIDEO,
                    title = parts[1],
                    author = parts[0],
                    lengthText = "",
                    lengthSec = 0,
                    views = 0,
                    viewsText = "",
                    publishedText = "",
                    daysAgo = 9999,
                    thumb = "",
                    url = parts[2]
                )
                if (allItems.none { it.id == item.id }) allItems.add(item)
            }
            rebuildChips()
            renderList()
            // глубина: пока не набрали достаточно и не исчерпали проходы — прокручиваем дальше
            scrapePass++
            if (scrapePass < depth && allItems.size < depth * 8) {
                scrapeWeb.postDelayed({ scrapePage() }, 1800)
            }
        }
    }

    /* ===================== карточка ===================== */

    private fun addRow(item: YouTubeSearch.Item) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 8, 12, 8)
        }
        val thumb = ImageView(this)
        val lp = LinearLayout.LayoutParams(dp(112), dp(66))
        lp.setMargins(0, 0, dp(10), 0)
        thumb.layoutParams = lp
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.setBackgroundColor(Color.parseColor("#1E2026"))
        val thumbUrl = item.thumb.ifBlank {
            if (item.id.length == 11) "https://i.ytimg.com/vi/${item.id}/mqdefault.jpg" else ""
        }
        if (thumbUrl.isNotBlank()) {
            Thread({
                val bmp = synchronized(thumbCache) { thumbCache[thumbUrl] } ?: loadBitmap(thumbUrl)
                synchronized(thumbCache) { thumbCache[thumbUrl] = bmp }
                if (bmp != null) runOnUiThread { thumb.setImageBitmap(bmp) }
            }, "Thumb").also { it.isDaemon = true; it.start() }
        } else {
            thumb.setImageResource(android.R.drawable.ic_media_play)
            thumb.scaleType = ImageView.ScaleType.CENTER
        }
        row.addView(thumb)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = when (item.kind) {
                YouTubeSearch.KIND_PLAYLIST -> "🎞 " + item.title
                YouTubeSearch.KIND_CHANNEL -> "📺 " + item.title
                else -> if (item.extra == "live") "🔴 " + item.title else item.title
            }
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 3
        })
        val meta = listOfNotNull(
            item.author.takeIf { it.isNotBlank() && it != "плейлист" && it != "канал" },
            item.lengthText.takeIf { it.isNotBlank() },
            item.viewsText.takeIf { it.isNotBlank() },
            item.publishedText.takeIf { it.isNotBlank() }
        ).distinct().joinToString(" · ")
        if (meta.isNotBlank()) {
            col.addView(TextView(this).apply {
                text = meta
                setTextColor(Color.parseColor("#9AA0A6"))
                textSize = 10f
                maxLines = 2
            })
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        when (item.kind) {
            YouTubeSearch.KIND_PLAYLIST -> {
                buttons.addView(small("▶ Плейлист") { playInline(item.url) })
                buttons.addView(small("🌐 Открыть") { openExternal(item.url) })
                buttons.addView(small("📋 Ссылка") { copy(item.url) })
            }
            YouTubeSearch.KIND_CHANNEL -> {
                buttons.addView(small("🎬 Видео канала") {
                    startActivity(
                        Intent(this, VideoSearchActivity::class.java)
                            .putExtra("query", "")
                            .putExtra("channel", item.title)
                            .putExtra("channelQuery", item.title)
                            .putExtra("source", "YouTube")
                    )
                })
                buttons.addView(small("🌐 Канал") { openExternal(item.url) })
            }
            else -> {
                buttons.addView(small("▶ Смотреть") { playInline(item.url) })
                buttons.addView(small("⬇ Скачать") { startCapture(item) })
                buttons.addView(small("🌐 Открыть") { openExternal(item.url) })
                buttons.addView(small("📋 Ссылка") { copy(item.url) })
            }
        }
        col.addView(buttons)
        row.addView(col)
        resultsBox.addView(row)
        resultsBox.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#23252B"))
        })
    }

    /* ===================== действия ===================== */

    private fun playInline(url: String) {
        playerBox.visibility = View.VISIBLE
        player.loadUrl(url)
        status.text = "Смотрю внутри приложения"
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Throwable) {
            toast("Не удалось открыть ссылку")
        }
    }

    private fun copy(url: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("video", url))
        toast("Ссылка скопирована")
    }

    /**
     * Скачивание внутри приложения. Для YouTube берём itag=18 (360p mp4 со звуком), для VK/RuTube —
     * прямой mp4, который отдаёт их же плеер (эти площадки не требуют входа для просмотра).
     * HLS (m3u8) скачать одним файлом нельзя — в этом случае честно открываем браузер приложения.
     */
    private fun startCapture(item: YouTubeSearch.Item) {
        grabSession++
        val session = grabSession
        grabbed.clear()
        captureStatus.text = "⏳ Ищу поток для «${item.title.take(28)}»…"
        captureWeb.loadUrl(item.url)
        captureWeb.postDelayed({
            captureWeb.evaluateJavascript(
                "(function(){var v=document.querySelector('video');if(v){v.muted=true;v.play();}return 'ok';})();",
                null
            )
        }, 1800)
        captureWeb.postDelayed({ finishCapture(session, item) }, 10_000)
    }

    private fun finishCapture(session: Int, item: YouTubeSearch.Item) {
        if (session != grabSession) return
        val candidates = synchronized(grabbed) { grabbed.toMap() }
        val url = candidates["yt18"]
            ?: candidates["mp4"]
            ?: candidates.entries.firstOrNull { it.key.startsWith("yt") }?.value
        if (url.isNullOrBlank()) {
            captureStatus.text = if (candidates["hls"] != null) {
                "ℹ Поток только HLS (m3u8) — открываю страницу в браузере приложения, там кнопка ⬇"
            } else {
                "❌ Поток не поймался — откройте ролик в браузере приложения и нажмите ⬇ «Скачать видео»"
            }
            captureWeb.loadUrl("about:blank")
            if (candidates["hls"] != null) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)).setPackage(packageName))
                } catch (_: Throwable) {
                }
            }
            return
        }
        val safeName = item.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(70).ifBlank { item.id } + ".mp4"
        val cookie = try {
            CookieManager.getInstance().getCookie(item.url)
        } catch (_: Throwable) {
            null
        }
        // v4.55.0: свой загрузчик вместо DownloadManager — он проверяет код ответа и размер.
        // Раньше DownloadManager сохранял ответ об ошибке (31 байт) и рапортовал «готово».
        captureStatus.text = "⬇ Качаю «${item.title.take(30)}» → Загрузки/${AppDownloads.SUBDIR}"
        StreamDownloader.start(
            context = this,
            url = url,
            fileName = safeName,
            userAgent = DESKTOP_UA,
            cookie = cookie,
            referer = item.url,
            minBytes = 50 * 1024
        ) { ok, note ->
            runOnUiThread {
                captureStatus.text = if (ok) {
                    "✅ Готово: $safeName · $note"
                } else {
                    "❌ Не скачалось: $note (поток устарел — попробуйте ещё раз)"
                }
            }
        }
        toast("Качаю: $safeName")
        captureWeb.loadUrl("about:blank")
    }

    /* ===================== мелочи ===================== */

    private fun newWeb(): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.userAgentString = MOBILE_UA
        CookieManager.getInstance().setAcceptCookie(true)
    }

    private fun small(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 10f
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun loadBitmap(url: String): Bitmap? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", MOBILE_UA)
        }
        if (conn.responseCode in 200..299) conn.inputStream.use { BitmapFactory.decodeStream(it) } else null
    } catch (_: Throwable) {
        null
    }

    companion object {
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0 Mobile Safari/537.36"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
