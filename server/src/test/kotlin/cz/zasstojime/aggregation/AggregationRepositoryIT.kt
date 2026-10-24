package cz.zasstojime.aggregation

import cz.zasstojime.gtfs.Category
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.LocalDate

@QuarkusTest
class AggregationRepositoryIT {
    @Inject lateinit var ingest: IngestRepository
    @Inject lateinit var aggregate: AggregationRepository
    @Inject lateinit var dashboard: DashboardRepository
    @Inject lateinit var dsl: DSLContext

    @Test
    fun `day and hour totals survive flush and are served by API`() {
        val day = LocalDate.of(2026, 6, 12)
        val at = Instant.parse("2026-06-12T05:05:00Z")
        val trip = TripState(TripKey(day, "aggregation-fixture"), "AGG", "97", Category.TRAM,
            "Centrum", null, at, at, at).apply {
            status = TripStatus.FINISHED
            depTotal = 1
            depLate = 1
            depLateSum = 240
            maxDelay = 240
        }
        val event = StopEvent(day, trip.key.tripId, 1, trip.routeId, "AGG-STOP", 98765,
            at.minusSeconds(30), at, 200, 240)
        val destination = StopEvent(day, trip.key.tripId, 2, trip.routeId, "AGG-DEST", 98766,
            at.plusSeconds(600), at.plusSeconds(610), 300, null)
        val metro = TripState(TripKey(day, "aggregation-metro"), "AGG-M", "A", Category.METRO,
            "Depo", null, at, at, at).apply {
            status = TripStatus.FINISHED
            depTotal = 5
            depOnTime = 5
            maxDelay = 600
        }
        ingest.persistCycle(listOf(trip, metro), listOf(event, destination), IngestRun(at, at, true, 2, 1, 2))
        dsl.execute("INSERT INTO calendar_days(day, day_type) VALUES (?, 'workday') ON CONFLICT (day) DO NOTHING", day)
        dsl.execute("INSERT INTO routes(route_id, short_name, route_type, category) VALUES ('AGG', '97', 0, 'tram'), ('AGG-M', 'A', 1, 'metro')")
        dsl.execute("INSERT INTO stops(stop_id, name, node_id) VALUES ('AGG-STOP', 'Start', 98765), ('AGG-DEST', 'Cíl', 98766)")

        aggregate.flush(DailyAggregation.calculate(day, listOf(trip, metro), at))
        aggregate.flush(DailyAggregation.calculate(day, listOf(trip, metro), at)) // retry must not double count
        val loaded = dashboard.loadDay(day)!!
        assertEquals(6, loaded.network.depTotal)
        assertEquals(1, loaded.publicNetwork.depTotal)
        assertEquals(240L, loaded.network.depLateSum)
        assertEquals(240L, loaded.publicNetwork.sumMaxDelay)
        assertEquals(1, dashboard.hourly(day).single().totals.depLate)
        assertEquals(7, dashboard.hourly(day).single().hour)
        assertEquals("aggregation-fixture", dashboard.record(day)?.tripId)

        aggregate.rebuildStopRouteMonth(day)
        val monthly = dsl.fetchOne("SELECT dep_total, dep_late FROM stop_route_hourly WHERE route_id='AGG' AND node_id=98765")!!
        assertEquals(1, monthly.get("dep_total", Int::class.javaObjectType))
        assertEquals(1, monthly.get("dep_late", Int::class.javaObjectType))

        val request = HttpRequest.newBuilder(URI.create("http://localhost:8081/api/day/$day")).build()
        val response = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()
            .send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), response.body())
        assertTrue(response.body().contains("\"network\":{\"trips_total\":1,"), response.body())
        assertTrue(!response.body().contains("\"metro\""), response.body())
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("s-maxage=30"))

        for (path in listOf("/api/day/$day/leaderboard", "/api/day/$day/record", "/api/day/$day/hourly")) {
            val result = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()
                .send(HttpRequest.newBuilder(URI.create("http://localhost:8081$path")).build(),
                    HttpResponse.BodyHandlers.ofString())
            assertEquals(200, result.statusCode(), result.body())
            assertTrue(result.body().contains("\"incomplete\""), result.body())
            assertTrue(!result.body().contains("AGG-M") && !result.body().contains("aggregation-metro"), result.body())
        }
    }
}
