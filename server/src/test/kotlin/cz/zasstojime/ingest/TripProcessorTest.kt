package cz.zasstojime.ingest

import cz.zasstojime.golemio.VehicleFeature
import cz.zasstojime.gtfs.Category
import cz.zasstojime.gtfs.RouteInfo
import cz.zasstojime.gtfs.ScheduleLookup
import cz.zasstojime.gtfs.StopInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** AC-01, AC-02, AC-09, AC-10: stavový automat spoje podle kapitol 4 a 5 specifikace. */
class TripProcessorTest {
    private val schedule = FakeSchedule()
    private val processor = TripProcessor(schedule)

    private val day: LocalDate = LocalDate.of(2026, 10, 2)
    private val key = TripKey(day, TRIP)

    private fun cycle(sec: Long, vararg features: VehicleFeature) = processor.process(at(sec), features.toList())

    private fun trip(): TripState = processor.trip(key)!!

    @Nested
    inner class MeasuringDepartures {
        @Test
        fun `odjezd se uzavre posunem na dalsi zastavku s posledni hodnotou`() {
            cycle(0, f(1, sec = 0, dep = 0))
            cycle(30, f(1, sec = 30, dep = 30))
            val r = cycle(60, f(2, sec = 60, dep = 200))
            assertEquals(listOf(1), r.events.map { it.stopSeq })
            assertEquals(30, r.events[0].depDelay)
            val r2 = cycle(120, f(3, sec = 120))
            assertEquals(200, r2.events.single().depDelay)

            val t = trip()
            assertEquals(2, t.depTotal)
            assertEquals(1, t.depOnTime)
            assertEquals(1, t.depLate)
            assertEquals(200L, t.depLateSum)
            assertEquals(200, t.maxDelay)
            assertEquals("S2", t.maxDelayStop)
            assertEquals(200, t.lastDelay)
            assertEquals(2, t.lastStopSeq)
            assertEquals(TripStatus.RUNNING, t.status)
        }

        @Test
        fun `null hodnota odjezdu neprepise posledni nenulovou`() {
            cycle(0, f(1, sec = 0, dep = 40))
            cycle(30, f(1, sec = 30, dep = null, arr = 35))
            val r = cycle(60, f(2, sec = 60))
            val e = r.events.single()
            assertEquals(40, e.depDelay)
            assertEquals(35, e.arrDelay)
        }

        @Test
        fun `zastavka jen s prijezdem se ulozi, ale do odjezdu se nepocita`() {
            cycle(0, f(1, sec = 0, arr = 50))
            val r = cycle(60, f(2, sec = 60))
            val e = r.events.single()
            assertEquals(50, e.arrDelay)
            assertNull(e.depDelay)
            assertEquals(0, trip().depTotal)
        }

        @Test
        fun `zastavka bez hodnot se neulozi`() {
            cycle(0, f(1, sec = 0))
            val r = cycle(60, f(2, sec = 60))
            assertTrue(r.events.isEmpty())
        }

        @Test
        fun `hranice dochvilnosti -60, -59, 179 a 180 s`() {
            cycle(0, f(1, sec = 0, dep = -60))
            cycle(60, f(2, sec = 60, dep = -59))
            cycle(120, f(3, sec = 120, dep = 179))
            cycle(240, f(4, sec = 240, dep = 180))
            cycle(300, f(5, sec = 300))
            val t = trip()
            assertEquals(4, t.depTotal)
            assertEquals(1, t.depEarly)
            assertEquals(2, t.depOnTime)
            assertEquals(1, t.depLate)
            assertEquals(180L, t.depLateSum)
        }

        @Test
        fun `max_delay_at je planovany odjezd plus zpozdeni`() {
            cycle(0, f(1, sec = 0, dep = 90))
            cycle(60, f(2, sec = 60))
            assertEquals(plannedDep(1).plusSeconds(90), trip().maxDelayAt)
        }

        @Test
        fun `technicky bod se neuklada`() {
            cycle(0, f(1, sec = 0, dep = 10, stopId = "T1"))
            val r = cycle(60, f(2, sec = 60))
            assertTrue(r.events.isEmpty())
            assertEquals(1, r.stats.dropped[DepartureDrop.TECHNICAL_STOP])
            assertEquals(0, trip().depTotal)
        }

        @Test
        fun `zastavka chybejici v GTFS se neuklada`() {
            cycle(0, f(1, sec = 0, dep = 10, stopId = "XYZ"))
            val r = cycle(60, f(2, sec = 60))
            assertTrue(r.events.isEmpty())
            assertEquals(1, r.stats.dropped[DepartureDrop.UNKNOWN_STOP])
        }

        @Test
        fun `ulozena udalost nese uzel, linku a planovane casy`() {
            cycle(0, f(1, sec = 0, dep = 10, arr = 5))
            val e = cycle(60, f(2, sec = 60)).events.single()
            assertEquals(StopEvent(day, TRIP, 1, "L1", "S1", 1001, plannedArr(1), plannedDep(1), 5, 10), e)
        }
    }

