package com.winwasm64

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.winwasm64.ui.WindowsWasm64DesktopScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialUri = intent?.dataString
        setContent {
            WindowsWasm64DesktopScreen(initialIntentUri = initialUri)
        }
    }
}
