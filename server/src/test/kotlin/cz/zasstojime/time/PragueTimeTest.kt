package cz.zasstojime.time

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** AC-03: provozní den a hodiny v Europe/Prague včetně změny času. */
class PragueTimeTest {
    @Test
    fun `spoj pres pulnoc patri do dne vyjezdu`() {
        // 2. 10. 23:50 SELČ
        assertEquals(LocalDate.of(2026, 10, 2), PragueTime.serviceDate(Instant.parse("2026-10-02T21:50:00Z"), 23 * 3600 + 50 * 60))
    }

    @Test
    fun `vyjezd 24_13 v jizdnim radu patri do predchoziho servisniho dne`() {
        // 3. 10. 00:13 SELČ = 24:13 servisního dne 2. 10.
        assertEquals(LocalDate.of(2026, 10, 2), PragueTime.serviceDate(Instant.parse("2026-10-02T22:13:00Z"), 24 * 3600 + 13 * 60))
    }

    @Test
    fun `vyjezd 00_13 v jizdnim radu patri do dne vyjezdu`() {
        assertEquals(LocalDate.of(2026, 10, 3), PragueTime.serviceDate(Instant.parse("2026-10-02T22:13:00Z"), 13 * 60))
    }

    @Test
    fun `25h den - podzimni zmena casu secte dve hodiny 02_00`() {
        // 25. 10. 2026: 02:30 SELČ (00:30Z) a 02:30 SEČ (01:30Z)
        assertEquals(2, PragueTime.hourOf(Instant.parse("2026-10-25T00:30:00Z")))
        assertEquals(2, PragueTime.hourOf(Instant.parse("2026-10-25T01:30:00Z")))
        assertEquals(3, PragueTime.hourOf(Instant.parse("2026-10-25T02:30:00Z")))
        assertEquals(LocalDate.of(2026, 10, 25), PragueTime.localDate(Instant.parse("2026-10-25T22:59:00Z")))
        assertEquals(LocalDate.of(2026, 10, 26), PragueTime.localDate(Instant.parse("2026-10-25T23:00:00Z")))
    }

    @Test
    fun `23h den - jarni zmena casu vynecha hodinu 02_00`() {
        // 29. 3. 2026: 01:59 SEČ (00:59Z) → 03:00 SELČ (01:00Z)
        assertEquals(1, PragueTime.hourOf(Instant.parse("2026-03-29T00:59:00Z")))
        assertEquals(3, PragueTime.hourOf(Instant.parse("2026-03-29T01:00:00Z")))
        val hours = (0 until 23 * 60 step 30).map { PragueTime.hourOf(Instant.parse("2026-03-28T23:00:00Z").plusSeconds(it * 60L)) }.toSet()
        assertEquals((0..23).toSet() - 2, hours)
    }
}
