package cz.zasstojime.gtfs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** AC-04: české státní svátky 2026–2027 a typ dne. */
class CzechCalendarTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun `velikonocni nedele`() {
        assertEquals(d("2025-04-20"), CzechCalendar.easterSunday(2025))
        assertEquals(d("2026-04-05"), CzechCalendar.easterSunday(2026))
        assertEquals(d("2027-03-28"), CzechCalendar.easterSunday(2027))
        assertEquals(d("2028-04-16"), CzechCalendar.easterSunday(2028))
    }

    @Test
    fun `vsechny svatky 2026`() {
        assertEquals(
            listOf(
                "2026-01-01", "2026-04-03", "2026-04-06", "2026-05-01", "2026-05-08", "2026-07-05", "2026-07-06",
                "2026-09-28", "2026-10-28", "2026-11-17", "2026-12-24", "2026-12-25", "2026-12-26",
            ).map(::d),
            CzechCalendar.holidays(2026).keys.toList(),
        )
    }

    @Test
    fun `vsechny svatky 2027`() {
        assertEquals(
            listOf(
                "2027-01-01", "2027-03-26", "2027-03-29", "2027-05-01", "2027-05-08", "2027-07-05", "2027-07-06",
                "2027-09-28", "2027-10-28", "2027-11-17", "2027-12-24", "2027-12-25", "2027-12-26",
            ).map(::d),
            CzechCalendar.holidays(2027).keys.toList(),
        )
        assertEquals("Velký pátek", CzechCalendar.holidayName(d("2027-03-26")))
        assertEquals("Velikonoční pondělí", CzechCalendar.holidayName(d("2027-03-29")))
    }

    @Test
    fun `typ dne`() {
        assertEquals(DayType.WORKDAY, CzechCalendar.dayType(d("2026-10-02"))) // pátek
        assertEquals(DayType.SATURDAY, CzechCalendar.dayType(d("2026-10-03")))
        assertEquals(DayType.SUNDAY_HOLIDAY, CzechCalendar.dayType(d("2026-10-04")))
        assertEquals(DayType.SUNDAY_HOLIDAY, CzechCalendar.dayType(d("2026-10-28"))) // středa, svátek
        assertEquals(DayType.SUNDAY_HOLIDAY, CzechCalendar.dayType(d("2026-04-03"))) // Velký pátek
        assertEquals(DayType.SUNDAY_HOLIDAY, CzechCalendar.dayType(d("2027-05-08"))) // sobota a svátek
        assertEquals(DayType.WORKDAY, CzechCalendar.dayType(d("2026-04-07")))
    }
}
