package com.winwasm64.ui.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.winwasm64.vpn.VpnLog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * v4.4.0: проверка обновлений в GitHub Releases (репозиторий, из которого собирается приложение).
 *
 * Раз в 6 часов (или вручную из настроек) читается `/releases/latest`; если тег новее установленной версии —
 * уведомление и строка в настройках «Доступна версия X». Нажатие открывает APK в браузере — он сохранит файл
 * в «Загрузки», и система предложит установить.
 */
object UpdateChecker {
    private const val TAG = "Обновление"
    private const val REPO = "sj0404-collab/OmniBrowser-VPN-Tunnel-Pro"
    private const val CHANNEL = "omni_updates"
    private const val NOTIF_ID = 7777
    private const val PREFS = "omni_browser_v21_prefs"

    class Info(val tag: String, val version: String, val apkUrl: String, val pageUrl: String, val notes: String, val sizeBytes: Long)

    @Volatile var latest: Info? = null
        private set
    @Volatile private var checking = false

    fun currentVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (_: Exception) { "0" }

    /** Сравнение версий «4.3.9» < «4.4.0»; true, если [a] новее [b]. */
    fun isNewer(a: String, b: String): Boolean {
        val pa = a.trimStart('v', 'V').split('.', '-').mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
        val pb = b.trimStart('v', 'V').split('.', '-').mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Автопроверка при запуске: не чаще раза в 6 часов и только если включена в настройках. */
    fun autoCheck(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("update_check_on", true)) return
        val last = prefs.getLong("update_last_check", 0L)
        if (System.currentTimeMillis() - last < 6 * 3600_000L) {
            // показать уже известный результат без сети
            val tag = prefs.getString("update_known_tag", "") ?: ""
            val url = prefs.getString("update_known_apk", "") ?: ""
            if (tag.isNotBlank() && url.isNotBlank() && isNewer(tag, currentVersion(ctx))) {
                latest = Info(tag, tag.trimStart('v'), url, "https://github.com/$REPO/releases/tag/$tag", prefs.getString("update_known_notes", "") ?: "", 0L)
            }
            return
        }
        check(ctx, manual = false, onResult = null)
    }

    /** Проверка сейчас. [onResult] вызывается в фоновом потоке: (новая версия или null, текст ошибки или null). */
    fun check(ctx: Context, manual: Boolean, onResult: ((Info?, String?) -> Unit)?) {
        if (checking) { onResult?.invoke(latest, null); return }
        checking = true
        thread(name = "omni-update", isDaemon = true) {
            val app = ctx.applicationContext
            try {
                val body = fetch("https://api.github.com/repos/$REPO/releases/latest")
                val o = JSONObject(body)
                val tag = o.optString("tag_name")
                if (tag.isBlank()) throw IllegalStateException("нет tag_name в ответе GitHub")
                var apk = ""
                var size = 0L
                val assets = o.optJSONArray("assets")
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); size = a.optLong("size"); break }
                }
                val notes = o.optString("body").take(1500)
                val cur = currentVersion(app)
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                prefs.edit().putLong("update_last_check", System.currentTimeMillis())
                    .putString("update_known_tag", tag).putString("update_known_apk", apk).putString("update_known_notes", notes).apply()
                if (apk.isNotBlank() && isNewer(tag, cur)) {
                    val info = Info(tag, tag.trimStart('v'), apk, o.optString("html_url"), notes, size)
                    latest = info
                    VpnLog.i(TAG, "доступна версия ${info.version} (установлена $cur), APK ${MediaGrabber.fmtSize(size)}")
                    if (prefs.getString("update_notified_tag", "") != tag || manual) {
                        prefs.edit().putString("update_notified_tag", tag).apply()
                        notify(app, info)
                    }
                    onResult?.invoke(info, null)
                } else {
                    latest = null
                    VpnLog.i(TAG, "установлена последняя версия ($cur, на GitHub $tag)")
                    onResult?.invoke(null, null)
                }
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                VpnLog.w(TAG, "проверка не удалась: $msg")
                onResult?.invoke(null, msg)
            } finally {
                checking = false
            }
        }
    }

    private fun fetch(url: String): String {
        // 1) напрямую (GitHub обычно доступен), 2) через туннель Browsec
        try {
            val c = URL(url).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "OmniBrowser")
            if (c.responseCode == 200) return c.inputStream.bufferedReader().readText()
            throw IllegalStateException("HTTP ${c.responseCode}")
        } catch (e: Exception) {
            return TunnelHttp().getText(url, mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "OmniBrowser"), 2 * 1024 * 1024)
        }
    }

    /** Интент на скачивание APK браузером приложения (он сохранит файл в «Загрузки», система предложит установить). */
    fun downloadIntent(ctx: Context, info: Info): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl)).setPackage(ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun notify(ctx: Context, info: Info) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Обновления приложения", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val pi = PendingIntent.getActivity(ctx, 7778, downloadIntent(ctx, info), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n: Notification = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Доступна версия ${info.version}")
                .setContentText("Нажмите, чтобы скачать APK (${MediaGrabber.fmtSize(info.sizeBytes).ifBlank { "~6 МБ" }})")
                .setStyle(NotificationCompat.BigTextStyle().bigText(info.notes.lines().filter { it.isNotBlank() }.take(6).joinToString("\n").ifBlank { "Нажмите, чтобы скачать APK" }))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID, n)
        } catch (_: Exception) {}
    }
}
