package com.winwasm64.torrent

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.mutableStateOf
import com.winwasm64.ui.browser.AiAssistant
import com.winwasm64.vpn.VpnLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * v4.74.0: длинные задачи агента в фоне.
 *
 * Пользователь просил: «большие задачи, на выполнение которых нужно время, и даже если я отключусь
 * от сети — он мог по-прежнему их выполнять, и я мог проследить».
 *
 * Как работает по-настоящему:
 *  • задача = запись в очереди (SharedPreferences, JSON) — переживает перезапуск приложения;
 *  • выполняет foreground-сервис [AgentRunnerService] с постоянным уведomлением, где видно шаг
 *    и время; сервис держит PARTIAL_WAKE_LOCK, поэтому не засыпает в Doze;
 *  • агент зовёт инструменты сам (workspace, GitHub по токену, RAG, поиск в интернете, плагины);
 *  • нет сети — задача переходит в состояние «ждёт сеть», сервис ждёт и продолжает с того же места;
 *  • приложение закрыли — сервис перезапускается по тревоге (AlarmManager) и после перезагрузки
 *    телефона (BOOT_COMPLETED);
 *  • результат и журнал шагов пишутся в workspace: agent/tasks/&lt;id&gt;.md — можно открыть и посмотреть.
 */
object AgentTasks {

    private const val PREFS = "omni_agent_tasks"
    private const val KEY = "tasks"

    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val WAITING = "waiting"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELED = "canceled"

    data class Task(
        val id: String,
        val title: String,
        val prompt: String,
        val agent: String = "browser",
        val status: String = QUEUED,
        val step: String = "",
        val result: String = "",
        val error: String = "",
        val created: Long = 0L,
        val updated: Long = 0L,
        val attempts: Int = 0,
        val tokens: Int = 0,
        val elapsedMs: Long = 0L,
        /** v4.77.0: до какого времени задача имеет право работать (0 — без ограничения). */
        val deadline: Long = 0L
    ) {
        val isActive: Boolean get() = status == RUNNING || status == QUEUED || status == WAITING
        /** Осталось миллисекунд (0 — без лимита). */
        val leftMs: Long get() = if (deadline <= 0L) 0L else (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
        val overLimit: Boolean get() = deadline > 0L && System.currentTimeMillis() > deadline
    }

    /** Для интерфейса: живой список задач. */
    val tasks = mutableStateOf<List<Task>>(emptyList())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val raw = prefs(ctx).getString(KEY, "") ?: ""
        val list = ArrayList<Task>()
        if (raw.isNotBlank()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    list.add(
                        Task(
                            id = o.optString("id"),
                            title = o.optString("title"),
                            prompt = o.optString("prompt"),
                            agent = o.optString("agent", "browser"),
                            status = o.optString("status", QUEUED),
                            step = o.optString("step"),
                            result = o.optString("result"),
                            error = o.optString("error"),
                            created = o.optLong("created"),
                            updated = o.optLong("updated"),
                            attempts = o.optInt("attempts"),
                            tokens = o.optInt("tokens"),
                            elapsedMs = o.optLong("elapsedMs")
                        )
                    )
                }
            } catch (_: Throwable) {
            }
        }
        tasks.value = list
    }

    private fun persist(ctx: Context, list: List<Task>) {
        val arr = JSONArray()
        for (t in list) {
            arr.put(
                JSONObject()
                    .put("id", t.id).put("title", t.title).put("prompt", t.prompt).put("agent", t.agent)
                    .put("status", t.status).put("step", t.step).put("result", t.result).put("error", t.error)
                    .put("created", t.created).put("updated", t.updated).put("attempts", t.attempts)
                    .put("tokens", t.tokens).put("elapsedMs", t.elapsedMs)
            )
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
        tasks.value = list
    }

    fun enqueue(ctx: Context, title: String, prompt: String, agent: String = "browser"): Task {
        load(ctx)
        val now = System.currentTimeMillis()
        val t = Task(
            id = now.toString(36) + (100..999).random(),
            title = title.ifBlank { prompt.take(48) },
            prompt = prompt,
            agent = agent,
            status = QUEUED,
            created = now,
            updated = now,
            // v4.77.0: срок из настройки «сколько максимум работает над задачей»
            deadline = com.winwasm64.ui.browser.AiAssistant.budgetMs(ctx)
                .let { if (it > 0L) now + it else 0L }
        )
        persist(ctx, tasks.value + t)
        VpnLog.i("Агент", "задача в очередь: ${t.title}")
        return t
    }

    fun update(ctx: Context, id: String, block: (Task) -> Task): Task? {
        val list = tasks.value.map { if (it.id == id) block(it).copy(updated = System.currentTimeMillis()) else it }
        val changed = list.firstOrNull { it.id == id }
        persist(ctx, list)
        return changed
    }

    fun cancel(ctx: Context, id: String) {
        update(ctx, id) { it.copy(status = CANCELED, step = "отменено пользователем") }
    }

    fun retry(ctx: Context, id: String) {
        update(ctx, id) { it.copy(status = QUEUED, error = "", step = "повтор", attempts = 0) }
        AgentRunnerService.start(ctx)
    }

    fun remove(ctx: Context, id: String) {
        persist(ctx, tasks.value.filterNot { it.id == id })
    }

    fun clearFinished(ctx: Context) {
        persist(ctx, tasks.value.filter { it.isActive })
    }

    internal fun nextRunnable(ctx: Context): Task? {
        load(ctx)
        return tasks.value.firstOrNull { it.status == QUEUED }
            ?: tasks.value.firstOrNull { it.status == WAITING }
    }

    /** Задача, которая выполняется прямо сейчас (для полоски сверху). */
    fun active(ctx: Context): Task? = tasks.value.firstOrNull { it.status == RUNNING }
        ?: tasks.value.firstOrNull { it.status == WAITING }

    fun online(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val c = cm.getNetworkCapabilities(n) ?: return false
        c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Throwable) {
        true
    }

    /** Журнал шагов задачи — файл в workspace, чтобы можно было открыть и посмотреть. */
    fun writeLog(ctx: Context, task: Task) {
        try {
            val dir = "agent/tasks"
            AgentWorkspace.mkdir(ctx, dir)
            AgentWorkspace.write(
                ctx, "$dir/${task.id}.md",
                buildString {
                    append("# ${task.title}\n\n")
                    append("- статус: **${task.status}**\n")
                    append("- агент: ${task.agent}\n")
                    append("- создана: ").append(java.util.Date(task.created)).append("\n")
                    append("- обновлена: ").append(java.util.Date(task.updated)).append("\n")
                    append("- попыток: ${task.attempts} · токенов ≈${task.tokens} · ${task.elapsedMs} мс\n")
                    append("- шаг: ${task.step}\n")
                    if (task.error.isNotBlank()) append("\n## Ошибка\n${task.error}\n")
                    if (task.result.isNotBlank()) append("\n## Результат\n${task.result}\n")
                    append("\n## Задание\n${task.prompt}\n")
                }
            )
        } catch (_: Throwable) {
        }
    }
}

