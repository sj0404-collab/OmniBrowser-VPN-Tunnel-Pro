package com.winwasm64.vpn

import com.winwasm64.ui.browser.EgressCheck
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.11.1: «домашний» IP на хостинге (GTHost и подобные) означает, что на телефоне включён
 * ещё один VPN — тогда непонятно, чей именно VPN сейчас работает. Проверяем эвристику.
 */
class EgressHostingTest {

    @Test
    fun `ip хостинга распознаётся`() {
        assertTrue(EgressCheck.looksLikeHosting("AS63023 GTHost"))
        assertTrue(EgressCheck.looksLikeHosting("rev-37-203-35-8.deac.net Vultr"))
        assertTrue(EgressCheck.looksLikeHosting("DigitalOcean LLC"))
        assertTrue(EgressCheck.looksLikeHosting("M247 Data Center"))
    }

    @Test
    fun `мобильный оператор не считается хостингом`() {
        assertFalse(EgressCheck.looksLikeHosting("LMT Latvijas Mobilais Telefons"))
        assertFalse(EgressCheck.looksLikeHosting("Tele2"))
        assertFalse(EgressCheck.looksLikeHosting(""))
    }
}
