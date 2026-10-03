package cz.zasstojime.ingest

import cz.zasstojime.gtfs.Category
import java.time.Instant
import java.time.LocalDate

/** Spoj = provozní den + trip_id (ADR-0003). */
data class TripKey(val serviceDate: LocalDate, val tripId: String)

enum class TripStatus(val db: String?) {
    /** Zatím jen viděn (např. `before_track`), do DB se neukládá. */
    NEW(null),
    RUNNING("running"),
    FINISHED("finished"),
    CANCELED("canceled");

    companion object {
        fun fromDb(value: String): TripStatus = entries.first { it.db == value }
    }
}

enum class FinishedBy(val db: String) {
    AFTER_TRACK("after_track"),
    TIMEOUT("timeout");

    companion object {
        fun fromDb(value: String?): FinishedBy? = entries.firstOrNull { it.db == value }
    }
}

/** Rozpracovaná zastávka: hodnoty se průběžně přepisují až do posunu na další (ADR-0041). */
class Pending(
    val seq: Int,
    val stopId: String?,
    val plannedArr: Instant?,
    val plannedDep: Instant?,
) {
    var arrDelay: Int? = null
    var depDelay: Int? = null
}

/** Stav spoje v paměti (ADR-0038). Pole odpovídají `trips_daily`. */
class TripState(
    val key: TripKey,
    var routeId: String,
    var routeShort: String,
    var category: Category,
    var headsign: String?,
    var vehicleId: String?,
    var tripStartAt: Instant?,
    val firstSeenAt: Instant,
    var lastSeenAt: Instant,
) {
    var status: TripStatus = TripStatus.NEW
    var finishedBy: FinishedBy? = null
    var finishedAt: Instant? = null

    /** Zpoždění posledního změřeného odjezdu. */
    var lastDelay: Int? = null
    var maxDelay: Int? = null
    var maxDelayAt: Instant? = null
    var maxDelayStop: String? = null

    /** Poslední uzavřená (uložená) zastávka. */
    var lastStopSeq: Int? = null

    var depTotal = 0
    var depOnTime = 0
    var depEarly = 0
    var depLate = 0
    var depLateSum = 0L
    var validSnapshots = 0
    var flagged = false
    var verified = false
    var excluded = false

    /** Nejvyšší dosud platná sekvence zastávky (ADR-0040, ADR-0052). */
    var maxSeq = 0
    var lastValidAt: Instant? = null
    var lastOriginAt: Instant? = null

    /** `origin_timestamp`, se kterým byl uzavřen poslední změřený odjezd (ADR-0035). */
    var lastDepOriginAt: Instant? = null
    var pending: Pending? = null

    /** Spoj se od posledního zápisu změnil způsobem, který stojí za UPSERT (FR-13). */
    var dirty = false

    val persistable: Boolean get() = status != TripStatus.NEW
}

/** Jeden průjezd zastávkou (`stop_events`, ADR-0044). */
data class StopEvent(
    val serviceDate: LocalDate,
    val tripId: String,
    val stopSeq: Int,
    val routeId: String,
    val stopId: String,
    val nodeId: Int,
    val plannedArr: Instant?,
    val plannedDep: Instant?,
    val arrDelay: Int?,
    val depDelay: Int?,
)

/** Hranice dochvilnosti odjezdu (metodika – Měření odjezdů, ADR-0007, ADR-0034). */
enum class Punctuality {
    EARLY,
    ON_TIME,
    LATE;

    companion object {
        const val EARLY_MAX_S = -60
        const val LATE_MIN_S = 180

        fun of(depDelay: Int): Punctuality = when {
            depDelay <= EARLY_MAX_S -> EARLY
            depDelay >= LATE_MIN_S -> LATE
            else -> ON_TIME
        }
    }
}
