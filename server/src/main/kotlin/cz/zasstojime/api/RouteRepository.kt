package cz.zasstojime.api

import jakarta.enterprise.context.ApplicationScoped
import org.jooq.DSLContext
import org.jooq.Record
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

data class LineOption(val routeId: String, val line: String, val category: String)
data class StopOption(val nodeId: Int, val name: String, val headsign: String?, val sequence: Int)
data class DepartureOption(val time: String, val observedDays: Int)
data class TripHeader(
    val routeId: String,
    val line: String,
    val category: String,
    val headsign: String?,
    val status: String,
    val lastDelay: Int?,
    val lastSeenAt: Instant,
)
data class TripStop(
    val sequence: Int,
    val stopId: String,
    val nodeId: Int,
    val name: String?,
    val plannedArrival: Instant?,
    val plannedDeparture: Instant?,
    val arrivalDelay: Int?,
    val departureDelay: Int?,
)
data class RouteObservation(
    val date: LocalDate,
    val tripId: String,
    val plannedDeparture: Instant,
    val boardingDelay: Int?,
    val boardingEstimated: Boolean,
    val arrivalDelay: Int?,
    val running: Boolean = false,
)

@ApplicationScoped
class RouteRepository(private val dsl: DSLContext) {
    private val prague = ZoneId.of("Europe/Prague")
    fun lines(query: String): List<LineOption> = dsl.fetch(
        """SELECT route_id, short_name, category FROM routes
           WHERE short_name ILIKE ? ORDER BY short_name, route_id LIMIT 30""", "${query.trim()}%",
    ).map { LineOption(it.str("route_id"), it.str("short_name"), it.str("category")) }

    /** Observed platform visits, grouped into nodes and destination directions. */
    fun stops(line: String): List<StopOption> = dsl.fetch(
        """SELECT e.node_id, MIN(s.name) AS name, t.headsign, MIN(e.stop_seq)::integer AS sequence
           FROM stop_events e JOIN routes r ON r.route_id = e.route_id
             JOIN trips_daily t ON t.service_date = e.service_date AND t.trip_id = e.trip_id
             JOIN stops s ON s.stop_id = e.stop_id
           WHERE r.short_name = ? GROUP BY e.node_id, t.headsign
           ORDER BY t.headsign NULLS LAST, sequence, name""", line,
    ).map { StopOption(it.num("node_id"), it.str("name"), it.get("headsign", String::class.java), it.num("sequence")) }

    /** [headsign] omezí časy na jeden směr linky; `null` = všechny směry. */
    fun departures(line: String, fromNode: Int, dayType: String, since: LocalDate, headsign: String? = null): List<DepartureOption> = dsl.fetch(
        """SELECT to_char(e.planned_dep AT TIME ZONE 'Europe/Prague', 'HH24:MI') AS time,
                  COUNT(DISTINCT e.service_date)::integer AS observed_days
           FROM stop_events e JOIN routes r ON r.route_id = e.route_id
             JOIN calendar_days c ON c.day = e.service_date
             JOIN trips_daily t ON t.service_date = e.service_date AND t.trip_id = e.trip_id
           WHERE r.short_name = ? AND e.node_id = ? AND c.day_type = ?
             AND e.service_date >= ? AND e.planned_dep IS NOT NULL
             AND (CAST(? AS text) IS NULL OR t.headsign = ?)
           GROUP BY 1 ORDER BY 1""", line, fromNode, dayType, since, headsign, headsign,
    ).map { DepartureOption(it.str("time"), it.num("observed_days")) }

