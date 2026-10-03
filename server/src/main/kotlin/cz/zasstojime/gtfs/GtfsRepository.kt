package cz.zasstojime.gtfs

import cz.zasstojime.db.Tables.CALENDAR_DAYS
import cz.zasstojime.db.Tables.GTFS_TRIPS
import cz.zasstojime.db.Tables.ROUTES
import cz.zasstojime.db.Tables.STOPS
import jakarta.enterprise.context.ApplicationScoped
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.LocalDate
import java.time.OffsetDateTime

@ApplicationScoped
class GtfsRepository(private val dsl: DSLContext) {
    private val chunk = 5_000

    /** UPSERT, nic se nemaže (FR-02): staré spoje a zastávky můžou být v historii. */
    fun save(data: GtfsData) {
        dsl.transaction { cfg ->
            val tx = DSL.using(cfg)
            data.routes.chunked(chunk).forEach { part ->
                val batch = tx.batch(
                    tx.insertInto(
                        ROUTES, ROUTES.ROUTE_ID, ROUTES.SHORT_NAME, ROUTES.ROUTE_TYPE, ROUTES.CATEGORY,
                        ROUTES.IS_NIGHT, ROUTES.IS_SUBSTITUTE, ROUTES.UPDATED_AT,
                    ).values(null as String?, null, null, null, null, null, null)
                        .onConflict(ROUTES.ROUTE_ID).doUpdate()
                        .set(ROUTES.SHORT_NAME, DSL.excluded(ROUTES.SHORT_NAME))
                        .set(ROUTES.ROUTE_TYPE, DSL.excluded(ROUTES.ROUTE_TYPE))
                        .set(ROUTES.CATEGORY, DSL.excluded(ROUTES.CATEGORY))
                        .set(ROUTES.IS_NIGHT, DSL.excluded(ROUTES.IS_NIGHT))
                        .set(ROUTES.IS_SUBSTITUTE, DSL.excluded(ROUTES.IS_SUBSTITUTE))
                        .set(ROUTES.UPDATED_AT, DSL.excluded(ROUTES.UPDATED_AT)),
                )
                val now = OffsetDateTime.now()
                part.forEach {
                    batch.bind(it.routeId, it.shortName, it.routeType.toShort(), it.category.db, it.isNight, it.isSubstitute, now)
                }
                batch.execute()
            }
            data.stops.chunked(chunk).forEach { part ->
                val batch = tx.batch(
                    tx.insertInto(
                        STOPS, STOPS.STOP_ID, STOPS.NAME, STOPS.NODE_ID, STOPS.PLATFORM_CODE,
                        STOPS.LAT, STOPS.LON, STOPS.UPDATED_AT,
                    ).values(null as String?, null, null, null, null, null, null)
                        .onConflict(STOPS.STOP_ID).doUpdate()
                        .set(STOPS.NAME, DSL.excluded(STOPS.NAME))
                        .set(STOPS.NODE_ID, DSL.excluded(STOPS.NODE_ID))
                        .set(STOPS.PLATFORM_CODE, DSL.excluded(STOPS.PLATFORM_CODE))
                        .set(STOPS.LAT, DSL.excluded(STOPS.LAT))
                        .set(STOPS.LON, DSL.excluded(STOPS.LON))
                        .set(STOPS.UPDATED_AT, DSL.excluded(STOPS.UPDATED_AT)),
                )
                val now = OffsetDateTime.now()
                part.forEach { batch.bind(it.stopId, it.name, it.nodeId, it.platformCode?.take(8), it.lat, it.lon, now) }
                batch.execute()
            }
            data.trips.chunked(chunk).forEach { part ->
                val batch = tx.batch(
                    tx.insertInto(GTFS_TRIPS, GTFS_TRIPS.TRIP_ID, GTFS_TRIPS.ROUTE_ID, GTFS_TRIPS.FIRST_DEPARTURE_S, GTFS_TRIPS.UPDATED_AT)
                        .values(null as String?, null, null, null)
                        .onConflict(GTFS_TRIPS.TRIP_ID).doUpdate()
                        .set(GTFS_TRIPS.ROUTE_ID, DSL.excluded(GTFS_TRIPS.ROUTE_ID))
                        .set(GTFS_TRIPS.FIRST_DEPARTURE_S, DSL.excluded(GTFS_TRIPS.FIRST_DEPARTURE_S))
                        .set(GTFS_TRIPS.UPDATED_AT, DSL.excluded(GTFS_TRIPS.UPDATED_AT)),
                )
                val now = OffsetDateTime.now()
                part.forEach { batch.bind(it.tripId, it.routeId, it.firstDepartureS, now) }
                batch.execute()
            }
        }
    }

    fun loadRoutes(): List<RouteInfo> = dsl.selectFrom(ROUTES).fetch { r ->
        RouteInfo(r.routeId, r.shortName, r.routeType.toInt(), Category.fromDb(r.category), r.isNight, r.isSubstitute)
    }

    fun loadStops(): List<StopInfo> = dsl.selectFrom(STOPS).fetch { r ->
        StopInfo(r.stopId, r.name, r.nodeId, r.platformCode, r.lat, r.lon)
    }

    /** Spoje z importů za posledních [days] dní; starší `trip_id` už v provozu nejsou. */
    fun loadRecentTrips(days: Long = 3): List<GtfsTrip> {
        val since = lastImportAt()?.minusDays(days) ?: return emptyList()
        return dsl.select(GTFS_TRIPS.TRIP_ID, GTFS_TRIPS.ROUTE_ID, GTFS_TRIPS.FIRST_DEPARTURE_S)
            .from(GTFS_TRIPS)
            .where(GTFS_TRIPS.UPDATED_AT.ge(since))
            .fetch { GtfsTrip(it.value1(), it.value2(), it.value3()) }
    }

    /** Čas posledního importu (nejnovější `updated_at` spojů), `null` = ještě nebyl. */
    fun lastImportAt(): OffsetDateTime? = dsl.select(DSL.max(GTFS_TRIPS.UPDATED_AT)).from(GTFS_TRIPS).fetchOne()?.value1()

    /** FR-04: typy dnů včetně svátků; přepisuje, aby se dala opravit chyba ve výpočtu. */
    fun saveCalendar(from: LocalDate, toInclusive: LocalDate) {
        dsl.transaction { cfg ->
            val tx = DSL.using(cfg)
            val batch = tx.batch(
                tx.insertInto(CALENDAR_DAYS, CALENDAR_DAYS.DAY, CALENDAR_DAYS.DAY_TYPE, CALENDAR_DAYS.HOLIDAY_NAME)
                    .values(null as LocalDate?, null, null)
                    .onConflict(CALENDAR_DAYS.DAY).doUpdate()
                    .set(CALENDAR_DAYS.DAY_TYPE, DSL.excluded(CALENDAR_DAYS.DAY_TYPE))
                    .set(CALENDAR_DAYS.HOLIDAY_NAME, DSL.excluded(CALENDAR_DAYS.HOLIDAY_NAME)),
            )
            var day = from
            while (!day.isAfter(toInclusive)) {
                batch.bind(day, CzechCalendar.dayType(day).db, CzechCalendar.holidayName(day))
                day = day.plusDays(1)
            }
            batch.execute()
        }
    }
}