    @Nested
    inner class Validation {
        @Test
        fun `preskok zpet na trase se ignoruje`() {
            cycle(0, f(5, sec = 0, dep = 10))
            val r = cycle(30, f(3, sec = 30, dep = 999))
            assertEquals(1, r.stats.skipped[SnapshotSkip.BACKWARD_SEQ])
            assertEquals(10, trip().pending!!.depDelay)
            assertEquals(5, trip().maxSeq)
        }

        @Test
        fun `poloha starsi nez 5 min se ignoruje`() {
            val r = cycle(400, f(1, sec = 99, dep = 10))
            assertEquals(1, r.stats.skipped[SnapshotSkip.STALE_ORIGIN])
            assertEquals(TripStatus.NEW, trip().status)
            assertTrue(r.changedTrips.isEmpty())
        }

        @Test
        fun `stejny origin_timestamp se nezpracuje dvakrat`() {
            cycle(0, f(1, sec = 0, dep = 10))
            val r = cycle(30, f(1, sec = 0, dep = 50))
            assertEquals(1, r.stats.skipped[SnapshotSkip.OLD_ORIGIN])
            assertEquals(10, trip().pending!!.depDelay)
        }

        @Test
        fun `chybejici sekvence zastavky se ignoruje`() {
            val r = cycle(0, f(null, sec = 0, dep = 10))
            assertEquals(1, r.stats.skipped[SnapshotSkip.BACKWARD_SEQ])
            assertEquals(TripStatus.NEW, trip().status)
        }

        @Test
        fun `chybejici zacatek spoje se preskoci`() {
            val r = cycle(0, f(1, sec = 0, start = null))
            assertEquals(1, r.stats.skipped[SnapshotSkip.NO_START])
            assertEquals(0, processor.tripCount)
        }

        @Test
        fun `nerealny narust zpozdeni se zahodi`() {
            cycle(0, f(1, sec = 0, dep = 0))
            cycle(60, f(2, sec = 60, dep = 600, arr = 590))
            val r = cycle(90, f(3, sec = 90))
            assertEquals(1, r.stats.dropped[DepartureDrop.IMPLAUSIBLE])
            val e = r.events.single()
            assertNull(e.depDelay)
            assertEquals(590, e.arrDelay)
            assertEquals(1, trip().depTotal)
            assertEquals(0, trip().maxDelay)
        }

        @Test
        fun `nerealny pokles zpozdeni se zahodi (ADR-0051)`() {
            cycle(0, f(1, sec = 0, dep = 900))
            cycle(60, f(2, sec = 60, dep = 100))
            val r = cycle(90, f(3, sec = 90))
            assertEquals(1, r.stats.dropped[DepartureDrop.IMPLAUSIBLE])
            assertEquals(900, trip().lastDelay)
        }

        @Test
        fun `narust v ramci uplynuleho casu + 120 s projde`() {
            cycle(0, f(1, sec = 0, dep = 0))
            cycle(60, f(2, sec = 60, dep = 170))
            val r = cycle(120, f(3, sec = 120))
            // uplynulo 60 s mezi uzavřeními, 170 ≤ 60 + 120
            assertTrue(r.stats.dropped.isEmpty())
            assertEquals(170, trip().lastDelay)
        }

        @Test
        fun `prvni odjezd spoje nad 2 h dostane priznak neovereno`() {
            cycle(0, f(1, sec = 0, dep = 7300))
            cycle(60, f(2, sec = 60))
            assertTrue(trip().flagged)
            assertEquals(7300, trip().maxDelay)
        }
    }

