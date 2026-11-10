package com.winwasm64.torrent

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL

/**
 * v4.50.0: «Видео» — такая же вкладка, как «Файлы»: список карточек с превью и тремя действиями
 * (смотреть / открыть / скачать). Поиск по названию, по ссылке и ВНУТРИ выбранного канала.
 *
 * Что было не так в 4.49.0 (по скриншотам):
 *  • вместо названий попадали подписи таймингов — «В ЭФИРЕ», «2:10:25», «1:33:55», потому что
 *    брался `innerText` первой ссылки-превью. Теперь название берётся из aria-label/title и
 *    отбраковывается, если это просто время или служебная подпись;
 *  • ссылки были с мусором (`&pp=ygUI0JjRgNC10L3QsNC60...`) — теперь вырезаем чистый ID ролика;
 *  • не было превью — теперь тянем обложку по ID (i.ytimg.com);
 *  • не было подписок — теперь есть, с поиском внутри канала.
 */
class VideoSearchActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var resultsBox: LinearLayout
    private lateinit var status: TextView
    private lateinit var playerBox: LinearLayout
    private lateinit var player: WebView
    private lateinit var pageButton: Button

    private var query = ""
    private var channelName = ""
    private var channelQuery = ""
    private var source = "YouTube"
    private val seenIds = HashSet<String>()
    private val thumbCache = HashMap<String, Bitmap?>()

    private val sourcesTop = listOf("YouTube", "RuTube", "VK", "Ссылка")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        query = intent.getStringExtra("query").orEmpty()
        channelName = intent.getStringExtra("channel").orEmpty()
        channelQuery = intent.getStringExtra("channelQuery").orEmpty()
        source = intent.getStringExtra("source").orEmpty().ifBlank { "YouTube" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E0F12"))
        }

        // ---------- шапка ----------
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 12, 12, 4)
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = if (channelName.isNotBlank()) "📺 $channelName" else "🎬 Видео"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(top)

        val subtitle = TextView(this).apply {
            text = "«${query.ifBlank { "—" }}»" +
                if (channelName.isNotBlank()) " · поиск внутри канала" else " · по всем площадкам"
            setTextColor(Color.parseColor("#9AA0A6"))
            textSize = 11f
            setPadding(12, 0, 12, 4)
        }
        root.addView(subtitle)

        val sourceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 0, 8, 4)
        }
        for (name in sourcesTop) {
            sourceRow.addView(Button(this).apply {
                text = name
                textSize = 11f
                setOnClickListener { switchSource(name) }
            })
        }
        root.addView(sourceRow)

        status = TextView(this).apply {
            text = "Открываю поиск видео…"
            setTextColor(Color.parseColor("#FFC107"))
            textSize = 11f
            setPadding(12, 2, 12, 6)
        }
        root.addView(status)

        // ---------- встроенный плеер ----------
        playerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(8, 0, 8, 6)
        }
        playerBox.addView(TextView(this).apply {
            text = "▶ Воспроизведение внутри приложения"
            setTextColor(Color.WHITE)
            textSize = 11f
        })
        player = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 560)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = MOBILE_UA
        }
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

        // ---------- служебный WebView, который приносит выдачу ----------
        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            visibility = View.GONE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = MOBILE_UA
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    status.text = "Собираю ролики…"
                    view?.postDelayed({ collectVideos(false) }, 1200)
                    view?.postDelayed({ collectVideos(true) }, 3200)
                }
            }
        }
        root.addView(web)

        // ---------- страница площадки по запросу (по желанию) ----------
        pageButton = Button(this).apply {
            text = "🌐 Показать страницу площадки"
            textSize = 11f
            setOnClickListener {
                if (web.visibility == View.GONE) {
                    (web.layoutParams as LinearLayout.LayoutParams).height = 520
                    web.visibility = View.VISIBLE
                    text = "🙈 Скрыть страницу площадки"
                } else {
                    web.visibility = View.GONE
                    (web.layoutParams as LinearLayout.LayoutParams).height = 1
                    text = "🌐 Показать страницу площадки"
                }
                web.requestLayout()
            }
        }
        root.addView(pageButton)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(resultsBox)
        root.addView(scroll)

        // кнопка «ещё» внизу списка
        root.addView(Button(this).apply {
            text = "⬇ Показать ещё (прокрутить выдачу)"
            textSize = 11f
            setOnClickListener {
                web.evaluateJavascript(
                    "(function(){window.scrollTo(0, document.body.scrollHeight);return 'ok';})();",
                    null
                )
                status.text = "Прокручиваю выдачу, ищу новые ролики…"
                web.postDelayed({ collectVideos(true) }, 2200)
            }
        })

        setContentView(root)

        if (query.startsWith("http")) {
            status.text = "Открываю ссылку…"
            playInline(query)
        } else {
            switchSource(source)
        }
    }

    private fun switchSource(name: String) {
        val term = VideoChannels.scopedQuery(
            query,
            VideoChannels.Channel(channelName.ifBlank { "YouTube" }, channelQuery.ifBlank { channelName })
        ).ifBlank { query }
        val encoded = android.net.Uri.encode(term)
        val url = when (name) {
            "RuTube" -> "https://rutube.ru/search/?query=$encoded"
            "VK" -> "https://m.vk.com/video?q=$encoded"
            "Ссылка" -> {
                if (query.startsWith("http")) {
                    playInline(query)
                    return
                }
                Toast.makeText(this, "Вставьте ссылку в поле поиска", Toast.LENGTH_LONG).show()
                return
            }
            else -> "https://m.youtube.com/results?search_query=$encoded"
        }
        status.text = "Открываю поиск видео · $name…"
        web.loadUrl(url)
    }

    private fun playInline(url: String) {
        playerBox.visibility = View.VISIBLE
        player.loadUrl(url)
        status.text = "Играет: ${url.take(60)}"
    }

    /**
     * Достаём ролики: сначала ID (чтобы отбросить рекламу и дубли), потом НАЗВАНИЕ из aria-label/title,
     * а не из innerText — именно из-за innerText в 4.49.0 в списке были одни тайминги.
     */
    private fun collectVideos(append: Boolean) {
        val js = """
            (function() {
              function clean(t) {
                if (!t) return '';
                t = t.replace(/\s+/g, ' ').trim();
                t = t.replace(/\s*[-–·]\s*\d+\s*(минут|мин|min|сек|seconds?).*${'$'}/i, '');
                return t;
              }
              function bad(t) {
                if (!t || t.length < 4) return true;
                if (/^[\d:.\s]+${'$'}/.test(t)) return true;
                if (/^(в эфире|shorts|главная|подписки|библиотека|you|вы|на главную|пропустить)${'$'}/i.test(t)) return true;
                if (/^[\d\s.,]+(тыс\.?|млн|просмотр|views)/i.test(t)) return true;
                return false;
              }
              var out = [];
              var nodes = document.querySelectorAll('a[href]');
              for (var i = 0; i < nodes.length; i++) {
                var a = nodes[i];
                var h = a.href || '';
                if (!/(youtube\.com\/watch|youtu\.be\/|rutube\.ru\/video\/|vk\.com\/video|dailymotion\.com\/video)/i.test(h)) continue;
                var id = '';
                var m = h.match(/[?&]v=([A-Za-z0-9_-]{11})/);
                if (m) id = m[1];
                if (!id) { m = h.match(/youtu\.be\/([A-Za-z0-9_-]{11})/); if (m) id = m[1]; }
                if (!id) { m = h.match(/rutube\.ru\/video\/([A-Za-z0-9]+)/); if (m) id = 'rt_' + m[1]; }
                if (!id) { m = h.match(/vk\.com\/video(-?[0-9_]+)/); if (m) id = 'vk_' + m[1]; }
                if (!id) { m = h.match(/dailymotion\.com\/video\/([A-Za-z0-9]+)/); if (m) id = 'dm_' + m[1]; }
                if (!id) continue;
                var t = clean(a.getAttribute('aria-label') || a.getAttribute('title') || '');
                if (bad(t)) {
                  var card = a.closest('ytm-media-item, ytd-rich-item-renderer, li, article');
                  if (card) {
                    var h2 = card.querySelector('#video-title, h3, .media-item-headline, .video-title');
                    if (h2) { var t2 = clean(h2.getAttribute('aria-label') || h2.innerText); if (!bad(t2)) t = t2; }
                    if (bad(t)) {
                      var lab = card.querySelector('[aria-label]');
                      var t3 = clean(lab ? lab.getAttribute('aria-label') : '');
                      if (!bad(t3)) t = t3;
                    }
                  }
                }
                if (bad(t)) continue;
                var src = /rutube/i.test(h) ? 'RuTube' : (/vk\.com/i.test(h) ? 'VK' : (/dailymotion/i.test(h) ? 'Dailymotion' : 'YouTube'));
                out.push(id + '|' + src + '|' + t.replace(/\|/g, ' ') + '|' + h);
              }
              var uniq = {}, res = [];
              for (var j = 0; j < out.length; j++) {
                var k = out[j].split('|')[0];
                if (uniq[k]) continue;
                uniq[k] = 1; res.push(out[j]);
              }
              return res.slice(0, 120).join('\n');
            })();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val text = raw
                ?.removePrefix("\"")
                ?.removeSuffix("\"")
                ?.replace("\\n", "\n")
                ?.replace("\\\"", "\"")
                ?.replace("\\\\", "\\")
                ?: ""
            val lines = text.split('\n').map { it.trim() }.filter { it.count { ch -> ch == '|' } >= 3 }
            if (lines.isEmpty()) {
                if (!append) {
                    status.text = "Роликов не нашлось — попробуйте другую площадку или прокрутите выдачу."
                }
                return@evaluateJavascript
            }
            var added = 0
            for (line in lines) {
                val parts = line.split('|')
                val id = parts[0]
                val src = parts[1]
                val title = parts[2]
                val url = parts[3]
                if (!seenIds.add(id)) continue
                added++
                addVideoRow(id, src, title, url)
            }
            status.text = "Найдено роликов: ${seenIds.size}" + (if (added == 0) " · новых нет" else "")
        }
    }

    private fun addVideoRow(id: String, src: String, title: String, url: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 8, 12, 8)
        }

        val thumb = ImageView(this)
        val lp = LinearLayout.LayoutParams(dp(110), dp(64))
        lp.setMargins(0, 0, dp(10), 0)
        thumb.layoutParams = lp
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.setBackgroundColor(Color.parseColor("#1E2026"))
        val thumbUrl = if (id.length == 11) "https://i.ytimg.com/vi/$id/mqdefault.jpg" else ""
        if (thumbUrl.isNotBlank()) {
            Thread({
                val cached = synchronized(thumbCache) { thumbCache[thumbUrl] }
                val bmp = cached ?: loadBitmap(thumbUrl)
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
            text = title
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 3
        })
        col.addView(TextView(this).apply {
            text = src + " · " + url.substringAfter("://").take(42)
            setTextColor(Color.parseColor("#8AB4F8"))
            textSize = 10f
            maxLines = 1
        })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        buttons.addView(small("▶ Смотреть") { playInline(url) })
        buttons.addView(small("🌐 Открыть") {
            try {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            } catch (_: Throwable) {
            }
        })
        buttons.addView(small("⬇ Скачать") {
            // честно: у этих площадок нет прямой ссылки без разбора потоков —
            // открываем страницу в браузере приложения, где есть кнопка «Скачать видео»
            try {
                startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url)
                    ).setPackage(packageName)
                )
                Toast.makeText(
                    this,
                    "Открыл страницу в браузере приложения — там кнопка ⬇ «Скачать видео»",
                    Toast.LENGTH_LONG
                ).show()
            } catch (_: Throwable) {
                Toast.makeText(this, "Не удалось открыть страницу", Toast.LENGTH_SHORT).show()
            }
        })
        buttons.addView(small("📋 Ссылка") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("video", url))
            Toast.makeText(this, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
        })
        col.addView(buttons)
        row.addView(col)

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#23252B"))
        }
        resultsBox.addView(row)
        resultsBox.addView(divider)
    }

    private fun small(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 10f
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun loadBitmap(url: String): Bitmap? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", MOBILE_UA)
            }
            if (conn.responseCode in 200..299) conn.inputStream.use { BitmapFactory.decodeStream(it) } else null
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0 Mobile Safari/537.36"
    }
}
