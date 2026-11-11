package com.winwasm64.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.winwasm64.MainActivity
import com.winwasm64.R
import com.wireguard.android.backend.GoBackend
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
class WgVpnService : VpnService(), GoBackend.TunnelStateCallback {

    companion object {
        const val ACTION_START = "com.winwasm64.vpn.WG_START"
        const val ACTION_STOP = "com.winwasm64.vpn.WG_STOP"
        const val EXTRA_CONFIG = "config_path"

        @Volatile
        var isWgActive: Boolean = false
            private set
    }

    private var backend: GoBackend? = null
    private var tunnel: Tunnel? = null
    private var vpnInterface: ParcelFileDescriptor? = null
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
            // Prepare VPN interface
            val builder = Builder()
                .setName("OmniBrowser-WG")
                .addAddress("10.0.0.1", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
            vpnInterface = builder.establish()

            // Load WireGuard config
            val configFile = java.io.File(configPath)
            val config = Config.parse(configFile.inputStream().bufferedReader())

            // Create GoBackend and tunnel
            backend = GoBackend(applicationContext, this)
            tunnel = backend?.newTunnel(config)

            tunnel?.up()
            isWgActive = true

            // Show notification
            showNotification("WireGuard активен", "Туннель WireGuard установлен")

            VpnLog.i("WireGuard", "Tunnel started: $configPath")
        } catch (e: Exception) {
            VpnLog.e("WireGuard", "Failed to start tunnel", e)
            stopWgTunnel()
        }
    }

    private fun stopWgTunnel() {
        try {
            tunnel?.down()
            tunnel = null
            backend = null
            vpnInterface?.close()
            vpnInterface = null
            isWgActive = false
            showNotification("WireGuard остановлен", "Туннель WireGuard закрыт")
            VpnLog.i("WireGuard", "Tunnel stopped")
            stopSelf()
        } catch (e: Exception) {
            VpnLog.e("WireGuard", "Failed to stop tunnel", e)
        }
    }

    override fun onTunnelStateChanged(state: Tunnel.State) {
        VpnLog.i("WireGuard", "Tunnel state: $state")
        when (state) {
            Tunnel.State.UP -> isWgActive = true
            Tunnel.State.DOWN -> {
                isWgActive = false
                stopWgTunnel()
            }
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
