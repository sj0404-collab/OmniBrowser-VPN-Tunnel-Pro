package com.winwasm64.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtensionEngineTest {

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, text) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(text.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun le(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())

    private fun crx3(zip: ByteArray, headerLen: Int = 37): ByteArray =
        "Cr24".toByteArray() + le(3) + le(headerLen) + ByteArray(headerLen) { 7 } + zip

    private fun crx2(zip: ByteArray, keyLen: Int = 20, sigLen: Int = 9): ByteArray =
        "Cr24".toByteArray() + le(2) + le(keyLen) + le(sigLen) + ByteArray(keyLen + sigLen) { 1 } + zip

    private fun tempDir(): File = Files.createTempDirectory("omni_ext_test").toFile()

    @Test
    fun crx3AndCrx2AndPlainZipAreUnpacked() {
        val zip = zipOf("manifest.json" to """{"manifest_version":3,"name":"A","version":"1"}""", "js/a.js" to "1;")
        for (pkg in listOf(crx3(zip), crx2(zip), zip)) {
            val dir = tempDir()
            assertEquals(2, CrxFormat.unpack(pkg, dir))
            assertTrue(File(dir, "manifest.json").isFile)
            assertEquals("1;", File(dir, "js/a.js").readText())
            dir.deleteRecursively()
        }
        assertEquals(12 + 37, CrxFormat.zipOffset(crx3(zip)))
        assertEquals(16 + 20 + 9, CrxFormat.zipOffset(crx2(zip)))
    }

    @Test
    fun zipSlipAndHtmlAndGarbageAreRejected() {
        val evil = zipOf("../evil.txt" to "x")
        try {
            CrxFormat.unpack(crx3(evil), tempDir())
            fail("zip-slip должен отклоняться")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Недопустимый путь"))
        }
        try {
            CrxFormat.zipOffset("<!DOCTYPE html><html>error</html>".toByteArray())
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("HTML"))
        }
        try {
            CrxFormat.zipOffset("Cr24".toByteArray() + le(3) + le(100000) + ByteArray(10))
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("CRX3"))
        }
    }

    @Test
    fun storeIdsAreExtractedFromAllUrlForms() {
        val id = "ddkjiahejlhfcafbddmgiahcphecmpfh"
        assertEquals(id, ChromeWebStore.extractId("https://chromewebstore.google.com/detail/ublock-origin-lite/$id"))
        assertEquals(id, ChromeWebStore.extractId("https://chrome.google.com/webstore/detail/ublock-origin-lite/$id?hl=ru"))
        assertEquals(id, ChromeWebStore.extractId("  $id  "))
        assertNull(ChromeWebStore.extractId("https://example.com/qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"))
        assertNull(ChromeWebStore.extractId("abc"))
        assertTrue(ChromeWebStore.isDetailPage("https://chromewebstore.google.com/detail/x/$id"))
        assertFalse(ChromeWebStore.isDetailPage("https://chromewebstore.google.com/category/extensions"))
        val url = ChromeWebStore.crxDownloadUrl(id)
        assertTrue(url.startsWith("https://clients2.google.com/service/update2/crx?response=redirect"))
        assertTrue(url.contains("x=id%3D$id%26installsource%3Dondemand%26uc"))
    }

    @Test
    fun extensionIdHasChromeAlphabet() {
        val id = CrxFormat.extensionIdFromPublicKey(ByteArray(294) { (it * 31).toByte() })
        assertEquals(32, id.length)
        assertTrue(id.all { it in 'a'..'p' })
        assertEquals(id, CrxFormat.extensionIdFromPublicKey(ByteArray(294) { (it * 31).toByte() }))
    }

    @Test
    fun matchPatternsFollowChromeRules() {
        assertTrue(MatchPattern.matches("*://*.youtube.com/*", "https://www.youtube.com/watch?v=1"))
        assertTrue(MatchPattern.matches("*://*.youtube.com/*", "http://youtube.com/"))
        assertFalse(MatchPattern.matches("*://*.youtube.com/*", "https://notyoutube.com/"))
        assertFalse(MatchPattern.matches("*://*.youtube.com/*", "ftp://www.youtube.com/"))
        assertTrue(MatchPattern.matches("https://example.com/path*", "https://example.com/path/sub?q=1"))
        assertFalse(MatchPattern.matches("https://example.com/path*", "https://example.com/other"))
        assertTrue(MatchPattern.matches("<all_urls>", "https://any.site/x"))
        assertTrue(MatchPattern.matches("*://*/*", "http://a.b/"))
        assertTrue(MatchPattern.matches("file:///*", "file:///sdcard/a.html"))
        assertTrue(MatchPattern.matches("*://WWW.Example.com/*", "https://www.example.com/A#frag"))
        assertNull(MatchPattern.toRegexSource("chrome://settings/*"))
        assertNull(MatchPattern.toRegexSource("https://ex*mple.com/*"))
        assertTrue(Regex(MatchPattern.globToRegexSource("*://*/watch?v=*")).matches("https://y.com/watch?v=abc"))
    }

    @Test
    fun manifestIsParsedWithLocalesAndAllParts() {
        val dir = tempDir()
        File(dir, "manifest.json").writeText(
            """
            {"manifest_version":3,"name":"__MSG_extName__","version":"2.5","default_locale":"en",
             "description":"__MSG_desc__","icons":{"16":"i16.png","48":"i48.png","128":"i128.png"},
             "action":{"default_popup":"popup.html","default_title":"T"},
             "options_ui":{"page":"opts.html"},
             "background":{"service_worker":"sw.js","type":"module"},
             "content_scripts":[{"matches":["*://*.youtube.com/*"],"js":["cs.js"],"css":["cs.css"],"run_at":"document_start","all_frames":true}],
             "permissions":["storage","declarativeNetRequest","https://api.example.com/*"],
             "host_permissions":["<all_urls>"],
             "web_accessible_resources":[{"resources":["img/*"],"matches":["<all_urls>"]}],
             "declarative_net_request":{"rule_resources":[{"id":"default","enabled":true,"path":"rules/a.json"},{"id":"extra","enabled":false,"path":"rules/b.json"}]}}
            """.trimIndent()
        )
        File(dir, "_locales/en").mkdirs()
        File(dir, "_locales/ru").mkdirs()
        File(dir, "_locales/en/messages.json").writeText("""{"extName":{"message":"Blocker"},"desc":{"message":"Blocks ads"}}""")
        File(dir, "_locales/ru/messages.json").writeText("\uFEFF" + """{"extName":{"message":"Блокировщик"}}""")
        val m = ManifestParser.parse(dir, "ru", "RU")
        assertEquals("Блокировщик", m.name)
        assertEquals("Blocks ads", m.description)
        assertEquals("ru", m.uiLocale)
        assertEquals("i48.png", m.iconPath)
        assertEquals("popup.html", m.popupPath)
        assertEquals("opts.html", m.optionsPath)
        assertEquals("sw.js", m.serviceWorker)
        assertTrue(m.serviceWorkerIsModule)
        assertTrue(m.hasBackground)
        assertEquals(1, m.contentScripts.size)
        assertEquals("document_start", m.contentScripts[0].runAt)
        assertTrue(m.contentScripts[0].allFrames)
        assertEquals(listOf("storage", "declarativeNetRequest"), m.permissions)
        assertTrue(m.hostPermissions.containsAll(listOf("https://api.example.com/*", "<all_urls>")))
        assertEquals(listOf("img/*"), m.webAccessibleResources)
        assertEquals(2, m.dnrRulesets.size)
        assertTrue(m.dnrRulesets[0].enabledByDefault)
        assertFalse(m.dnrRulesets[1].enabledByDefault)
        assertTrue(m.rawJson.contains("Блокировщик"))
        dir.deleteRecursively()
    }

    private fun req(url: String, type: String? = "script", initiator: String? = "news.example.org", method: String = "GET"): DnrRequest {
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
        return DnrRequest(url, url.lowercase(), host, method, type, initiator)
    }

    @Test
    fun dnrRulesBlockAllowRedirectWithPriorities() {
        val rules = """
        [
          {"id":1,"priority":1,"action":{"type":"block"},"condition":{"urlFilter":"||ads.example.com^"}},
          {"id":2,"priority":5,"action":{"type":"allow"},"condition":{"urlFilter":"||ads.example.com/ok/"}},
          {"id":3,"priority":1,"action":{"type":"block"},"condition":{"urlFilter":"/banner/","resourceTypes":["image"]}},
          {"id":4,"priority":1,"action":{"type":"block"},"condition":{"requestDomains":["tracker.net"],"domainType":"thirdParty"}},
          {"id":5,"priority":1,"action":{"type":"redirect","redirect":{"extensionPath":"/web_accessible_resources/noop.js"}},"condition":{"urlFilter":"||cdn.example.com/ga.js","resourceTypes":["script"]}},
          {"id":6,"priority":1,"action":{"type":"block"},"condition":{"regexFilter":"^https://[a-z]+\\.popads\\.net/","resourceTypes":["script","sub_frame"]}},
          {"id":7,"priority":1,"action":{"type":"modifyHeaders","responseHeaders":[]},"condition":{"urlFilter":"x"}},
          {"id":8,"priority":1,"action":{"type":"block"},"condition":{"urlFilter":"||evil.com^","excludedInitiatorDomains":["trusted.org"]}}
        ]
        """.trimIndent()
        val (parsed, skipped) = DnrEngine.parseRuleset("ext", rules)
        assertEquals(7, parsed.size)
        assertEquals(1, skipped)
        val idx = DnrIndex()
        parsed.forEach { idx.add(it) }

        assertEquals(1, idx.bestMatch(req("https://ads.example.com/x.js"))?.id)
        assertEquals(1, idx.bestMatch(req("https://sub.ads.example.com:8080/a"))?.id)
        assertNull(idx.bestMatch(req("https://ads.example.community/x.js")))
        assertEquals("allow", idx.bestMatch(req("https://ads.example.com/ok/a.js"))?.action)
        assertEquals(3, idx.bestMatch(req("https://img.site/banner/1.png", type = "image"))?.id)
        assertNull(idx.bestMatch(req("https://img.site/banner/1.js", type = "script")))
        assertEquals(4, idx.bestMatch(req("https://x.tracker.net/p"))?.id)
        assertNull(idx.bestMatch(req("https://x.tracker.net/p", initiator = "www.tracker.net")))
        val redirect = idx.bestMatch(req("https://cdn.example.com/ga.js"))
        assertEquals("redirect", redirect?.action)
        assertEquals("/web_accessible_resources/noop.js", redirect?.redirectExtensionPath)
        assertEquals(6, idx.bestMatch(req("https://abc.popads.net/s.js"))?.id)
        assertNull(idx.bestMatch(req("https://abc.popads.net/s.js", type = "image")))
        assertEquals(8, idx.bestMatch(req("https://evil.com/a"))?.id)
        assertNull(idx.bestMatch(req("https://evil.com/a", initiator = "www.trusted.org")))
        // Правило без resourceTypes не действует на main_frame (как в Chrome)
        assertNull(idx.bestMatch(req("https://ads.example.com/", type = "main_frame", initiator = null)))
    }

    @Test
    fun dnrUrlFilterSyntax() {
        val r = Regex(DnrEngine.urlFilterToRegex("||example.com^"))
        assertTrue(r.containsMatchIn("https://example.com/"))
        assertTrue(r.containsMatchIn("https://a.b.example.com/x"))
        assertTrue(r.containsMatchIn("https://example.com"))
        assertFalse(r.containsMatchIn("https://myexample.com/"))
        val anchored = Regex(DnrEngine.urlFilterToRegex("|https://x.org/a.js|"))
        assertTrue(anchored.containsMatchIn("https://x.org/a.js"))
        assertFalse(anchored.containsMatchIn("https://x.org/a.js?v=1"))
        assertEquals("example.co.uk", DnrEngine.registrableDomain("a.b.example.co.uk"))
        assertEquals("example.com", DnrEngine.registrableDomain("a.example.com"))
        assertEquals("image", DnrEngine.guessResourceType(false, "image/avif,image/webp,*/*", "/a"))
        assertEquals("script", DnrEngine.guessResourceType(false, "*/*", "/static/app.js"))
        assertEquals("main_frame", DnrEngine.guessResourceType(true, null, null))
        assertNotNull(DnrEngine.parseRule("e", org.json.JSONObject("""{"id":1,"action":{"type":"allowAllRequests"},"condition":{"requestDomains":["a.com"],"resourceTypes":["main_frame"]}}""")))
    }
}
