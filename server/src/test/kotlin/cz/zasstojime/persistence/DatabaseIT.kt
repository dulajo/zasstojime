package cz.zasstojime.persistence

import cz.zasstojime.db.Tables.CALENDAR_DAYS
import cz.zasstojime.db.Tables.INGEST_RUNS
import cz.zasstojime.db.Tables.STOP_EVENTS
import cz.zasstojime.db.Tables.TRIPS_DAILY
import cz.zasstojime.gtfs.Category
import cz.zasstojime.gtfs.GtfsData
import cz.zasstojime.gtfs.GtfsRepository
import cz.zasstojime.gtfs.GtfsTrip
import cz.zasstojime.gtfs.RouteInfo
import cz.zasstojime.gtfs.StopInfo
import cz.zasstojime.ingest.FinishedBy
import cz.zasstojime.ingest.IngestRepository
import cz.zasstojime.ingest.IngestRun
import cz.zasstojime.ingest.StopEvent
import cz.zasstojime.ingest.TripKey
import cz.zasstojime.ingest.TripState
import cz.zasstojime.ingest.TripStatus
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.jooq.DSLContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** AC-06: migrace, UPSERT a oddíly `stop_events` proti skutečnému PostgreSQL (Dev Services). */
@QuarkusTest
class DatabaseIT {
    @Inject
    lateinit var dsl: DSLContext

    @Inject
    lateinit var partitions: PartitionManager

    @Inject
    lateinit var ingest: IngestRepository

    @Inject
    lateinit var gtfs: GtfsRepository

    @Test
    fun `oddily se zakladaji dopredu a stare se mazou`() {
        partitions.ensure(YearMonth.of(2024, 1))
        partitions.ensure(YearMonth.of(2025, 9))
        partitions.maintain(LocalDate.of(2026, 10, 3))
        val existing = partitions.existing()
        assertTrue(YearMonth.of(2026, 9) in existing)
        assertTrue(YearMonth.of(2026, 10) in existing)
        assertTrue(YearMonth.of(2026, 11) in existing)
        assertTrue(YearMonth.of(2025, 9) in existing, "13 měsíců zpět zůstává")
        assertTrue(YearMonth.of(2024, 1) !in existing, "starší se maže")
    }

    @Test
    fun `cyklus se zapise a UPSERT neprepise rucni priznaky spravce`() {
        val day = LocalDate.of(2026, 8, 14)
        val t0 = Instant.parse("2026-08-14T06:00:00Z")
        val trip = TripState(TripKey(day, "22_1"), "L22", "22", Category.TRAM, "Bílá Hora", "9301", t0, t0, t0).apply {
            status = TripStatus.RUNNING
            depTotal = 1
            depOnTime = 1
            lastDelay = 30
            maxDelay = 30
            lastStopSeq = 1
            lastValidAt = t0
            lastOriginAt = t0
            lastDepOriginAt = t0
        }
        val event = StopEvent(day, "22_1", 1, "L22", "U1Z1P", 1, t0, t0.plusSeconds(30), 10, 30)
        ingest.persistCycle(listOf(trip), listOf(event), IngestRun(t0, t0, true, 100, 50, 1))

        // správce potvrdí spoj
        dsl.update(TRIPS_DAILY).set(TRIPS_DAILY.VERIFIED, true).where(TRIPS_DAILY.TRIP_ID.eq("22_1")).execute()

        trip.apply {
            status = TripStatus.FINISHED
            finishedBy = FinishedBy.AFTER_TRACK
            finishedAt = t0.plusSeconds(600)
            depTotal = 2
            depLate = 1
            depLateSum = 400
            maxDelay = 400
            flagged = true
        }
        ingest.persistCycle(listOf(trip), listOf(event.copy(depDelay = 35)), IngestRun(t0, t0, true, 100, 50, 1))

        val row = dsl.selectFrom(TRIPS_DAILY).where(TRIPS_DAILY.SERVICE_DATE.eq(day)).fetchSingle()
        assertEquals("finished", row.status)
        assertEquals("after_track", row.finishedBy)
        assertEquals(2, row.depTotal)
        assertEquals(400L, row.depLateSum)
        assertEquals(true, row.verified, "UPSERT sběru nesmí přepsat verified")
        assertEquals(true, row.flagged)

        val events = dsl.selectFrom(STOP_EVENTS).where(STOP_EVENTS.SERVICE_DATE.eq(day)).fetch()
        assertEquals(1, events.size)
        assertEquals(35, events[0].depDelay)
        assertEquals(1, dsl.fetchCount(dsl.selectFrom("stop_events_y2026m08")))

        val restored = ingest.loadTrips(day).single { it.key.tripId == "22_1" }
        assertEquals(TripStatus.FINISHED, restored.status)
        assertEquals(FinishedBy.AFTER_TRACK, restored.finishedBy)
        assertEquals(t0.plusSeconds(600), restored.finishedAt)
        assertEquals(400, restored.maxDelay)
        assertEquals(1, restored.maxSeq)
        assertEquals(t0, restored.lastDepOriginAt)
        assertNull(restored.pending)
        assertEquals(Category.TRAM, restored.category)

        assertTrue(dsl.fetchCount(INGEST_RUNS, INGEST_RUNS.OK.isTrue) >= 2)
        assertTrue(ingest.lastSuccessfulRunAt() != null)
    }

