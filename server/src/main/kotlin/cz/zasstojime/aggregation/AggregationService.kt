package cz.zasstojime.aggregation

import cz.zasstojime.config.AppConfig
import cz.zasstojime.config.JobsDisabled
import cz.zasstojime.ingest.TripState
import cz.zasstojime.time.PragueTime
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

@ApplicationScoped
class AggregationService(
    private val repository: AggregationRepository,
    private val config: AppConfig,
) {
    private val log = Logger.getLogger(AggregationService::class.java)

    @Volatile private var current: Map<LocalDate, DaySnapshot> = emptyMap()
    private var lastFlush: Instant? = null

    fun snapshot(day: LocalDate): DaySnapshot? = current[day]

    /** The ingest thread is the only writer; readers see a complete immutable replacement. */
    fun refresh(trips: Iterable<TripState>, at: Instant?) {
        val today = LocalDate.now(PragueTime.ZONE)
        val materialized = trips.toList()
        current = listOf(today.minusDays(1), today).associateWith { day ->
            DailyAggregation.calculate(day, materialized, at)
        }
    }

    /** Failed flushes are retried on the next successful ingest cycle. */
    fun flushDue(now: Instant) {
        if (lastFlush != null && Duration.between(lastFlush, now).seconds < config.dailyFlushS()) return
        try {
            current.values.forEach(repository::flush)
            lastFlush = now
        } catch (e: Exception) {
            log.warn("Zápis denních souhrnů selhal; další cyklus jej zopakuje", e)
        }
    }

    /** Yesterday's service day is closed after overnight trips have ended. */
    @Scheduled(cron = "0 10 6 * * ?", timeZone = "Europe/Prague", skipExecutionIf = JobsDisabled::class)
    fun closeYesterday() {
        val yesterday = LocalDate.now(PragueTime.ZONE).minusDays(1)
        try {
            snapshot(yesterday)?.let(repository::flush)
            repository.rebuildStopRouteMonth(yesterday)
        } catch (e: Exception) {
            log.error("Uzavření denních souhrnů selhalo", e)
        }
    }
}
