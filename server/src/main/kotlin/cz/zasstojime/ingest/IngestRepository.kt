package cz.zasstojime.ingest

import cz.zasstojime.db.Tables.INGEST_RUNS
import cz.zasstojime.db.Tables.STOP_EVENTS
import cz.zasstojime.db.Tables.TRIPS_DAILY
import cz.zasstojime.db.tables.records.TripsDailyRecord
import cz.zasstojime.gtfs.Category
import cz.zasstojime.persistence.PartitionManager
import jakarta.enterprise.context.ApplicationScoped
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

class IngestRun(
    val startedAt: Instant,
    val finishedAt: Instant,
    val ok: Boolean,
    val vehicles: Int? = null,
    val validSnapshots: Int? = null,
    val stopEvents: Int? = null,
    val error: String? = null,
)

@ApplicationScoped
class IngestRepository(
    private val dsl: DSLContext,
    private val partitions: PartitionManager,
) {
    /** FR-13: změněné spoje a průjezdy jednoho cyklu v jedné transakci, včetně záznamu v `ingest_runs`. */
    fun persistCycle(trips: Collection<TripState>, events: Collection<StopEvent>, run: IngestRun) {
        partitions.ensureForDates(events.map { it.serviceDate })
        dsl.transaction { cfg ->
            val tx = DSL.using(cfg)
            if (trips.isNotEmpty()) {
                tx.batch(trips.map { trip ->
                    val rec = toRecord(trip)
                    tx.insertInto(TRIPS_DAILY).set(rec)
                        .onConflict(TRIPS_DAILY.SERVICE_DATE, TRIPS_DAILY.TRIP_ID)
                        .doUpdate().set(rec)
                }).execute()
            }
            if (events.isNotEmpty()) {
                val batch = tx.batch(
                    tx.insertInto(
                        STOP_EVENTS, STOP_EVENTS.SERVICE_DATE, STOP_EVENTS.TRIP_ID, STOP_EVENTS.STOP_SEQ,
                        STOP_EVENTS.ROUTE_ID, STOP_EVENTS.STOP_ID, STOP_EVENTS.NODE_ID, STOP_EVENTS.PLANNED_ARR,
                        STOP_EVENTS.PLANNED_DEP, STOP_EVENTS.ARR_DELAY, STOP_EVENTS.DEP_DELAY,
                    ).values(null as LocalDate?, null, null, null, null, null, null, null, null, null)
                        .onConflict(STOP_EVENTS.SERVICE_DATE, STOP_EVENTS.TRIP_ID, STOP_EVENTS.STOP_SEQ).doUpdate()
                        .set(STOP_EVENTS.ROUTE_ID, DSL.excluded(STOP_EVENTS.ROUTE_ID))
                        .set(STOP_EVENTS.STOP_ID, DSL.excluded(STOP_EVENTS.STOP_ID))
                        .set(STOP_EVENTS.NODE_ID, DSL.excluded(STOP_EVENTS.NODE_ID))
                        .set(STOP_EVENTS.PLANNED_ARR, DSL.excluded(STOP_EVENTS.PLANNED_ARR))
                        .set(STOP_EVENTS.PLANNED_DEP, DSL.excluded(STOP_EVENTS.PLANNED_DEP))
                        .set(STOP_EVENTS.ARR_DELAY, DSL.excluded(STOP_EVENTS.ARR_DELAY))
                        .set(STOP_EVENTS.DEP_DELAY, DSL.excluded(STOP_EVENTS.DEP_DELAY)),
                )
                events.forEach { e ->
                    batch.bind(
                        e.serviceDate, e.tripId, e.stopSeq.toShort(), e.routeId, e.stopId, e.nodeId,
                        e.plannedArr.odt(), e.plannedDep.odt(), e.arrDelay, e.depDelay,
                    )
                }
                batch.execute()
            }
            insertRun(tx, run)
        }
    }

    fun recordRun(run: IngestRun) = insertRun(dsl, run)

    private fun insertRun(ctx: DSLContext, run: IngestRun) {
        ctx.insertInto(INGEST_RUNS)
            .set(INGEST_RUNS.STARTED_AT, run.startedAt.odt())
            .set(INGEST_RUNS.FINISHED_AT, run.finishedAt.odt())
            .set(INGEST_RUNS.OK, run.ok)
            .set(INGEST_RUNS.VEHICLES, run.vehicles)
            .set(INGEST_RUNS.VALID_SNAPSHOTS, run.validSnapshots)
            .set(INGEST_RUNS.STOP_EVENTS, run.stopEvents)
            .set(INGEST_RUNS.ERROR, run.error?.take(2000))
            .execute()
    }

    fun lastSuccessfulRunAt(): Instant? = dsl.select(DSL.max(INGEST_RUNS.FINISHED_AT))
        .from(INGEST_RUNS)
        .where(INGEST_RUNS.OK.isTrue)
        .fetchOne()?.value1()?.toInstant()

    /** FR-14: stav spojů od [since] (včetně) pro obnovu po restartu. */
    fun loadTrips(since: LocalDate): List<TripState> = dsl.selectFrom(TRIPS_DAILY)
        .where(TRIPS_DAILY.SERVICE_DATE.ge(since))
        .fetch { fromRecord(it) }

    private fun toRecord(t: TripState): TripsDailyRecord = TripsDailyRecord().apply {
        serviceDate = t.key.serviceDate
        tripId = t.key.tripId
        routeId = t.routeId
        routeShort = t.routeShort.take(16)
        category = t.category.db
        headsign = t.headsign
        vehicleId = t.vehicleId?.take(32)
        tripStartAt = t.tripStartAt.odt()
        firstSeenAt = t.firstSeenAt.odt()
        lastSeenAt = t.lastSeenAt.odt()
        status = t.status.db
        finishedBy = t.finishedBy?.db
        finishedAt = t.finishedAt.odt()
        lastDelay = t.lastDelay
        maxDelay = t.maxDelay
        maxDelayAt = t.maxDelayAt.odt()
        maxDelayStop = t.maxDelayStop
        lastStopSeq = t.lastStopSeq
        depTotal = t.depTotal
        depOnTime = t.depOnTime
        depEarly = t.depEarly
        depLate = t.depLate
        depLateSum = t.depLateSum
        validSnapshots = t.validSnapshots
        lastValidAt = t.lastValidAt.odt()
        lastOriginAt = t.lastOriginAt.odt()
        lastDepOriginAt = t.lastDepOriginAt.odt()
        flagged = t.flagged
        // verified / excluded nastavuje správce, zápis sběru je nepřepisuje
    }

    private fun fromRecord(r: TripsDailyRecord): TripState = TripState(
        key = TripKey(r.serviceDate, r.tripId),
        routeId = r.routeId,
        routeShort = r.routeShort,
        category = Category.fromDb(r.category),
        headsign = r.headsign,
        vehicleId = r.vehicleId,
        tripStartAt = r.tripStartAt?.toInstant(),
        firstSeenAt = r.firstSeenAt.toInstant(),
        lastSeenAt = r.lastSeenAt.toInstant(),
    ).apply {
        status = TripStatus.fromDb(r.status)
        finishedBy = FinishedBy.fromDb(r.finishedBy)
        finishedAt = r.finishedAt?.toInstant()
        lastDelay = r.lastDelay
        maxDelay = r.maxDelay
        maxDelayAt = r.maxDelayAt?.toInstant()
        maxDelayStop = r.maxDelayStop
        lastStopSeq = r.lastStopSeq
        depTotal = r.depTotal
        depOnTime = r.depOnTime
        depEarly = r.depEarly
        depLate = r.depLate
        depLateSum = r.depLateSum
        validSnapshots = r.validSnapshots
        lastValidAt = r.lastValidAt?.toInstant() ?: r.lastSeenAt.toInstant()
        lastOriginAt = r.lastOriginAt?.toInstant()
        lastDepOriginAt = r.lastDepOriginAt?.toInstant()
        flagged = r.flagged
        maxSeq = r.lastStopSeq ?: 0
    }
}

private fun Instant?.odt(): OffsetDateTime? = this?.atOffset(ZoneOffset.UTC)

@JvmName("odtNonNull")
private fun Instant.odt(): OffsetDateTime = atOffset(ZoneOffset.UTC)
