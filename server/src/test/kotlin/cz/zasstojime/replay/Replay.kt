package cz.zasstojime.replay

import cz.zasstojime.golemio.SnapshotParser
import cz.zasstojime.gtfs.GtfsCache
import cz.zasstojime.gtfs.GtfsData
import cz.zasstojime.gtfs.GtfsParser
import cz.zasstojime.ingest.CycleResult
import cz.zasstojime.ingest.FinishedBy
import cz.zasstojime.ingest.Punctuality
import cz.zasstojime.ingest.StopEvent
import cz.zasstojime.ingest.TripProcessor
import cz.zasstojime.ingest.TripState
import cz.zasstojime.ingest.TripStatus
import org.tukaani.xz.XZInputStream
import java.nio.file.Files
import java.time.Instant
import java.util.Locale

/**
 * Fixture z 30 min reálných snímků Golemia (AC-05), vytvořená `tools/make_replay_fixture.py`.
 * `snapshots.tsv.xz`: na řádek čas stažení TAB JSON; `gtfs.zip`: výřez PID GTFS.
 */
object Replay {
    private const val DIR = "/replay"

    fun gtfsData(): GtfsData {
        val tmp = Files.createTempFile("replay-gtfs", ".zip")
        try {
            javaClass.getResourceAsStream("$DIR/gtfs.zip")!!.use { Files.copy(it, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            return GtfsParser.parse(tmp)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    fun schedule(): GtfsCache = gtfsData().let { GtfsCache().apply { replace(it.routes, it.stops, it.trips) } }

    /** Snímky v pořadí stažení. */
    fun forEachSnapshot(block: (Instant, SnapshotParser.Result) -> Unit) {
        XZInputStream(javaClass.getResourceAsStream("$DIR/snapshots.tsv.xz")!!).bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                val tab = line.indexOf('\t')
                block(Instant.parse(line.substring(0, tab)), SnapshotParser.parse(line.substring(tab + 1)))
            }
        }
    }

    /** Výsledek přehrání čistou logikou: průjezdy podle klíče jako po UPSERTu do DB. */
    class Outcome(
        val snapshots: Int,
        val results: List<CycleResult>,
        val events: Map<Triple<java.time.LocalDate, String, Int>, StopEvent>,
        val trips: List<TripState>,
    )

    fun run(): Outcome {
        val processor = TripProcessor(schedule())
        val results = ArrayList<CycleResult>()
        val events = LinkedHashMap<Triple<java.time.LocalDate, String, Int>, StopEvent>()
        forEachSnapshot { fetchTime, parsed ->
            val r = processor.process(fetchTime, parsed.features)
            r.events.forEach { events[Triple(it.serviceDate, it.tripId, it.stopSeq)] = it }
            results += r
        }
        return Outcome(results.size, results, events, processor.allTrips().filter { it.persistable })
    }

    /** Lidsky čitelný souhrn pro snapshot test: počty průjezdů, index po kategoriích, top 5 zpoždění. */
    fun summary(o: Outcome): String = buildString {
        val events = o.events.values
        appendLine("snímků: ${o.snapshots}")
        appendLine("vozidel celkem: ${o.results.sumOf { it.stats.vehicles }}")
        appendLine("platných snímků spojů: ${o.results.sumOf { it.stats.validSnapshots }}")
        appendLine("průjezdů: ${events.size} (s odjezdem ${events.count { it.depDelay != null }}, s příjezdem ${events.count { it.arrDelay != null }})")
        appendLine(
            "spojů: running ${o.trips.count { it.status == TripStatus.RUNNING }}, " +
                "finished/after_track ${o.trips.count { it.finishedBy == FinishedBy.AFTER_TRACK }}, " +
                "finished/timeout ${o.trips.count { it.finishedBy == FinishedBy.TIMEOUT }}, " +
                "canceled ${o.trips.count { it.status == TripStatus.CANCELED }}",
        )
        appendLine()
        appendLine("kategorie       odjezdů    včas  předjetí  zpožděno  index")
        val byCategory = o.trips.groupBy { it.category }.toSortedMap()
        val all = o.trips
        (byCategory.map { (c, t) -> c.db to t } + ("all" to all)).forEach { (name, trips) ->
            val total = trips.sumOf { it.depTotal }
            val onTime = trips.sumOf { it.depOnTime }
            appendLine(
                String.format(
                    Locale.ROOT, "%-14s %8d %7d %9d %9d %6.2f",
                    name, total, onTime, trips.sumOf { it.depEarly }, trips.sumOf { it.depLate },
                    if (total == 0) 0.0 else onTime * 100.0 / total,
                ),
            )
        }
        appendLine()
        appendLine("top 5 max_delay:")
        o.trips.filter { it.maxDelay != null }
            .sortedWith(compareByDescending<TripState> { it.maxDelay }.thenBy { it.key.tripId })
            .take(5)
            .forEach { appendLine("  ${it.key.serviceDate} ${it.key.tripId} linka ${it.routeShort}: ${it.maxDelay} s na ${it.maxDelayStop}") }
        appendLine()
        val skipped = o.results.flatMap { it.stats.skipped.entries }.groupBy({ it.key.tag }, { it.value }).mapValues { it.value.sum() }.toSortedMap()
        val dropped = o.results.flatMap { it.stats.dropped.entries }.groupBy({ it.key.tag }, { it.value }).mapValues { it.value.sum() }.toSortedMap()
        appendLine("přeskočené snímky: $skipped")
        appendLine("zahozené průjezdy: $dropped")
        appendLine("neznámé linky: ${o.results.sumOf { it.stats.unknownRoutes }}")
        appendLine("pásma odjezdů: " + Punctuality.entries.associateWith { p -> events.count { e -> e.depDelay?.let { Punctuality.of(it) } == p } })
    }
}