    @Nested
    inner class Lifecycle {
        @Test
        fun `spoj pred vyjezdem se neuklada`() {
            val r = cycle(0, f(1, sec = 0, state = "before_track"))
            assertEquals(TripStatus.NEW, trip().status)
            assertTrue(r.changedTrips.isEmpty())
        }

        @Test
        fun `off_track spoj neukonci ani nemeri`() {
            cycle(0, f(1, sec = 0, dep = 10))
            val r = cycle(30, f(2, sec = 30, dep = 500, state = "off_track"))
            assertTrue(r.events.isEmpty())
            assertEquals(TripStatus.RUNNING, trip().status)
            assertEquals(1, trip().maxSeq)
        }

        @Test
        fun `dojeti uzavre zastavku a ulozi jen prijezd na konecnou`() {
            cycle(0, f(4, sec = 0, dep = 30))
            val r = cycle(60, f(5, sec = 55, arr = 100, dep = 120, state = "after_track"))
            assertEquals(listOf(4, 5), r.events.map { it.stopSeq })
            assertEquals(30, r.events[0].depDelay)
            assertEquals(100, r.events[1].arrDelay)
            assertNull(r.events[1].depDelay)
            val t = trip()
            assertEquals(TripStatus.FINISHED, t.status)
            assertEquals(FinishedBy.AFTER_TRACK, t.finishedBy)
            assertEquals(1, t.depTotal)
        }

        @Test
        fun `dojeti na uz rozpracovane konecne zapise prijezd`() {
            cycle(0, f(4, sec = 0, dep = 30))
            cycle(30, f(5, sec = 30, arr = 60, state = "at_stop"))
            val r = cycle(60, f(5, sec = 55, arr = 70, state = "after_track_delayed"))
            assertEquals(70, r.events.single { it.stopSeq == 5 }.arrDelay)
            assertEquals(TripStatus.FINISHED, trip().status)
        }

        @Test
        fun `after_track a pak znovu na trase do 15 min znovu otevre spoj`() {
            cycle(0, f(4, sec = 0, dep = 30))
            cycle(60, f(5, sec = 55, arr = 40, state = "after_track"))
            val r = cycle(600, f(6, sec = 600, dep = 50))
            assertEquals(TripStatus.RUNNING, trip().status)
            assertNull(trip().finishedBy)
            assertTrue(r.changedTrips.contains(trip()))
            cycle(660, f(7, sec = 660))
            assertEquals(2, trip().depTotal)
        }

        @Test
        fun `po 15 min od dojeti se spoj uz neotevre`() {
            cycle(0, f(4, sec = 0, dep = 30))
            cycle(60, f(5, sec = 55, state = "after_track"))
            cycle(60 + 901, f(6, sec = 60 + 901, dep = 50))
            assertEquals(TripStatus.FINISHED, trip().status)
            assertNull(trip().pending)
        }

        @Test
        fun `spoj bez snimku 15 min skonci timeoutem a uzavre zastavku`() {
            cycle(0, f(1, sec = 0, dep = 0))
            cycle(30, f(2, sec = 30, dep = 20))
            cycle(900, f(1, sec = 900, trip = "OTHER"))
            assertEquals(TripStatus.RUNNING, trip().status)
            val r = cycle(931, f(1, sec = 931, trip = "OTHER"))
            assertEquals(TripStatus.FINISHED, trip().status)
            assertEquals(FinishedBy.TIMEOUT, trip().finishedBy)
            assertEquals(20, r.events.single { it.tripId == TRIP }.depDelay)
        }

        @Test
        fun `zruseni za jizdy zachova zmerene odjezdy (ADR-0054)`() {
            cycle(0, f(1, sec = 0, dep = 0))
            cycle(60, f(2, sec = 60, dep = 30))
            val r = cycle(90, f(2, sec = 90, state = "canceled"))
            assertEquals(TripStatus.CANCELED, trip().status)
            assertEquals(2, trip().depTotal)
            assertEquals(30, r.events.single().depDelay)
            // zrušený spoj se už neotevře
            cycle(120, f(3, sec = 120))
            assertEquals(TripStatus.CANCELED, trip().status)
        }

        @Test
        fun `is_canceled rusi spoj i bez stavu canceled`() {
            val r = cycle(0, f(1, sec = 0, state = "before_track", canceled = true))
            assertEquals(TripStatus.CANCELED, trip().status)
            assertEquals(listOf(trip()), r.changedTrips)
        }

        @Test
        fun `AC-09 vypadek 20 min neukonci spoje, ktere po obnoveni jedou dal`() {
            cycle(0, f(3, sec = 0, dep = 60), f(3, sec = 0, dep = 60, trip = "GONE"))
            // 20 min bez úspěšného snímku: process se nevolá
            cycle(1200, f(9, sec = 1195, dep = 120))
            assertEquals(TripStatus.RUNNING, trip().status)
            val gone = processor.trip(TripKey(day, "GONE"))!!
            assertEquals(TripStatus.FINISHED, gone.status)
            assertEquals(FinishedBy.TIMEOUT, gone.finishedBy)
        }
    }

