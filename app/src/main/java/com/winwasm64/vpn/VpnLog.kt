package com.winwasm64.vpn

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Журнал VPN: что включилось, какие приложения куда подключались, через какой профиль, ошибки прокси.
 * Кольцевой буфер в памяти + сохранение в «Загрузки/OmniBrowser-logs».
 */
object VpnLog {
    private const val MAX = 2000
    private val lines = ArrayDeque<String>()
    private val labels = ConcurrentHashMap<String, String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var appContext: Context? = null

    @Volatile
    var version: Int = 0
        private set

    fun init(ctx: Context) {
        if (appContext == null) appContext = ctx.applicationContext
    }

    fun i(tag: String, msg: String) = add("ℹ", tag, msg)
    fun ok(tag: String, msg: String) = add("✅", tag, msg)
    fun w(tag: String, msg: String) = add("⚠", tag, msg)
    fun e(tag: String, msg: String) = add("❌", tag, msg)

    /** Соединение приложения через VPN: куда, через какой профиль, сколько заняло, ошибка. */
    fun conn(pkg: String?, host: String, port: Int, profile: String, ms: Long, err: String?) {
        val who = label(pkg)
        val dur = if (ms >= 1000) String.format(java.util.Locale.US, "%.1f с", ms / 1000.0) else "$ms мс"
        if (err == null) add("📱", "Прил", "$who → $host:$port · профиль $profile · длилось $dur")
        else add("❌", "Прил", "$who → $host:$port · профиль $profile · ошибка: $err")
    }

    fun label(pkg: String?): String {
        if (pkg.isNullOrBlank()) return "приложение"
        labels[pkg]?.let { return it }
        val name = try {
            val pm = appContext!!.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
        labels[pkg] = name
        return name
    }

    fun appList(pkgs: Collection<String>): String {
        if (pkgs.isEmpty()) return "список пуст"
        val shown = pkgs.take(6).joinToString(", ") { label(it) }
        return if (pkgs.size > 6) "$shown и ещё ${pkgs.size - 6}" else shown
    }

    @Synchronized
    private fun add(level: String, tag: String, msg: String) {
        lines.addLast("${fmt.format(Date())} $level [$tag] $msg")
        while (lines.size > MAX) lines.removeFirst()
        version++
    }

    /** filter: null — всё, "err" — ошибки и предупреждения, иначе тег ("Прил", "Браузер", "Туннель", "Сервис"). */
    @Synchronized
    fun snapshot(filter: String?): List<String> = when (filter) {
        null -> lines.toList()
        "err" -> lines.filter { it.contains("❌") || it.contains("⚠") }
        else -> lines.filter { it.contains("[$filter]") }
    }

    @Synchronized
    fun clear() {
        lines.clear()
        version++
    }

    fun text(filter: String?): String = snapshot(filter).joinToString("\n")

    /** Сохраняет журнал в «Загрузки/OmniBrowser-logs». Возвращает путь для показа или null. */
    fun saveToDownloads(ctx: Context): String? {
        val name = "omnibrowser_vpn_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
        val data = ("OmniBrowser VPN — журнал\n" + text(null) + "\n").toByteArray(Charsets.UTF_8)
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OmniBrowser-logs")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) } ?: return null
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OmniBrowser-logs")
                dir.mkdirs()
                File(dir, name).writeBytes(data)
            }
            "Загрузки/OmniBrowser-logs/$name"
        } catch (_: Exception) {
            null
        }
    }
}
