package com.winwasm64.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Proxy
import android.net.ProxyInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.ArrayMap

/**
 * Резервная настройка локального прокси для системного WebView на Android 5.0–7.1 (API 21–25),
 * когда на устройстве не обновлён Android System WebView и `WebViewFeature.PROXY_OVERRIDE` недоступен.
 *
 * Не трогает глобальные JVM `http.proxyHost` (чтобы собственные сокеты VPN/туннеля не уходили в петлю),
 * а доставляет `ProxyInfo` напрямую в зарегистрированный Chromium `ProxyChangeListener$ProxyReceiver`.
 */
object LegacyWebViewProxy {

    fun setProxy(context: Context, host: String, port: Int, onReady: (() -> Unit)? = null) {
        val app = context.applicationContext
        val runOnMain = Runnable {
            try {
                val proxyInfo = ProxyInfo.buildDirectProxy(host, port, listOf("localhost", "127.0.0.1"))
                deliverProxyChange(app, proxyInfo)
            } catch (_: Throwable) {
            } finally {
                onReady?.invoke()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runOnMain.run()
        } else {
            Handler(Looper.getMainLooper()).post(runOnMain)
        }
    }

    fun clearProxy(context: Context, onReady: (() -> Unit)? = null) {
        val app = context.applicationContext
        val runOnMain = Runnable {
            try {
                val empty = ProxyInfo.buildDirectProxy("", 0)
                deliverProxyChange(app, empty)
            } catch (_: Throwable) {
            } finally {
                onReady?.invoke()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runOnMain.run()
        } else {
            Handler(Looper.getMainLooper()).post(runOnMain)
        }
    }

    @Suppress("PrivateApi", "DiscouragedPrivateApi")
    private fun deliverProxyChange(appContext: Context, proxyInfo: ProxyInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return
        val appClass = Class.forName("android.app.Application")
        val loadedApkField = appClass.getDeclaredField("mLoadedApk").apply { isAccessible = true }
        val loadedApk = loadedApkField.get(appContext) ?: return
        val receiversField = loadedApk.javaClass.getDeclaredField("mReceivers").apply { isAccessible = true }
        val receiversMap = receiversField.get(loadedApk) as? ArrayMap<*, *> ?: return

        val intent = Intent(Proxy.PROXY_CHANGE_ACTION).apply {
            putExtra("android.intent.extra.PROXY_INFO", proxyInfo)
        }
        for (inner in receiversMap.values) {
            val receiverMap = inner as? ArrayMap<*, *> ?: continue
            for (rec in receiverMap.keys) {
                val br = rec as? BroadcastReceiver ?: continue
                val clsName = br.javaClass.name
                if (clsName.contains("ProxyChangeListener") || clsName.contains("ProxyReceiver")) {
                    try {
                        br.onReceive(appContext, intent)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }
}
