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
        listOf("Google" to "https://www.google.com/search?q=", "Яндекс" to "https://yandex.ru/search/?text=", "DDG" to "https://duckduckgo.com/?q=")
            .forEach { (name, base) ->
                top.addView(Button(this).apply {
                    text = name
                    textSize = 11f
                    setOnClickListener { load(base + android.net.Uri.encode("$query torrent")) }
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
        load("https://www.google.com/search?q=" + android.net.Uri.encode("$query torrent"))
    }

    private fun load(url: String) {
        status.text = "Открываю поиск…"
        web.loadUrl(url)
    }

    /** Собрать ссылки со страницы выдачи и оставить только трекеры/magnet. */
    private fun collectLinks() {
        val js = """
            (function() {
              var out = [];
              var as = document.querySelectorAll('a[href]');
              for (var i = 0; i < as.length; i++) {
                var h = as[i].href || '';
                var t = (as[i].innerText || '').replace(/\s+/g, ' ').trim();
                if (!h) continue;
                if (h.indexOf('magnet:') === 0) { out.push('MAGNET|' + h + '|' + t); continue; }
                if (/(rutor|nnmclub|rutracker|kinozal|torrent-igruha|byxatab|brotorrent|catorrent|1337x|kickass|torrentdownloads|limetorrents|yts|torrentgalaxy|tapochek|bitru|nnm-club|piratebay|magnetdl)/i.test(h)) {
                  out.push('PAGE|' + h + '|' + t);
                }
              }
              return out.slice(0, 40).join('\n');
            })();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val decoded = raw?.replace("\\n", "\n")?.trim('"')?.replace("\\\"", "\"")?.replace("\\\\", "\\")
            val lines = decoded?.split('\n')?.filter { it.contains('|') } ?: emptyList()
            if (lines.isEmpty()) {
                status.text = "Ссылок на трекеры на этой странице нет. Попробуйте другой поисковик или прокрутите выдачу."
                return@evaluateJavascript
            }
            resultsBox.removeAllViews()
            status.text = "Найдено ссылок: ${lines.size} — проверяю…"
            Thread({
                var added = 0
                for (line in lines) {
                    if (added >= 8) break
                    val parts = line.split('|')
                    val kind = parts.getOrNull(0) ?: continue
                    val url = parts.getOrNull(1) ?: continue
                    val title = parts.getOrNull(2) ?: ""
                    val found = when (kind) {
                        "MAGNET" -> TorrentSearchAgent.Found(
                            name = title.ifBlank { "magnet" },
                            magnet = url,
                            source = "поисковик"
                        )
                        else -> TorrentSearchAgent.fromPageUrl(url, title)
                    } ?: continue
                    added++
                    runOnUiThread { addResultRow(found) }
                }
                runOnUiThread {
                    status.text = if (added == 0) "По ссылкам не удалось вытащить ни magnet, ни .torrent"
                    else "Готово: $added вариантов ниже"
                }
            }, "WebSearchParse").also { it.isDaemon = true; it.start() }
        }
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
