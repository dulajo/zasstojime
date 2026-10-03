package cz.zasstojime.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Všechny dny a hodiny se počítají v Europe/Prague (NFR-07). */
object PragueTime {
    val ZONE: ZoneId = ZoneId.of("Europe/Prague")

    const val SECONDS_PER_DAY = 86_400

    fun localDate(instant: Instant): LocalDate = instant.atZone(ZONE).toLocalDate()

    /** Hodina 0–23 v Europe/Prague. Na podzim se dvě hodiny 02:00 sečtou, na jaře hodina 02:00 chybí. */
    fun hourOf(instant: Instant): Int = instant.atZone(ZONE).hour

    /**
     * Provozní den spoje (ADR-0050): lokální datum začátku spoje,
     * mínus 1 den, pokud jízdní řád uvádí výjezd ≥ 24:00.
     */
    fun serviceDate(tripStart: Instant, scheduledFirstDepartureS: Int?): LocalDate {
        val date = localDate(tripStart)
        return if (scheduledFirstDepartureS != null && scheduledFirstDepartureS >= SECONDS_PER_DAY) date.minusDays(1) else date
    }
}
