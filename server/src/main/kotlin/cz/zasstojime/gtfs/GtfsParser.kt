package cz.zasstojime.gtfs

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVRecord
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.file.Path
import java.util.zip.ZipFile

class GtfsData(
    val routes: List<RouteInfo>,
    val stops: List<StopInfo>,
    val trips: List<GtfsTrip>,
)

/**
 * Čte PID GTFS (FR-02, FR-02a) přímo ze zipu.
 * Z `stop_times.txt` (~115 MB) se drží jen čas výjezdu z první zastávky každého spoje.
 */
object GtfsParser {
    private val format: CSVFormat = CSVFormat.RFC4180.builder()
        .setHeader()
        .setSkipHeaderRecord(true)
        .setIgnoreSurroundingSpaces(true)
        .get()

    fun parse(zip: Path): GtfsData = ZipFile(zip.toFile()).use { z ->
        fun open(name: String): InputStream =
            z.getEntry(name)?.let { z.getInputStream(it) } ?: throw IllegalArgumentException("GTFS neobsahuje $name")

        val routes = open("routes.txt").use { parseRoutes(it) }
        val stops = open("stops.txt").use { parseStops(it) }
        val tripRoutes = open("trips.txt").use { parseTripRoutes(it) }
        val firstDepartures = open("stop_times.txt").use { parseFirstDepartures(it) }
        val trips = tripRoutes.mapNotNull { (tripId, routeId) ->
            firstDepartures[tripId]?.let { GtfsTrip(tripId, routeId, it) }
        }
        GtfsData(routes, stops, trips)
    }

    fun parseRoutes(input: InputStream): List<RouteInfo> = records(input) { r ->
        val routeType = r.int("route_type") ?: return@records null
        RouteInfo(
            routeId = r.str("route_id") ?: return@records null,
            shortName = r.str("route_short_name") ?: r.get("route_id"),
            routeType = routeType,
            category = Category.of(routeType, r.flag("is_regional")),
            isNight = r.flag("is_night"),
            isSubstitute = r.flag("is_substitute_transport"),
            longName = r.str("route_long_name"),
        )
    }

    fun parseStops(input: InputStream): List<StopInfo> = records(input) { r ->
        StopInfo(
            stopId = r.str("stop_id") ?: return@records null,
            name = r.str("stop_name") ?: "",
            nodeId = r.int("asw_node_id"),
            platformCode = r.str("platform_code"),
            lat = r.str("stop_lat")?.toDoubleOrNull(),
            lon = r.str("stop_lon")?.toDoubleOrNull(),
        )
    }

    /** trip_id → route_id */
    fun parseTripRoutes(input: InputStream): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        records(input) { r ->
            val tripId = r.str("trip_id")
            val routeId = r.str("route_id")
            if (tripId != null && routeId != null) out[tripId] = routeId
            null
        }
        return out
    }

    /** trip_id → výjezd z první zastávky (nejnižší `stop_sequence`) v s od půlnoci. */
    fun parseFirstDepartures(input: InputStream): Map<String, Int> {
        val firstSeq = HashMap<String, Int>(131_072)
        val departure = HashMap<String, Int>(131_072)
        records(input) { r ->
            val tripId = r.str("trip_id") ?: return@records null
            val seq = r.int("stop_sequence") ?: return@records null
            val time = parseGtfsTime(r.str("departure_time") ?: r.str("arrival_time")) ?: return@records null
            val known = firstSeq[tripId]
            if (known == null || seq < known) {
                firstSeq[tripId] = seq
                departure[tripId] = time
            }
            null
        }
        return departure
    }

    /** `HH:MM:SS`, hodiny mohou být ≥ 24. */
    fun parseGtfsTime(value: String?): Int? {
        val parts = value?.split(':') ?: return null
        if (parts.size != 3) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val s = parts[2].toIntOrNull() ?: return null
        return h * 3600 + m * 60 + s
    }

    private fun <T : Any> records(input: InputStream, map: (CSVRecord) -> T?): List<T> {
        val reader = InputStreamReader(BomSkippingInputStream(input), Charsets.UTF_8).buffered(1 shl 16)
        val out = ArrayList<T>()
        CSVParser.parse(reader, format).use { parser ->
            for (record in parser) map(record)?.let { out += it }
        }
        return out
    }

    private fun CSVRecord.str(name: String): String? =
        if (isMapped(name) && isSet(name)) get(name).takeIf { it.isNotEmpty() } else null

    private fun CSVRecord.int(name: String): Int? = str(name)?.toIntOrNull()

    private fun CSVRecord.flag(name: String): Boolean = str(name) == "1"
}

/** Přeskočí UTF-8 BOM na začátku souboru, jinak by se první sloupec jmenoval „﻿route_id“. */
private class BomSkippingInputStream(input: InputStream) : java.io.PushbackInputStream(input, 3) {
    init {
        val bom = ByteArray(3)
        val n = super.read(bom, 0, 3)
        val isBom = n == 3 && bom[0] == 0xEF.toByte() && bom[1] == 0xBB.toByte() && bom[2] == 0xBF.toByte()
        if (!isBom && n > 0) unread(bom, 0, n)
    }
}