    @Nested
    inner class VehicleSelection {
        @Test
        fun `AC-10 novy spoj se dvema vozidly sekvenci 9 a 1 bere sekvenci 1`() {
            val r = cycle(0, f(9, sec = 0, dep = 500, vehicle = "A"), f(1, sec = 0, dep = 10, vehicle = "B"))
            assertEquals(1, trip().maxSeq)
            assertEquals("B", trip().vehicleId)
            assertEquals(1, r.stats.skipped[SnapshotSkip.DUPLICATE_VEHICLE])
        }

        @Test
        fun `AC-10 rozjety spoj bere vozidlo, ktere navazuje na prubeh`() {
            cycle(0, f(1, sec = 0, dep = 10, vehicle = "B"))
            cycle(30, f(3, sec = 30, dep = 20, vehicle = "B"))
            cycle(60, f(9, sec = 60, dep = 500, vehicle = "A"), f(4, sec = 60, dep = 30, vehicle = "B"))
            assertEquals(4, trip().maxSeq)
            cycle(90, f(2, sec = 90, vehicle = "A"), f(5, sec = 90, vehicle = "B"))
            assertEquals(5, trip().maxSeq)
            assertEquals("B", trip().vehicleId)
        }
    }

    @Nested
    inner class ServiceDayAndRoutes {
        @Test
        fun `spoj s vyjezdem 24_13 v jizdnim radu patri do predchoziho dne`() {
            schedule.firstDep["NIGHT"] = 24 * 3600 + 13 * 60
            val start = Instant.parse("2026-10-02T22:13:00Z") // 3. 10. 00:13 SELČ
            cycle(0, f(1, sec = 0, trip = "NIGHT", start = start))
            assertTrue(processor.trip(TripKey(LocalDate.of(2026, 10, 2), "NIGHT")) != null)
        }

        @Test
        fun `spoj mimo GTFS bere lokalni datum vyjezdu`() {
            val start = Instant.parse("2026-10-02T22:13:00Z")
            cycle(0, f(1, sec = 0, trip = "UNKNOWN", start = start))
            assertTrue(processor.trip(TripKey(LocalDate.of(2026, 10, 3), "UNKNOWN")) != null)
        }

        @Test
        fun `stejne trip_id ve dvou provoznich dnech jsou dva spoje`() {
            cycle(0, f(5, sec = 0, state = "after_track"), f(1, sec = 0, start = T0.plusSeconds(86_400), state = "before_track"))
            assertEquals(2, processor.tripCount)
        }

        @Test
        fun `linka chybejici v GTFS ma kategorii other`() {
            val r = cycle(0, f(1, sec = 0, dep = 0, route = "L999"))
            assertEquals(Category.OTHER, trip().category)
            assertEquals(1, r.stats.unknownRoutes)
        }

        @Test
        fun `kategorie a cislo linky z GTFS`() {
            cycle(0, f(1, sec = 0))
            assertEquals(Category.TRAM, trip().category)
            assertEquals("1", trip().routeShort)
        }

        @Test
        fun `evictBefore zapomene stare provozni dny`() {
            cycle(0, f(1, sec = 0))
            assertEquals(1, processor.evictBefore(day.plusDays(1)))
            assertEquals(0, processor.tripCount)
        }
    }

