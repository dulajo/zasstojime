package cz.zasstojime.aggregation

import cz.zasstojime.gtfs.Category
import cz.zasstojime.time.PragueTime
import jakarta.enterprise.context.ApplicationScoped
import org.jooq.DSLContext
import org.jooq.Record
import java.time.Instant
import java.time.LocalDate

data class HourlyPoint(val hour: Int, val category: String, val totals: DailyTotals)
data class DayCoverage(val dataAsOf: Instant?, val incomplete: Boolean)

@ApplicationScoped
class DashboardRepository(private val dsl: DSLContext) {
    fun loadDay(day: LocalDate): DaySnapshot? {
        val rows = dsl.fetch("SELECT * FROM daily_network_stats WHERE service_date = ?", day)
        if (rows.isEmpty()) return null
        val network = rows.firstOrNull { it.get("category", String::class.java) == "all" }?.totals()
            ?: return null
        val categories = rows.filter { it.get("category", String::class.java) != "all" }
            .associate { Category.fromDb(it.get("category", String::class.java)) to it.totals() }
        val routes = dsl.fetch(
            """SELECT d.*, r.short_name, r.category
               FROM daily_route_stats d LEFT JOIN routes r ON r.route_id = d.route_id
               WHERE d.service_date = ?""", day,
        ).associate { row ->
            val id = row.get("route_id", String::class.java)
            id to RouteTotals(id, row.get("short_name", String::class.java) ?: id,
                Category.fromDb(row.get("category", String::class.java) ?: "other"), row.totals())
        }
        return DaySnapshot(day, coverage(day).dataAsOf, network, categories, routes, record(day), 0, 0)
    }

    fun record(day: LocalDate): RecordTrip? = dsl.fetchOne(
        """SELECT trip_id, route_short, headsign, max_delay, max_delay_at, max_delay_stop, flagged, verified
           FROM trips_daily WHERE service_date = ? AND excluded = false AND max_delay IS NOT NULL
             AND category <> ALL(?)
           ORDER BY max_delay DESC, trip_id LIMIT 1""", day, HIDDEN_CATEGORIES,
    )?.let { row ->
        RecordTrip(row.get("trip_id", String::class.java), row.get("route_short", String::class.java),
            row.get("headsign", String::class.java), row.int("max_delay"),
            row.get("max_delay_at", java.time.OffsetDateTime::class.java)?.toInstant(),
            row.get("max_delay_stop", String::class.java),
            row.get("flagged", Boolean::class.javaObjectType) ?: false,
            row.get("verified", Boolean::class.javaObjectType) ?: false)
    }

    fun hourly(day: LocalDate): List<HourlyPoint> = dsl.fetch(
        "SELECT * FROM hourly_stats WHERE service_date = ? ORDER BY hour, category", day,
    ).map { HourlyPoint(it.int("hour"), it.get("category", String::class.java), it.totals()) }
        .filter { Category.fromDb(it.category).public }

    /** Gaps between successful runs longer than 90 s contribute their excess over 30 s. */
    fun coverage(day: LocalDate): DayCoverage {
        val start = day.atStartOfDay(PragueTime.ZONE).toOffsetDateTime()
        val end = day.plusDays(1).atTime(6, 0).atZone(PragueTime.ZONE).toOffsetDateTime()
        val row = dsl.fetchOne(
            """SELECT MAX(finished_at) AS data_as_of,
                      COALESCE(SUM(CASE WHEN previous_at IS NOT NULL
                           AND EXTRACT(EPOCH FROM finished_at - previous_at) > 90
                           THEN EXTRACT(EPOCH FROM finished_at - previous_at) - 30 ELSE 0 END), 0) AS gap_seconds
               FROM (SELECT finished_at, LAG(finished_at) OVER (ORDER BY finished_at) AS previous_at
                     FROM ingest_runs WHERE ok = true AND finished_at >= ?::timestamptz
                       AND finished_at < ?::timestamptz) runs""",
            start, end,
        ) ?: return DayCoverage(null, true)
        val asOf = row.get("data_as_of", java.time.OffsetDateTime::class.java)?.toInstant()
        val gap = row.get("gap_seconds", java.math.BigDecimal::class.java)?.toDouble() ?: 0.0
        return DayCoverage(asOf, asOf == null || gap > 1800)
    }
}

private val HIDDEN_CATEGORIES = Category.entries.filterNot { it.public }.map { it.db }.toTypedArray()

private fun Record.int(name: String): Int = if (field(name) == null) 0 else get(name, Int::class.javaObjectType) ?: 0

private fun Record.long(name: String): Long = if (field(name) == null) 0L else get(name, Long::class.javaObjectType) ?: 0L

private fun Record.totals() = DailyTotals(
    tripsTotal = int("trips_total"), tripsFinished = int("trips_finished"),
    tripsCanceled = int("trips_canceled"), depTotal = int("dep_total"),
    depOnTime = int("dep_on_time"), depEarly = int("dep_early"), depLate = int("dep_late"),
    depLateSum = long("dep_late_sum"), sumMaxDelay = long("sum_max_delay"), maxDelay = int("max_delay"),
)
