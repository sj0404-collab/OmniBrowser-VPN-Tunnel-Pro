package com.winwasm64

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * v4.2.7: ловим необработанные исключения, пишем стек в files/crash_last.txt, а при следующем запуске —
 * первые строки в журнал VPN (чтобы причину «приложение остановлено» было видно без logcat).
 */
object CrashCatcher {
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val file = File(app.filesDir, "crash_last.txt")
        try {
            if (file.isFile) {
                val text = file.readText()
                val head = text.lines().filter { it.isNotBlank() }.take(14).joinToString("\n")
                com.winwasm64.vpn.VpnLog.init(app)
                com.winwasm64.vpn.VpnLog.e("Сбой", "предыдущий запуск завершился аварийно:\n$head")
                file.renameTo(File(app.filesDir, "crash_prev.txt"))
            }
        } catch (_: Exception) {}
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        val mainThread = android.os.Looper.getMainLooper().thread
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val text = try {
                val sw = StringWriter()
                PrintWriter(sw).use { pw ->
                    pw.println("${java.util.Date()} поток ${t.name}")
                    e.printStackTrace(pw)
                }
                sw.toString()
            } catch (_: Exception) { e.toString() }
            if (t !== mainThread && e !is OutOfMemoryError) {
                // v4.2.8: ошибка в фоновом (сетевом) потоке — пишем в журнал, поток умирает, приложение продолжает работать
                try {
                    com.winwasm64.vpn.VpnLog.w("Сбой", "ошибка в фоновом потоке ${t.name} (приложение продолжает работу):\n" +
                        text.lines().filter { it.isNotBlank() }.take(6).joinToString("\n"))
                    File(app.filesDir, "crash_bg_last.txt").writeText(text)
                } catch (_: Exception) {}
                return@setDefaultUncaughtExceptionHandler
            }
            try { file.writeText(text) } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }
}
