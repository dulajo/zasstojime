package cz.zasstojime.golemio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Převede odpověď `/v2/vehiclepositions` na seznam vozidel.
 * Tolerantní k novým a chybějícím polím (riziko R1); vozidlo bez `trip_id` nebo `route_id` se vynechá.
 */
object SnapshotParser {
    data class Result(val features: List<VehicleFeature>, val total: Int, val skipped: Int)

    fun parse(json: ByteArray): Result = parse(json.toString(Charsets.UTF_8))

    fun parse(json: String): Result {
        val root = Json.parseToJsonElement(json)
        val features = (root.obj()?.get("features") as? JsonArray) ?: throw IllegalArgumentException("Snímek nemá pole features")
        val out = ArrayList<VehicleFeature>(features.size)
        var skipped = 0
        for (feature in features) {
            val parsed = parseFeature(feature)
            if (parsed == null) skipped++ else out += parsed
        }
        return Result(out, features.size, skipped)
    }

    private fun parseFeature(feature: JsonElement): VehicleFeature? {
        val props = feature.obj()?.get("properties").obj() ?: return null
        val trip = props["trip"].obj() ?: return null
        val gtfs = trip["gtfs"].obj() ?: return null
        val tripId = gtfs["trip_id"].str() ?: return null
        val routeId = gtfs["route_id"].str() ?: return null
        val pos = props["last_position"].obj()
        val delay = pos?.get("delay").obj()
        val lastStop = pos?.get("last_stop").obj()
        return VehicleFeature(
            tripId = tripId,
            routeId = routeId,
            routeShortName = gtfs["route_short_name"].str(),
            routeType = gtfs["route_type"].int(),
            headsign = gtfs["trip_headsign"].str(),
            startTimestamp = trip["start_timestamp"].instant(),
            vehicleId = trip["vehicle_registration_number"].str(),
            statePosition = pos?.get("state_position").str(),
            isCanceled = pos?.get("is_canceled").bool(),
            originTimestamp = pos?.get("origin_timestamp").instant(),
            lastStopSequence = lastStop?.get("sequence").int(),
            lastStopId = lastStop?.get("id").str(),
            lastStopArrivalTime = lastStop?.get("arrival_time").instant(),
            lastStopDepartureTime = lastStop?.get("departure_time").instant(),
            delayLastStopArrival = delay?.get("last_stop_arrival").int(),
            delayLastStopDeparture = delay?.get("last_stop_departure").int(),
        )
    }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

    private fun JsonElement?.prim(): JsonPrimitive? = if (this is JsonPrimitive && this !is JsonNull) this else null

    private fun JsonElement?.str(): String? = prim()?.content?.takeIf { it.isNotEmpty() }

    private fun JsonElement?.int(): Int? = prim()?.let { it.intOrNull ?: it.content.toDoubleOrNull()?.toInt() }

    private fun JsonElement?.bool(): Boolean? = prim()?.booleanOrNull

    private fun JsonElement?.instant(): Instant? = str()?.let {
        try {
            OffsetDateTime.parse(it).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
