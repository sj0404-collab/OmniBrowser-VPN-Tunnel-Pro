package com.winwasm64.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import com.winwasm64.MainActivity
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config

/**
 * WireGuard VPN service — integration portal-wg (AetherWG) tunnel module.
 *
 * v4.64.0: добавлен WireGuard alongside existing Psiphon/Browsec tunnels.
 *
 * Usage:
 * 1. Пользователь добавляет конфиг WireGuard (.conf файл)
 * 2. WgVpnService запускается с конфигом
 * 3. GoBackend создаёт туннель и перенаправляет трафик
 */
class WgVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.winwasm64.vpn.WG_START"
        const val ACTION_STOP = "com.winwasm64.vpn.WG_STOP"
        const val EXTRA_CONFIG = "config_path"

        @Volatile
        var isWgActive: Boolean = false
            private set

        @Volatile
        var wgConfig: Config? = null
            private set

        @Volatile
        var wgStatistics: Statistics? = null
            private set
    }

    private var backend: GoBackend? = null
    private var tunnel: Tunnel? = null
    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val configPath = intent.getStringExtra(EXTRA_CONFIG)
                if (configPath != null) {
                    startWgTunnel(configPath)
                }
            }
            ACTION_STOP -> {
                stopWgTunnel()
            }
        }
        return START_NOT_STICKY
    }

    private fun startWgTunnel(configPath: String) {
        try {
            // Load WireGuard config
            val configFile = java.io.File(configPath)
            if (!configFile.exists()) {
                VpnLog.e("WireGuard", "Config file not found: $configPath")
                stopWgTunnel()
                return
            }
            val config = Config.parse(configFile.inputStream())

            // Create GoBackend
            backend = GoBackend(applicationContext)

            // Create tunnel object
            tunnel = object : Tunnel {
                override fun getName(): String = "OmniBrowser-WG"
                override fun onStateChange(newState: Tunnel.State) {
                    VpnLog.i("WireGuard", "Tunnel state: $newState")
                    when (newState) {
                        Tunnel.State.UP -> {
                            isWgActive = true
                            showNotification("WireGuard активен", "Туннель WireGuard установлен")
                        }
                        Tunnel.State.DOWN -> {
                            isWgActive = false
                            showNotification("WireGuard остановлен", "Туннель WireGuard закрыт")
                        }
                        Tunnel.State.TOGGLE -> {
                            if (isWgActive) stopWgTunnel()
                        }
                    }
                }
            }

            // Start tunnel via backend
            wgConfig = config
            backend?.setState(tunnel!!, Tunnel.State.UP, config)
            VpnLog.i("WireGuard", "Tunnel started: $configPath")
        } catch (e: Exception) {
            VpnLog.e("WireGuard", "Failed to start tunnel: ${e.message}")
            stopWgTunnel()
        }
    }

    private fun stopWgTunnel() {
        try {
            backend?.let { backend ->
                tunnel?.let { tunnel ->
                    backend.setState(tunnel, Tunnel.State.DOWN, null)
                }
            }
            tunnel = null
            backend = null
            wgConfig = null
            wgStatistics = null
            isWgActive = false
            showNotification("WireGuard остановлен", "Туннель WireGuard закрыт")
            VpnLog.i("WireGuard", "Tunnel stopped")
            stopSelf()
        } catch (e: Exception) {
            VpnLog.e("WireGuard", "Failed to stop tunnel: ${e.message}")
        }
    }

    override fun onDestroy() {
        stopWgTunnel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "wg_vpn_channel",
                "WireGuard VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "WireGuard VPN tunnel status"
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun showNotification(title: String, message: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = Notification.Builder(this, "wg_vpn_channel")
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }
}
