package com.winwasm64

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.winwasm64.ui.WindowsWasm64DesktopScreen
import com.winwasm64.ui.theme.OmniTheme
import com.winwasm64.vpn.BrowsecVpnService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)
        val initialUri = intent?.dataString
        setContent {
            OmniTheme {
                WindowsWasm64DesktopScreen(initialIntentUri = initialUri)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == BrowsecVpnService.ACTION_OPEN_SERVER_PICKER) {
            BrowsecVpnService.openServerPickerRequestCount++
        }
    }
}
