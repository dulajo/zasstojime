package cz.zasstojime.ingest

import cz.zasstojime.golemio.StatePosition
import cz.zasstojime.golemio.VehicleFeature
import cz.zasstojime.gtfs.Category
import cz.zasstojime.gtfs.ScheduleLookup
import cz.zasstojime.time.PragueTime
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs

data class ProcessorParams(
    val tripTimeoutS: Long = 900,
    val reopenWindowS: Long = 900,
    val maxOriginAgeS: Long = 300,
    val maxDelayJumpS: Long = 120,
    val alertDelayS: Int = 7200,
)

/** Proč snímek vozidla nevstoupil do výpočtu. */
enum class SnapshotSkip(val tag: String) {
    NO_START("no_start_timestamp"),
    DUPLICATE_VEHICLE("duplicate_vehicle"),
    STALE_ORIGIN("stale_origin"),
    OLD_ORIGIN("old_origin"),
    BACKWARD_SEQ("backward_seq"),
}

/** Proč se změřený odjezd nebo průjezd neuložil. */
enum class DepartureDrop(val tag: String) {
    IMPLAUSIBLE("implausible_jump"),
    TECHNICAL_STOP("technical_stop"),
    UNKNOWN_STOP("unknown_stop"),
}

class CycleStats {
    var vehicles = 0
    var validSnapshots = 0
    var unknownRoutes = 0
    val skipped = sortedMapOf<SnapshotSkip, Int>()
    val dropped = sortedMapOf<DepartureDrop, Int>()

    fun skip(reason: SnapshotSkip) = skipped.merge(reason, 1, Int::plus)

    fun drop(reason: DepartureDrop) = dropped.merge(reason, 1, Int::plus)
}

class CycleResult(
    val events: List<StopEvent>,
    val changedTrips: List<TripState>,
    val stats: CycleStats,
)

/**
 * Stavový automat spojů – algoritmus z kapitoly 4 specifikace.
 * Čistá logika bez I/O; čas cyklu přichází zvenku, takže přehrání záznamu je deterministické.
 * Není vláknově bezpečný, volá ho jen jedno vlákno sběru.
 */
