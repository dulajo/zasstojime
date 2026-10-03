package cz.zasstojime.aggregation

import cz.zasstojime.gtfs.Category
import cz.zasstojime.ingest.TripKey
import cz.zasstojime.ingest.TripState
import cz.zasstojime.ingest.TripStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class DailyAggregationTest {
    private val day = LocalDate.of(2026, 10, 2)
    private val now = Instant.parse("2026-10-02T12:00:00Z")

    private fun trip(id: String, category: Category, route: String = "1") = TripState(
        TripKey(day, id), route, route, category, null, null, now, now, now,
    ).apply { status = TripStatus.FINISHED }

    @Test
    fun `counts departures rather than finished trips and never subtracts early running`() {
        val a = trip("a", Category.TRAM).apply {
            depTotal = 3; depOnTime = 1; depEarly = 1; depLate = 1; depLateSum = 420
            maxDelay = 420
        }
        val b = trip("b", Category.TRAM).apply {
            depTotal = 2; depOnTime = 2; maxDelay = -30
        }
        val c = trip("c", Category.BUS_CITY, "100").apply {
            status = TripStatus.CANCELED; depTotal = 1; depLate = 1; depLateSum = 200; maxDelay = 200
        }
        val result = DailyAggregation.calculate(day, listOf(a, b, c), now)
        assertEquals(6, result.network.depTotal)
        assertEquals(3, result.network.depOnTime)
        assertEquals(50.0, result.network.punctuality)
        assertEquals(620L, result.network.sumMaxDelay)
        assertEquals(1, result.network.tripsCanceled)
        assertEquals(2, result.categories.getValue(Category.TRAM).tripsFinished)
        assertEquals(420, result.record?.delay)
    }

    @Test
    fun `record excludes manually rejected trips while totals still include them`() {
        val rejected = trip("rejected", Category.TRAM).apply { maxDelay = 1000; excluded = true }
        val accepted = trip("accepted", Category.TRAM).apply { maxDelay = 300; flagged = true }
        val result = DailyAggregation.calculate(day, listOf(rejected, accepted), now)
        assertEquals(1300L, result.network.sumMaxDelay)
        assertEquals("accepted", result.record?.tripId)
        assertEquals(true, result.record?.flagged)
        assertNull(result.network.punctuality)
    }

    @Test
    fun `metro stays in stored totals but not in public network, record or running trips`() {
        val metro = trip("metro", Category.METRO, "991").apply {
            status = TripStatus.RUNNING; depTotal = 10; depOnTime = 10; maxDelay = 900; lastDelay = 900
        }
        val tram = trip("tram", Category.TRAM).apply {
            status = TripStatus.RUNNING; depTotal = 4; depOnTime = 2; depLate = 2; depLateSum = 400
            maxDelay = 200; lastDelay = 200
        }
        val result = DailyAggregation.calculate(day, listOf(metro, tram), now)
        assertEquals(14, result.network.depTotal)
        assertEquals(10, result.categories.getValue(Category.METRO).depTotal)
        assertEquals(4, result.publicNetwork.depTotal)
        assertEquals(50.0, result.publicNetwork.punctuality)
        assertEquals(200L, result.publicNetwork.sumMaxDelay)
        assertEquals("tram", result.record?.tripId)
        assertEquals(1, result.runningTrips)
        assertEquals(1, result.runningLateTrips)
    }
}
