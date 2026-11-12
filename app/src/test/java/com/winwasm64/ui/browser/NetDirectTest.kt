package com.winwasm64.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.67.0: «OpenCode Zen: нет соединения: socket failed: ENONET».
 * Проверяем, что такие ошибки распознаются как «сеть потерялась» и объясняются человеку понятно.
 */
class NetDirectTest {

    @Test
    fun enonet_isNetworkLoss() {
        assertTrue(NetDirect.looksLikeNetworkLoss("socket failed: ENONET (Machine is not on the network)"))
        assertTrue(NetDirect.looksLikeNetworkLoss("connect failed: ENETUNREACH"))
        assertTrue(NetDirect.looksLikeNetworkLoss("Network is unreachable"))
        assertTrue(NetDirect.looksLikeNetworkLoss("socket failed: EPERM (Operation not permitted)"))
    }

    @Test
    fun httpErrors_areNotNetworkLoss() {
        assertFalse(NetDirect.looksLikeNetworkLoss("HTTP 401: нужен ключ"))
        assertFalse(NetDirect.looksLikeNetworkLoss("слишком часто (HTTP 429)"))
        assertFalse(NetDirect.looksLikeNetworkLoss(null))
    }

    @Test
    fun enonetMessage_isHumanReadable() {
        val msg = NetDirect.humanError("socket failed: ENONET (Machine is not on the network)")
        assertTrue(msg.contains("сеть"))
        assertFalse(msg.contains("ENONET"))
    }

    @Test
    fun dnsMessage_isHumanReadable() {
        assertEquals(
            "не удалось разобрать имя (DNS) — проверьте интернет",
            NetDirect.humanError("Unable to resolve host \"opencode.ai\"")
        )
    }
}