class TripProcessor(
    private val schedule: ScheduleLookup,
    private val params: ProcessorParams = ProcessorParams(),
) {
    private val trips = LinkedHashMap<TripKey, TripState>()

    val tripCount: Int get() = trips.size

    fun trip(key: TripKey): TripState? = trips[key]

    fun allTrips(): Collection<TripState> = trips.values

    /** Obnova po restartu (FR-14). Rozpracované zastávky se ztratí. */
    fun restore(states: Collection<TripState>) {
        states.forEach { trips[it.key] = it }
    }

    /** Zapomene spoje provozních dnů starších než [oldestKept]. */
    fun evictBefore(oldestKept: LocalDate): Int {
        val it = trips.values.iterator()
        var removed = 0
        while (it.hasNext()) {
            if (it.next().key.serviceDate < oldestKept) {
                it.remove()
                removed++
            }
        }
        return removed
    }

    fun process(fetchTime: Instant, features: List<VehicleFeature>): CycleResult {
        val stats = CycleStats().also { it.vehicles = features.size }
        val events = ArrayList<StopEvent>()
        val changed = LinkedHashSet<TripState>()
        val cycle = Cycle(fetchTime, stats, events, changed)

        // 1) výběr vozidla, když je jeden spoj hlášen víckrát (ADR-0052)
        val groups = LinkedHashMap<TripKey, MutableList<VehicleFeature>>()
        for (f in features) {
            val start = f.startTimestamp
            if (start == null) {
                stats.skip(SnapshotSkip.NO_START)
                continue
            }
            val key = TripKey(PragueTime.serviceDate(start, schedule.firstDepartureS(f.tripId)), f.tripId)
            groups.getOrPut(key) { ArrayList(1) } += f
        }
        for ((key, group) in groups) {
            val f = if (group.size == 1) group[0] else selectVehicle(trips[key], group)
            repeat(group.size - 1) { stats.skip(SnapshotSkip.DUPLICATE_VEHICLE) }
            processFeature(cycle, key, f)
        }

        // 9) timeout – jen po úspěšném snímku, takže výpadek sběru spoje neukončí
        for (trip in trips.values) {
            if (trip.status != TripStatus.RUNNING) continue
            val lastValid = trip.lastValidAt ?: continue
            if (Duration.between(lastValid, fetchTime).seconds > params.tripTimeoutS) {
                closePending(cycle, trip, trip.lastOriginAt ?: lastValid)
                finish(cycle, trip, FinishedBy.TIMEOUT)
            }
        }

        return CycleResult(events, changed.filter { it.persistable }, stats)
    }

    private fun selectVehicle(trip: TripState?, group: List<VehicleFeature>): VehicleFeature {
        val maxSeq = trip?.maxSeq ?: 0
        return group
            .filter { it.lastStopSequence != null && it.lastStopSequence >= maxSeq }
            .minByOrNull { it.lastStopSequence!! }
            ?: group.filter { it.lastStopSequence != null }.minByOrNull { it.lastStopSequence!! }
            ?: group[0]
    }

    private inner class Cycle(
        val fetchTime: Instant,
        val stats: CycleStats,
        val events: MutableList<StopEvent>,
        val changed: MutableSet<TripState>,
    )

    private fun processFeature(c: Cycle, key: TripKey, f: VehicleFeature) {
        val trip = trips.getOrPut(key) { newTrip(c, key, f) }
        trip.lastSeenAt = c.fetchTime
        val st = f.statePosition

        // 2) zrušení (ADR-0009, ADR-0054)
        if (st == StatePosition.CANCELED || f.isCanceled == true) {
            if (trip.status != TripStatus.CANCELED) {
                closePending(c, trip, closingOrigin(trip, f))
                trip.status = TripStatus.CANCELED
                markChanged(c, trip)
            }
            return
        }

        // 3) dojetí
        if (st in StatePosition.FINISHED) {
            if (trip.status == TripStatus.RUNNING) {
                closeAtTerminus(c, trip, f)
                finish(c, trip, FinishedBy.AFTER_TRACK)
            }
            return
        }

        // 4) neměřené stavy: before_track*, off_track
        if (st !in StatePosition.MEASURED) return

        // 5) validace (metodika: Platný snapshot)
        val origin = f.originTimestamp
        if (origin == null || Duration.between(origin, c.fetchTime).seconds > params.maxOriginAgeS) {
            c.stats.skip(SnapshotSkip.STALE_ORIGIN)
            return
        }
        val lastOrigin = trip.lastOriginAt
        if (lastOrigin != null && !origin.isAfter(lastOrigin)) {
            c.stats.skip(SnapshotSkip.OLD_ORIGIN)
            return
        }
        val seq = f.lastStopSequence
        if (seq == null || seq < trip.maxSeq) {
            c.stats.skip(SnapshotSkip.BACKWARD_SEQ) // ADR-0040
            return
        }
        trip.lastOriginAt = origin
        trip.lastValidAt = c.fetchTime

        // 6) znovuotevření (ADR-0042)
        if (trip.status == TripStatus.FINISHED) {
            val finishedAt = trip.finishedAt
            if (finishedAt != null && Duration.between(finishedAt, c.fetchTime).seconds <= params.reopenWindowS) {
                trip.status = TripStatus.RUNNING
                trip.finishedBy = null
                trip.finishedAt = null
                markChanged(c, trip)
            }
        }
        if (trip.status != TripStatus.RUNNING && trip.status != TripStatus.NEW) return
        if (trip.status == TripStatus.NEW) {
            trip.status = TripStatus.RUNNING
            markChanged(c, trip)
        }
        trip.validSnapshots++
        c.stats.validSnapshots++
        if (f.vehicleId != null && f.vehicleId != trip.vehicleId) {
            trip.vehicleId = f.vehicleId
            markChanged(c, trip)
        }

        // 7) posun na další zastávku → uzavřít předchozí
        if (seq > trip.maxSeq) {
            closePending(c, trip, origin)
            trip.pending = Pending(seq, f.lastStopId, f.lastStopArrivalTime, f.lastStopDepartureTime)
            trip.maxSeq = seq
        }

        // 8) průběžná aktualizace rozpracované zastávky (ADR-0041)
        val p = trip.pending
        if (p != null && p.seq == seq) {
            f.delayLastStopArrival?.let { p.arrDelay = it }
            f.delayLastStopDeparture?.let { p.depDelay = it }
        }
    }

    private fun newTrip(c: Cycle, key: TripKey, f: VehicleFeature): TripState {
        val route = schedule.route(f.routeId)
        if (route == null) c.stats.unknownRoutes++
        return TripState(
            key = key,
            routeId = f.routeId,
            routeShort = route?.shortName ?: f.routeShortName ?: f.routeId,
            category = route?.category ?: Category.OTHER,
            headsign = f.headsign,
            vehicleId = f.vehicleId,
            tripStartAt = f.startTimestamp,
            firstSeenAt = c.fetchTime,
            lastSeenAt = c.fetchTime,
        )
    }

    private fun markChanged(c: Cycle, trip: TripState) {
        trip.dirty = true
        c.changed += trip
    }

    private fun finish(c: Cycle, trip: TripState, by: FinishedBy) {
        trip.status = TripStatus.FINISHED
        trip.finishedBy = by
        trip.finishedAt = c.fetchTime
        markChanged(c, trip)
    }

    /** `origin_timestamp` pro uzavření mimo platný snímek (dojetí, zrušení). */
    private fun closingOrigin(trip: TripState, f: VehicleFeature): Instant {
        val a = f.originTimestamp
        val b = trip.lastOriginAt
        return when {
            a == null -> b ?: trip.lastSeenAt
            b == null -> a
            else -> maxOf(a, b)
        }
    }

    /** Dojetí: uzavře rozpracovanou zastávku a uloží příjezd na konečnou (jen příjezd). */
    private fun closeAtTerminus(c: Cycle, trip: TripState, f: VehicleFeature) {
        val origin = closingOrigin(trip, f)
        val seq = f.lastStopSequence
        if (seq != null && seq > trip.maxSeq) {
            closePending(c, trip, origin)
            trip.pending = Pending(seq, f.lastStopId, f.lastStopArrivalTime, f.lastStopDepartureTime)
            trip.maxSeq = seq
        }
        val p = trip.pending
        if (p != null && seq != null && p.seq == seq) {
            f.delayLastStopArrival?.let { p.arrDelay = it }
            p.depDelay = null
        }
        closePending(c, trip, origin)
    }

    private fun closePending(c: Cycle, trip: TripState, origin: Instant) {
        val p = trip.pending ?: return
        trip.pending = null
        val stopId = p.stopId
        val stop = stopId?.let { schedule.stop(it) }
        if (stop == null) {
            c.stats.drop(DepartureDrop.UNKNOWN_STOP)
            return
        }
        val node = stop.nodeId
        if (node == null) {
            c.stats.drop(DepartureDrop.TECHNICAL_STOP) // ADR-0045
            return
        }
        var dep = p.depDelay
        if (dep != null && !plausible(trip, dep, origin)) {
            c.stats.drop(DepartureDrop.IMPLAUSIBLE) // ADR-0035, ADR-0051
            dep = null
        }
        if (p.arrDelay == null && dep == null) return

        c.events += StopEvent(
            serviceDate = trip.key.serviceDate,
            tripId = trip.key.tripId,
            stopSeq = p.seq,
            routeId = trip.routeId,
            stopId = stopId,
            nodeId = node,
            plannedArr = p.plannedArr,
            plannedDep = p.plannedDep,
            arrDelay = p.arrDelay,
            depDelay = dep,
        )
        trip.lastStopSeq = p.seq
        if (dep != null) recordDeparture(trip, p, dep, origin)
        markChanged(c, trip)
    }

    private fun recordDeparture(trip: TripState, p: Pending, dep: Int, origin: Instant) {
        trip.depTotal++
        when (Punctuality.of(dep)) {
            Punctuality.EARLY -> trip.depEarly++
            Punctuality.ON_TIME -> trip.depOnTime++
            Punctuality.LATE -> {
                trip.depLate++
                trip.depLateSum += dep
            }
        }
        trip.lastDelay = dep
        trip.lastDepOriginAt = origin
        val max = trip.maxDelay
        if (max == null || dep > max) {
            trip.maxDelay = dep
            trip.maxDelayAt = p.plannedDep?.plusSeconds(dep.toLong()) ?: origin
            trip.maxDelayStop = p.stopId
            if (dep > params.alertDelayS) trip.flagged = true // ADR-0055
        }
    }

    /** Změna zpoždění oproti poslednímu změřenému odjezdu ≤ uplynulý čas + tolerance (ADR-0035, ADR-0051). */
    private fun plausible(trip: TripState, dep: Int, origin: Instant): Boolean {
        val last = trip.lastDelay ?: return true
        val lastOrigin = trip.lastDepOriginAt ?: return true
        val elapsed = Duration.between(lastOrigin, origin).seconds
        return abs(dep - last).toLong() <= elapsed + params.maxDelayJumpS
    }
}
