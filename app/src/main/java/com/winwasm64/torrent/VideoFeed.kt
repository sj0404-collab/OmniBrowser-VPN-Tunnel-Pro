package com.winwasm64.torrent

import com.winwasm64.ui.browser.NetDirect
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v4.71.0: лента видео для отдельной вкладки «Видео» (внешний вид как у обычного видеоприложения).
 *
 * Раньше поиск видео жил внутри вкладки «Торренты» и был строкой текста с кнопками. Теперь это
 * самостоятельный раздел: обложки, длительность, автор, просмотры, дата — как в обычном клиенте.
 *
 * Источники:
 *  • YouTube — внутренний JSON-эндпоинт (тот же, что у [YouTubeSearch]), без ключей;
 *  • RuTube — публичный поиск `/api/search/video/` (может отвечать пусто из-за гео-фильтра,
 *    тогда честно сообщаем и предлагаем открыть сайт/включить VPN);
 *  • VK Видео — открытого поиска без входа нет, поэтому возвращаем подсказку.
 */
object VideoFeed {

    const val SRC_YOUTUBE = "YouTube"
    const val SRC_RUTUBE = "RuTube"
    const val SRC_VK = "VK"

    data class Clip(
        val id: String,
        /** video | playlist | channel */
        val kind: String,
        val title: String,
        val author: String,
        val lengthText: String,
        val viewsText: String,
        val publishedText: String,
        val thumb: String,
        val url: String,
        val live: Boolean = false,
        val source: String = SRC_YOUTUBE,
        /** числовые поля — для сортировки «новые / популярные / старые» */
        val views: Long = 0L,
        val days: Int = 0,
        /** длительность в секундах — для фильтров и сортировки по длительности */
        val lengthSec: Int = 0
    )

    const val CLIP_VIDEO = "video"
    const val CLIP_PLAYLIST = "playlist"
    const val CLIP_CHANNEL = "channel"

    data class Page(
        val clips: List<Clip> = emptyList(),
        val continuation: String? = null,
        /** Человеческое пояснение, если источник ничего не отдал. */
        val note: String? = null
    )

    /* -------------------------- кэш выдачи -------------------------- *
     * v4.75.0: повторный поиск того же запроса отдаётся мгновенно (10 минут).
     */
    private val cache = LinkedHashMap<String, Pair<Long, Page>>()
    private const val CACHE_TTL = 10 * 60_000L

    fun cached(source: String, query: String): Page? {
        val key = source + "|" + query.trim().lowercase()
        val hit = cache[key] ?: return null
        if (System.currentTimeMillis() - hit.first > CACHE_TTL) { cache.remove(key); return null }
        return hit.second
    }

    private fun put(source: String, query: String, page: Page): Page {
        if (page.clips.isNotEmpty()) {
            cache[source + "|" + query.trim().lowercase()] = System.currentTimeMillis() to page
            if (cache.size > 24) cache.remove(cache.keys.first())
        }
        return page
    }

    /** VK и RuTube ищем во встроенном браузере: только он проходит без входа и без капчи. */
    fun scrapeUrl(source: String, query: String): String = when (source) {
        // v4.82.0: та же страница, что открывает человек в браузере (старый m.vk.com выдачу не отдаёт)
        SRC_VK -> "https://m.vkvideo.ru/?q=" + enc(query)
        SRC_RUTUBE -> "https://rutube.ru/search/?query=" + enc(query)
        else -> "https://m.youtube.com/results?search_query=" + enc(query)
    }

