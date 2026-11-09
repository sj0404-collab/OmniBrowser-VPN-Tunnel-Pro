package com.winwasm64.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.32.0: порт локального прокси для приложения должен быть ОДИН И ТОТ ЖЕ.
 *
 * Было: счётчик 8901, 8902, … при каждом старте процесса. Telegram оставался со старым портом
 * в настройках, слушателя на нём уже не было — «прокси то включался, то сам себя менял».
 */
class AppPortAllocatorTest {

    @Test
    fun `один пакет — один и тот же порт всегда`() {
        val a = AppPortAllocator.preferred("org.telegram.messenger")
        repeat(5) { assertEquals(a, AppPortAllocator.preferred("org.telegram.messenger")) }
    }

    @Test
    fun `порт всегда внутри диапазона`() {
        for (pkg in listOf("org.telegram.messenger", "com.google.android.youtube", "x", "")) {
            val p = AppPortAllocator.preferred(pkg)
            assertTrue("порт $p вне диапазона для $pkg", p in AppPortAllocator.BASE until (AppPortAllocator.BASE + AppPortAllocator.SPAN))
        }
    }

    @Test
    fun `занятый порт заменяется свободным`() {
        val pkg = "org.telegram.messenger"
        val own = AppPortAllocator.preferred(pkg)
        val picked = AppPortAllocator.pick(pkg, setOf(own))
        assertTrue(picked != own)
        assertTrue(picked in AppPortAllocator.BASE until (AppPortAllocator.BASE + AppPortAllocator.SPAN))
    }

    @Test
    fun `закрытый порт заменяется свободным`() {
        val pkg = "org.telegram.messenger"
        val own = AppPortAllocator.preferred(pkg)
        val picked = AppPortAllocator.pick(pkg, emptySet()) { it != own }
        assertTrue(picked != own)
    }

    @Test
    fun `разные пакеты обычно получают разные порты`() {
        val values = listOf("org.telegram.messenger", "com.google.android.youtube", "org.telegram.plus", "com.discord")
            .map { AppPortAllocator.preferred(it) }
        assertTrue("порты совпали: $values", values.toSet().size >= 3)
    }
}
