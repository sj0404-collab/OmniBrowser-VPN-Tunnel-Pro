package com.winwasm64.ui

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * v4.10.0: User-Agent и подсказки клиента (UA Client Hints) «как у Chrome».
 * Системный WebView выдаёт себя строкой «; wv» + «Version/4.0» и брендом «Android WebView» в Sec-CH-UA /
 * navigator.userAgentData — сайты вроде GeForce NOW отвечают «браузер не поддерживается», а вход Google — disallowed_useragent.
 */
object BrowserUa {
    @Volatile private var cachedMobile: String? = null

    /** Мобильный UA WebView без признаков WebView (как Chrome для Android той же версии). */
    fun mobile(context: Context): String {
        cachedMobile?.let { return it }
        val def = try { WebSettings.getDefaultUserAgent(context) } catch (_: Throwable) {
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        }
        val ua = def.replace(Regex("; wv\\)"), ")").replace(Regex("\\s*Version/\\d+(\\.\\d+)*"), "")
            .replace(Regex("\\s{2,}"), " ").trim()
        cachedMobile = ua
        return ua
    }

    private fun chromeVersion(ua: String): Pair<String, String> {
        val m = Regex("Chrome/(\\d+)((?:\\.\\d+)*)").find(ua)
        val major = m?.groupValues?.get(1) ?: "131"
        val full = if (m != null && m.groupValues[2].isNotEmpty()) major + m.groupValues[2] else "$major.0.0.0"
        return major to full
    }

    /** Sec-CH-UA / navigator.userAgentData согласованно с UA: бренды Chromium + Google Chrome, без «Android WebView». */
    fun applyClientHints(wv: WebView, desktop: Boolean) {
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
            val (major, full) = chromeVersion(wv.settings.userAgentString ?: "")
            val brands = listOf(
                UserAgentMetadata.BrandVersion.Builder().setBrand("Chromium").setMajorVersion(major).setFullVersion(full).build(),
                UserAgentMetadata.BrandVersion.Builder().setBrand("Google Chrome").setMajorVersion(major).setFullVersion(full).build(),
                UserAgentMetadata.BrandVersion.Builder().setBrand("Not)A;Brand").setMajorVersion("99").setFullVersion("99.0.0.0").build()
            )
            val androidVer = Regex("Android (\\d+(?:\\.\\d+)*)").find(wv.settings.userAgentString ?: "")?.groupValues?.get(1) ?: android.os.Build.VERSION.RELEASE ?: "13"
            val md = UserAgentMetadata.Builder()
                .setBrandVersionList(brands)
                .setFullVersion(full)
                .setMobile(!desktop)
                .setPlatform(if (desktop) "Windows" else "Android")
                .setPlatformVersion(if (desktop) "15.0.0" else androidVer)
                .setArchitecture(if (desktop) "x86" else "arm")
                .setBitness(64)
                .setModel(if (desktop) "" else (android.os.Build.MODEL ?: ""))
                .setWow64(false)
                .build()
            WebSettingsCompat.setUserAgentMetadata(wv.settings, md)
        } catch (_: Throwable) {
        }
    }
}
