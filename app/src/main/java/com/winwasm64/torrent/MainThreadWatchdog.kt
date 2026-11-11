package com.winwasm64.torrent

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * v4.61.0: «где-то процесс не в правильном потоке — при поиске и после поиска нагрузка».
 *
 * Android показывает «Приложение не отвечает», когда главный поток занят дольше ~5 секунд.
 * Причина была найдена и убрана (ожидание ИИ в главном потоке, сеть в проверке релевантности),
 * но чтобы такое больше не искали наугад — здесь сторож: он каждые 250 мс отмечается в главном
 * потоке, а отдельный поток сравнивает время. Если главный поток «провалился» — в журнал
 * (`adb logcat -s OmniANR`) пишется, на сколько именно и что происходило.
 *
 * Сторож ничего не блокирует и почти ничего не стоит: один Handler-тик и одно сравнение чисел.
 */
object MainThreadWatchdog {

    private const val TAG = "OmniANR"
    private const val TICK_MS = 250L
    private const val WARN_MS = 1_200L

    private val lastTick = AtomicLong(System.currentTimeMillis())
    private val worstGap = AtomicLong(0L)
    @Volatile private var started = false

    /** Самый долгий «провал» главного потока с момента запуска — для отладки. */
    fun worstGapMs(): Long = worstGap.get()

    fun start() {
        if (started) return
        started = true
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                lastTick.set(System.currentTimeMillis())
                handler.postDelayed(this, TICK_MS)
            }
        }
        handler.post(tick)
        Thread({
            while (true) {
                try {
                    Thread.sleep(TICK_MS)
                    val gap = System.currentTimeMillis() - lastTick.get() - TICK_MS
                    if (gap > WARN_MS) {
                        if (gap > worstGap.get()) worstGap.set(gap)
                        Log.w(TAG, "главный поток занят ${gap} мс — интерфейс не отвечает")
                    }
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {
                }
            }
        }, "OmniAnrWatch").apply { isDaemon = true; start() }
    }
}