/**
 * Foreground-сервис, который тянет задачи из очереди. Живёт, пока есть работа:
 * ни закрытие приложения, ни пропажа сети его не останавливают.
 */
class AgentRunnerService : Service() {

    companion object {
        private const val CHANNEL = "agent_tasks"
        private const val NOTIF_ID = 7711
        private const val ALARM_ID = 7712
        private const val MAX_ATTEMPTS = 20
        private const val WAIT_MS = 30_000L

        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, AgentRunnerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (t: Throwable) {
                VpnLog.w("Агент", "не удалось запустить сервис задач: ${t.message}")
            }
        }

        /** Перезапуск, если пользователь смахнул приложение из недавних. */
        fun scheduleRestart(ctx: Context) {
            try {
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = PendingIntent.getBroadcast(
                    ctx, ALARM_ID,
                    Intent(ctx, AgentTaskAlarmReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val at = System.currentTimeMillis() + 15_000
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.set(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (_: Throwable) {
            }
        }
    }

    @Volatile private var stopped = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, notification("Готовлю задачу…", null, 0))
        if (!stopped) {
            Thread({ work() }, "agent-tasks").also { it.isDaemon = true; it.start() }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // приложение закрыли, а работа осталась — поднимемся снова по тревоге
        if (AgentTasks.tasks.value.any { it.isActive }) scheduleRestart(this)
        super.onTaskRemoved(rootIntent)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Задачи агента", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Фоновые задачи ИИ-агента: шаг, время, результат"
            }
        )
    }

    private fun notification(text: String, title: String?, progress: Int): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent().setClassName(packageName, "com.winwasm64.MainActivity")
        val open = PendingIntent.getActivity(
            this, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle(title ?: "Агент работает")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .apply { if (progress in 0..100) setProgress(100, progress, false) }
            .build()
    }