    @Test
    fun `chybny zapis odvola celou transakci cyklu`() {
        val day = LocalDate.of(2026, 7, 1)
        val t0 = Instant.parse("2026-07-01T06:00:00Z")
        val broken = TripState(TripKey(day, "bad"), "L22", "22", Category.TRAM, null, null, t0, t0, t0).apply {
            status = TripStatus.RUNNING
            depTotal = 5 // porušuje CHECK dep_total = on_time + early + late
        }
        val event = StopEvent(day, "bad", 1, "L22", "U1Z1P", 1, null, null, null, 10)
        val before = dsl.fetchCount(INGEST_RUNS)
        runCatching { ingest.persistCycle(listOf(broken), listOf(event), IngestRun(t0, t0, true)) }
        assertEquals(0, dsl.fetchCount(STOP_EVENTS, STOP_EVENTS.TRIP_ID.eq("bad")))
        assertEquals(before, dsl.fetchCount(INGEST_RUNS))
    }

    @Test
    fun `import GTFS je UPSERT a kalendar obsahuje svatky`() {
        val data = GtfsData(
            routes = listOf(RouteInfo("L22", "22", 0, Category.TRAM, isNight = false, isSubstitute = false)),
            stops = listOf(StopInfo("U1Z1P", "Boletická", 1, "A", 50.1, 14.5), StopInfo("T1", "Km 2,8", null, null, null, null)),
            trips = listOf(GtfsTrip("22_1", "L22", 18_000), GtfsTrip("907_1", "L907", 87_180)),
        )
        gtfs.save(data)
        gtfs.save(GtfsData(data.routes.map { it.copy(shortName = "22N") }, data.stops, data.trips))
        assertEquals("22N", gtfs.loadRoutes().single { it.routeId == "L22" }.shortName)
        assertNull(gtfs.loadStops().single { it.stopId == "T1" }.nodeId)
        assertEquals(87_180, gtfs.loadRecentTrips().single { it.tripId == "907_1" }.firstDepartureS)

        gtfs.saveCalendar(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31))
        val holidays = dsl.selectFrom(CALENDAR_DAYS).where(CALENDAR_DAYS.HOLIDAY_NAME.isNotNull)
            .and(CALENDAR_DAYS.DAY.between(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31))).fetch()
        assertEquals(26, holidays.size)
        assertTrue(holidays.all { it.dayType == "sunday_holiday" })
        assertEquals(
            "saturday",
            dsl.select(CALENDAR_DAYS.DAY_TYPE).from(CALENDAR_DAYS).where(CALENDAR_DAYS.DAY.eq(LocalDate.of(2026, 10, 3))).fetchSingle().value1(),
        )
    }
}
