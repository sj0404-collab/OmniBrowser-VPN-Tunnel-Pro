package com.winwasm64.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.11.0: «значок VPN ≠ VPN для всех приложений» — тексты не должны обещать лишнего.
 */
class VpnScopeTest {

    @Test
    fun `область действия названа явно`() {
        assertEquals("только 3 прил.", VpnScope.titleShort(perAppOnly = true, count = 3))
        assertEquals("все приложения", VpnScope.titleShort(perAppOnly = false, count = 3))
        assertTrue(VpnScope.scopeLine(true, 3).contains("ТОЛЬКО к 3"))
        assertTrue(VpnScope.scopeLine(true, 3).contains("остальные приложения идут напрямую"))
        assertTrue(VpnScope.scopeLine(false, 3).contains("ко ВСЕМ приложениям"))
    }

    @Test
    fun `приложение вне VPN так и подписано`() {
        assertTrue(VpnScope.appState(false).startsWith("вне VPN"))
        assertTrue(VpnScope.appState(true).startsWith("в VPN"))
        assertNull(VpnScope.whyWorksWithoutVpn(true))
        assertEquals(
            "открывается без VPN — значит не заблокировано; VPN тут ни при чём",
            VpnScope.whyWorksWithoutVpn(false)
        )
    }

    @Test
    fun `сравнение IP не выдаёт прямой трафик за VPN`() {
        assertTrue(VpnScope.ipCompare("1.2.3.4", "1.2.3.4").contains("IP не меняется"))
        assertTrue(VpnScope.ipCompare("1.2.3.4", "5.6.7.8").contains("IP изменился"))
        assertTrue(VpnScope.ipCompare("1.2.3.4", "").contains("не удалось"))
        assertTrue(VpnScope.ipCompare("", "").contains("ни напрямую"))
    }
}
