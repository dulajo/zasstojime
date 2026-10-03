package cz.zasstojime.gtfs

import cz.zasstojime.config.AppConfig
import cz.zasstojime.config.JobsDisabled
import cz.zasstojime.golemio.HttpClients
import cz.zasstojime.time.PragueTime
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.deleteIfExists

/** Import PID GTFS (FR-01–FR-04). Selhání nezastaví sběr, zůstane poslední úspěšný import (FR-03). */
@ApplicationScoped
class GtfsImportService(
    private val config: AppConfig,
    private val repository: GtfsRepository,
    private val cache: GtfsCache,
    registry: MeterRegistry,
) {
    private val log = Logger.getLogger(GtfsImportService::class.java)
    private val lastSuccessEpoch = AtomicLong(0)
    private val failures = registry.counter("gtfs_import_failures_total")

    init {
        registry.gauge("gtfs_import_last_success_seconds", lastSuccessEpoch) { it.get().toDouble() }
    }

    /** Načte jízdní řád z DB do paměti. */
    fun loadFromDb() {
        val trips = repository.loadRecentTrips()
        cache.replace(repository.loadRoutes(), repository.loadStops(), trips)
        repository.lastImportAt()?.let { lastSuccessEpoch.set(it.toEpochSecond()) }
        log.infof("Jízdní řád z DB: %d spojů", trips.size)
    }

    fun importNeeded(): Boolean {
        val last = repository.lastImportAt() ?: return true
        return Duration.between(last.toInstant(), Instant.now()) > config.gtfs().maxAge()
    }

    @Scheduled(cron = "0 30 4 * * ?", timeZone = "Europe/Prague", concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        skipExecutionIf = JobsDisabled::class)
    fun nightly() {
        importNow()
        fillCalendar()
    }

    /** @return true při úspěchu */
    fun importNow(): Boolean {
        val started = System.nanoTime()
        val file = Files.createTempFile("PID_GTFS", ".zip")
        return try {
            val request = HttpRequest.newBuilder(URI.create(config.gtfs().url())).timeout(Duration.ofMinutes(5)).GET().build()
            val response = HttpClients.default.send(request, HttpResponse.BodyHandlers.ofFile(file))
            check(response.statusCode() == 200) { "GTFS HTTP ${response.statusCode()}" }
            val data = GtfsParser.parse(file)
            check(data.trips.isNotEmpty() && data.routes.isNotEmpty()) { "GTFS je prázdné" }
            repository.save(data)
            cache.replace(data.routes, data.stops, data.trips)
            lastSuccessEpoch.set(Instant.now().epochSecond)
            log.infof(
                "GTFS import: %d linek, %d zastávek, %d spojů za %d s",
                data.routes.size, data.stops.size, data.trips.size, (System.nanoTime() - started) / 1_000_000_000,
            )
            true
        } catch (e: Exception) {
            failures.increment()
            log.error("GTFS import selhal, zůstává poslední úspěšný", e)
            false
        } finally {
            file.deleteIfExists()
        }
    }

    /** FR-04: kalendář od začátku loňského roku na 2 roky dopředu. */
    fun fillCalendar() {
        val today = LocalDate.now(PragueTime.ZONE)
        repository.saveCalendar(LocalDate.of(today.year - 1, 1, 1), today.plusYears(2))
    }
}