    /**
     * v4.83.0: довести карточку из индекса до нормального вида — взять настоящее название, канал,
     * длительность и обложку со страницы площадки (без ключей и без входа).
     */
    fun enrich(clip: Clip): Clip {
        if (clip.source == SRC_VK) {
            val m = Regex("""(?:video|clip)(-?\d+)_(\d+)""").find(clip.url) ?: return clip
            val (code, text) = try {
                NetDirect.requestText(
                    "https://vk.com/video_ext.php?oid=${m.groupValues[1]}&id=${m.groupValues[2]}&hd=2",
                    mapOf("User-Agent" to UA_WEB_YT, "Accept-Language" to "ru", "Referer" to "https://vk.com/"),
                    timeoutMs = 9_000
                )
            } catch (_: Throwable) {
                return clip
            }
            if (code !in 200..299) return clip
            val title = listOf("md_title", "video_title", "title").firstNotNullOfOrNull { k ->
                Regex(""""$k"\s*:\s*"((?:[^"\\]|\\.)+)"""").find(text)?.groupValues?.get(1)
                    ?.let { unescapeJs(decodeUnicode(it)) }?.trim()?.takeIf { it.isNotBlank() }
            }.orEmpty()
            val author = listOf("md_author", "author", "video_author").firstNotNullOfOrNull { k ->
                Regex(""""$k"\s*:\s*"((?:[^"\\]|\\.)+)"""").find(text)?.groupValues?.get(1)
                    ?.let { unescapeJs(decodeUnicode(it)) }?.trim()?.takeIf { it.isNotBlank() }
            }.orEmpty()
            val jpg = Regex(""""jpg"\s*:\s*"(https:[^"]+)"""").find(text)?.groupValues?.get(1)?.replace("\\/", "/").orEmpty()
            val dur = Regex(""""duration"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return clip.copy(
                title = title.ifBlank { clip.title }.take(150),
                author = author.ifBlank { clip.author },
                thumb = jpg.ifBlank { clip.thumb },
                lengthText = if (dur > 0) String.format(Locale.US, "%d:%02d", dur / 60, dur % 60) else clip.lengthText,
                lengthSec = if (dur > 0) dur else clip.lengthSec
            )
        }
        if (clip.source == SRC_RUTUBE) {
            val id = Regex("""/video/([0-9a-f]{6,})""", RegexOption.IGNORE_CASE).find(clip.url)?.groupValues?.get(1) ?: return clip
            val (code, text) = try {
                NetDirect.requestText(
                    "https://rutube.ru/api/video/$id/",
                    mapOf("User-Agent" to UA_WEB_YT, "Accept" to "application/json", "Referer" to "https://rutube.ru/"),
                    timeoutMs = 9_000
                )
            } catch (_: Throwable) {
                return clip
            }
            if (code !in 200..299) return clip
            return try {
                val o = JSONObject(text)
                val dur = o.optInt("duration", 0)
                clip.copy(
                    title = o.optString("title").ifBlank { clip.title }.take(150),
                    author = o.optJSONObject("author")?.optString("name").orEmpty().ifBlank { clip.author },
                    thumb = o.optString("thumbnail_url").ifBlank { clip.thumb },
                    lengthText = if (dur > 0) String.format(Locale.US, "%d:%02d", dur / 60, dur % 60) else clip.lengthText,
                    lengthSec = if (dur > 0) dur else clip.lengthSec
                )
            } catch (_: Throwable) {
                clip
            }
        }
        if (clip.thumb.isBlank()) return clip.copy(thumb = thumbFor(clip.url))
        return clip
    }

    private fun unescapeJs(s: String): String = s
        .replace("\\/", "/").replace("\\\"", "\"").replace("\\n", " ").replace("\\\\", "\\")

    /**
     * v4.82.0: площадки (VK, RuTube) — это SPA: данные приходят в JSON по XHR/fetch, а не в HTML.
     * Поэтому вешаем хук на fetch и XMLHttpRequest и складываем ответы в window.__omni.
     * Из них получаются точные названия, обложки, длительности, просмотры и дата — как в оригинале.
     */
    fun jsonHookJs(): String = """(function(){try{
if(window.__omniInstalled)return 'ok';window.__omniInstalled=1;window.__omni=[];
function push(u,b){try{
  if(!b||b.length<200)return;
  u=String(u||'');if(!/video|search|feed|catalog|results/i.test(u))return;
  if(b.indexOf('"title"')<0)return;
  window.__omni.push({u:u.slice(0,200),b:b.slice(0,600000)});
  if(window.__omni.length>6)window.__omni.shift();
}catch(e){}}
var of=window.fetch;
if(of){window.fetch=function(){var u='';try{u=(arguments[0]&&arguments[0].url)||arguments[0]||'';}catch(e){}
  var p=of.apply(this,arguments);
  try{p.then(function(r){try{r.clone().text().then(function(t){push(u,t);});}catch(e){}}).catch(function(){});}catch(e){}
  return p;};}
var oo=XMLHttpRequest.prototype.open, os=XMLHttpRequest.prototype.send;
XMLHttpRequest.prototype.open=function(m,u){try{this.__omniU=u;}catch(e){}return oo.apply(this,arguments);};
XMLHttpRequest.prototype.send=function(){var x=this;
  try{x.addEventListener('load',function(){try{push(x.__omniU,x.responseText);}catch(e){}});}catch(e){}
  return os.apply(this,arguments);};
return 'ok';}catch(e){return 'err';}})()"""

    /** Разобрать накопленные JSON-ответы площадки в карточки. */
    fun parsePageJson(raw: String?, source: String): List<Clip> {
        if (raw.isNullOrBlank()) return emptyList()
        val text = try {
            (org.json.JSONTokener(raw).nextValue() as? String) ?: raw
        } catch (_: Throwable) {
            raw
        }
        val arr = try { org.json.JSONArray(text) } catch (_: Throwable) { return emptyList() }
        val out = LinkedHashMap<String, Clip>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val body = o.optString("b")
            if (body.isBlank()) continue
            val root = try { org.json.JSONTokener(body).nextValue() } catch (_: Throwable) { continue }
            walkVideos(root, source) { clip -> if (!out.containsKey(keyOf(clip))) out[keyOf(clip)] = clip }
            if (out.size > 120) break
        }
        return out.values.toList()
    }

    private fun walkVideos(node: Any?, source: String, hit: (Clip) -> Unit) {
        when (node) {
            is JSONObject -> {
                clipFromJson(node, source)?.let(hit)
                for (k in node.keys()) walkVideos(node.opt(k), source, hit)
            }
            is org.json.JSONArray -> for (i in 0 until node.length()) walkVideos(node.opt(i), source, hit)
            else -> {}
        }
    }

    /** Узнать в объекте видео площадки (VK/RuTube) и превратить в карточку. */
    private fun clipFromJson(o: JSONObject, source: String): Clip? {
        // ---------- VK ----------
        if (o.has("owner_id") && o.has("id") && o.has("title")) {
            val id = o.optLong("id")
            val owner = o.optLong("owner_id")
            if (id == 0L || owner == 0L) return null
            val title = o.optString("title").trim()
            if (title.length < 3) return null
            val dur = o.optInt("duration", 0)
            val views = if (o.has("views_count")) o.optLong("views_count", 0L) else o.optLong("views", 0L)
            val date = o.optLong("date", o.optLong("added", 0L))
            var thumb = ""
            val img = o.optJSONArray("image") ?: o.optJSONArray("images") ?: o.optJSONArray("thumbs")
            if (img != null && img.length() > 0) {
                thumb = img.optJSONObject(img.length() - 1)?.optString("url").orEmpty()
            }
            if (thumb.isBlank()) thumb = o.optString("photo_800").ifBlank { o.optString("photo_320") }
            if (thumb.isBlank()) o.optJSONObject("first_frame_320")?.optString("url")?.let { thumb = it }
            if (thumb.isBlank()) o.optJSONObject("first_frame_160")?.optString("url")?.let { thumb = it }
            val ageDays = if (date > 0) ((System.currentTimeMillis() / 1000 - date) / 86400).toInt().coerceAtLeast(0) else 0
            return Clip(
                id = "${owner}_$id",
                kind = CLIP_VIDEO,
                title = title.take(150),
                author = o.optString("owner_name").takeIf { it.length in 2..60 }
                    ?: o.optString("author").takeIf { it.length in 2..60 } ?: "",
                lengthText = if (dur > 0) String.format(Locale.US, "%d:%02d", dur / 60, dur % 60) else "",
                viewsText = if (views > 0) fmtCount(views) + " просмотров" else "",
                publishedText = if (date > 0) ageDays.toString() + " дн. назад" else "",
                thumb = thumb,
                url = "https://vk.com/video${owner}_$id",
                source = SRC_VK,
                views = views,
                days = ageDays,
                lengthSec = dur
            )
        }
        // ---------- RuTube ----------
        if (source == SRC_RUTUBE && o.has("thumbnail_url") && o.has("title") && o.has("id")) {
            val id = o.optString("id")
            if (id.length < 8) return null
            val dur = o.optInt("duration", 0)
            val hits = o.optLong("hits", 0L)
            return Clip(
                id = id,
                kind = CLIP_VIDEO,
                title = o.optString("title").trim().take(150),
                author = o.optJSONObject("author")?.optString("name").orEmpty(),
                lengthText = if (dur > 0) String.format(Locale.US, "%d:%02d", dur / 60, dur % 60) else "",
                viewsText = if (hits > 0) fmtCount(hits) + " просмотров" else "",
                publishedText = "",
                thumb = o.optString("thumbnail_url"),
                url = "https://rutube.ru/video/$id/",
                source = SRC_RUTUBE,
                views = hits,
                lengthSec = dur
            )
        }
        return null
    }

    private fun fmtCount(n: Long): String = when {
        n >= 1_000_000 -> String.format(Locale.US, "%.1f млн", n / 1_000_000.0)
        n >= 1_000 -> String.format(Locale.US, "%.1f тыс.", n / 1_000.0)
        else -> n.toString()
    }

    /**
     * v4.76.0: JS взят с проверенной схемы (именно она работает у VK/RuTube): строгий шаблон ссылки
     * на видео, отсев служебных подписей, заголовок ищется в блоке, вместе с ним снимается весь текст
     * блока — оттуда потом берём канал, площадку, просмотры и дату, как в обычном клиенте.
     */
    fun scrapeJs(source: String): String = """(function(){try{
function clean(t){return t?t.replace(/\s+/g,' ').trim():'';}
function isVideoLink(h){
  if(/rutube\.ru\/video\/[0-9a-f]{8,}/i.test(h))return true;
  if(/vk(video)?\.(com|ru)\/video-?\d+_\d+/i.test(h))return true;
  if(/vk\.com\/clip-?\d+_\d+/i.test(h))return true;
  if(/youtube\.com\/(watch|shorts)/i.test(h)||/youtu\.be\//i.test(h))return true;
  return false;}
function badTitle(t){
  if(!t||t.length<4)return true;
  if(/^club\d+$/i.test(t))return true;
  if(/^[\d\s:.,]+$/.test(t))return true;
  if(/^(на главную|поиск|видео|музыка|клипы|главная|ещё|показать все|все видео|vk видео|войти|вход|войдите|рекомендации|подписки|трансляции|моя страница)$/i.test(t))return true;
  return false;}
var out=[],nodes=document.querySelectorAll('a[href]');
for(var i=0;i<nodes.length;i++){
  var a=nodes[i],h=a.href||'';
  if(!isVideoLink(h))continue;
  var box=a.closest('li,article,div[class*="video"],div[class*="item"],div[class*="thumb"]')||a.parentElement;
  var t=clean(a.getAttribute('aria-label')||a.getAttribute('title')||'');
  if(badTitle(t)&&box){var hh=box.querySelector('h3,h2,h4,[class*=title]');if(hh)t=clean(hh.innerText||hh.getAttribute('aria-label')||'');}
  if(badTitle(t)){var im=((box&&box.querySelector('img[alt]'))||a.querySelector('img[alt]'));if(im)t=clean(im.getAttribute('alt'));}
  if(badTitle(t))continue;
  var img=box?box.querySelector('img'):a.querySelector('img');
  var snip=clean(box?box.innerText:'').slice(0,260);
  out.push({u:h,t:t,i:img?(img.src||img.getAttribute('data-src')||''):'',s:snip});
}
var seen={},res=[];
for(var j=0;j<out.length;j++){var k=out[j].u.split('#')[0];if(seen[k])continue;seen[k]=1;res.push(out[j]);}
return JSON.stringify(res.slice(0,200));
}catch(e){return JSON.stringify([]);}})()"""

    /**
     * Разбор того, что вернул встроенный браузер: заголовок, обложка, канал, площадка, просмотры,
     * «22 часа назад», длительность. Площадка определяется по самой ссылке — поэтому у карточки
     * всегда видно, откуда она (YouTube / RuTube / VK Видео), как в эталонном клиенте.
     */
    fun parseScraped(source: String, raw: String?, query: String = ""): List<Clip> {
        if (raw.isNullOrBlank() || raw == "null") return emptyList()
        val json = try {
            (org.json.JSONTokener(raw).nextValue() as? String) ?: raw
        } catch (_: Throwable) {
            raw
        }
        val arr = try {
            org.json.JSONArray(json)
        } catch (_: Throwable) {
            return emptyList()
        }
        val all = ArrayList<Clip>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("u").substringBefore('#').trim()
            if (url.isBlank() || !seen.add(url)) continue
            val rawTitle = o.optString("t").trim()
            val snap = o.optString("s").removePrefix(rawTitle).trim()
            if (rawTitle.length < 3) continue
            val platform = when {
                url.contains("rutube", true) -> SRC_RUTUBE
                url.contains("vk.com", true) || url.contains("vkvideo", true) -> SRC_VK
                url.contains("youtu", true) -> SRC_YOUTUBE
                else -> source
            }
            val (lenText, lenSec) = parseLength(rawTitle + " " + snap)
            val (viewsText, viewsNum) = parseViews(snap)
            val (ageText, ageDays) = parseAge(snap)
            val author = parseAuthor(snap, platform)
            all.add(
                Clip(
                    id = url.substringAfterLast('/').ifBlank { url.hashCode().toString() },
                    kind = CLIP_VIDEO,
                    title = rawTitle.take(150),
                    author = author,
                    lengthText = lenText,
                    viewsText = viewsText,
                    publishedText = ageText,
                    thumb = o.optString("i"),
                    url = url,
                    source = platform,
                    views = viewsNum,
                    days = ageDays,
                    lengthSec = lenSec
                )
            )
        }
        if (query.isBlank() || all.isEmpty()) return all
        // оставляем только про запрос (по заголовку и по тексту блока), но не до пустого экрана
        val keep = all.filter { Relevance.scoreFields(query, emptyList(), it.title, it.author) > 0 }
        return if (keep.size >= 3) keep else all
    }

    private fun parseLength(text: String): Pair<String, Int> {
        val m = Regex("""(?:^|[\s·|•])(\d{1,2}:\d{2}(?::\d{2})?)(?![\d:])""").find(text) ?: return "" to 0
        val t = m.groupValues[1]
        val parts = t.split(":").mapNotNull { it.toIntOrNull() }
        val sec = when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
        return t to sec
    }

    private fun parseViews(snap: String): Pair<String, Long> {
        val m = Regex(
            """([\d][\d\s.,]{0,12})\s*(тыс\.?|млн|млрд|K|M)?\s*(?:просмотр|view)""",
            RegexOption.IGNORE_CASE
        ).find(snap) ?: return "" to 0L
        val numRaw = m.groupValues[1].replace('\u00A0', ' ').replace(" ", "").replace(',', '.')
        val num = numRaw.toDoubleOrNull() ?: return "" to 0L
        val mult = when (m.groupValues[2].lowercase()) {
            "тыс", "тыс.", "k" -> 1_000.0
            "млн", "m" -> 1_000_000.0
            "млрд" -> 1_000_000_000.0
            else -> 1.0
        }
        return m.value.replaceFirstChar { it.uppercase() } to (num * mult).toLong()
    }

    private fun parseAge(snap: String): Pair<String, Int> {
        val m = Regex(
            """(\d+)\s*(сек|мин|час|день|дня|дней|недел|мес|год|года|лет)\w*\s*назад""",
            RegexOption.IGNORE_CASE
        ).find(snap) ?: return "" to 0
        val n = m.groupValues[1].toIntOrNull() ?: return "" to 0
        val days = when (m.groupValues[2].lowercase()) {
            "сек" -> 0
            "мин" -> 0
            "час" -> 1
            "день", "дня", "дней" -> n
            "недел" -> n * 7
            "мес" -> n * 30
            "год", "года", "лет" -> n * 365
            else -> 0
        }
        return m.value to days
    }

    /** Канал — сегмент перед названием площадки: «Идеальные рецепты • RuTube • 1,3 тыс. просмотров». */
    private fun parseAuthor(snap: String, platform: String): String {
        val parts = snap.split('•', '·', '|').map { it.trim() }.filter { it.isNotBlank() }
        for (i in parts.indices) {
            val p = parts[i].lowercase()
            val mark = when (platform) {
                SRC_RUTUBE -> p == "rutube"
                SRC_VK -> p.contains("вконтакте") || p.contains("vk видео") || p == "vk"
                else -> p.contains("youtube")
            }
            if (mark && i > 0) {
                val candidate = parts[i - 1]
                if (candidate.length in 2..48 && candidate.firstOrNull()?.isDigit() != true) return candidate
            }
        }
        return ""
    }

    /* ------------------- v4.78.0: быстрый поиск по индексу -------------------
     * VK и RuTube отдают собственную выдачу только настоящему браузеру, и это небыстро (десятки
     * секунд). Чтобы результат появлялся сразу, параллельно с браузером спрашиваем поисковый индекс
     * по своей же площадке (site:vkvideo.ru / site:rutube.ru) — это те же видео с площадки, но за 1–3 с.
     */
    fun searchIndexed(source: String, query: String): Page {
        if (query.isBlank()) return Page()
        // v4.83.0: по одному сайту за запрос — так поисковики отдают больше и точнее
        val sites = when (source) {
            SRC_VK -> listOf("vkvideo.ru", "vk.com/video")
            SRC_RUTUBE -> listOf("rutube.ru")
            else -> listOf("youtube.com")
        }
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
            "Accept-Language" to "ru,en;q=0.8",
            "Accept" to "text/html,application/xhtml+xml"
        )
        val all = LinkedHashMap<String, Clip>()
        for (site in sites) {
            val q = enc("site:$site $query")
            val engines = listOf(
                "https://search.brave.com/search?q=$q&source=web",
                "https://www.bing.com/search?q=$q&setlang=ru",
                "https://html.duckduckgo.com/html/?q=$q"
            )
            for (u in engines) {
                val pair = try {
                    NetDirect.requestText(u, headers, timeoutMs = 9_000)
                } catch (_: Throwable) {
                    continue
                }
                if (pair.first !in 200..299) continue
                val clips = parseEngineHtml(source, pair.second, query)
                for (c in clips) if (!all.containsKey(keyOf(c))) all[keyOf(c)] = c
                if (all.size >= 8) break
            }
            if (all.size >= 8) break
        }
        // v4.84.0: в ленту — только то, что действительно про запрос
        val only = filterRelevant(query, all.values.toList())
        val dropped = all.size - only.size
        return if (only.isEmpty()) Page(note = if (dropped > 0) "по индексу ничего по теме (отброшено $dropped не по теме)" else "")
        else Page(
            clips = only,
            note = "быстрый поиск по индексу: ${only.size} видео" + if (dropped > 0) " (отброшено $dropped не по теме)" else ""
        )
    }

    /**
     * v4.79.0: вытащить карточки площадки из HTML поисковика — БЕЗ мусора.
     * Раньше в заголовок попадал текст ссылки целиком («Vkvideo m.vkvideo.ru › video 225265420_456239214 …»),
     * поэтому в ленте были такие «названия». Теперь разбираем результат по блокам, чистим хлебные крошки,
     * отбрасываем строки-идентификаторы и служебные слова.
     */
    private fun parseEngineHtml(source: String, html: String, query: String): List<Clip> {
        val out = ArrayList<Clip>()
        val seen = HashSet<String>()
        val linkRe = Regex("""<a[^>]+href="(https?://[^"]+)"[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (m in linkRe.findAll(html)) {
            var href = m.groupValues[1]
            val ok = when (source) {
                SRC_VK -> Regex("""vk(video)?\.(com|ru)/(video|clip)-?\d+_\d+""").containsMatchIn(href)
                SRC_RUTUBE -> Regex("""rutube\.ru/video/[0-9a-f]{6,}""").containsMatchIn(href)
                else -> Regex("""(youtube\.com/watch\?v=|youtu\.be/)[A-Za-z0-9_-]{11}""").containsMatchIn(href)
            }
            if (!ok) continue
            if (href.contains("/l/") || href.contains("redirect")) {
                Regex("""[?&]u=([^&]+)""").find(href)?.groupValues?.get(1)?.let {
                    try { href = java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Throwable) {}
                }
            }
            if (!seen.add(href)) continue
            val title = cleanEngineTitle(stripTags(m.groupValues[2]))
            if (!looksLikeTitle(title, query)) continue
            val author = authorFromTitle(title)
            out.add(
                Clip(
                    id = if (source == SRC_YOUTUBE) (Regex("""(?:v=|youtu\.be/)([A-Za-z0-9_-]{11})""").find(href)?.groupValues?.get(1) ?: href)
                         else href.substringAfterLast('/').substringBefore('?'),
                    kind = CLIP_VIDEO,
                    title = title.take(150),
                    author = author,
                    lengthText = "",
                    viewsText = "",
                    publishedText = "",
                    thumb = if (source == SRC_YOUTUBE) headerPic(href) else "",
                    url = href,
                    source = source
                )
            )
            if (out.size >= 30) break
        }
        return out
    }

    /** Убрать хлебные крошки, адреса, id и служебные слова из заголовка выдачи. */
    private fun cleanEngineTitle(raw: String): String {
        var t = decodeUnicode(raw).replace("\u00A0", " ")
        // «Vkvideo m.vkvideo.ru › video 225265420_456239214 Продается всё…» → «Продается всё…»
        t = t.replace(Regex("""^.*?(v?k?video|vk|rutube|youtube)[^>»›]*[»›]\s*""", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("""\b\d{6,}_\d{4,}\b"""), " ")
        t = t.replace(Regex("""\b(www\.)?(m\.)?(vkvideo\.ru|vk\.com|rutube\.ru|youtube&#39;s|youtube\.com|youtu\.be)\b""", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("""\s*[›»]\s*"""), " ")
        t = t.replace(Regex("""\s*[-–—|]\s*(ВКонтакте|VK|RuTube|YouTube)\s*$""", RegexOption.IGNORE_CASE), " ")
        // v4.84.0: «video-», «video828472353_456240247», «clip123_456» в начале строки — это кусок адреса
        t = t.replace(Regex("""^\s*(video|clip)[\s\-_]*\d*_?\d*[\s:.,–—-]*""", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("""\s*[–—-]\s*Видео от\s+\d.*$""", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("""\s*[–—-]\s*Видео\s*$""", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("""\s*[–—-]?\s*ВКонтакте\s*$""", RegexOption.IGNORE_CASE), " ")
        return t.replace(Regex("""\s+"""), " ").trim().trim('.', '·', '-', '—', '|', ',')
    }

    /** v4.84.0: VK отдаёт кириллицу как \\u0424\\u043e\\u0440… — раскодировать. */
    fun decodeUnicode(src: String): String {
        if (!src.contains("\\u")) return src
        val sb = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val ch = src[i]
            if (ch == '\\' && i + 5 < src.length && (src[i + 1] == 'u' || src[i + 1] == 'U')) {
                val hex = src.substring(i + 2, i + 6)
                val code = hex.toIntOrNull(16)
                if (code != null) {
                    sb.append(code.toChar())
                    i += 6
                    continue
                }
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    /**
     * v4.84.0: жёсткий фильтр темы. Раньше по «LightFox манга» в ленту попадали «Форпост»,
     * «Ковбой Бибоп» и футбол — то есть возвращалось всё подряд. Теперь карточка остаётся, только
     * если слова запроса реально есть в названии, канале или адресе (с учётом словоформ).
     */
    fun relevant(query: String, c: Clip): Boolean {
        val tokens = query.lowercase().replace('ё', 'е')
            .split(Regex("""[^\p{L}\p{N}]+"""))
            .filter { it.length >= 3 }
            .distinct()
        if (tokens.isEmpty()) return true
        val hay = (c.title + " " + c.author + " " + c.url + " " + c.id)
            .lowercase().replace('ё', 'е').replace("-", "").replace(" ", "")
        var hit = 0
        for (t in tokens) {
            val probe = t.replace("-", "").take(5)
            if (hay.contains(probe) || hay.contains(t)) hit++
        }
        val need = if (tokens.size == 1) 1 else (tokens.size + 1) / 2
        return hit >= need
    }

    /** Оставить только то, что относится к запросу (без смены темы). */
    fun filterRelevant(query: String, clips: List<Clip>): List<Clip> =
        clips.filter { relevant(query, it) }.distinctBy { keyOf(it) }

    private fun looksLikeTitle(t: String, query: String): Boolean {
        if (t.length < 5) return false
        val letters = t.count { it.isLetter() }
        if (letters < 4) return false
        if (Regex("""^[\d\s_.,:–-]+$""").matches(t)) return false
        // v4.83.0: «video-», «clip», «VK Видео», «Смотреть видео» — это не название, а мусор из ссылки
        if (Regex("""^(видео|смотреть|смотрите|видео онлайн|vk ?видео|rutube|youtube|video|clip|watch)[\s\-–—|:.]*$""", RegexOption.IGNORE_CASE).matches(t)) return false
        if (Regex("""^(video|clip)[\s\-–—_.]?$""", RegexOption.IGNORE_CASE).matches(t)) return false
        if (Regex("""^(video|clip)[\s\-_]*\d""", RegexOption.IGNORE_CASE).containsMatchIn(t)) return false
        // заголовок должен быть хоть немного про запрос (иначе это «случайный» блок навигации)
        return Relevance.scoreFields(query, emptyList(), t) > 0 || t.split(' ').size >= 2
    }

    /** Канал из заголовка вида «Название — Канал» (если он есть). */
    private fun authorFromTitle(title: String): String {
        val parts = title.split(" — ", " – ", " | ", " · ").map { it.trim() }.filter { it.isNotBlank() }
        return if (parts.size > 1) parts.last().take(40) else ""
    }

    /**
     * v4.79.0: обложки для карточек из индекса. У YouTube они есть сразу, у VK и RuTube берём
     * из их же страниц (без ключей): RuTube — api/video/<id>/, VK — video_ext.php.
     */
    fun thumbFor(link: String): String {
        val u = link.trim()
        if (u.contains("youtu", true)) return headerPic(u)
        if (u.contains("rutube.ru/video/", true)) {
            val id = Regex("""/video/([0-9a-f]{6,})""", RegexOption.IGNORE_CASE).find(u)?.groupValues?.get(1) ?: return ""
            val (code, text) = try {
                NetDirect.requestText(
                    "https://rutube.ru/api/video/$id/",
                    mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) Chrome/126.0 Mobile Safari/537.36", "Accept" to "application/json"),
                    timeoutMs = 8_000
                )
            } catch (_: Throwable) {
                return ""
            }
            if (code !in 200..299) return ""
            return Regex(""""thumbnail_url"\s*:\s*"(https:[^"]+)"""").find(text)?.groupValues?.get(1) ?: ""
        }
        if (u.contains("vk.com/video", true) || u.contains("vkvideo.ru/video", true) || u.contains("vk.com/clip", true)) {
            val m = Regex("""(?:video|clip)(-?\d+)_(\d+)""").find(u) ?: return ""
            val oid = m.groupValues[1]
            val vid = m.groupValues[2]
            val (code, text) = try {
                NetDirect.requestText(
                    "https://vk.com/video_ext.php?oid=$oid&id=$vid",
                    mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) Chrome/126.0 Mobile Safari/537.36", "Accept-Language" to "ru"),
                    timeoutMs = 8_000
                )
            } catch (_: Throwable) {
                return ""
            }
            if (code !in 200..299) return ""
            return Regex(""""jpg"\s*:\s*"(https:[^"]+)"""").find(text)?.groupValues?.get(1)?.replace("\\/", "/")
                ?: Regex("""(https://sun[0-9-]*\.userapi\.com/[^"'\s]+)""").find(text)?.groupValues?.get(1)?.replace("\\/", "/")
                ?: ""
        }
        return ""
    }

    private fun headerPic(link: String): String {
        val id = Regex("""(?:v=|youtu\.be/)([A-Za-z0-9_-]{11})""").find(link)?.groupValues?.get(1) ?: return ""
        return "https://i.ytimg.com/vi/$id/mqdefault.jpg"
    }

    private fun stripTags(html: String): String = html
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("\\s+"), " ")

    /* ================= v4.80.0: настоящая страница канала =================
     * Раньше «канал» открывался как поиск по его названию — отсюда в канале оказывались чужие
     * видео. Теперь для YouTube берём id канала и его собственную выдачу (innertube /browse),
     * а для RuTube/VK фильтруем выданное по имени канала.
     */

    /** Устойчивый ключ видео: одна и та же карточка из индекса и из браузера схлопывается. */
    fun keyOf(c: Clip): String {
        val u = c.url
        Regex("""(?:v=|youtu\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})""").find(u)?.let { return "yt:" + it.groupValues[1] }
        Regex("""(?:video|clip)(-?\d+)_(\d+)""").find(u)?.let { return "vk:" + it.groupValues[1] + "_" + it.groupValues[2] }
        Regex("""rutube\.ru/video/([0-9a-f]{6,})""", RegexOption.IGNORE_CASE).find(u)?.let { return "rt:" + it.groupValues[1].lowercase() }
        return "u:" + u.substringBefore('#')
    }

    private fun isChannelMatch(name: String, c: Clip): Boolean {
        val key = com.winwasm64.torrent.VideoSubs.normalize(name)
        if (key.isBlank()) return true
        val a = com.winwasm64.torrent.VideoSubs.normalize(c.author)
        if (a.isNotBlank() && (a.contains(key) || key.contains(a))) return true
        val t = c.title.lowercase()
        return key.split(' ').filter { it.length > 3 }.any { t.contains(it) }
    }

    /** id канала YouTube: из адреса, а если его нет — по названию через поиск. */
    fun resolveYouTubeChannel(name: String, url: String): String? {
        Regex("""channel/(UC[A-Za-z0-9_-]{20,})""").find(url)?.let { return it.groupValues[1] }
        val q = name.trim()
        if (q.isBlank()) return null
        val body = JSONObject()
            .put("query", q)
            .put("context", JSONObject().put("client", ytClient()))
            .toString()
        val (code, text) = try {
            NetDirect.requestText(
                "https://www.youtube.com/youtubei/v1/search?prettyPrint=false",
                mapOf("Content-Type" to "application/json", "User-Agent" to UA_WEB_YT, "Accept-Language" to "ru"),
                "POST", body, timeoutMs = 12_000
            )
        } catch (_: Throwable) {
            return null
        }
        if (code !in 200..299) return null
        val found = ArrayList<JSONObject>()
        collectNodes(text, setOf("channelRenderer"), found)
        val key = com.winwasm64.torrent.VideoSubs.normalize(q)
        var best: String? = null
        var bestScore = -1
        for (o in found) {
            val id = o.optString("channelId")
            if (id.isBlank()) continue
            val t = com.winwasm64.torrent.VideoSubs.normalize(titleText(o.opt("title")))
            var score = 0
            if (t.isNotBlank() && (t.contains(key) || key.contains(t))) score += 5
            for (w in key.split(' ').filter { it.length > 3 }) if (t.contains(w)) score++
            if (score > bestScore) { bestScore = score; best = id }
        }
        return best
    }

    private fun ytClient(): JSONObject = JSONObject()
        .put("clientName", "WEB")
        .put("clientVersion", "2.20260101.00.00")
        .put("hl", "ru").put("gl", "RU")

    /** Рекурсивно собрать объекты по именам ключей (videoRenderer, continuationCommand…). */
    private fun collectNodes(text: String, keys: Set<String>, out: MutableList<JSONObject>) {
        val root = try { JSONTokener(text).nextValue() } catch (_: Throwable) { return }
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    for (k in node.keys()) {
                        val v = node.opt(k)
                        if (k in keys && v is JSONObject) out.add(v) else walk(v)
                    }
                }
                is org.json.JSONArray -> for (i in 0 until node.length()) walk(node.opt(i))
                else -> {}
            }
        }
        walk(root)
    }

    private fun titleText(o: Any?): String {
        val obj = o as? JSONObject ?: return ""
        obj.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        val runs = obj.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        return sb.toString()
    }

    /**
     * Видео канала. YouTube — собственная выдача канала (только его видео, с продолжением),
     * RuTube/VK — выдача площадки, отфильтрованная по имени канала.
     */
    fun channelPage(source: String, name: String, url: String, continuation: String? = null): Page {
        if (source == SRC_YOUTUBE) {
            val id = resolveYouTubeChannel(name, url)
                ?: return Page(note = "не нашёл канал «$name» на YouTube")
            val body = JSONObject()
                .put("context", JSONObject().put("client", ytClient()))
            if (continuation.isNullOrBlank()) body.put("browseId", id) else body.put("continuation", continuation)
            val (code, text) = try {
                NetDirect.requestText(
                    "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
                    mapOf("Content-Type" to "application/json", "User-Agent" to UA_WEB_YT, "Accept-Language" to "ru"),
                    "POST", body.toString(), timeoutMs = 14_000
                )
            } catch (e: Throwable) {
                return Page(note = "канал недоступен: " + (e.message ?: "сеть"))
            }
            if (code !in 200..299) return Page(note = "YouTube ответил $code")
            val nodes = ArrayList<JSONObject>()
            collectNodes(text, setOf("videoRenderer", "gridVideoRenderer", "compactVideoRenderer"), nodes)
            val clips = nodes.mapNotNull { vr ->
                val vid = vr.optString("videoId")
                if (vid.length != 11) return@mapNotNull null
                val len = vr.optJSONObject("lengthText")?.optString("simpleText").orEmpty()
                val views = vr.optJSONObject("viewCountText")?.optString("simpleText").orEmpty()
                val age = vr.optJSONObject("publishedTimeText")?.optString("simpleText").orEmpty()
                val thumbs = vr.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                val thumb = if (thumbs != null && thumbs.length() > 0)
                    thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty() else ""
                Clip(
                    id = vid,
                    kind = CLIP_VIDEO,
                    title = titleText(vr.opt("title")).ifBlank { "видео" },
                    author = name,
                    lengthText = len,
                    viewsText = views,
                    publishedText = age,
                    thumb = thumb.ifBlank { "https://i.ytimg.com/vi/$vid/mqdefault.jpg" },
                    url = "https://www.youtube.com/watch?v=$vid",
                    source = SRC_YOUTUBE,
                    views = parseViews("$views просмотров").second,
                    days = parseAge(age).second,
                    lengthSec = parseLength(len).second
                )
            }.distinctBy { it.id }
            val tokens = ArrayList<JSONObject>()
            collectNodes(text, setOf("continuationCommand"), tokens)
            val next = tokens.firstOrNull { it.optString("token").isNotBlank() }?.optString("token")
            return Page(clips = clips, continuation = next, note = if (clips.isEmpty()) "у канала нет видео в этой выдаче" else null)
        }
        // RuTube / VK: своя выдача браузером, но оставляем только карточки этого канала
        val page = search(source, name, 0, null)
        val only = page.clips.filter { isChannelMatch(name, it) }.distinctBy { keyOf(it) }
        return Page(clips = only, continuation = page.continuation, note = page.note)
    }

    private val UA_WEB_YT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    fun search(source: String, query: String, sort: Int = 0, continuation: String? = null): Page {
        if (continuation == null) cached(source, query)?.let { return it }
        val page = searchFresh(source, query, sort, continuation)
        // v4.80.0: одна и та же карточка не должна повторяться (индекс и браузер дают разные адреса)
        return put(source, query, page.copy(clips = page.clips.distinctBy { keyOf(it) }))
    }

    private fun searchFresh(source: String, query: String, sort: Int, continuation: String?): Page {
        val q = query.trim()
        if (q.isBlank()) return Page(note = "Введите запрос")
        return when (source) {
            SRC_RUTUBE -> rutube(q, continuation)
            // VK без входа отдаёт результаты только настоящему браузеру — его и использует экран «Видео»
            SRC_VK -> Page(
                note = "VK ищу во встроенном браузере — если пусто, включите VPN и повторите"
            )
            else -> youtube(q, sort, continuation)
        }
    }

    /* ------------------------------- YouTube ------------------------------- */

    private fun youtube(query: String, sort: Int, continuation: String?): Page {
        val page = try {
            YouTubeSearch.search(query, "Все", sort, "Любая", continuation)
        } catch (t: Throwable) {
            return Page(note = "YouTube не ответил: " + (t.message ?: "сеть").take(90))
        }
        val clips = page.items.map { it.toClip() }
        return Page(
            clips = clips,
            continuation = page.continuation,
            note = if (clips.isEmpty() && continuation == null) "По запросу «$query» ничего не нашлось" else null
        )
    }

    private fun YouTubeSearch.Item.toClip(): Clip {
        val thumbUrl = thumb.ifBlank {
            if (id.length == 11) "https://i.ytimg.com/vi/$id/mqdefault.jpg" else ""
        }
        return Clip(
            id = id,
            kind = if (kind == YouTubeSearch.KIND_PLAYLIST) "playlist"
            else if (kind == YouTubeSearch.KIND_CHANNEL) "channel" else "video",
            title = title,
            author = author,
            lengthText = lengthText,
            viewsText = viewsText,
            publishedText = publishedText,
            thumb = thumbUrl,
            url = url,
            live = extra == "live",
            source = SRC_YOUTUBE,
            views = views,
            days = daysAgo,
            lengthSec = parseLength(lengthText).second
        )
    }

    /* -------------------------------- RuTube ------------------------------- */

    private const val RUTUBE_PAGE = 20

    private fun rutube(query: String, continuation: String?): Page {
        val offset = continuation?.removePrefix("off:")?.toIntOrNull() ?: 0
        val url = "https://rutube.ru/api/search/video/?query=" + enc(query) +
            "&limit=$RUTUBE_PAGE&offset=$offset"
        val raw = try {
            // requestText возвращает пару (код ответа, тело)
            val (code, text) = com.winwasm64.ui.browser.NetDirect.requestText(
                url,
                mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36",
                    "Accept" to "application/json",
                    "Referer" to "https://rutube.ru/search/?query=" + enc(query)
                ),
                timeoutMs = 20_000
            )
            if (code !in 200..299) {
                return Page(note = "RuTube ответил кодом $code — обычно это гео-фильтр: включите VPN и повторите")
            }
            text
        } catch (t: Throwable) {
            return Page(note = "RuTube не ответил: " + (t.message ?: "сеть").take(90))
        }
        if (raw.isBlank()) return Page(note = "RuTube не ответил (пустой ответ)")
        val results = try {
            JSONObject(raw).optJSONArray("results")
        } catch (_: Throwable) {
            null
        } ?: return Page(note = "RuTube вернул непонятный ответ")
        val clips = ArrayList<Clip>()
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val title = o.optString("title").ifBlank { "видео $id" }
            val videoUrl = o.optString("video_url").ifBlank { if (id.isNotBlank()) "https://rutube.ru/video/$id/" else "" }
            if (videoUrl.isBlank()) continue
            val sec = o.optInt("duration", 0)
            val created = o.optLong("created_ts", 0L)
            clips.add(
                Clip(
                    id = id,
                    kind = "video",
                    title = title,
                    author = o.optJSONObject("author")?.optString("name").orEmpty(),
                    lengthText = if (sec > 0) String.format(Locale.US, "%d:%02d", sec / 60, sec % 60) else "",
                    viewsText = o.optLong("view_count", 0L).takeIf { it > 0 }
                        ?.let { "$it просмотров" }.orEmpty(),
                    publishedText = if (created > 0L) ago(created * 1000L) else "",
                    thumb = o.optString("thumbnail_url"),
                    url = videoUrl,
                    source = SRC_RUTUBE,
                    views = o.optLong("view_count", 0L),
                    days = if (created > 0L) ((System.currentTimeMillis() - created * 1000L) / 86_400_000L).toInt() else 0
                )
            )
        }
        val hasNext = try {
            JSONObject(raw).optBoolean("has_next", false)
        } catch (_: Throwable) {
            false
        }
        return Page(
            clips = clips,
            continuation = if (hasNext) "off:${offset + RUTUBE_PAGE}" else null,
            note = if (clips.isEmpty()) {
                if (offset == 0) "RuTube не отдал результаты по «$query» — часто это гео-фильтр: " +
                    "включите VPN (щит) и повторите" else null
            } else null
        )
    }

    /* ------------------------------- утилиты ------------------------------- */

    private fun enc(s: String): String = try {
        java.net.URLEncoder.encode(s, "UTF-8")
    } catch (_: Throwable) {
        s
    }

    private fun ago(ts: Long): String {
        val days = ((System.currentTimeMillis() - ts) / 86_400_000L).toInt()
        return when {
            days <= 0 -> "сегодня"
            days == 1 -> "вчера"
            days < 30 -> "$days дн. назад"
            days < 365 -> (days / 30).toString() + " мес. назад"
            else -> SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(ts))
        }
    }
}
