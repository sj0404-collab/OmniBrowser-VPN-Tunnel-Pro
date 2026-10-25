package com.winwasm64.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * v4.5.0: плитка быстрых настроек «VPN приложений» — включить/выключить туннель для списка приложений из шторки,
 * не открывая браузер. Список приложений и страна берутся из сохранённых настроек.
 */
@RequiresApi(Build.VERSION_CODES.N)
class AppsVpnTileService : TileService() {

    companion object {
        /** Дёргать при смене состояния VPN, чтобы плитка обновилась, пока шторка открыта. */
        fun refresh(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
            try { requestListeningState(ctx, android.content.ComponentName(ctx, AppsVpnTileService::class.java)) } catch (_: Exception) {}
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        if (BrowsecVpnService.isRunning) {
            VpnLog.i("Сервис", "плитка: выключение VPN приложений")
            BrowsecVpnService.stop(this)
            update(forceOff = true)
            return
        }
        val prefs = getSharedPreferences("omni_browser_v21_prefs", Context.MODE_PRIVATE)
        val apps = prefs.getStringSet("vpn_routed_apps", emptySet()).orEmpty()
        val country = prefs.getString("apps_vpn_country", null)?.ifBlank { null } ?: BrowsecVpnService.activeCountry
        val prep = try { VpnService.prepare(this) } catch (_: Exception) { null }
        if (prep != null || apps.isEmpty()) {
            // нет разрешения на VPN (или список пуст) — открываем раздел «Приложения» в браузере
            val i = Intent(this, com.winwasm64.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("omni_open_apps", true)
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    startActivityAndCollapse(PendingIntent.getActivity(this, 7801, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                } else {
                    startActivityAndCollapse(i)
                }
            } catch (_: Exception) {}
            return
        }
        // процесс мог быть перезапущен системой — восстанавливаем список приложений и их страны
        if (BrowsecVpnService.routedPackages.isEmpty()) BrowsecVpnService.routedPackages.addAll(apps)
        val serverMap = prefs.getStringSet("vpn_app_server_map", emptySet()).orEmpty()
        for (entry in serverMap) {
            val pkg = entry.substringBefore('=')
            val cc = entry.substringAfter('=', "").ifBlank { country }
            if (pkg.isNotBlank()) try { BrowsecLocalProxyServer.getOrCreateAppProfile(pkg, cc.uppercase()) } catch (_: Exception) {}
        }
        prefs.edit().putBoolean("apps_vpn_on", true).putBoolean("browsec_paused", false).apply()
        VpnLog.i("Сервис", "плитка: включение VPN приложений ($country, приложений ${apps.size})")
        BrowsecVpnService.startForApp(this, country, null)
        update(forceOn = true)
    }

    private fun update(forceOn: Boolean = false, forceOff: Boolean = false) {
        val tile = qsTile ?: return
        val on = forceOn || (!forceOff && BrowsecVpnService.isRunning)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "VPN приложений"
        tile.icon = Icon.createWithResource(this, android.R.drawable.ic_lock_lock)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val n = BrowsecVpnService.routedPackages.size
            tile.subtitle = if (on) (BrowsecVpnService.activeCountry + (if (BrowsecVpnService.isPaused) " · пауза" else "") + (if (n > 0) " · $n прил." else "")) else "выключен"
        }
        try { tile.updateTile() } catch (_: Exception) {}
    }
}
