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
import android.view.Gravity
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
 * v4.51.0: «Видео» переписана под ту же схему, что «Файлы» и «Торренты».
 *
 * Что было сломано в 4.50.0 и что сделано:
 *  • выбор «VK Видео» всё равно искал в YouTube — строка «VK Видео» не совпадала с «VK» в when;
 *    теперь источник нормализуется и РЕАЛЬНО используется при загрузке;
 *  • «⬇ Скачать» была заглушкой — теперь это внутренний аналог кнопки «Скачать видео» из браузера:
 *    ролик открывается в скрытом WebView, перехватывается поток videoplayback (itag=18, 360p —
 *    минимальное склеенное качество) и уходит в «Загрузки» приложения;
 *  • «прокрутка не работает» — поиск YouTube идёт через JSON-интерфейс с токеном продолжения,
 *    поэтому список пополняется бесконечно, без скролла невидимой страницы;
 *  • не было плейлистов и каналов — теперь есть категории «Видео / Плейлисты / Каналы / Трансляции»,
 *    сортировка (новые, популярные, старые) и фильтр по длительности — как фильтры в других вкладках.
 */
class VideoSearchActivity : Activity() {

    private var query = ""
    private var channelName = ""
    private var channelQuery = ""
    private var source = "YouTube"
    private var category = "Все"
    private var sortMode = 0
    private var duration = "Любая"

    private lateinit var status: TextView
    private lateinit var captureStatus: TextView
    private lateinit var resultsBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var playerBox: LinearLayout
    private lateinit var player: WebView
    private lateinit var scrapeWeb: WebView
    private lateinit var captureWeb: WebView

