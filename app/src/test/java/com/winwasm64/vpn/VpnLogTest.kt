package com.winwasm64.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnLogTest {
    @Test
    fun filtersAndClears() {
        VpnLog.clear()
        VpnLog.i("Сервис", "команда: CONNECT")
        VpnLog.conn(null, "youtube.com", 443, "LT", 120, null)
        VpnLog.conn(null, "t.me", 443, "LT", 30, "IOException: reset")
        VpnLog.w("Браузер", "прокси не отвечает")
        assertEquals(4, VpnLog.snapshot(null).size)
        assertEquals(2, VpnLog.snapshot("Прил").size)
        assertEquals(2, VpnLog.snapshot("err").size)
        assertTrue(VpnLog.text("Браузер").contains("прокси не отвечает"))
        VpnLog.clear()
        assertEquals(0, VpnLog.snapshot(null).size)
    }
}