    @Test
    fun `zmenene spoje obsahuji jen ty se zmenou, ne kazdy videny`() {
        cycle(0, f(1, sec = 0, dep = 0))
        trip().dirty = false
        val r = cycle(30, f(1, sec = 30, dep = 5))
        assertTrue(r.changedTrips.isEmpty())
        assertFalse(trip().dirty)
        val r2 = cycle(60, f(2, sec = 60))
        assertEquals(listOf(trip()), r2.changedTrips)
    }

    companion object {
        const val TRIP = "1_100_261001"
        val T0: Instant = Instant.parse("2026-10-02T06:00:00Z")

        fun at(sec: Long): Instant = T0.plusSeconds(sec)

        fun plannedArr(seq: Int): Instant = T0.plusSeconds(seq * 120L - 20)

        fun plannedDep(seq: Int): Instant = T0.plusSeconds(seq * 120L)

        fun f(
            seq: Int?,
            sec: Long,
            dep: Int? = null,
            arr: Int? = null,
            state: String = "on_track",
            trip: String = TRIP,
            route: String = "L1",
            start: Instant? = T0,
            canceled: Boolean? = null,
            vehicle: String? = "8001",
            stopId: String? = seq?.let { "S$it" },
        ) = VehicleFeature(
            tripId = trip,
            routeId = route,
            routeShortName = route.removePrefix("L"),
            routeType = 0,
            headsign = "Konečná",
            startTimestamp = start,
            vehicleId = vehicle,
            statePosition = state,
            isCanceled = canceled,
            originTimestamp = at(sec),
            lastStopSequence = seq,
            lastStopId = stopId,
            lastStopArrivalTime = seq?.let { plannedArr(it) },
            lastStopDepartureTime = seq?.let { plannedDep(it) },
            delayLastStopArrival = arr,
            delayLastStopDeparture = dep,
        )
    }
}

class FakeSchedule : ScheduleLookup {
    val routes = mutableMapOf("L1" to RouteInfo("L1", "1", 0, Category.TRAM, isNight = false, isSubstitute = false))
    val stops = (1..40).associate { "S$it" to StopInfo("S$it", "Zastávka $it", 1000 + it, "A", null, null) }.toMutableMap()
        .apply { put("T1", StopInfo("T1", "Technický bod", null, null, null, null)) }
    val firstDep = mutableMapOf<String, Int>()

    override fun route(routeId: String) = routes[routeId]

    override fun stop(stopId: String) = stops[stopId]

    override fun firstDepartureS(tripId: String) = firstDep[tripId]
}
