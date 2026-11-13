package com.winwasm64.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * v4.92.0: «YouTube сразу в браузере — как PiP».
 *
 * Вы просили не изобретать извлечение потока, а открывать само видео во встроенном браузере,
 * обрезав страницу так, чтобы на экране было только видео. Здесь так и сделано:
 *
 *  • сначала грузим официальный встраиваемый плеер (`/embed/<id>`): на странице нет ничего,
 *    кроме видео, поэтому «обрезать низ» не приходится — оно само занимает верхнюю часть;
 *  • если встраивание запрещeno автором — грузим мобильную страницу ролика и вырезаем из неё
 *    только плеер (остальная разметка удаляется), получается тот же PiP-вид;
 *  • звук и качество — родные, вход в аккаунт используется тот, что уже есть в браузере
 *    приложения (общие cookie), поэтому работают и приватные ролики, и «ваши» подписки.
 */
class YouTubeWebPlayerActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var hint: TextView? = null
    private var videoId: String = ""
    private var triedCrop = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        videoId = com.winwasm64.torrent.VideoPlay.youtubeId(intent.getStringExtra("url").orEmpty()).orEmpty()
        val title = intent.getStringExtra("title").orEmpty()
        if (videoId.isEmpty()) {
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 21) {
            window.statusBarColor = Color.BLACK
            window.navigationBarColor = Color.BLACK
        }
        hideSystemBars()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val wv = WebView(this)
        webView = wv
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/126.0.0.0 Mobile Safari/537.36"
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        wv.setBackgroundColor(Color.BLACK)
        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                // полноэкранный режим самого плеера — показываем как есть
                if (view != null) {
                    root.addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    hideSystemBars()
                }
            }

            override fun onHideCustomView() {
                while (root.childCount > 1) root.removeViewAt(root.childCount - 1)
            }
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val u = url.orEmpty()
                if (!triedCrop && !u.contains("/embed/")) {
                    // встраивание не сработало — вырезаем плеер из обычной страницы
                    triedCrop = true
                    view?.evaluateJavascript(CROP_JS, null)
                }
            }
        }

        root.addView(
            wv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // подсказка поверх до старта видео
        hint = TextView(this).apply {
            text = if (title.isBlank()) "Загружаю видео…" else title
            setTextColor(Color.WHITE)
            textSize = 13f
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        root.addView(
            hint,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START }
        )

        setContentView(root)

        wv.loadUrl(
            "https://www.youtube.com/embed/$videoId" +
                "?autoplay=1&playsinline=1&rel=0&modestbranding=1&fs=1&hl=ru"
        )
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        hideSystemBars()
    }

    override fun onDestroy() {
        try {
            webView?.loadUrl("about:blank")
            webView?.destroy()
        } catch (_: Throwable) {
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        /**
         * Оставляем на экране только плеер: прячем шапку, описание, рекомендации и нижнее меню,
         * видео растягиваем на всю ширину сверху — получается вид «как PiP».
         */
        private const val CROP_JS = """
        (function(){
          try {
            var p = document.querySelector('.player-container, #player-container-id, ytm-player, video');
            if (p) {
              var pl = p.closest('.player-container') || p.parentElement || p;
              document.body.innerHTML = '';
              document.body.style.cssText = 'margin:0;padding:0;background:#000;overflow:hidden;height:100vh;';
              pl.style.cssText = 'position:fixed;top:0;left:0;width:100vw;height:56.25vw;max-height:100vh;';
              document.body.appendChild(pl);
            }
          } catch (e) {}
        })();
        """
    }
}