    private val ytItems = mutableListOf<YouTubeSearch.Item>()
    private var continuation: String? = null
    private var loading = false
    private val thumbCache = HashMap<String, Bitmap?>()
    private val grabbed = Collections.synchronizedMap(LinkedHashMap<String, String>())
    private var grabSession = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        query = intent.getStringExtra("query").orEmpty()
        channelName = intent.getStringExtra("channel").orEmpty()
        channelQuery = intent.getStringExtra("channelQuery").orEmpty()
        source = normalizeSource(intent.getStringExtra("source").orEmpty())

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
            text = "«${query.ifBlank { "—" }}»" +
                if (channelName.isNotBlank()) " · только этот канал" else ""
            setTextColor(Color.parseColor("#9AA0A6"))
            textSize = 11f
            setPadding(12, 0, 12, 6)
        })

        // площадки — реальный выбор, а не декорация
        val sourceRow = HorizontalScrollView(this)
        val sourceInner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("YouTube", "RuTube", "VK", "Ссылка").forEach { name ->
            sourceInner.addView(chip(name, source == name) {
                source = name
                if (name == "Ссылка") {
                    if (query.startsWith("http")) playInline(query) else toast("Вставьте ссылку в поле поиска")
                } else {
                    reload()
                }
                recreateChips()
            })
        }
        sourceRow.addView(sourceInner)
        root.addView(sourceRow)
        sourceChipsHolder = sourceInner

        // категории
        val catRow = HorizontalScrollView(this)
        val catInner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Все", "🎬 Видео", "🎞 Плейлисты", "📺 Каналы", "🔴 Трансляции").forEach { name ->
            catInner.addView(chip(name, category == name) {
                category = name
                reload()
                recreateCategoryChips()
            })
        }
        catRow.addView(catInner)
        root.addView(catRow)
        categoryChipsHolder = catInner

        // сортировка + длительность
        val sortRow = HorizontalScrollView(this)
        val sortInner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("По релевантности", "Новые", "Популярные", "Старые").forEachIndexed { idx, label ->
            sortInner.addView(chip(label, sortMode == idx) {
                sortMode = idx
                reload()
                recreateSortChips()
            })
        }
        listOf("Любая", "До 4 мин", "4–20 мин", "20+ мин").forEach { label ->
            sortInner.addView(chip(label, duration == label) {
                duration = label
                reload()
                recreateSortChips()
            })
        }
        sortRow.addView(sortInner)
        root.addView(sortRow)
        sortChipsHolder = sortInner

        status = TextView(this).apply {
            text = "Ищу…"
            setTextColor(Color.parseColor("#FFC107"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(12, 6, 12, 4)
        }
        root.addView(status)

        captureStatus = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#8AB4F8"))
            textSize = 10f
            setPadding(12, 0, 12, 4)
        }
        root.addView(captureStatus)

        // встроенный плеер
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

        // служебные WebView: скрапинг RuTube/VK и перехват потока при скачивании
        scrapeWeb = newWeb().apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            visibility = View.GONE
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.postDelayed({ scrapePage() }, 1200)
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
                        grabbed[itag.ifBlank { "other" }] = u
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

        if (query.startsWith("http")) {
            playInline(query)
            status.text = "Открыл ссылку во встроенном плеере"
        } else {
            reload()
        }
    }

    /* ===================== чипы ===================== */

    private var sourceChipsHolder: LinearLayout? = null
    private var categoryChipsHolder: LinearLayout? = null
    private var sortChipsHolder: LinearLayout? = null

    private fun recreateChips() {
        val holder = sourceChipsHolder ?: return
        holder.removeAllViews()
        listOf("YouTube", "RuTube", "VK", "Ссылка").forEach { name ->
            holder.addView(chip(name, source == name) {
                source = name
                if (name != "Ссылка") reload()
                recreateChips()
            })
        }
    }

    private fun recreateCategoryChips() {
        val holder = categoryChipsHolder ?: return
        holder.removeAllViews()
        listOf("Все", "🎬 Видео", "🎞 Плейлисты", "📺 Каналы", "🔴 Трансляции").forEach { name ->
            holder.addView(chip(name, category == name) {
                category = name
                reload()
                recreateCategoryChips()
            })
        }
    }

    private fun recreateSortChips() {
        val holder = sortChipsHolder ?: return
        holder.removeAllViews()
        listOf("По релевантности", "Новые", "Популярные", "Старые").forEachIndexed { idx, label ->
            holder.addView(chip(label, sortMode == idx) {
                sortMode = idx
                reload()
                recreateSortChips()
            })
        }
        listOf("Любая", "До 4 мин", "4–20 мин", "20+ мин").forEach { label ->
            holder.addView(chip(label, duration == label) {
                duration = label
                reload()
                recreateSortChips()
            })
        }
    }

    private fun chip(label: String, selected: Boolean, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 10f
            setPadding(dp(8), 0, dp(8), 0)
            setTextColor(if (selected) Color.WHITE else Color.parseColor("#C7CBD1"))
            setBackgroundColor(
                Color.parseColor(if (selected) "#2B4A8B" else "#1E2026")
            )
            setOnClickListener { action() }
        }

    /* ===================== загрузка ===================== */

    private fun normalizeSource(raw: String): String = when {
        raw.contains("VK", true) -> "VK"
        raw.contains("RuTube", true) || raw.contains("Рутуб", true) -> "RuTube"
        raw.contains("Dailymotion", true) -> "YouTube"
        raw.isBlank() || raw == "Все" -> "YouTube"
        else -> "YouTube"
    }

    private fun reload() {
        ytItems.clear()
        continuation = null
        resultsBox.removeAllViews()
        loading = false
        if (source == "YouTube") {
            loadMore(manual = true)
        } else {
            // RuTube и VK открытого поиска без ключа не имеют — берём их выдачу через WebView
            val term = VideoChannels.scopedQuery(
                query,
                VideoChannels.Channel(channelName.ifBlank { "все" }, channelQuery.ifBlank { channelName })
            ).ifBlank { query }
            val url = when (source) {
                "RuTube" -> "https://rutube.ru/search/?query=" + Uri.encode(term)
                else -> "https://m.vk.com/video?q=" + Uri.encode(term)
            }
            status.text = "Ищу на $source…"
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
        status.text = if (ytItems.isEmpty()) "Ищу на YouTube…" else "Догружаю ещё…"
        val term = if (channelName.isBlank()) query else "$query $channelQuery".trim()
        val next = continuation
        Thread({
            val page = try {
                YouTubeSearch.search(term, category, sortMode, duration, next)
            } catch (_: Throwable) {
                YouTubeSearch.Page(emptyList(), null)
            }
            runOnUiThread {
                loading = false
                continuation = page.continuation
                if (page.items.isEmpty() && ytItems.isEmpty()) {
                    status.text = "Ничего не нашлось по «$term» (категория: $category)"
                } else {
                    val before = ytItems.size
                    ytItems.addAll(page.items.filter { new -> ytItems.none { it.id == new.id && it.kind == new.kind } })
                    for (i in before until ytItems.size) addRow(ytItems[i])
                    status.text = "Найдено: ${ytItems.size} · $category" +
                        (if (duration != "Любая") " · $duration" else "") +
                        (if (continuation == null) " · это всё" else " · листайте дальше")
                }
            }
        }, "YTSearch").also { it.isDaemon = true; it.start() }
    }

    /* ===================== скрапинг RuTube / VK ===================== */

    private fun scrapePage() {
        val js = """
            (function() {
              function clean(t){ if(!t) return ''; return t.replace(/\s+/g,' ').trim(); }
              var out=[]; var nodes=document.querySelectorAll('a[href]');
              for (var i=0;i<nodes.length;i++){
                var a=nodes[i], h=a.href||'';
                if(!/(rutube\.ru\/video\/|vk\.com\/video|vkvideo\.ru)/i.test(h)) continue;
                var t = clean(a.getAttribute('aria-label')||a.getAttribute('title')||'');
                if(!t || t.length<4){
                  var c=a.closest('li,article,div[class]');
                  var h2=c?c.querySelector('h3,h2,[class*=title]'):null;
                  if(h2) t=clean(h2.innerText||h2.getAttribute('aria-label'));
                }
                if(!t || t.length<4) continue;
                var src=/rutube/i.test(h)?'RuTube':'VK';
                out.push(src+'|'+t.replace(/\|/g,' ')+'|'+h);
              }
              var seen={},res=[];
              for(var j=0;j<out.length;j++){var k=out[j];if(seen[k])continue;seen[k]=1;res.push(k);}
              return res.slice(0,80).join('\n');
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
            resultsBox.removeAllViews()
            ytItems.clear()
            for (line in lines) {
                val parts = line.split('|')
                val src = parts[0]
                val title = parts[1]
                val url = parts[2]
                val item = YouTubeSearch.Item(
                    id = url.substringAfterLast('/').trimEnd('/'),
                    kind = YouTubeSearch.KIND_VIDEO,
                    title = title,
                    author = src,
                    lengthText = "",
                    lengthSec = 0,
                    views = 0,
                    viewsText = "",
                    publishedText = "",
                    daysAgo = 9999,
                    thumb = "",
                    url = url
                )
                ytItems.add(item)
                addRow(item)
            }
            status.text = "Найдено: ${ytItems.size} · $source"
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
                else -> item.title
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
            item.publishedText.takeIf { it.isNotBlank() },
            item.viewsText.takeIf { item.kind != YouTubeSearch.KIND_VIDEO && it.isNotBlank() }
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
                buttons.addView(small("▶ Открыть плейлист") { playInline(item.url) })
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
                buttons.addView(small("🌐 Открыть") { openExternal(item.url) })
                buttons.addView(small("⬇ Скачать 360p") { startCapture(item) })
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
     * Скачивание видео внутри приложения (аналог кнопки «Скачать видео» в браузере).
     *
     * Загружаем страницу ролика в скрытый WebView и перехватываем реальный поток videoplayback.
     * Минимальное склеенное качество — itag=18 (360p mp4 со звуком); если YouTube его не отдал,
     * берём самый лёгкий mp4, который удалось поймать.
     */
    private fun startCapture(item: YouTubeSearch.Item) {
        if (!item.url.contains("youtube", true)) {
            // RuTube/VK: прямой поток отдаётся только после входа, честно отправляем в браузер приложения
            toast("У этой площадки поток только через браузер приложения — открываю")
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)).setPackage(packageName))
            } catch (_: Throwable) {
            }
            return
        }
        grabSession++
        val session = grabSession
        grabbed.clear()
        captureStatus.text = "⏳ Ищу поток 360p для «${item.title.take(30)}»…"
        captureWeb.loadUrl("https://m.youtube.com/watch?v=${item.id}&hl=ru")
        captureWeb.postDelayed({
            captureWeb.evaluateJavascript(
                "(function(){var v=document.querySelector('video');if(v){v.muted=true;v.play();}return 'ok';})();",
                null
            )
        }, 1500)
        captureWeb.postDelayed({ finishCapture(session, item) }, 9_000)
    }

    private fun finishCapture(session: Int, item: YouTubeSearch.Item) {
        if (session != grabSession) return
        val candidates = synchronized(grabbed) { grabbed.toMap() }
        if (candidates.isEmpty()) {
            captureStatus.text = "❌ Не удалось поймать поток — откройте ролик в браузере приложения и нажмите ⬇ «Скачать видео»"
            captureWeb.loadUrl("about:blank")
            return
        }
        // itag 18 — 360p mp4 (видео+звук). Иначе — самый лёгкий из пойманных.
        val url = candidates["18"]
            ?: candidates.entries.sortedBy { it.key.toIntOrNull() ?: 999 }.first().value
        val safeName = item.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(70)
            .ifBlank { item.id } + ".mp4"
        val cookie = try {
            CookieManager.getInstance().getCookie("https://www.youtube.com")
        } catch (_: Throwable) {
            null
        }
        val id = AppDownloads.enqueue(
            context = this,
            url = url,
            fileName = safeName,
            mimeType = "video/mp4",
            userAgent = DESKTOP_UA,
            cookie = cookie,
            referer = "https://www.youtube.com/watch?v=${item.id}"
        )
        captureStatus.text = if (id != null) {
            "⬇ 360p → Загрузки/${AppDownloads.SUBDIR}/$safeName (видно во вкладке «Загрузки»)"
        } else {
            "❌ Не удалось поставить загрузку"
        }
        if (id != null) toast("Качаю 360p: $safeName")
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
        // у пользователя выполнен вход в YouTube — сохраняем cookies между запусками
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
