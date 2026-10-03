package cz.zasstojime.gtfs

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay

enum class DayType(val db: String) {
    WORKDAY("workday"),
    SATURDAY("saturday"),
    SUNDAY_HOLIDAY("sunday_holiday"),
}

/** České státní svátky a typ dne (ADR-0046). */
object CzechCalendar {
    private val fixedHolidays: Map<MonthDay, String> = linkedMapOf(
        MonthDay.of(1, 1) to "Den obnovy samostatného českého státu, Nový rok",
        MonthDay.of(5, 1) to "Svátek práce",
        MonthDay.of(5, 8) to "Den vítězství",
        MonthDay.of(7, 5) to "Den slovanských věrozvěstů Cyrila a Metoděje",
        MonthDay.of(7, 6) to "Den upálení mistra Jana Husa",
        MonthDay.of(9, 28) to "Den české státnosti",
        MonthDay.of(10, 28) to "Den vzniku samostatného československého státu",
        MonthDay.of(11, 17) to "Den boje za svobodu a demokracii a Mezinárodní den studentstva",
        MonthDay.of(12, 24) to "Štědrý den",
        MonthDay.of(12, 25) to "1. svátek vánoční",
        MonthDay.of(12, 26) to "2. svátek vánoční",
    )

    /** Velikonoční neděle (gregoriánský kalendář, anonymní algoritmus Meeus/Jones/Butcher). */
    fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }

    fun holidays(year: Int): Map<LocalDate, String> {
        val easter = easterSunday(year)
        val result = sortedMapOf<LocalDate, String>()
        fixedHolidays.forEach { (md, name) -> result[md.atYear(year)] = name }
        result[easter.minusDays(2)] = "Velký pátek"
        result[easter.plusDays(1)] = "Velikonoční pondělí"
        return result
    }

    fun holidayName(date: LocalDate): String? = holidays(date.year)[date]

    fun dayType(date: LocalDate): DayType = when {
        holidayName(date) != null || date.dayOfWeek == DayOfWeek.SUNDAY -> DayType.SUNDAY_HOLIDAY
        date.dayOfWeek == DayOfWeek.SATURDAY -> DayType.SATURDAY
        else -> DayType.WORKDAY
    }
}
