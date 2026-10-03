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
data class RouteObservation(
    val date: LocalDate,
    val tripId: String,
    val plannedDeparture: Instant,
    val boardingDelay: Int?,
    val boardingEstimated: Boolean,
    val arrivalDelay: Int?,
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

    fun departures(line: String, fromNode: Int, dayType: String, since: LocalDate): List<DepartureOption> = dsl.fetch(
        """SELECT to_char(e.planned_dep AT TIME ZONE 'Europe/Prague', 'HH24:MI') AS time,
                  COUNT(DISTINCT e.service_date)::integer AS observed_days
           FROM stop_events e JOIN routes r ON r.route_id = e.route_id
             JOIN calendar_days c ON c.day = e.service_date
           WHERE r.short_name = ? AND e.node_id = ? AND c.day_type = ?
             AND e.service_date >= ? AND e.planned_dep IS NOT NULL
           GROUP BY 1 ORDER BY 1""", line, fromNode, dayType, since,
    ).map { DepartureOption(it.str("time"), it.num("observed_days")) }

    /** A direct trip is selected separately for each service day, never by historical trip_id. */
    fun observations(line: String, fromNode: Int, toNode: Int, time: String,
                     dayType: String, since: LocalDate, through: LocalDate): List<RouteObservation> = dsl.fetch(
        """SELECT DISTINCT ON (a.service_date)
                  a.service_date, a.trip_id, a.planned_dep,
                  COALESCE(a.dep_delay, a.arr_delay) AS boarding_delay,
                  (a.dep_delay IS NULL AND a.arr_delay IS NOT NULL) AS boarding_estimated,
                  COALESCE(b.arr_delay, b.dep_delay) AS arrival_delay
           FROM stop_events a JOIN stop_events b
             ON b.service_date = a.service_date AND b.trip_id = a.trip_id AND b.stop_seq > a.stop_seq
             JOIN routes r ON r.route_id = a.route_id
             JOIN calendar_days c ON c.day = a.service_date
           WHERE r.short_name = ? AND a.node_id = ? AND b.node_id = ?
             AND c.day_type = ? AND a.service_date >= ? AND a.service_date <= ?
             AND a.planned_dep IS NOT NULL
             AND to_char(a.planned_dep AT TIME ZONE 'Europe/Prague', 'HH24:MI') = ?
           ORDER BY a.service_date, a.planned_dep, b.stop_seq""",
        line, fromNode, toNode, dayType, since, through, time,
    ).map { row ->
        RouteObservation(row.get("service_date", LocalDate::class.java), row.str("trip_id"),
            row.get("planned_dep", OffsetDateTime::class.java).toInstant(),
            row.get("boarding_delay", Int::class.javaObjectType),
            row.get("boarding_estimated", Boolean::class.javaObjectType) ?: false,
            row.get("arrival_delay", Int::class.javaObjectType))
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
