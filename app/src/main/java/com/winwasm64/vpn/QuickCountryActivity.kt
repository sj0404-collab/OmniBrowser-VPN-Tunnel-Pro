package com.winwasm64.vpn

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ScrollView

/** Быстрое окно стран из шторки: открывается поверх текущего приложения, без перехода в браузер. */
class QuickCountryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BrowsecNative.init(this)
        val panel = BrowsecPanelView(this, false, { finish() }, true)
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(panel)
        }
        setContentView(scroll)
        val dm = resources.displayMetrics
        val width = minOf((380 * dm.density).toInt(), dm.widthPixels - (24 * dm.density).toInt())
        window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }
}
