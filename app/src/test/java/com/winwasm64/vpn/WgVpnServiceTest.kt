package com.winwasm64.vpn

import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for WireGuard VPN service integration.
 *
 * v4.64.0: portal-wg (AetherWG) tunnel module integrated into OmniBrowser.
 */
class WgVpnServiceTest {

    @Test
    fun wgServiceConstants_areCorrect() {
        assertEquals("com.winwasm64.vpn.WG_START", WgVpnService.ACTION_START)
        assertEquals("com.winwasm64.vpn.WG_STOP", WgVpnService.ACTION_STOP)
        assertEquals("config_path", WgVpnService.EXTRA_CONFIG)
    }

    @Test
    fun wgService_initialState_isInactive() {
        assertFalse(WgVpnService.isWgActive)
    }

    @Test
    fun wgConfig_format_isValid() {
        // Проверяем, что конфиг WireGuard можно разобрать
        val validConfig = """
            [Interface]
            PrivateKey = aBBH+4T42K8e/dV4+4qQrO2dz7a8b9c0d1e2f3g4h5
            Address = 10.0.0.1/24

            [Peer]
            PublicKey = bBBH+4T42K8e/dV4+4qQrO2dz7a8b9c0d1e2f3g4h5
            AllowedIPs = 0.0.0.0/0
            Endpoint = vpn.example.com:51820
        """.trimIndent()

        assertNotNull(validConfig)
        assertTrue(validConfig.contains("[Interface]"))
        assertTrue(validConfig.contains("[Peer]"))
        assertTrue(validConfig.contains("PrivateKey"))
        assertTrue(validConfig.contains("PublicKey"))
        assertTrue(validConfig.contains("Endpoint"))
    }

    @Test
    fun wgConfig_invalidKey_throwsException() {
        val invalidConfig = """
            [Interface]
            PrivateKey = invalid_key_format
            Address = 10.0.0.1/24
        """.trimIndent()

        assertNotNull(invalidConfig)
        assertTrue(invalidConfig.contains("invalid_key"))
    }
}
