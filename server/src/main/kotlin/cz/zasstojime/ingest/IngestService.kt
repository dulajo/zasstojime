package cz.zasstojime.ingest

import cz.zasstojime.config.AppConfig
import cz.zasstojime.config.JobsDisabled
import cz.zasstojime.golemio.GolemioClient
import cz.zasstojime.golemio.GolemioException
import cz.zasstojime.golemio.SnapshotParser
import cz.zasstojime.gtfs.GtfsCache
import cz.zasstojime.time.PragueTime
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

/**
 * Cyklus sběru (FR-10–FR-14): stažení snímku, stavový automat, zápis změn.
 * Cykly se nepřekrývají; veškerý stav spojů mění jen vlákno plánovače.
 */
@ApplicationScoped
class IngestService(
    private val config: AppConfig,
    private val golemio: GolemioClient,
    private val repository: IngestRepository,
    gtfs: GtfsCache,
    private val registry: MeterRegistry,
) {
    private val log = Logger.getLogger(IngestService::class.java)

    private val processor = TripProcessor(
        gtfs,
        ProcessorParams(
            tripTimeoutS = config.tripTimeoutS(),
            maxDelayJumpS = config.maxDelayJumpS(),
            alertDelayS = config.alertDelayS(),
        ),
    )

    @Volatile
    private var readyFlag = false

    @Volatile
    private var lastSuccess: Instant? = null

    val ready: Boolean get() = readyFlag

    /** Čas posledního úspěšně uloženého snímku. */
    val lastSuccessAt: Instant? get() = lastSuccess

    /** Neuložené změny z cyklů, kdy selhal zápis do DB; zkusí se znovu v dalším cyklu. */
    private val unsavedTrips = LinkedHashMap<TripKey, TripState>()
    private val unsavedEvents = LinkedHashMap<Triple<LocalDate, String, Int>, StopEvent>()
    private var evictedUpTo: LocalDate? = null

    private val cycleTimer: Timer = Timer.builder("ingest_cycle_seconds").register(registry)
    private val vehicles = AtomicLong()
    private val runningTrips = AtomicLong()
    private val flaggedTrips = AtomicLong()

    init {
        registry.gauge("ingest_vehicles", vehicles) { it.get().toDouble() }
        registry.gauge("trips_running", runningTrips) { it.get().toDouble() }
        registry.gauge("flagged_trips", flaggedTrips) { it.get().toDouble() }
        registry.gauge("ingest_data_age_seconds", this) { s ->
            s.lastSuccessAt?.let { Duration.between(it, Instant.now()).seconds.toDouble() } ?: Double.NaN
        }
    }

    val runningTripCount: Long get() = runningTrips.get()

    /** FR-14: obnoví dnešní a včerejší spoje z DB a povolí sběr. */
    fun restore() {
        val since = LocalDate.now(PragueTime.ZONE).minusDays(1)
        val trips = repository.loadTrips(since)
        processor.restore(trips)
        lastSuccess = repository.lastSuccessfulRunAt()
        updateGauges()
        readyFlag = true
        log.infof("Obnoveno %d spojů od %s, sběr spuštěn", trips.size, since)
    }

    @Scheduled(
        every = "{app.poll-interval}",
        delayed = "{app.poll-delay}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        skipExecutionIf = JobsDisabled::class,
    )
    fun scheduledCycle() {
        if (ready) runCycle()
    }

    /** @return true, pokud se snímek stáhl, zpracoval i uložil */
    fun runCycle(): Boolean {
        val fetchTime = Instant.now()
        val sample = Timer.start(registry)
        val parsed = try {
            SnapshotParser.parse(golemio.fetchVehiclePositions())
        } catch (e: Exception) {
            val kind = (e as? GolemioException)?.kind ?: "parse"
            registry.counter("golemio_errors_total", "kind", kind).increment()
            fail(fetchTime, "Golemio: ${e.message}", e)
            sample.stop(cycleTimer)
            return false
        }
        val ok = apply(fetchTime, parsed)
        sample.stop(cycleTimer)
        return ok
    }

    /** Zpracuje stažený snímek a uloží změny. Veřejné kvůli přehrání záznamu v testech. */
    fun apply(fetchTime: Instant, parsed: SnapshotParser.Result): Boolean {
        val result = processor.process(fetchTime, parsed.features)
        result.changedTrips.forEach { unsavedTrips[it.key] = it }
        result.events.forEach { unsavedEvents[Triple(it.serviceDate, it.tripId, it.stopSeq)] = it }
        val stats = result.stats

        return try {
            repository.persistCycle(
                unsavedTrips.values,
                unsavedEvents.values,
                IngestRun(
                    startedAt = fetchTime,
                    finishedAt = Instant.now(),
                    ok = true,
                    vehicles = parsed.total,
                    validSnapshots = stats.validSnapshots,
                    stopEvents = unsavedEvents.size,
                ),
            )
            unsavedTrips.values.forEach { it.dirty = false }
            registry.counter("ingest_stop_events_total").increment(unsavedEvents.size.toDouble())
            unsavedTrips.clear()
            unsavedEvents.clear()
            lastSuccess = fetchTime
            registry.counter("ingest_cycles_total", "result", "ok").increment()
            record(parsed, stats)
            evictOldTrips()
            updateGauges()
            true
        } catch (e: Exception) {
            fail(fetchTime, "DB: ${e.message}", e)
            trimUnsaved()
            false
        }
    }

    /** Při dlouhém výpadku DB nesmí neuložené průjezdy vyčerpat paměť; nejstarší se zahodí. */
    private fun trimUnsaved() {
        val excess = unsavedEvents.size - MAX_UNSAVED_EVENTS
        if (excess <= 0) return
        val oldest = unsavedEvents.values.iterator()
        repeat(excess) {
            oldest.next()
            oldest.remove()
        }
        registry.counter("ingest_departures_dropped_total", "reason", "db_unavailable").increment(excess.toDouble())
        log.warnf("DB nedostupná, zahozeno %d nejstarších neuložených průjezdů", excess)
    }

    companion object {
        /** ~2 h provozu ve špičce */
        const val MAX_UNSAVED_EVENTS = 100_000
    }

    private fun record(parsed: SnapshotParser.Result, stats: CycleStats) {
        vehicles.set(parsed.total.toLong())
        if (parsed.skipped > 0) registry.counter("ingest_snapshots_skipped_total", "reason", "unparsable").increment(parsed.skipped.toDouble())
        stats.skipped.forEach { (reason, n) ->
            registry.counter("ingest_snapshots_skipped_total", "reason", reason.tag).increment(n.toDouble())
        }
        stats.dropped.forEach { (reason, n) ->
            registry.counter("ingest_departures_dropped_total", "reason", reason.tag).increment(n.toDouble())
        }
        if (stats.unknownRoutes > 0) registry.counter("gtfs_unknown_routes_total").increment(stats.unknownRoutes.toDouble())
    }

    private fun fail(fetchTime: Instant, message: String, e: Exception) {
        registry.counter("ingest_cycles_total", "result", "error").increment()
        log.warn("Cyklus sběru selhal: $message", if (e is GolemioException) null else e)
        try {
            repository.recordRun(IngestRun(fetchTime, Instant.now(), ok = false, error = message))
        } catch (dbError: Exception) {
            log.warn("Nelze zapsat ingest_runs: ${dbError.message}")
        }
    }

    /** Spoje provozních dnů starších než včera už nejedou (FR-14 drží dnešek a včerejšek). */
    private fun evictOldTrips() {
        val oldestKept = LocalDate.now(PragueTime.ZONE).minusDays(1)
        if (evictedUpTo == oldestKept) return
        val removed = processor.evictBefore(oldestKept)
        evictedUpTo = oldestKept
        if (removed > 0) log.infof("Z paměti uvolněno %d spojů starších než %s", removed, oldestKept)
    }

    private fun updateGauges() {
        var running = 0L
        var flagged = 0L
        for (t in processor.allTrips()) {
            if (t.status == TripStatus.RUNNING) running++
            if (t.flagged) flagged++
        }
        runningTrips.set(running)
        flaggedTrips.set(flagged)
    }
}
