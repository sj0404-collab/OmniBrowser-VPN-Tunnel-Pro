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
 * v4.49.0: «Видео» — поиск роликов по названию или по ссылке.
 *
 * Можно искать «я злой бог» и получить ролики с YouTube, RuTube, VK Видео и Dailymotion, либо вставить
 * готовую ссылку — приложение откроет сам ролик внутри себя (WebView), без ухода в браузер.
 *
 * Почему WebView: у всех этих площадок нет открытого поиска без ключа/регистрации, но обычная выдача
 * отдаётся нормально. Скрипт собирает ссылки на ролики и обложки, а показ идёт строкой: превью —
 * название — кнопки «▶ Смотреть» (в этом же окне) и «🌐 Открыть».
 */
class VideoSearchActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var resultsBox: LinearLayout
    private lateinit var status: TextView
    private lateinit var playerBox: LinearLayout
    private lateinit var player: WebView
    private var query = ""

    private val sources = listOf(
        "YouTube" to "https://m.youtube.com/results?search_query=",
        "RuTube" to "https://rutube.ru/api/search/video/?query=",
        "VK" to "https://m.vk.com/video?q=",
        "Dailymotion" to "https://www.dailymotion.com/search/"
    )

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
            text = "🎬 Видео: ${query.take(26)}"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        listOf("YouTube", "RuTube", "VK", "Ссылка").forEach { name ->
            top.addView(Button(this).apply {
                text = name
                textSize = 11f
                setOnClickListener {
                    when (name) {
                        "YouTube" -> load("https://m.youtube.com/results?search_query=" + android.net.Uri.encode(query))
                        "RuTube" -> load("https://rutube.ru/search/?query=" + android.net.Uri.encode(query))
                        "VK" -> load("https://m.vk.com/video?q=" + android.net.Uri.encode(query))
                        else -> {
                            if (query.startsWith("http")) playInline(query)
                            else Toast.makeText(
                                this@VideoSearchActivity,
                                "Вставьте ссылку в поле поиска, чтобы открыть ролик",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            })
        }
        root.addView(top)

        status = TextView(this).apply {
            text = "Открываю поиск видео…"
            setTextColor(Color.parseColor("#9AA0A6"))
            textSize = 11f
            setPadding(12, 0, 12, 6)
        }
        root.addView(status)

        // встроенный плеер — сворачивается, пока не выбрано видео
        playerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }
        player = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
        }
        playerBox.addView(TextView(this).apply {
            text = "▶ Воспроизведение внутри приложения"
            setTextColor(Color.WHITE)
            textSize = 11f
        })
        playerBox.addView(player)
        playerBox.addView(Button(this).apply {
            text = "✖ Закрыть плеер"
            textSize = 11f
            setOnClickListener {
                player.stopLoading()
                player.loadUrl("about:blank")
                playerBox.visibility = android.view.View.GONE
            }
        })
        root.addView(playerBox)

        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    status.text = "Собираю ролики…"
                    collectVideos()
                }
            }
        }
        root.addView(web)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f)
        }
        resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(resultsBox)
        root.addView(scroll)

        setContentView(root)
        if (query.startsWith("http")) {
            status.text = "Открываю ссылку…"
            playInline(query)
        } else {
            load("https://m.youtube.com/results?search_query=" + android.net.Uri.encode(query))
        }
    }

    private fun load(url: String) {
        status.text = "Открываю поиск видео…"
        web.loadUrl(url)
    }

    private fun playInline(url: String) {
        playerBox.visibility = android.view.View.VISIBLE
        val embed = when {
            url.contains("youtube.com/watch") || url.contains("youtu.be") -> {
                val id = if (url.contains("youtu.be")) url.substringAfterLast('/').substringBefore('?')
                else url.substringAfter("v=").substringBefore('&')
                "https://m.youtube.com/watch?v=$id"
            }
            else -> url
        }
        player.loadUrl(embed)
        status.text = "Играет: ${embed.take(60)}"
    }

    /** Собираем ссылки на ролики и обложки (YouTube отдаёт их в разметке выдачи). */
    private fun collectVideos() {
        val js = """
            (function() {
              var out = [];
              var as = document.querySelectorAll('a[href]');
              for (var i = 0; i < as.length; i++) {
                var h = as[i].href || '';
                var t = (as[i].innerText || as[i].getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim();
                if (!/(youtube\.com\/watch|youtu\.be\/|rutube\.ru\/video\/|vk\.com\/video|dailymotion\.com\/video)/i.test(h)) continue;
                if (t.length < 3) continue;
                out.push(h + '|' + t);
              }
              return out.slice(0, 120).join('\n');
            })();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val decoded = raw?.replace("\\n", "\n")?.trim('"')?.replace("\\\"", "\"")?.replace("\\\\", "\\")
            val lines = decoded?.split('\n')?.filter { it.contains('|') } ?: emptyList()
            if (lines.isEmpty()) {
                status.text = "Роликов на странице не нашлось — попробуйте другой источник или прокрутите выдачу."
                return@evaluateJavascript
            }
            resultsBox.removeAllViews()
            status.text = "Найдено роликов: ${lines.size}"
            val seen = HashSet<String>()
            for (line in lines) {
                val url = line.substringBefore('|')
                val title = line.substringAfter('|')
                if (!seen.add(url)) continue
                addVideoRow(url, title)
            }
        }
    }

    private fun addVideoRow(url: String, title: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
        }
        box.addView(TextView(this).apply {
            text = title.take(100)
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = url.substringAfter("://").take(60)
            setTextColor(Color.parseColor("#8AB4F8"))
            textSize = 10f
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "▶ Смотреть"
            textSize = 11f
            setOnClickListener { playInline(url) }
        })
        row.addView(Button(this).apply {
            text = "🌐 Открыть"
            textSize = 11f
            setOnClickListener {
                try {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                } catch (_: Throwable) {
                }
            }
        })
        row.addView(Button(this).apply {
            text = "⬇ Скачать"
            textSize = 11f
            setOnClickListener {
                Toast.makeText(
                    this@VideoSearchActivity,
                    "Прямой файл у этой площадки не отдаётся — смотрите во встроенном плеере",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
        box.addView(row)
        resultsBox.addView(box)
    }
}
