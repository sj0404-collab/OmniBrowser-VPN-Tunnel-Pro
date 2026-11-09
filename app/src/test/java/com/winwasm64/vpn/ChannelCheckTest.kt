package com.winwasm64.vpn

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.36.0: вывод диагностики должен быть читаемым и честным:
 * у неудачных каналов обязательно есть подсказка, у итога — счёт проблемных.
 */
class ChannelCheckTest {

    private fun row(name: String, state: ChannelCheck.State, hint: String = "") =
        ChannelCheck.Row(name, state, "проверено", hint)

    @Test
    fun `подсказка печатается только у проблемных каналов`() {
        val text = ChannelCheck.summary(
            listOf(
                row("DNS", ChannelCheck.State.OK, "не должна печататься"),
                row("Browsec", ChannelCheck.State.FAIL, "смените сервер")
            )
        )
        assertTrue(text.contains("✅ DNS"))
        assertTrue(text.contains("❌ Browsec"))
        assertTrue(text.contains("→ смените сервер"))
        assertTrue(!text.contains("не должна печататься"))
    }

    @Test
    fun `итог считает проблемные каналы`() {
        val text = ChannelCheck.summary(
            listOf(
                row("DNS", ChannelCheck.State.OK),
                row("Browsec", ChannelCheck.State.FAIL),
                row("Psiphon", ChannelCheck.State.WARN)
            )
        )
        assertTrue(text.contains("требуют внимания 2 из 3"))
    }

    @Test
    fun `всё живо — так и написано`() {
        val text = ChannelCheck.summary(listOf(row("DNS", ChannelCheck.State.OK)))
        assertTrue(text.contains("все каналы отвечают"))
    }

    @Test
    fun `пустой список не роняет вывод`() {
        assertTrue(ChannelCheck.summary(emptyList()).contains("ещё не запускалась"))
    }
}
