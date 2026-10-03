package cz.zasstojime.aggregation

import jakarta.enterprise.context.ApplicationScoped
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.LocalDate

/** Materialized day and hour totals; a flush replaces a whole day atomically. */
@ApplicationScoped
class AggregationRepository(private val dsl: DSLContext) {
    fun flush(snapshot: DaySnapshot) {
        dsl.transaction { cfg ->
            val tx = DSL.using(cfg)
            val day = snapshot.date
            tx.execute("DELETE FROM daily_network_stats WHERE service_date = ?", day)
            tx.execute("DELETE FROM daily_route_stats WHERE service_date = ?", day)
            tx.execute("DELETE FROM hourly_stats WHERE service_date = ?", day)
            saveNetwork(tx, day, "all", snapshot.network)
            snapshot.categories.forEach { (category, totals) -> saveNetwork(tx, day, category.db, totals) }
            snapshot.routes.values.forEach { route ->
                val t = route.totals
                tx.execute(
                    """INSERT INTO daily_route_stats
                       (service_date, route_id, trips_total, trips_finished, trips_canceled,
                        dep_total, dep_on_time, dep_early, dep_late, dep_late_sum, sum_max_delay, max_delay)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    day, route.routeId, t.tripsTotal, t.tripsFinished, t.tripsCanceled,
                    t.depTotal, t.depOnTime, t.depEarly, t.depLate, t.depLateSum, t.sumMaxDelay, t.maxDelay,
                )
            }
            // Hour is the scheduled local departure hour. The repeated autumn 02:00 hour is combined.
            tx.execute(
                """INSERT INTO hourly_stats
                   (service_date, hour, category, dep_total, dep_on_time, dep_early, dep_late, dep_late_sum)
                   SELECT e.service_date,
                          EXTRACT(HOUR FROM e.planned_dep AT TIME ZONE 'Europe/Prague')::smallint,
                          t.category,
                          COUNT(*)::integer,
                          COUNT(*) FILTER (WHERE e.dep_delay BETWEEN -59 AND 179)::integer,
                          COUNT(*) FILTER (WHERE e.dep_delay <= -60)::integer,
                          COUNT(*) FILTER (WHERE e.dep_delay >= 180)::integer,
                          COALESCE(SUM(e.dep_delay) FILTER (WHERE e.dep_delay >= 180), 0)::bigint
                   FROM stop_events e JOIN trips_daily t
                     ON (t.service_date = e.service_date AND t.trip_id = e.trip_id)
                   WHERE e.service_date = ? AND e.planned_dep IS NOT NULL AND e.dep_delay IS NOT NULL
                   GROUP BY e.service_date, 2, t.category""",
                day,
            )
        }
    }

    private fun saveNetwork(tx: DSLContext, day: LocalDate, category: String, t: DailyTotals) {
        tx.execute(
            """INSERT INTO daily_network_stats
               (service_date, category, trips_total, trips_finished, trips_canceled,
                dep_total, dep_on_time, dep_early, dep_late, dep_late_sum, sum_max_delay)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            day, category, t.tripsTotal, t.tripsFinished, t.tripsCanceled,
            t.depTotal, t.depOnTime, t.depEarly, t.depLate, t.depLateSum, t.sumMaxDelay,
        )
    }

    /** Rebuild the affected month from source events so retries and late events cannot double count. */
    fun rebuildStopRouteMonth(day: LocalDate) {
        val month = day.withDayOfMonth(1)
        val end = month.plusMonths(1)
        dsl.transaction { cfg ->
            val tx = DSL.using(cfg)
            tx.execute("DELETE FROM stop_route_hourly WHERE month = ?", month)
            tx.execute(
                """INSERT INTO stop_route_hourly
                   (month, route_id, node_id, day_type, hour, dep_total, dep_on_time,
                    dep_early, dep_late, dep_late_sum, max_delay)
                   SELECT ?, e.route_id, e.node_id, c.day_type,
                          EXTRACT(HOUR FROM e.planned_dep AT TIME ZONE 'Europe/Prague')::smallint,
                          COUNT(*)::integer,
                          COUNT(*) FILTER (WHERE e.dep_delay BETWEEN -59 AND 179)::integer,
                          COUNT(*) FILTER (WHERE e.dep_delay <= -60)::integer,
                          COUNT(*) FILTER (WHERE e.dep_delay >= 180)::integer,
                          COALESCE(SUM(e.dep_delay) FILTER (WHERE e.dep_delay >= 180), 0)::bigint,
                          GREATEST(0, COALESCE(MAX(e.dep_delay), 0))::integer
                   FROM stop_events e JOIN calendar_days c ON c.day = e.service_date
                   WHERE e.service_date >= ? AND e.service_date < ?
                     AND e.planned_dep IS NOT NULL AND e.dep_delay IS NOT NULL
                   GROUP BY e.route_id, e.node_id, c.day_type, 5""",
                month, month, end,
            )
        }
    }
}
