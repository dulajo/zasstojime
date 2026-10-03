package cz.zasstojime.persistence

import cz.zasstojime.config.JobsDisabled
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.jooq.DSLContext
import java.util.concurrent.atomic.AtomicLong

/** Velikost DB pro metriku (NFR-09); dotaz jen jednou za 5 min, ne při každém scrapu. */
@ApplicationScoped
class DbMetrics(private val dsl: DSLContext, registry: MeterRegistry) {
    private val size = AtomicLong(-1)

    init {
        registry.gauge("db_size_bytes", size) { it.get().toDouble() }
    }

    @Scheduled(every = "5m", delayed = "1m", skipExecutionIf = JobsDisabled::class)
    fun refresh() {
        size.set(dsl.fetchValue("SELECT pg_database_size(current_database())") as Long)
    }
}
