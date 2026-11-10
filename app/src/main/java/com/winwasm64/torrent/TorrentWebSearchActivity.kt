package com.winwasm64.torrent

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * v4.44.0: поиск через настоящие поисковики — Google, Яндекс, DuckDuckGo.
 *
 * Почему так, а не запросом из кода: поисковики отдают ботам заглушку или капчу (проверено: Google и Bing
 * возвращают страницу без результатов, DuckDuckGo отвечает 202). Зато внутри приложения есть настоящий
 * браузер — он проходит и JS, и капчу, и работает через тот же VPN.
 *
 * Схема: WebView открывает выдачу → скрипт собирает ссылки → оставляем те, что ведут на известные
 * трекеры (и любые ссылки с magnet) → каждую проверяем через [TorrentSearchAgent.fromPageUrl] и
 * показываем кнопкой «Скачать». Никакой магии: видно, что именно нашлось.
 */
class TorrentWebSearchActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var resultsBox: LinearLayout
    private lateinit var status: TextView
    private var query = ""

    /** v4.45.0: движки по порядку. Если один отдал пустую выдачу (Google в WebView часто рисует
     * только «скелетоны») — автоматически пробуем следующий, а не оставляем человека с пустым экраном. */
    private val engines = listOf(
        "Google" to "https://www.google.com/search?q=",
        "Яндекс" to "https://yandex.ru/search/?text=",
        "DuckDuckGo" to "https://html.duckduckgo.com/html/?q=",
        "Bing" to "https://www.bing.com/search?q="
    )
    private var engineIndex = 0
    private var triedFallback = false
    /** v4.46.0: «мало сайтов» — сами добираем выдачу запросами по конкретным трекерам. */
    private var queryIndex = 0
    private var rounds = 0
    private var totalAdded = 0
    /**
     * v4.48.0: список запросов, по которым идём подряд. Сюда добавлены и трекеры, и онлайн-сайты:
     * seasonvar, anilibria, anidub, yummyanime, anistar, spaces.im (и зеркала), doramy — для тех
     * случаев, когда торрента нет вовсе, а посмотреть/скачать всё равно надо.
     */
    private val queries: List<String> get() = listOf(
        "$query torrent",
        "$query скачать торрент",
        "$query смотреть онлайн",
        "\"$query\" site:rutracker.org",
        "$query site:nnmclub.to",
        "$query site:rutor.info",
        "$query site:kinozal.tv",
        "$query site:seasonvar.ru",
        "$query site:anilibria.tv",
        "$query site:anidub.com",
        "$query site:yummyanime.tv",
        "$query site:anistar.org",
        "$query site:spaces.im",
        "$query site:doramy.club",
        "$query site:rutor-games.com",
        "$query site:stoigr.org",
        "$query site:kickasstorrents.to",
        "$query site:archive.org",
        // v4.49.0: манга — официальные русскоязычные библиотеки
        "$query манга читать онлайн",
        "$query site:mangalib.me",
        "$query site:mangabuff.ru",
        "$query site:remanga.org",
        "$query site:readmanga.live",
        "$query site:mintmanga.live",
        "$query site:acomics.ru",
        "$query site:lib.knigomir.ru"
    )
    private val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        query = intent.getStringExtra("query").orEmpty()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E0F12"))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 12, 12, 6)
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = "Поиск: ${query.take(28)}"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        engines.forEachIndexed { idx, (name, base) ->
            top.addView(Button(this).apply {
                text = if (name == "DuckDuckGo") "DDG" else name
                textSize = 11f
                setOnClickListener {
                    engineIndex = idx
                    triedFallback = false
                    queryIndex = 0
                    rounds = 0
                    totalAdded = 0
                    seen.clear()
                    resultsBox.removeAllViews()
                    load(base + android.net.Uri.encode(queries.first()))
                }
            })
        }
        root.addView(top)

        status = TextView(this).apply {
            text = "Открываю поиск…"
            setTextColor(Color.parseColor("#9AA0A6"))
            textSize = 11f
            setPadding(12, 0, 12, 6)
        }
        root.addView(status)

        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    status.text = "Страница загружена, собираю ссылки…"
                    collectLinks()
                }
            }
        }
        root.addView(web)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f)
        }
        resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(resultsBox)
        root.addView(scroll)

        setContentView(root)
        loadCurrent()
    }

    private fun load(url: String) {
        status.text = "Открываю поиск…"
        web.loadUrl(url)
    }

    private fun loadCurrent() {
        val q = queries.getOrElse(queryIndex) { queries.first() }
        load(engines[engineIndex].second + android.net.Uri.encode(q))
    }

    /** Следующий запрос (по другому сайту), потом — другой поисковик. */
    private fun nextRound(): Boolean {
        if (rounds >= 20) return false
        rounds++
        if (queryIndex < queries.lastIndex) {
            queryIndex++
        } else if (engineIndex < engines.lastIndex && totalAdded == 0) {
            queryIndex = 1
            engineIndex++
        } else {
            return false
        }
        loadCurrent()
        return true
    }

    /** Собрать ссылки со страницы выдачи; редиректы поисковиков разворачиваются в Kotlin. */
    private fun collectLinks() {
        val js = """
            (function() {
              var out = [];
              var as = document.querySelectorAll('a[href]');
              for (var i = 0; i < as.length; i++) {
                var h = as[i].href || '';
                var t = (as[i].innerText || '').replace(/\s+/g, ' ').trim();
                if (!h || h.indexOf('javascript:') === 0) continue;
                if (h.indexOf('magnet:') === 0) { out.push('MAGNET|' + h + '|' + t); continue; }
                out.push('PAGE|' + h + '|' + t);
              }
              return out.slice(0, 150).join('\n');
            })();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val decoded = raw?.replace("\\n", "\n")?.trim('"')
                ?.replace("\\\"", "\"")?.replace("\\\\", "\\")
            val lines = decoded?.split('\n')?.filter { it.contains('|') } ?: emptyList()

            // magnet-ссылки берём как есть; страницы — только похожие на трекеры
            val magnets = mutableListOf<Triple<String, String, String>>()
            val pages = linkedMapOf<String, String>()
            for (line in lines) {
                val parts = line.split('|')
                val kind = parts.getOrNull(0) ?: continue
                val url = parts.getOrNull(1) ?: continue
                val title = parts.getOrNull(2) ?: ""
                if (kind == "MAGNET") {
                    magnets.add(Triple(kind, url, title))
                    continue
                }
                val real = TorrentSearchAgent.resolveSearchRedirect(url)
                if (!real.startsWith("http")) continue
                if (!looksLikeTracker(real)) continue
                val key = real.substringBefore('#')
                if (!pages.containsKey(key)) pages[key] = title
                if (pages.size >= 60) break
            }

            if (magnets.isEmpty() && pages.isEmpty()) {
                // пустая выдача: сначала следующий запрос/сайт, потом другой поисковик
                if (nextRound()) {
                    status.text = "Мало ссылок — ищу по другому запросу (${engines[engineIndex].first}, «${queries.getOrElse(queryIndex) { "" }.take(40)}»)…"
                } else {
                    status.text = "Поисковики не отдали ссылок на раздачи. Проверьте запрос или откройте поиск сами."
                }
                return@evaluateJavascript
            }

            status.text = "Проверяю ${magnets.size + pages.size} ссылок из «${engines[engineIndex].first}»…"
            Thread({
                var added = 0
                var onlineAdded = 0
                for ((_, url, title) in magnets) {
                    if (added >= 80) break
                    val found = TorrentSearchAgent.Found(
                        name = title.ifBlank { "magnet" },
                        magnet = url,
                        source = "magnet со страницы"
                    )
                    added++
                    runOnUiThread { addResultRow(found) }
                }
                // страницы разбираем параллельно: так 16 ссылок не ждут друг друга по 8 секунд
                val queue = java.util.concurrent.LinkedBlockingQueue<Pair<String, String>>(pages.map { it.key to it.value })
                val workers = (1..3).map {
                    Thread({
                        while (true) {
                            val next = queue.poll() ?: break
                            if (added >= 80) break
                            val found = try {
                                TorrentSearchAgent.fromPageUrl(next.first, next.second)
                            } catch (_: Throwable) {
                                null
                            }
                            if (found == null) {
                                // v4.48.0: у онлайн-кинотек и аниме-сайтов (seasonvar, anilibria, spaces.im…)
                                // .torrent нет вовсе — не выбрасываем их, а даём кнопку «Открыть страницу».
                                if (looksOnline(next.first) && onlineAdded < 15) {
                                    onlineAdded++
                                    runOnUiThread { addPageRow(next.first, next.second) }
                                }
                                continue
                            }
                            val key = found.magnet.ifBlank { found.torrentUrl }
                            val fresh = synchronized(seen) { key.isBlank() || seen.add(key) }
                            if (!fresh) continue
                            added++
                            runOnUiThread { addResultRow(found) }
                        }
                    }, "WebSearchParse$it").also { it.isDaemon = true; it.start() }
                }
                workers.forEach { it.join(45_000) }
                totalAdded += added
                runOnUiThread {
                    if (totalAdded < 80 && nextRound()) {
                        status.text = "Нашлось $totalAdded — добираю по другим сайтам («${queries.getOrElse(queryIndex) { "" }.take(40)}»)…"
                    } else {
                        status.text = when {
                            totalAdded == 0 -> "По ссылкам не удалось вытащить ни magnet, ни .torrent. Откройте страницу раздачи в браузере — ссылка на .torrent часто появляется после клика."
                            else -> "Готово: $totalAdded вариантов ниже"
                        }
                    }
                }
            }, "WebSearchParse").also { it.isDaemon = true; it.start() }
        }
    }

    /** Сайты, где смотрят онлайн и торрента может не быть вовсе. */
    private fun looksOnline(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/').lowercase()
        return Regex(
            "seasonvar|anilibria|anidub|yummyanime|anistar|doramy|spaces[.]im|hdrezka|kinogo|filmix|" +
                "kinopoisk|rutube|vk[.]com|ok[.]ru|anime|dorama|online|serial|kino|" +
                "mangalib|mangabuff|remanga|readmanga|mintmanga|acomics|manga",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(host)
    }

    /** Строка «сайт без торрента»: открыть страницу, а не «Скачать/Смотреть». */
    private fun addPageRow(url: String, title: String) {
        val host = url.substringAfter("://").substringBefore('/')
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
        }
        box.addView(TextView(this).apply {
            text = title.ifBlank { host }.take(90)
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = "$host · онлайн без торрента"
            setTextColor(Color.parseColor("#8AB4F8"))
            textSize = 10f
        })
        box.addView(Button(this).apply {
            text = "🌐 Открыть страницу"
            textSize = 11f
            setOnClickListener {
                try {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                } catch (_: Throwable) {
                    Toast.makeText(this@TorrentWebSearchActivity, "Не удалось открыть", Toast.LENGTH_SHORT).show()
                }
            }
        })
        resultsBox.addView(box)
    }

    /** Похоже ли на трекер/киносайт — только такие страницы стоит разбирать. */
    private fun looksLikeTracker(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/').lowercase()
        return Regex(
            "(torrent|torr|tracker|rutor|nnm|rutracker|kinozal|pirat|byxatab|catorrent|tapochek|" +
                "bitru|igruha|zooqle|yts|1337x|kickass|limetorrent|magnet|btdig|galaxy|rustorrent|bt[.-]|" +
                "kino|film|movie|toloka|mazepa|hdrezka|seasonvar|mega|unionpeer|lasttorrent|ximepa|" +
                "stoigr|gofrag|seedpeer|glotorrents|torrentfunk|torlock|bitsearch|nyaa|aniliberty|" +
                "archive[.]org|cinema|serial|mult|dl-torrent|torrent-games|gamestracker|" +
                "spaces[.]im|seasonvar|anilibria|anidub|yummyanime|anistar|doramy|anime|dorama|" +
                "kino|serial|online|hdrezka|kinogo|filmix|rustorrent|nnm|toloka|mazepa)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(host)
    }

    private fun addResultRow(found: TorrentSearchAgent.Found) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
        }
        box.addView(TextView(this).apply {
            text = found.name.take(90)
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = "${found.source} · ${found.sizeLabel}"
            setTextColor(Color.parseColor("#8AB4F8"))
            textSize = 10f
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "⬇ Скачать"
            textSize = 11f
            setOnClickListener {
                val engine = BitTorrentEngine(applicationContext)
                engine.startFromFound(found)
                Toast.makeText(this@TorrentWebSearchActivity, "Запущено: ${found.name.take(40)}", Toast.LENGTH_SHORT).show()
            }
        })
        row.addView(Button(this).apply {
            text = "▶ Смотреть"
            textSize = 11f
            setOnClickListener {
                startActivity(
                    android.content.Intent(this@TorrentWebSearchActivity, TorrentPlayerActivity::class.java)
                        .putExtra("name", found.name)
                        .putExtra("magnet", found.magnet)
                        .putExtra("torrentUrl", found.torrentUrl)
                        .putExtra("referer", found.referer)
                )
            }
        })
        box.addView(row)
        resultsBox.addView(box)
    }
}
