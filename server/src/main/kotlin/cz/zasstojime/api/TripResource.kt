package cz.zasstojime.api

import cz.zasstojime.gtfs.Category
import cz.zasstojime.gtfs.CzechCalendar
import cz.zasstojime.gtfs.GtfsCache
import cz.zasstojime.ingest.IngestService
import cz.zasstojime.time.PragueTime
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.media.Schema
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

private const val LIVE_TRIP_CACHE = "public, s-maxage=30, stale-while-revalidate=60"
private const val PAST_TRIP_CACHE = "public, s-maxage=3600"
private const val CALENDAR_CACHE = "public, s-maxage=86400"
private const val ROUTE_TODAY_CACHE = "public, s-maxage=300"

@Serializable
data class TripStopDto(
    val sequence: Int,
    @SerialName("stop_id") val stopId: String,
    @SerialName("node_id") val nodeId: Int,
    val name: String?,
    @SerialName("planned_arrival") val plannedArrival: String?,
    @SerialName("planned_departure") val plannedDeparture: String?,
    @SerialName("arrival_delay_s") val arrivalDelayS: Int?,
    @SerialName("departure_delay_s") val departureDelayS: Int?,
)

@Serializable
data class TripResponse(
    val date: String,
    @SerialName("trip_id") val tripId: String,
    val line: String,
    val category: String,
    val mode: String,
    @SerialName("route_name") val routeName: String?,
    val headsign: String?,
    /** running, finished nebo canceled. */
    val status: String,
    @SerialName("last_delay_s") val lastDelayS: Int?,
    @SerialName("last_seen_at") val lastSeenAt: String,
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("data_as_of") val dataAsOf: String?,
    val incomplete: Boolean,
    val stops: List<TripStopDto>,
    val source: String = SOURCE_ATTRIBUTION,
)

@Serializable
data class CalendarDayResponse(
    val date: String,
    @SerialName("day_type") val dayType: String,
    @SerialName("holiday_name") val holidayName: String?,
)

/** Průběh jednoho spoje po zastávkách, pro detail v „Moje trasa“. */
@Path("/api/trip")
@Produces(MediaType.APPLICATION_JSON)
class TripResource(private val routes: RouteRepository, private val gtfs: GtfsCache, private val ingest: IngestService) {
    @GET
    @Path("{date}/{tripId}")
    @APIResponse(responseCode = "200", content = [Content(schema = Schema(implementation = TripResponse::class))])
    fun trip(@PathParam("date") raw: String, @PathParam("tripId") tripId: String): Response {
        val date = parseDay(raw)
        if (tripId.isBlank() || tripId.length > 64) throw BadRequestException("Neplatné ID spoje")
        val header = routes.tripHeader(date, tripId) ?: return Response.status(404).build()
        val route = gtfs.route(header.routeId)
        val stops = routes.tripStops(date, tripId).map {
            TripStopDto(it.sequence, it.stopId, it.nodeId, it.name, it.plannedArrival?.toString(),
                it.plannedDeparture?.toString(), it.arrivalDelay, it.departureDelay)
        }
        val body = TripResponse(date.toString(), tripId, header.line, header.category,
            transportMode(route?.routeType, Category.fromDb(header.category)), route?.longName, header.headsign,
            header.status, header.lastDelay, header.lastSeenAt.toString(), Instant.now().toString(),
            ingest.lastSuccessAt?.toString(), false, stops)
        val live = header.status == "running" || date >= LocalDate.now(PragueTime.ZONE).minusDays(1)
        return Response.ok(body).header("Cache-Control", if (live) LIVE_TRIP_CACHE else PAST_TRIP_CACHE).build()
    }
}

/** Typ dne pro výběr spoje v „Moje trasa“ (metodika – Typ dne). */
@Path("/api/calendar")
@Produces(MediaType.APPLICATION_JSON)
class CalendarResource {
    @GET
    @Path("{date}")
    @APIResponse(responseCode = "200", content = [Content(schema = Schema(implementation = CalendarDayResponse::class))])
    fun day(@PathParam("date") raw: String): Response {
        val date = parseDay(raw)
        // „today“ se o půlnoci mění, konkrétní datum ne.
        return Response.ok(CalendarDayResponse(date.toString(), CzechCalendar.dayType(date).db, CzechCalendar.holidayName(date)))
            .header("Cache-Control", if (raw == "today") ROUTE_TODAY_CACHE else CALENDAR_CACHE).build()
    }
}

private fun parseDay(raw: String): LocalDate = if (raw == "today") LocalDate.now(PragueTime.ZONE) else try {
    LocalDate.parse(raw)
} catch (e: DateTimeParseException) {
    throw BadRequestException("Datum musí být YYYY-MM-DD nebo today")
}
