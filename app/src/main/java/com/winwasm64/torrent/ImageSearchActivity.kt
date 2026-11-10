package com.winwasm64.torrent

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * v4.47.0: галерея фото и обложек ТОЛЬКО по текущему названию.
 *
 * Зачем отдельная вкладка: в браузере поиск картинок приносит всё подряд, приходится листать.
 * Здесь запрос уже сужен названием (и фильтром: обложки игр / постеры фильмов / обложки книг /
 * кадры / альбомы), а найденные картинки показываются сеткой сразу — с кнопками «Скачать» и
 * «Открыть». Кэш живёт только для этого запроса: чужие фото в нём не оседают.
 */
class ImageSearchActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var grid: LinearLayout
    private lateinit var status: TextView
    private var query = ""
    private var filter = "Фото"
    private val handler = Handler(Looper.getMainLooper())
    private val shown = LinkedHashSet<String>()

    /** Суффикс к запросу под выбранный фильтр — так отсекается лишнее. */
    private fun searchText(): String = when (filter) {
        "Обложки игр" -> "$query обложка игры постер"
        "Постеры фильмов" -> "$query постер фильма"
        "Обложки книг" -> "$query обложка книги"
        "Кадры из видео" -> "$query кадр из фильма скриншот"
        "Альбомы музыки" -> "$query обложка альбома"
        else -> query
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        query = intent.getStringExtra("query").orEmpty()
        filter = intent.getStringExtra("filter") ?: "Фото"

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
            text = "🖼 $filter: $query"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        listOf("Bing" to "https://www.bing.com/images/search?q=", "Яндекс" to "https://yandex.ru/images/search?text=")
            .forEach { (name, base) ->
                top.addView(Button(this).apply {
                    text = name
                    textSize = 11f
                    setOnClickListener { load(base + android.net.Uri.encode(searchText())) }
                })
            }
        root.addView(top)

        status = TextView(this).apply {
            text = "Открываю поиск картинок…"
            setTextColor(Color.parseColor("#9AA0A6"))
            textSize = 11f
            setPadding(12, 0, 12, 6)
        }
        root.addView(status)

        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    status.text = "Собираю картинки…"
                    collectImages()
                }
            }
        }
        root.addView(web)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f)
        }
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(grid)
        root.addView(scroll)

        setContentView(root)
        load("https://www.bing.com/images/search?q=" + android.net.Uri.encode(searchText()))
    }

    private fun load(url: String) {
        status.text = "Открываю поиск картинок…"
        web.loadUrl(url)
    }

    /**
     * Bing держит прямые адреса картинок в атрибуте m у ссылок .iusc (JSON вида {"murl":"…"}),
     * Яндекс — в data-атрибутах. Забираем всё, что удалось, и отсекаем мелочь в Котлине.
     */
    private fun collectImages() {
        val js = """
            (function() {
              var out = [];
              var as = document.querySelectorAll('a.iusc');
              for (var i = 0; i < as.length; i++) {
                var m = as[i].getAttribute('m');
                if (m) {
                  try { var j = JSON.parse(m); if (j.murl) out.push(j.murl + '|' + (j.t || '')); } catch (e) {}
                }
              }
              if (out.length === 0) {
                var imgs = document.querySelectorAll('img');
                for (var k = 0; k < imgs.length; k++) {
                  var s = imgs[k].currentSrc || imgs[k].src || '';
                  if (s.indexOf('http') === 0) out.push(s + '|' + (imgs[k].alt || ''));
                }
              }
              return out.slice(0, 120).join('\n');
            })();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val decoded = raw?.replace("\\n", "\n")?.trim('"')?.replace("\\\"", "\"")?.replace("\\\\", "\\")
            val lines = decoded?.split('\n')?.filter { it.contains('|') } ?: emptyList()
            if (lines.isEmpty()) {
                status.text = "Картинок на странице не нашлось. Попробуйте другой поиск или прокрутите выдачу."
                return@evaluateJavascript
            }
            status.text = "Найдено ${lines.size} картинок"
            var row: LinearLayout? = null
            var inRow = 0
            for (line in lines) {
                val url = line.substringBefore('|')
                if (!url.startsWith("http")) continue
                if (!shown.add(url)) continue
                if (inRow == 0) {
                    row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    grid.addView(row)
                }
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setPadding(4, 4, 4, 8)
                }
                val title = line.substringAfter('|').take(40)
                cell.addView(ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 240)
                    setBackgroundColor(Color.parseColor("#1A1D22"))
                    setOnClickListener {
                        try {
                            startActivity(
                                android.content.Intent(this@ImageSearchActivity, ImagePreviewActivity::class.java)
                                    .putExtra("url", url)
                                    .putExtra("title", title)
                            )
                        } catch (_: Throwable) {
                        }
                    }
                    Thread({
                        val bmp: Bitmap? = com.winwasm64.ui.browser.CoverCache.load(url)
                        if (bmp != null) handler.post { setImageBitmap(bmp) }
                    }, "ImgLoad").also { it.isDaemon = true; it.start() }
                })
                cell.addView(Button(this).apply {
                    text = "⬇"
                    textSize = 11f
                    setOnClickListener {
                        try {
                            val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "image.jpg" }
                            val request = android.app.DownloadManager.Request(android.net.Uri.parse(url))
                                .setTitle(name)
                                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name)
                            (getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager).enqueue(request)
                            Toast.makeText(this@ImageSearchActivity, "⬇ $name", Toast.LENGTH_SHORT).show()
                        } catch (_: Throwable) {
                            Toast.makeText(this@ImageSearchActivity, "Не удалось скачать", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
                row!!.addView(cell)
                inRow++
                if (inRow == 2) inRow = 0
            }
        }
    }
}

/** Полноэкранный просмотр выбранной картинки. */
class ImagePreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url").orEmpty()
        val title = intent.getStringExtra("title").orEmpty()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(8, 24, 8, 8)
        }
        root.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 12f
        })
        val image = ImageView(this).apply {
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(image)
        Thread({
            val bmp: Bitmap? = com.winwasm64.ui.browser.CoverCache.load(url)
            if (bmp != null) runOnUiThread { image.setImageBitmap(bmp) }
        }, "ImgPreview").also { it.isDaemon = true; it.start() }
        root.addView(Button(this).apply {
            text = "🌐 Открыть в браузере"
            setOnClickListener {
                try {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                } catch (_: Throwable) {
                }
            }
        })
        setContentView(root)
    }
}
