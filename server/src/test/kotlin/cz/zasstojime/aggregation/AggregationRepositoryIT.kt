package cz.zasstojime.aggregation

import cz.zasstojime.gtfs.Category
import cz.zasstojime.gtfs.CzechCalendar
import cz.zasstojime.ingest.IngestRepository
import cz.zasstojime.ingest.IngestRun
import cz.zasstojime.ingest.StopEvent
import cz.zasstojime.ingest.TripKey
import cz.zasstojime.ingest.TripState
import cz.zasstojime.ingest.TripStatus
import cz.zasstojime.time.PragueTime
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

        val routeRequest = HttpRequest.newBuilder(URI.create(
            "http://localhost:8081/api/my-route?line=97&from=98765&to=98766&time=07:05&day_type=workday&period=13m",
        )).build()
        val routeResponse = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()
            .send(routeRequest, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, routeResponse.statusCode(), routeResponse.body())
        assertTrue(routeResponse.body().contains("\"measured_days\":1"), routeResponse.body())
        assertTrue(routeResponse.body().contains("\"average_extra_wait_s\":240.0"), routeResponse.body())

        val http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()
        for (path in listOf("/api/lines?q=9", "/api/lines/97/stops",
            "/api/lines/97/departures?from=98765&day_type=workday")) {
            val result = http.send(HttpRequest.newBuilder(URI.create("http://localhost:8081$path")).build(),
                HttpResponse.BodyHandlers.ofString())
            assertEquals(200, result.statusCode(), result.body())
        }

        val tripResponse = http.send(HttpRequest.newBuilder(URI.create(
            "http://localhost:8081/api/trip/$day/aggregation-fixture")).build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, tripResponse.statusCode(), tripResponse.body())
        val tripBody = tripResponse.body()
        assertTrue(tripBody.contains("\"line\":\"97\"") && tripBody.contains("\"mode\":\"tram\""), tripBody)
        assertTrue(tripBody.contains("\"name\":\"Start\"") && tripBody.contains("\"name\":\"Cíl\""), tripBody)
        assertTrue(tripBody.indexOf("\"sequence\":1") < tripBody.indexOf("\"sequence\":2"), tripBody)
        assertTrue(tripBody.contains("\"departure_delay_s\":240"), tripBody)
        assertEquals(404, http.send(HttpRequest.newBuilder(URI.create("http://localhost:8081/api/trip/$day/missing")).build(),
            HttpResponse.BodyHandlers.ofString()).statusCode())

        val holiday = http.send(HttpRequest.newBuilder(URI.create("http://localhost:8081/api/calendar/2026-10-28")).build(),
            HttpResponse.BodyHandlers.ofString())
        assertEquals(200, holiday.statusCode(), holiday.body())
        assertTrue(holiday.body().contains("\"day_type\":\"sunday_holiday\""), holiday.body())
        assertTrue(holiday.body().contains("\"holiday_name\":\""), holiday.body())
        val saturday = http.send(HttpRequest.newBuilder(URI.create("http://localhost:8081/api/calendar/2026-10-03")).build(),
            HttpResponse.BodyHandlers.ofString())
        assertTrue(saturday.body().contains("\"day_type\":\"saturday\"") && saturday.body().contains("\"holiday_name\":null"),
            saturday.body())

        // Rozjetý spoj, který do cíle ještě nedojel, se vrátí bez příjezdu a neoznačí období jako neúplné.
        val later = at.plusSeconds(900)
        val running = TripState(TripKey(day, "aggregation-running"), "AGG", "97", Category.TRAM,
            "Centrum", null, later, later, later).apply { status = TripStatus.RUNNING; depTotal = 1; depOnTime = 1 }
        ingest.persistCycle(listOf(running), listOf(StopEvent(day, running.key.tripId, 1, running.routeId, "AGG-STOP", 98765,
            later.minusSeconds(30), later, 60, 60)), IngestRun(later, later, true, 1, 1, 1))
        val runningRoute = http.send(HttpRequest.newBuilder(URI.create(
            "http://localhost:8081/api/my-route?line=97&from=98765&to=98766&time=07:20&day_type=workday&period=13m")).build(),
            HttpResponse.BodyHandlers.ofString())
        assertTrue(runningRoute.body().contains("\"trip_id\":\"aggregation-running\""), runningRoute.body())
        assertTrue(runningRoute.body().contains("\"measured\":false") && runningRoute.body().contains("\"running\":true"),
            runningRoute.body())
        assertTrue(runningRoute.body().contains("\"measured_days\":0"), runningRoute.body())
        assertTrue(runningRoute.headers().firstValue("Cache-Control").orElse("").contains("s-maxage=30"))

        // Dojetý spoj bez změřeného cíle vrátí jen dotaz se směrem, bez něj by mohl jít opačným směrem.
        val partialAt = at.plusSeconds(1800)
        val partial = TripState(TripKey(day, "aggregation-partial"), "AGG", "97", Category.TRAM,
            "Centrum", null, partialAt, partialAt, partialAt).apply { status = TripStatus.FINISHED; depTotal = 1; depOnTime = 1 }
        ingest.persistCycle(listOf(partial), listOf(StopEvent(day, partial.key.tripId, 1, partial.routeId, "AGG-STOP", 98765,
            partialAt, partialAt, 30, 30)), IngestRun(partialAt, partialAt, true, 1, 1, 1))
        fun partialRoute(query: String) = http.send(HttpRequest.newBuilder(URI.create(
            "http://localhost:8081/api/my-route?line=97&from=98765&to=98766&time=07:35&day_type=workday&period=13m$query")).build(),
            HttpResponse.BodyHandlers.ofString()).body()
        assertTrue(partialRoute("").contains("\"days\":[]"), partialRoute(""))
        assertTrue(partialRoute("&headsign=Centrum").contains("\"trip_id\":\"aggregation-partial\""), partialRoute("&headsign=Centrum"))
        assertTrue(partialRoute("&headsign=Jinam").contains("\"days\":[]"), partialRoute("&headsign=Jinam"))

        // Nabídka časů hledá jen za posledních 13 dní, proto spoj z dneška.
        val today = LocalDate.now(PragueTime.ZONE)
        val dayType = CzechCalendar.dayType(today).db
        val todayAt = today.atTime(7, 30).atZone(PragueTime.ZONE).toInstant()
        val todayTrip = TripState(TripKey(today, "aggregation-today"), "AGG", "97", Category.TRAM,
            "Centrum", null, todayAt, todayAt, todayAt).apply { status = TripStatus.FINISHED; depTotal = 1; depOnTime = 1 }
        dsl.execute("INSERT INTO calendar_days(day, day_type) VALUES (?, ?) ON CONFLICT (day) DO NOTHING", today, dayType)
        ingest.persistCycle(listOf(todayTrip), listOf(StopEvent(today, todayTrip.key.tripId, 1, todayTrip.routeId, "AGG-STOP",
            98765, todayAt, todayAt, 0, 0)), IngestRun(todayAt, todayAt, true, 1, 1, 1))
        fun departures(headsign: String) = http.send(HttpRequest.newBuilder(URI.create(
            "http://localhost:8081/api/lines/97/departures?from=98765&day_type=$dayType&headsign=$headsign")).build(),
            HttpResponse.BodyHandlers.ofString()).body()
        assertTrue(departures("Centrum").contains("\"time\":\"07:30\""), departures("Centrum"))
        assertTrue(departures("Jinam").contains("\"items\":[]"), departures("Jinam"))
    }
}
