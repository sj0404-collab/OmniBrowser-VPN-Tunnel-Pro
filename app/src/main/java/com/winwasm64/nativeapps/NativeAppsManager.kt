package com.winwasm64.nativeapps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import java.io.File

data class NativeAppEntry(
    val appName: String,
    val packageName: String,
    val activityName: String,
    val category: String,
    val supportedFormats: List<String>,
    val iconBitmap: ImageBitmap?,
    val isSystemApp: Boolean
)

data class NativeFormatBinding(
    val formatKey: String,
    val formatTitle: String,
    val mimeType: String,
    val sampleDescription: String,
    val boundPackage: String?
)

class NativeAppsManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("omni_native_bindings", Context.MODE_PRIVATE)

    val defaultFormats = listOf(
        NativeFormatBinding(".mp4 / .mkv", "Видеофайлы и Стримы (MP4, MKV, WebM)", "video/*", "Открывать скачанные фильмы и видеопотоки в нативном видеоплеере", null),
        NativeFormatBinding(".mp3 / .flac", "Аудио и Музыка (MP3, FLAC, WAV)", "audio/*", "Воспроизводить торрент-альбомы и аудио в нативном плеере", null),
        NativeFormatBinding(".apk", "Пакеты Android (.APK)", "application/vnd.android.package-archive", "Устанавливать скачанные APK через системный установщик", null),
        NativeFormatBinding(".zip / .rar / .7z", "Архивы (ZIP, RAR, 7Z, ISO)", "application/zip", "Распаковывать архивы в ZArchiver / RAR / Проводнике", null),
        NativeFormatBinding(".exe / .msi", "Программы и Игры Windows (.EXE)", "application/x-msdos-program", "Передавать .exe напрямую в WinOS Pro x86/x64 или эмулятор", "com.winos.pro"),
        NativeFormatBinding(".pdf / .epub", "Книги и Документы (PDF, EPUB, DOCX)", "application/pdf", "Открывать документы в нативной читалке или Office", null),
        NativeFormatBinding("youtube.com", "Ссылки YouTube и Видео", "text/plain", "Передавать видео напрямую в нативный клиент или плеер", null)
    )

    fun getBindings(): List<NativeFormatBinding> {
        return defaultFormats.map { fmt ->
            val savedPkg = prefs.getString("bind_${fmt.formatKey}", fmt.boundPackage)
            fmt.copy(boundPackage = savedPkg)
        }
    }

    fun setBinding(formatKey: String, packageName: String?) {
        prefs.edit().apply {
            if (packageName.isNullOrBlank()) remove("bind_$formatKey")
            else putString("bind_$formatKey", packageName)
            apply()
        }
    }

    fun loadInstalledNativeApps(): List<NativeAppEntry> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = try {
            pm.queryIntentActivities(mainIntent, 0)
        } catch (_: Exception) {
            emptyList()
        }

        val videoPackages = queryPackagesForMime(pm, "video/*")
        val audioPackages = queryPackagesForMime(pm, "audio/*")
        val archivePackages = queryPackagesForMime(pm, "application/zip")
        val pdfPackages = queryPackagesForMime(pm, "application/pdf")
        val browserPackages = queryPackagesForScheme(pm, "https")

        val entries = mutableListOf<NativeAppEntry>()
        val seenPackages = mutableSetOf<String>()

        for (ri in resolveInfos) {
            val ai = ri.activityInfo ?: continue
            val pkg = ai.packageName ?: continue
            if (pkg == context.packageName || !seenPackages.add(pkg)) continue

            val label = try {
                ri.loadLabel(pm).toString().ifBlank { pkg }
            } catch (_: Exception) {
                pkg
            }

            val icon = try {
                drawableToImageBitmap(ri.loadIcon(pm))
            } catch (_: Exception) {
                null
            }

            val formats = mutableListOf<String>()
            val lowerPkg = pkg.lowercase()
            val lowerLabel = label.lowercase()

            if (pkg in videoPackages || lowerPkg.contains("vlc") || lowerPkg.contains("video") || lowerPkg.contains("player") || lowerPkg.contains("mpv") || lowerPkg.contains("mxtech")) {
                formats.add("🎬 Видео (MP4/MKV)")
            }
            if (pkg in audioPackages || lowerPkg.contains("music") || lowerPkg.contains("audio") || lowerPkg.contains("aimp") || lowerPkg.contains("spotify")) {
                formats.add("🎵 Аудио (MP3/FLAC)")
            }
            if (pkg in archivePackages || lowerPkg.contains("zarchiver") || lowerPkg.contains("rar") || lowerPkg.contains("file") || lowerPkg.contains("explorer") || lowerPkg.contains("amaze")) {
                formats.add("📦 Архивы и Файлы")
            }
            if (pkg in pdfPackages || lowerPkg.contains("pdf") || lowerPkg.contains("office") || lowerPkg.contains("word") || lowerPkg.contains("doc") || lowerPkg.contains("reader")) {
                formats.add("📄 Документы (PDF/DOCX)")
            }
            if (lowerPkg.contains("winos") || lowerPkg.contains("winlator") || lowerPkg.contains("box64") || lowerPkg.contains("wine") || lowerPkg.contains("dosbox") || lowerPkg.contains("termux")) {
                formats.add("🖥️ Запуск .EXE / ОС")
            }
            if (pkg in browserPackages || lowerPkg.contains("chrome") || lowerPkg.contains("firefox") || lowerPkg.contains("opera") || lowerPkg.contains("brave") || lowerPkg.contains("youtube") || lowerPkg.contains("telegram")) {
                formats.add("🌐 Сеть / Ссылки")
            }
            if (formats.isEmpty()) {
                formats.add("📱 Нативное приложение")
            }

            val primaryCategory = when {
                formats.any { it.contains(".EXE") } -> "🖥️ Эмуляторы и ОС (.EXE)"
                formats.any { it.contains("Видео") || it.contains("Аудио") } -> "🎬 Видео и Аудио плееры"
                formats.any { it.contains("Архивы") } -> "📦 Файлы и Архиваторы"
                formats.any { it.contains("Документы") } -> "📄 Офис и Документы"
                formats.any { it.contains("Сеть") } || lowerLabel.contains("youtube") || lowerLabel.contains("telegram") -> "🌐 Браузеры и Мессенджеры"
                else -> "📱 Все приложения устройства"
            }

            val isPriorityApp = pkg == "com.google.android.youtube" ||
                pkg == "com.android.vending" ||
                pkg.contains("youtube", ignoreCase = true) ||
                pkg.contains("telegram", ignoreCase = true) ||
                pkg.contains("ayugram", ignoreCase = true) ||
                pkg.contains("discord", ignoreCase = true) ||
                pkg.contains("tiktok", ignoreCase = true) ||
                pkg.contains("instagram", ignoreCase = true)

            val isSys = !isPriorityApp && ((ai.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)

            entries.add(
                NativeAppEntry(
                    appName = label,
                    packageName = pkg,
                    activityName = ai.name ?: "",
                    category = primaryCategory,
                    supportedFormats = formats,
                    iconBitmap = icon,
                    isSystemApp = isSys
                )
            )
        }

        // Гарантируем присутствие нативного YouTube и Google Play Маркет в самом верху списка, даже если Android скрывает их из queryIntentActivities
        val guaranteedApps = listOf(
            Triple("YouTube (Нативное приложение)", "com.google.android.youtube", "🎬 Видео и Аудио плееры"),
            Triple("Google Play Маркет (Нативное приложение)", "com.android.vending", "🌐 Браузеры и Мессенджеры"),
            Triple("YouTube Music (Нативное приложение)", "com.google.android.apps.youtube.music", "🎬 Видео и Аудио плееры"),
            Triple("Telegram (Нативное приложение)", "org.telegram.messenger", "🌐 Браузеры и Мессенджеры")
        )
        for ((title, gPkg, cat) in guaranteedApps) {
            if (entries.none { it.packageName == gPkg }) {
                val icon = try {
                    drawableToImageBitmap(pm.getApplicationIcon(gPkg))
                } catch (_: Exception) {
                    null
                }
                entries.add(
                    0,
                    NativeAppEntry(
                        appName = title,
                        packageName = gPkg,
                        activityName = "",
                        category = cat,
                        supportedFormats = listOf("📱 Нативное приложение", "🌐 Сеть / Ссылки"),
                        iconBitmap = icon,
                        isSystemApp = false
                    )
                )
            }
        }

        return entries.sortedWith(
            compareBy<NativeAppEntry> {
                when (it.packageName) {
                    "com.google.android.youtube" -> 0
                    "com.android.vending" -> 1
                    else -> if (it.isSystemApp) 3 else 2
                }
            }.thenBy { it.appName.lowercase() }
        )
    }

    private fun queryPackagesForMime(pm: PackageManager, mime: String): Set<String> {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply { type = mime }
            pm.queryIntentActivities(intent, 0).mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun queryPackagesForScheme(pm: PackageManager, scheme: String): Set<String> {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://example.com"))
            pm.queryIntentActivities(intent, 0).mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun isTelegramPackage(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return lower.contains("telegram") ||
            lower.contains("ayugram") ||
            lower.contains("nekogram") ||
            lower.contains("challegram") ||
            lower.contains("plusmessenger")
    }

    /**
     * Подключает встроенный движок Telegram (tgnet DC1..DC5) к выделенному локальному SOCKS5-порту
     * этого приложения (`tg://socks?server=127.0.0.1&port=<localPort>`), чтобы видео (DC2),
     * кружочки, голосовые и файлы в Telegram скачивались без бесконечного «Соединение...».
     */
    fun connectTelegramBuiltInProxy(packageName: String): Int {
        com.winwasm64.vpn.BrowsecLocalProxyServer.ensureRunning()
        val profile = com.winwasm64.vpn.BrowsecLocalProxyServer.getOrCreateAppProfile(packageName)
        val port = profile.localPort.takeIf { it > 0 } ?: com.winwasm64.vpn.BrowsecLocalProxyServer.activePort
        try {
            val tgUri = Uri.parse("tg://socks?server=127.0.0.1&port=$port")
            val intent = Intent(Intent.ACTION_VIEW, tgUri).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallbackUri = Uri.parse("https://t.me/socks?server=127.0.0.1&port=$port")
                val intent = Intent(Intent.ACTION_VIEW, fallbackUri).apply {
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
            }
        }
        return port
    }

    fun launchApp(packageName: String, useVpnTunnel: Boolean = false): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: when (packageName) {
                    "com.google.android.youtube" -> Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube://")).apply {
                        setPackage("com.google.android.youtube")
                    }
                    "com.android.vending" -> Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.youtube")).apply {
                        setPackage("com.android.vending")
                    }
                    else -> Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        setPackage(packageName)
                    }
                }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (useVpnTunnel) {
                com.winwasm64.vpn.BrowsecLocalProxyServer.ensureRunning()
                val profile = com.winwasm64.vpn.BrowsecLocalProxyServer.getOrCreateAppProfile(packageName)
                val dedicatedPort = profile.localPort.takeIf { it > 0 } ?: com.winwasm64.vpn.BrowsecLocalProxyServer.activePort
                launchIntent.putExtra("proxy_host", "127.0.0.1")
                launchIntent.putExtra("proxy_port", dedicatedPort)
                launchIntent.putExtra("http.proxyHost", "127.0.0.1")
                launchIntent.putExtra("http.proxyPort", dedicatedPort.toString())
            }
            context.startActivity(launchIntent)
            true
        } catch (_: Exception) {
            try {
                val fallback = when (packageName) {
                    "com.google.android.youtube" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")).apply {
                        setPackage("com.google.android.youtube")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    "com.android.vending" -> Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_APP_MARKET)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    else -> return false
                }
                context.startActivity(fallback)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun openFileInNativeApp(file: File, targetPackage: String? = null): Result<String> {
        if (!file.exists()) {
            return Result.failure(IllegalArgumentException("Файл не найден: ${file.absolutePath}"))
        }
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val ext = file.extension.lowercase()
            val mime = when (ext) {
                "apk" -> "application/vnd.android.package-archive"
                "mp4", "m4v" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "mp3" -> "audio/mpeg"
                "flac" -> "audio/flac"
                "wav" -> "audio/wav"
                "zip" -> "application/zip"
                "rar" -> "application/x-rar-compressed"
                "7z" -> "application/x-7z-compressed"
                "pdf" -> "application/pdf"
                "exe", "msi" -> "application/octet-stream"
                "torrent" -> "application/x-bittorrent"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            }

            val autoBoundPkg = targetPackage ?: findBoundPackageForExtension(ext)

            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!autoBoundPkg.isNullOrBlank()) {
                    setPackage(autoBoundPkg)
                }
            }

            try {
                context.startActivity(viewIntent)
                Result.success("Открыт ${file.name} (${mime})")
            } catch (_: Exception) {
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = Intent.createChooser(fallbackIntent, "Открыть ${file.name} в приложении").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
                Result.success("Открыт системный выбор приложения для ${file.name}")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun openUrlInNativeApp(url: String, targetPackage: String? = null): Result<String> {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!targetPackage.isNullOrBlank()) {
                    setPackage(targetPackage)
                }
            }
            context.startActivity(intent)
            Result.success("Ссылка передана в приложение")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun findBoundPackageForExtension(ext: String): String? {
        val bindings = getBindings()
        return when (ext) {
            "mp4", "mkv", "webm", "avi" -> bindings.find { it.formatKey.contains(".mp4") }?.boundPackage
            "mp3", "flac", "wav", "ogg" -> bindings.find { it.formatKey.contains(".mp3") }?.boundPackage
            "apk" -> bindings.find { it.formatKey == ".apk" }?.boundPackage
            "zip", "rar", "7z", "iso" -> bindings.find { it.formatKey.contains(".zip") }?.boundPackage
            "exe", "msi" -> bindings.find { it.formatKey.contains(".exe") }?.boundPackage
            "pdf", "epub", "docx" -> bindings.find { it.formatKey.contains(".pdf") }?.boundPackage
            else -> null
        }
    }

    private fun drawableToImageBitmap(drawable: Drawable): ImageBitmap {
        val width = drawable.intrinsicWidth.coerceIn(48, 128)
        val height = drawable.intrinsicHeight.coerceIn(48, 128)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap.asImageBitmap()
    }
}
