package com.winwasm64.ui.browser

import android.content.Context
import android.net.Uri
import com.winwasm64.vpn.VpnLog
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v4.4.0: экспорт/импорт настроек одним JSON-файлом (+ журнал для багрепортов).
 *
 * Формат: `{ "app": "OmniBrowser", "version": "4.4.0", "exported": "…", "prefs": { "<файл prefs>": { ключ: значение } }, "journal": "…" }`.
 * При импорте значения пишутся в те же SharedPreferences с исходными типами (Boolean/Int/Long/Float/String/Set<String>).
 */
object SettingsBackup {
    private const val TAG = "Настройки"
    private val PREF_FILES = listOf("omni_browser_v21_prefs", "omni_bundled_ext", "omni_native_bindings")

    fun export(ctx: Context, includeJournal: Boolean): String {
        val root = JSONObject()
        root.put("app", "OmniBrowser")
        root.put("version", UpdateChecker.currentVersion(ctx))
        root.put("exported", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        val prefsObj = JSONObject()
        for (name in PREF_FILES) {
            val sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
            val o = JSONObject()
            for ((k, v) in sp.all) {
                when (v) {
                    null -> {}
                    is Boolean -> o.put(k, JSONObject().put("t", "b").put("v", v))
                    is Int -> o.put(k, JSONObject().put("t", "i").put("v", v))
                    is Long -> o.put(k, JSONObject().put("t", "l").put("v", v))
                    is Float -> o.put(k, JSONObject().put("t", "f").put("v", v.toDouble()))
                    is String -> o.put(k, JSONObject().put("t", "s").put("v", v))
                    is Set<*> -> o.put(k, JSONObject().put("t", "ss").put("v", JSONArray(v.map { it.toString() })))
                    else -> o.put(k, JSONObject().put("t", "s").put("v", v.toString()))
                }
            }
            prefsObj.put(name, o)
        }
        root.put("prefs", prefsObj)
        if (includeJournal) root.put("journal", VpnLog.text(null))
        val json = root.toString(2)
        val name = "omni-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json"
        val where = DownloadSaver.saveBytes(ctx, name, "application/json", json.toByteArray(Charsets.UTF_8))
        VpnLog.i(TAG, "экспорт настроек${if (includeJournal) " и журнала" else ""} → $where")
        return where
    }

    /** Возвращает число импортированных ключей; бросает исключение при неверном файле. */
    fun import(ctx: Context, uri: Uri): Int {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: throw IllegalStateException("файл не открылся")
        val root = JSONObject(text)
        if (root.optString("app") != "OmniBrowser") throw IllegalStateException("это не файл настроек OmniBrowser")
        val prefsObj = root.optJSONObject("prefs") ?: throw IllegalStateException("в файле нет настроек")
        var count = 0
        for (name in prefsObj.keys()) {
            if (name !in PREF_FILES) continue
            val o = prefsObj.optJSONObject(name) ?: continue
            val ed = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            for (k in o.keys()) {
                val e = o.optJSONObject(k) ?: continue
                when (e.optString("t")) {
                    "b" -> ed.putBoolean(k, e.optBoolean("v"))
                    "i" -> ed.putInt(k, e.optInt("v"))
                    "l" -> ed.putLong(k, e.optLong("v"))
                    "f" -> ed.putFloat(k, e.optDouble("v").toFloat())
                    "s" -> ed.putString(k, e.optString("v"))
                    "ss" -> {
                        val arr = e.optJSONArray("v") ?: JSONArray()
                        val set = HashSet<String>()
                        for (i in 0 until arr.length()) set.add(arr.optString(i))
                        ed.putStringSet(k, set)
                    }
                    else -> continue
                }
                count++
            }
            ed.commit()
        }
        VpnLog.i(TAG, "импорт настроек: $count ключей из ${root.optString("version", "?")} (${root.optString("exported", "")})")
        return count
    }
}