    private fun notify(text: String, title: String? = null, progress: Int = -1) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, notification(text, title, progress))
        } catch (_: Throwable) {
        }
    }

    private fun lock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "omni:agent").apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L)
            }
        } catch (_: Throwable) {
        }
    }

    private fun unlock() {
        try {
            wakeLock?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    private fun work() {
        lock()
        AgentTasks.load(this)
        var guard = 0
        try {
            while (!stopped && guard++ < 200) {
                val task = AgentTasks.nextRunnable(this) ?: break
                if (!AgentTasks.online(this)) {
                    AgentTasks.update(this, task.id) { it.copy(status = AgentTasks.WAITING, step = "ждёт сеть") }
                    notify("Жду сеть — задача «${task.title}» на паузе", null, 0)
                    Thread.sleep(WAIT_MS)
                    continue
                }
                if (task.overLimit) {
                    AgentTasks.update(this, task.id) {
                        it.copy(status = AgentTasks.DONE, step = "время вышло", error = "лимит времени исчерпан")
                    }
                    notify("Время вышло: ${task.title}", null, 0)
                    continue
                }
                val startedAt = System.currentTimeMillis()
                AgentTasks.update(this, task.id) { it.copy(status = AgentTasks.RUNNING, step = "запуск", attempts = it.attempts + 1) }
                notify("Работаю: ${task.title}", task.title, 5)
                val result = try {
                    AiAssistant.runHeadless(
                        this, task.prompt, task.agent,
                        budgetMs = if (task.deadline > 0L) (task.deadline - System.currentTimeMillis()).coerceAtLeast(0L) else 0L
                    ) { step ->
                        AgentTasks.update(this, task.id) { it.copy(step = step) }
                        notify(step, task.title, 40)
                    }
                } catch (t: Throwable) {
                    AiAssistant.HeadlessResult(false, "сбой: " + (t.message ?: t.javaClass.simpleName), 0)
                }
                val elapsed = System.currentTimeMillis() - startedAt
                val current = AgentTasks.tasks.value.firstOrNull { it.id == task.id }
                if (current?.status == AgentTasks.CANCELED) {
                    notify("Задача отменена: ${task.title}", null, 0)
                    continue
                }
                if (result.ok) {
                    AgentTasks.update(this, task.id) {
                        it.copy(
                            status = AgentTasks.DONE, step = "готово", result = result.text,
                            elapsedMs = elapsed, tokens = result.tokens, error = ""
                        )
                    }
                    AgentTasks.tasks.value.firstOrNull { it.id == task.id }?.let { AgentTasks.writeLog(this, it) }
                    // результат ещё и в файл, чтобы открыть руками
                    AgentWorkspace.write(this, "agent/tasks/${task.id}-result.md", result.text)
                    notify("Готово: ${task.title}", task.title, 100)
                } else {
                    val network = result.text.contains("сеть", true) || result.text.contains("timeout", true) ||
                        result.text.contains("ENONET", true) || result.text.contains("обрыв", true) ||
                        result.text.contains("Unable to resolve host", true)
                    val attempts = (current?.attempts ?: 1)
                    if (network && attempts < MAX_ATTEMPTS) {
                        AgentTasks.update(this, task.id) {
                            it.copy(status = AgentTasks.WAITING, step = "нет связи, повтор", error = result.text.take(500), elapsedMs = elapsed)
                        }
                        notify("Нет связи — повторю «${task.title}» через 30 с", null, 0)
                        Thread.sleep(WAIT_MS)
                    } else {
                        AgentTasks.update(this, task.id) {
                            it.copy(status = AgentTasks.FAILED, step = "ошибка", error = result.text.take(2000), elapsedMs = elapsed, tokens = result.tokens)
                        }
                        AgentTasks.tasks.value.firstOrNull { it.id == task.id }?.let { AgentTasks.writeLog(this, it) }
                        notify("Ошибка: ${task.title}", task.title, 100)
                    }
                }
            }
            val active = AgentTasks.tasks.value.any { it.isActive }
            if (active) notify("Жду следующего шага…", null, -1) else notify("Все задачи выполнены", null, 100)
        } catch (t: Throwable) {
            VpnLog.w("Агент", "сервис задач: ${t.message}")
        } finally {
            unlock()
            AgentTasks.load(this)
            val left = AgentTasks.tasks.value.any { it.isActive }
            if (left) scheduleRestart(this)
            stopForeground(false)
            stopSelf()
        }
    }
}

/** Тревога: перезапустить сервис задач, если остались незавершённые. */
class AgentTaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AgentTasks.load(context)
        if (AgentTasks.tasks.value.any { it.isActive }) AgentRunnerService.start(context)
    }
}

/** После перезагрузки телефона — продолжить незавершённые задачи. */
class AgentBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        AgentTasks.load(context)
        if (AgentTasks.tasks.value.any { it.isActive }) AgentRunnerService.start(context)
    }
}
