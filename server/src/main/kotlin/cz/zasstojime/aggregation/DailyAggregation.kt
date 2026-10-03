package cz.zasstojime.aggregation

import cz.zasstojime.gtfs.Category
import cz.zasstojime.ingest.TripState
import cz.zasstojime.ingest.TripStatus
import java.time.Instant
import java.time.LocalDate

/** Immutable values published to API readers after each successful ingest cycle. */
data class DailyTotals(
    val tripsTotal: Int = 0,
    val tripsFinished: Int = 0,
    val tripsCanceled: Int = 0,
    val depTotal: Int = 0,
    val depOnTime: Int = 0,
    val depEarly: Int = 0,
    val depLate: Int = 0,
    val depLateSum: Long = 0,
    val sumMaxDelay: Long = 0,
    val maxDelay: Int = 0,
) {
    val punctuality: Double? get() = if (depTotal == 0) null else depOnTime * 100.0 / depTotal
    val averageLateDelay: Double? get() = if (depLate == 0) null else depLateSum.toDouble() / depLate

    operator fun plus(o: DailyTotals) = DailyTotals(tripsTotal + o.tripsTotal, tripsFinished + o.tripsFinished,
        tripsCanceled + o.tripsCanceled, depTotal + o.depTotal, depOnTime + o.depOnTime, depEarly + o.depEarly,
        depLate + o.depLate, depLateSum + o.depLateSum, sumMaxDelay + o.sumMaxDelay, maxOf(maxDelay, o.maxDelay))
}

data class RouteTotals(val routeId: String, val routeShort: String, val category: Category, val totals: DailyTotals)

data class RecordTrip(
    val tripId: String,
    val routeShort: String,
    val headsign: String?,
    val delay: Int,
    val at: Instant?,
    val stopId: String?,
    val flagged: Boolean,
    val verified: Boolean,
)

data class DaySnapshot(
    val date: LocalDate,
    val dataAsOf: Instant?,
    val network: DailyTotals,
    val categories: Map<Category, DailyTotals>,
    val routes: Map<String, RouteTotals>,
    val record: RecordTrip?,
    val runningTrips: Int,
    val runningLateTrips: Int,
) {
    /** Celá síť bez kategorií skrytých na webu (ADR-0013); uložené `network` je obsahuje. */
    val publicNetwork: DailyTotals
        get() = if (categories.isEmpty()) network
        else categories.filterKeys { it.public }.values.fold(DailyTotals(), DailyTotals::plus)
}

/** Pure aggregation over the state already held by the ingest engine. */
object DailyAggregation {
    fun calculate(date: LocalDate, trips: Iterable<TripState>, dataAsOf: Instant?): DaySnapshot {
        val network = Counter()
        val categories = Category.entries.associateWith { Counter() }
        val routes = linkedMapOf<String, Pair<TripState, Counter>>()
        var record: RecordTrip? = null
        var running = 0
        var runningLate = 0

        for (trip in trips) {
            if (trip.key.serviceDate != date || !trip.persistable) continue
            network.add(trip)
            categories.getValue(trip.category).add(trip)
            routes.getOrPut(trip.routeId) { trip to Counter() }.second.add(trip)
            if (!trip.category.public) continue
            if (trip.status == TripStatus.RUNNING) {
                running++
                if ((trip.lastDelay ?: 0) >= 180) runningLate++
            }
            val delay = trip.maxDelay ?: continue
            if (!trip.excluded && delay > (record?.delay ?: Int.MIN_VALUE)) {
                record = RecordTrip(trip.key.tripId, trip.routeShort, trip.headsign, delay,
                    trip.maxDelayAt, trip.maxDelayStop, trip.flagged, trip.verified)
            }
        }
        return DaySnapshot(date, dataAsOf, network.value(), categories.mapValues { it.value.value() },
            routes.mapValues { (id, pair) -> RouteTotals(id, pair.first.routeShort, pair.first.category, pair.second.value()) },
            record, running, runningLate)
    }

    private class Counter {
        var tripsTotal = 0
        var tripsFinished = 0
        var tripsCanceled = 0
        var depTotal = 0
        var depOnTime = 0
        var depEarly = 0
        var depLate = 0
        var depLateSum = 0L
        var sumMaxDelay = 0L
        var maxDelay = 0

        fun add(t: TripState) {
            tripsTotal++
            if (t.status == TripStatus.FINISHED) tripsFinished++
            if (t.status == TripStatus.CANCELED) tripsCanceled++
            depTotal += t.depTotal
            depOnTime += t.depOnTime
            depEarly += t.depEarly
            depLate += t.depLate
            depLateSum += t.depLateSum
            val delay = (t.maxDelay ?: 0).coerceAtLeast(0)
            sumMaxDelay += delay.toLong()
            maxDelay = maxOf(maxDelay, delay)
        }

        fun value() = DailyTotals(tripsTotal, tripsFinished, tripsCanceled, depTotal, depOnTime,
            depEarly, depLate, depLateSum, sumMaxDelay, maxDelay)
    }
}
