package cz.zasstojime.replay

import cz.zasstojime.db.Tables.INGEST_RUNS
import cz.zasstojime.db.Tables.STOP_EVENTS
import cz.zasstojime.db.Tables.TRIPS_DAILY
import cz.zasstojime.gtfs.GtfsCache
import cz.zasstojime.ingest.IngestService
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AC-05/AC-06: záznam přehraný přes službu sběru do PostgreSQL odpovídá přehrání čistou logikou.
 * Vlastní profil = vlastní instance aplikace, stav sběru neovlivní ostatní testy (např. /api/health).
 */
@QuarkusTest
@TestProfile(ReplayDbIT.Isolated::class)
class ReplayDbIT {
    class Isolated : QuarkusTestProfile

    @Inject
    lateinit var ingest: IngestService

    @Inject
    lateinit var gtfs: GtfsCache

    @Inject
    lateinit var dsl: DSLContext

    @Test
    fun `zaznam ulozeny do DB odpovida ciste logice`() {
        val expected = Replay.run()
        val dates = expected.trips.map { it.key.serviceDate }.toSet()
        val lastRunId = dsl.select(DSL.coalesce(DSL.max(INGEST_RUNS.ID), 0L)).from(INGEST_RUNS).fetchSingle().value1()

        // GtfsCache bean naplníme stejným výřezem jízdního řádu
        Replay.gtfsData().let { gtfs.replace(it.routes, it.stops, it.trips) }

        var cycles = 0
        Replay.forEachSnapshot { fetchTime, parsed ->
            assertTrue(ingest.apply(fetchTime, parsed))
            cycles++
        }

        val tripsDb = dsl.selectFrom(TRIPS_DAILY).where(TRIPS_DAILY.SERVICE_DATE.`in`(dates)).fetch()
        assertEquals(expected.trips.size, tripsDb.size)
        assertEquals(expected.trips.sumOf { it.depTotal }.toLong(), tripsDb.sumOf { it.depTotal.toLong() })
        assertEquals(expected.trips.sumOf { it.depLateSum }, tripsDb.sumOf { it.depLateSum })
        assertEquals(
            expected.trips.count { it.status.db == "finished" },
            tripsDb.count { it.status == "finished" },
        )
        val eventsDb = dsl.fetchCount(STOP_EVENTS, STOP_EVENTS.SERVICE_DATE.`in`(dates))
        assertEquals(expected.events.size, eventsDb)
        assertEquals(
            expected.events.values.count { it.depDelay != null },
            dsl.fetchCount(STOP_EVENTS, STOP_EVENTS.SERVICE_DATE.`in`(dates).and(STOP_EVENTS.DEP_DELAY.isNotNull)),
        )
        val runs = dsl.selectFrom(INGEST_RUNS).where(INGEST_RUNS.ID.gt(lastRunId)).fetch()
        assertEquals(cycles, runs.size)
        assertTrue(runs.all { it.ok })
        assertEquals(expected.events.size, runs.sumOf { it.stopEvents })
    }
}
