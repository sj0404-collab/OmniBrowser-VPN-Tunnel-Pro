package com.winwasm64.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v4.30.0: порядок перебора резервов.
 * Смысл: при отказе канала браузер должен уйти на следующий живой, а безнадёжный —
 * на время остыть, иначе каждое соединение снова будет ждать мёртвый адрес.
 */
class ReserveChainTest {

    private fun up(label: String, kind: ReserveChain.Kind = ReserveChain.Kind.HTTP) =
        ReserveUpstream(kind, "10.0.0.1", label.hashCode(), label)

    @Test
    fun `первый в списке активен сразу`() {
        val st = ReserveChain.State()
        val a = up("a"); val b = up("b")
        st.setList(listOf(a, b))
        assertEquals(a, st.active(0L))
    }

    @Test
    fun `пустой список ничего не даёт`() {
        val st = ReserveChain.State()
        st.setList(emptyList())
        assertNull(st.active(0L))
    }

    @Test
    fun `один провал ещё не уводит в отстой`() {
        val st = ReserveChain.State()
        val a = up("a"); val b = up("b")
        st.setList(listOf(a, b))
        assertEquals(a, st.reportFailure(a, 1_000L))
    }

    @Test
    fun `два провала уводят в отстой и переключают дальше`() {
        val st = ReserveChain.State()
        val a = up("a"); val b = up("b")
        st.setList(listOf(a, b))
        st.reportFailure(a, 1_000L)
        val next = st.reportFailure(a, 2_000L)
        assertEquals(b, next)
        // сразу после отстоя первый не выбирается
        assertEquals(b, st.active(3_000L))
    }

    @Test
    fun `через время отстоя канал возвращается`() {
        val st = ReserveChain.State(cooldownMs = 5_000L)
        val a = up("a")
        st.setList(listOf(a))
        st.reportFailure(a, 0L)
        st.reportFailure(a, 0L)
        assertNull(st.active(1_000L))          // в отстое, других нет
        assertEquals(a, st.active(10_000L))    // отстой истёк
    }

    @Test
    fun `успех снимает счётчики`() {
        val st = ReserveChain.State()
        val a = up("a")
        st.setList(listOf(a))
        st.reportFailure(a, 0L)
        st.reportSuccess(a, 1L)
        // после успеха снова нужен провал дважды, чтобы получить отстой
        st.reportFailure(a, 2L)
        assertEquals(a, st.active(3L))
    }

    @Test
    fun `все в отстое — активного нет`() {
        val st = ReserveChain.State(cooldownMs = 60_000L)
        val a = up("a"); val b = up("b")
        st.setList(listOf(a, b))
        st.reportFailure(a, 0L); st.reportFailure(a, 0L)
        st.reportFailure(b, 0L); st.reportFailure(b, 0L)
        assertNull(st.active(1_000L))
    }

    @Test
    fun `в снимке видно текущий резерв`() {
        val st = ReserveChain.State()
        st.setList(listOf(up("psiphon"), up("proxy")))
        assertEquals(true, st.snapshot(0L).contains("источников 2"))
    }
}
