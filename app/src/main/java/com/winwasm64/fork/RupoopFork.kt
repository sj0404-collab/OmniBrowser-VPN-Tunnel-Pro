package com.winwasm64.fork

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/**
 * v4.86.0: второе приложение — настоящий RupoopFork (пакет com.santiago43rus.rupoop).
 *
 * Оно собрано из исходников проекта RupoopFork и лежит ВНУТРИ нашего APK (assets/rupofork.apk),
 * то есть в одном установочном файле едут два приложения. Дальше система ставит его штатно —
 * через PackageInstaller (или, если он недоступен, обычным интентом установщика).
 */
object RupoopFork {

    const val PKG = "com.santiago43rus.rupoop"
    const val ASSET = "rupofork.apk"

    /** Второе приложение уже установлено? */
    fun isInstalled(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(PKG, 0)
        true
    }.getOrDefault(false)

    fun installedVersion(ctx: Context): String = runCatching {
        val pi = ctx.packageManager.getPackageInfo(PKG, 0)
        @Suppress("DEPRECATION")
        (pi.versionName ?: "") + " (" + pi.versionCode + ")"
    }.getOrDefault("")

    /** Встроенный APK есть в установочном пакете? */
    fun hasBundle(ctx: Context): Boolean = runCatching {
        ctx.assets.open(ASSET).use { it.read() != -1 }
    }.getOrDefault(false)

    fun bundledBytes(ctx: Context): Long = runCatching {
        ctx.assets.openFd(ASSET).use { it.length }
    }.getOrElse {
        runCatching { ctx.assets.open(ASSET).use { it.available().toLong() } }.getOrDefault(0L)
    }

    /** Скопировать встроенный APK во временный файл (нужен и установщику, и запасному пути). */
    fun unpack(ctx: Context): File? = runCatching {
        val out = File(ctx.cacheDir, "rupofork.apk")
        ctx.assets.open(ASSET).use { input ->
            out.outputStream().use { input.copyTo(it, 1 shl 16) }
        }
        out
    }.getOrNull()

    /** Открыть уже установленный RupoopFork. */
    fun launch(ctx: Context): Boolean = runCatching {
        val i = ctx.packageManager.getLaunchIntentForPackage(PKG) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    }.getOrDefault(false)

    /**
     * Установить/обновить второе приложение из встроенного APK.
     * Возвращает текст ошибки или null, если сессия установки запущена.
     */
    fun install(ctx: Context): String? {
        val apk = unpack(ctx) ?: return "во встроенном пакете нет APK RupoopFork"
        // 1) штатный PackageInstaller: одна сессия, обновление поверх предыдущей версии
        val err = runCatching {
            val pm = ctx.packageManager
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(PKG)
            val sessionId = pm.packageInstaller.createSession(params)
            val session = pm.packageInstaller.openSession(sessionId)
            session.openWrite("rupofork", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 1 shl 16) }
                session.fsync(out)
            }
            val done = Intent(ctx, ForkInstallReceiver::class.java)
                .putExtra(ForkInstallReceiver.EXTRA_PKG, PKG)
            val flags = if (Build.VERSION.SDK_INT >= 31)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getBroadcast(ctx, sessionId, done, flags)
            session.commit(pi.intentSender)
            session.close()
            null as String?
        }.getOrElse { t -> t.message ?: t.javaClass.simpleName }

        if (err == null) return null

        // 2) запасной путь: обычный системный установщик через FileProvider
        val fallback = runCatching {
            val uri: Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", apk)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            null as String?
        }.getOrElse { t -> t.message ?: t.javaClass.simpleName }
        return fallback ?: if (err.isNotBlank()) null else err
    }
}
