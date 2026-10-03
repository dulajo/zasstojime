package cz.zasstojime.persistence

import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import org.jooq.DSLContext
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap

/** Měsíční oddíly `stop_events` (ADR-0044, FR-23): zakládání dopředu a mazání starších než 13 měsíců. */
@ApplicationScoped
class PartitionManager(private val dsl: DSLContext) {
    private val log = Logger.getLogger(PartitionManager::class.java)
    private val known: MutableSet<YearMonth> = ConcurrentHashMap.newKeySet()

    companion object {
        const val RETAIN_MONTHS = 13L
        private val NAME = Regex("""stop_events_y(\d{4})m(\d{2})""")

        fun partitionName(month: YearMonth): String = "stop_events_y%04dm%02d".format(month.year, month.monthValue)
    }

    fun ensure(month: YearMonth) {
        if (month in known) return
        val from = month.atDay(1)
        val to = month.plusMonths(1).atDay(1)
        dsl.execute(
            "CREATE TABLE IF NOT EXISTS ${partitionName(month)} PARTITION OF stop_events " +
                "FOR VALUES FROM (DATE '$from') TO (DATE '$to')",
        )
        known += month
    }

    fun ensureForDates(dates: Collection<LocalDate>) {
        dates.mapTo(HashSet()) { YearMonth.from(it) }.forEach { ensure(it) }
    }

    fun existing(): List<YearMonth> = dsl.fetch(
        """
        SELECT c.relname FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        JOIN pg_class p ON p.oid = i.inhparent
        WHERE p.relname = 'stop_events'
        """.trimIndent(),
    ).mapNotNull { r ->
        NAME.matchEntire(r.get(0, String::class.java))?.let { YearMonth.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
    }.sorted()

    /** Založí minulý, tento a příští měsíc; smaže oddíly starší než [RETAIN_MONTHS] měsíců. */
    fun maintain(today: LocalDate) {
        val current = YearMonth.from(today)
        listOf(current.minusMonths(1), current, current.plusMonths(1)).forEach { ensure(it) }
        val oldestKept = current.minusMonths(RETAIN_MONTHS)
        existing().filter { it < oldestKept }.forEach { month ->
            dsl.execute("DROP TABLE IF EXISTS ${partitionName(month)}")
            known -= month
            log.infof("Smazán oddíl %s", partitionName(month))
        }
    }
}