    /**
     * A direct trip is selected separately for each service day, never by historical trip_id.
     * A running trip that has not reached the destination yet is returned without arrival. With a known
     * [headsign] the direction is certain, so a finished trip whose destination was not measured is returned too.
     */
    fun observations(line: String, fromNode: Int, toNode: Int, time: String,
                     dayType: String, since: LocalDate, through: LocalDate, headsign: String? = null): List<RouteObservation> = dsl.fetch(
        """SELECT DISTINCT ON (a.service_date)
                  a.service_date, a.trip_id, a.planned_dep,
                  COALESCE(a.dep_delay, a.arr_delay) AS boarding_delay,
                  (a.dep_delay IS NULL AND a.arr_delay IS NOT NULL) AS boarding_estimated,
                  COALESCE(b.arr_delay, b.dep_delay) AS arrival_delay,
                  (t.status = 'running') AS running
           FROM stop_events a
             JOIN routes r ON r.route_id = a.route_id
             JOIN calendar_days c ON c.day = a.service_date
             JOIN trips_daily t ON t.service_date = a.service_date AND t.trip_id = a.trip_id
             LEFT JOIN LATERAL (
               SELECT e.stop_seq, e.arr_delay, e.dep_delay FROM stop_events e
               WHERE e.service_date = a.service_date AND e.trip_id = a.trip_id
                 AND e.stop_seq > a.stop_seq AND e.node_id = ?
               ORDER BY e.stop_seq LIMIT 1
             ) b ON true
           WHERE r.short_name = ? AND a.node_id = ?
             AND c.day_type = ? AND a.service_date >= ? AND a.service_date <= ?
             AND a.planned_dep IS NOT NULL
             AND to_char(a.planned_dep AT TIME ZONE 'Europe/Prague', 'HH24:MI') = ?
             AND (CAST(? AS text) IS NULL OR t.headsign = ?)
             AND (b.stop_seq IS NOT NULL OR t.status = 'running' OR CAST(? AS text) IS NOT NULL)
           ORDER BY a.service_date, (b.stop_seq IS NULL), a.planned_dep""",
        toNode, line, fromNode, dayType, since, through, time, headsign, headsign, headsign,
    ).map { row ->
        RouteObservation(row.get("service_date", LocalDate::class.java), row.str("trip_id"),
            row.get("planned_dep", OffsetDateTime::class.java).toInstant(),
            row.get("boarding_delay", Int::class.javaObjectType),
            row.get("boarding_estimated", Boolean::class.javaObjectType) ?: false,
            row.get("arrival_delay", Int::class.javaObjectType),
            row.get("running", Boolean::class.javaObjectType) ?: false)
    }

    fun tripHeader(date: LocalDate, tripId: String): TripHeader? = dsl.fetchOne(
        """SELECT route_id, route_short, category, headsign, status, last_delay, last_seen_at
           FROM trips_daily WHERE service_date = ? AND trip_id = ?""", date, tripId,
    )?.let { row ->
        TripHeader(row.str("route_id"), row.str("route_short"), row.str("category"),
            row.get("headsign", String::class.java), row.str("status"),
            row.get("last_delay", Int::class.javaObjectType),
            row.get("last_seen_at", OffsetDateTime::class.java).toInstant())
    }

    /** Measured stops of one trip; stops passed between two snapshots are missing (metodika – Měření odjezdů). */
    fun tripStops(date: LocalDate, tripId: String): List<TripStop> = dsl.fetch(
        """SELECT e.stop_seq, e.stop_id, e.node_id, s.name, e.planned_arr, e.planned_dep, e.arr_delay, e.dep_delay
           FROM stop_events e LEFT JOIN stops s ON s.stop_id = e.stop_id
           WHERE e.service_date = ? AND e.trip_id = ? ORDER BY e.stop_seq""", date, tripId,
    ).map { row ->
        TripStop(row.num("stop_seq"), row.str("stop_id"), row.num("node_id"), row.get("name", String::class.java),
            row.get("planned_arr", OffsetDateTime::class.java)?.toInstant(),
            row.get("planned_dep", OffsetDateTime::class.java)?.toInstant(),
            row.get("arr_delay", Int::class.javaObjectType), row.get("dep_delay", Int::class.javaObjectType))
    }

    /** A period is incomplete if a selected day has no successful run or >30 min of run gaps. */
    fun incomplete(since: LocalDate, through: LocalDate, dayType: String): Boolean {
        val start = since.atStartOfDay(prague).toOffsetDateTime()
        val end = through.plusDays(1).atStartOfDay(prague).toOffsetDateTime()
        return dsl.fetchOne(
            """WITH ordered_runs AS (
                 SELECT (finished_at AT TIME ZONE 'Europe/Prague')::date AS day,
                        finished_at,
                        LAG(finished_at) OVER (
                          PARTITION BY (finished_at AT TIME ZONE 'Europe/Prague')::date
                          ORDER BY finished_at) AS previous_at
                 FROM ingest_runs
                 WHERE ok = true AND started_at >= ?::timestamptz AND started_at < ?::timestamptz
               ), gaps AS (
                 SELECT day,
                        SUM(CASE WHEN previous_at IS NOT NULL
                                  AND EXTRACT(EPOCH FROM finished_at - previous_at) > 90
                                 THEN EXTRACT(EPOCH FROM finished_at - previous_at) - 30 ELSE 0 END) AS gap_s
                 FROM ordered_runs GROUP BY day
               )
               SELECT EXISTS (
                 SELECT 1 FROM calendar_days c LEFT JOIN gaps g ON g.day = c.day
                 WHERE c.day >= ? AND c.day <= ? AND c.day_type = ?
                   AND (g.day IS NULL OR g.gap_s > 1800)
               ) AS incomplete""",
            start, end, since, through, dayType,
        )?.get("incomplete", Boolean::class.javaObjectType) ?: true
    }
}

private fun Record.str(name: String): String = get(name, String::class.java)
private fun Record.num(name: String): Int = get(name, Int::class.javaObjectType)
