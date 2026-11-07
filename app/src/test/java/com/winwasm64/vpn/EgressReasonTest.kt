package com.winwasm64.vpn

import com.winwasm64.ui.browser.EgressCheck
import org.junit.Assert.assertTrue
import org.junit.Test

/** v4.13.0: ошибка вроде «Binding socket to network 319 failed: EPERM» должна читаться человеком. */
class EgressReasonTest {

    @Test
    fun `EPERM объясняется словами`() {
        val r = EgressCheck.shortReason("ipinfo.io: Binding socket to network 319 failed: EPERM (Operation not permitted)")
        assertTrue(r.contains("EPERM"))
        assertTrue(r.contains("не разрешает"))
    }

    @Test
    fun `таймаут и отказ сервиса распознаются`() {
        assertTrue(EgressCheck.shortReason("ip-api.com: connect timed out").contains("таймаут"))
        assertTrue(EgressCheck.shortReason("api.ipify.org: HTTP 403").contains("сервис отказал"))
        assertTrue(EgressCheck.shortReason(null).contains("нет ответа"))
    }
}
