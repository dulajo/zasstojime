package cz.zasstojime

import cz.zasstojime.config.AppConfig
import cz.zasstojime.gtfs.GtfsCache
import cz.zasstojime.gtfs.GtfsImportService
import cz.zasstojime.ingest.IngestService
import cz.zasstojime.persistence.PartitionManager
import cz.zasstojime.time.PragueTime
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.jboss.logging.Logger
import java.time.LocalDate

/**
 * Start: oddíly, kalendář, jízdní řád, obnova stavu spojů, teprve pak sběr.
 * Běží ve vlastním vlákně, aby pomalý první import GTFS neblokoval start HTTP serveru.
 */
@ApplicationScoped
class Bootstrap(
    private val config: AppConfig,
    private val partitions: PartitionManager,
    private val gtfsImport: GtfsImportService,
    private val gtfsCache: GtfsCache,
    private val ingest: IngestService,
) {
    private val log = Logger.getLogger(Bootstrap::class.java)

    fun onStart(@Observes event: StartupEvent) {
        if (!config.jobsEnabled()) return
        Thread.ofPlatform().name("bootstrap").daemon().start { run() }
    }

    private fun run() {
        while (true) {
            try {
                partitions.maintain(LocalDate.now(PragueTime.ZONE))
                gtfsImport.fillCalendar()
                gtfsImport.loadFromDb()
                if (!gtfsCache.loaded && !gtfsImport.importNow()) {
                    log.warn("Jízdní řád chybí, sběr poběží bez kategorií a stanic, dokud import neprojde")
                }
                ingest.restore()
                break
            } catch (e: Exception) {
                log.error("Start selhal, další pokus za 30 s", e)
                Thread.sleep(30_000)
            }
        }
        if (gtfsImport.importNeeded()) gtfsImport.importNow()
    }

    /** Noční údržba oddílů `stop_events` (FR-23). */
    @Scheduled(cron = "0 15 3 * * ?", timeZone = "Europe/Prague", skipExecutionIf = cz.zasstojime.config.JobsDisabled::class)
    fun nightlyPartitions() {
        partitions.maintain(LocalDate.now(PragueTime.ZONE))
    }
}
