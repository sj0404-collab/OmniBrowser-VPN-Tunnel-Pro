package com.winwasm64.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowsecNativeTest {
    @Test
    fun parsesServersFreeFirst() {
        val json = """{"version":1,"countries":{"at":{"premium_servers":[{"host":"at3.p.live","port":2727}]},""" +
            """"de":{"timezoneOffset":120,"servers":[{"host":"de1.example.com","port":443},{"host":"","port":1}],"premium_servers":[{"host":"de3.p.com","port":6188}]}}}"""
        val list = BrowsecNative.parseServers(json)
        assertEquals(2, list.size)
        assertEquals("de", list[0].code)
        assertTrue(list[0].isFree)
        assertEquals(1, list[0].free.size)
        assertEquals("de1.example.com", list[0].free[0].host)
        assertEquals(443, list[0].free[0].port)
        assertFalse(list[1].isFree)
        assertEquals("at3.p.live", list[1].servers()[0].host)
    }

    @Test
    fun fallbackHasSevenFreeCountries() {
        val list = BrowsecNative.fallbackCountries()
        assertEquals(7, list.size)
        assertTrue(list.all { it.isFree && it.free.size == 3 })
    }

    @Test
    fun formatsTrafficAndFlags() {
        assertEquals("512 Б", BrowsecNative.formatBytes(512))
        assertEquals("1.5 КБ", BrowsecNative.formatBytes(1536))
        assertEquals("2.0 МБ", BrowsecNative.formatBytes(2L * 1024 * 1024))
        assertEquals("85К", BrowsecNative.formatRateShort(85L * 1024))
        assertEquals("🇩🇪", BrowsecNative.flagOf("de"))
        assertEquals("🇬🇧", BrowsecNative.flagOf("uk"))
    }
}
