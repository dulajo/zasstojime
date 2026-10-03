package cz.zasstojime.api

import cz.zasstojime.ingest.IngestService
import cz.zasstojime.time.PragueTime
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private const val ROUTE_CACHE = "public, s-maxage=300"
private val DAY_TYPES = setOf("workday", "saturday", "sunday_holiday")

@Serializable data class LineDto(@SerialName("route_id") val routeId: String, val line: String, val category: String)
@Serializable data class StopDto(@SerialName("node_id") val nodeId: Int, val name: String, val headsign: String?, val sequence: Int)
@Serializable data class DepartureDto(val time: String, @SerialName("observed_days") val observedDays: Int)

@Serializable
data class LineLookupResponse(
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("data_as_of") val dataAsOf: String?,
    val incomplete: Boolean,
    val items: List<LineDto>,
    val source: String = SOURCE_ATTRIBUTION,
)

@Serializable
data class StopLookupResponse(
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("data_as_of") val dataAsOf: String?,
    val incomplete: Boolean,
    val items: List<StopDto>,
    val source: String = SOURCE_ATTRIBUTION,
)

@Serializable
data class DepartureLookupResponse(
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("data_as_of") val dataAsOf: String?,
    val incomplete: Boolean,
    val items: List<DepartureDto>,
    val source: String = SOURCE_ATTRIBUTION,
)

@Path("/api/lines")
@Produces(MediaType.APPLICATION_JSON)
class LinesResource(private val routes: RouteRepository, private val ingest: IngestService) {
    @GET
    fun lines(@QueryParam("q") query: String?): Response {
        val q = query?.trim() ?: ""
        if (q.length > 16) throw BadRequestException("Dotaz na linku je příliš dlouhý")
        return routeCached(LineLookupResponse(Instant.now().toString(), ingest.lastSuccessAt?.toString(), false,
            routes.lines(q).map { LineDto(it.routeId, it.line, it.category) }))
    }

    @GET
    @Path("{line}/stops")
    fun stops(@PathParam("line") line: String): Response = routeCached(StopLookupResponse(
        Instant.now().toString(), ingest.lastSuccessAt?.toString(), false,
        routes.stops(line).map { StopDto(it.nodeId, it.name, it.headsign, it.sequence) },
    ))

    @GET
    @Path("{line}/departures")
    fun departures(@PathParam("line") line: String, @QueryParam("from") from: Int?,
                   @QueryParam("day_type") dayType: String?): Response {
        if (from == null || dayType !in DAY_TYPES) throw BadRequestException("Vyberte uzel a typ dne")
        val since = LocalDate.now(PragueTime.ZONE).minusDays(13)
        return routeCached(DepartureLookupResponse(Instant.now().toString(), ingest.lastSuccessAt?.toString(), false,
            routes.departures(line, from, dayType!!, since).map { DepartureDto(it.time, it.observedDays) }))
    }
}

@Serializable
data class RouteDayDto(
    val date: String,
    @SerialName("trip_id") val tripId: String,
    @SerialName("planned_departure") val plannedDeparture: String,
    val measured: Boolean,
    @SerialName("boarding_delay_s") val boardingDelayS: Int?,
    @SerialName("boarding_estimated") val boardingEstimated: Boolean,
    @SerialName("arrival_delay_s") val arrivalDelayS: Int?,
)

@Serializable
data class RouteSummaryDto(
    @SerialName("measured_days") val measuredDays: Int,
    @SerialName("estimated_boarding_days") val estimatedBoardingDays: Int,
    @SerialName("on_time_boarding_pct") val onTimeBoardingPct: Double?,
    @SerialName("average_extra_wait_s") val averageExtraWaitS: Double?,
    @SerialName("average_arrival_delay_s") val averageArrivalDelayS: Double?,
    @SerialName("max_arrival_delay_s") val maxArrivalDelayS: Int?,
    @SerialName("average_extra_ride_s") val averageExtraRideS: Double?,
    @SerialName("total_collected_delay_s") val totalCollectedDelayS: Long,
)

@Serializable
data class MyRouteResponse(
    val line: String,
    val from: Int,
    val to: Int,
    val time: String,
    @SerialName("day_type") val dayType: String,
    val period: String,
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("data_as_of") val dataAsOf: String?,
    val incomplete: Boolean,
    val summary: RouteSummaryDto,
    val days: List<RouteDayDto>,
    val source: String = SOURCE_ATTRIBUTION,
)

@Path("/api/my-route")
@Produces(MediaType.APPLICATION_JSON)
class MyRouteResource(private val routes: RouteRepository, private val ingest: IngestService) {
    @GET
    fun route(@QueryParam("line") line: String?, @QueryParam("from") from: Int?,
              @QueryParam("to") to: Int?, @QueryParam("time") time: String?,
              @QueryParam("day_type") dayType: String?, @QueryParam("period") period: String?): Response {
        if (line.isNullOrBlank() || line.length > 16 || from == null || to == null || from == to || dayType !in DAY_TYPES)
            throw BadRequestException("Vyberte linku, dvě různé stanice a typ dne")
        val localTime = try { LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm")) }
            catch (e: Exception) { throw BadRequestException("Čas musí být HH:mm") }
        val timeText = localTime.format(DateTimeFormatter.ofPattern("HH:mm"))
        val selectedPeriod = period ?: "30d"
        val today = LocalDate.now(PragueTime.ZONE)
        val since = when (selectedPeriod) {
            "30d" -> today.minusDays(29)
            "90d" -> today.minusDays(89)
            "13m" -> today.minusMonths(13).plusDays(1)
            else -> throw BadRequestException("Období musí být 30d, 90d nebo 13m")
        }
        val observations = routes.observations(line, from, to, timeText, dayType!!, since, today)
        val measured = observations.filter { it.boardingDelay != null && it.arrivalDelay != null }
        val count = measured.size
        fun average(value: (RouteObservation) -> Double): Double? =
            if (count == 0) null else measured.sumOf(value) / count
        val summary = RouteSummaryDto(
            measuredDays = count,
            estimatedBoardingDays = measured.count { it.boardingEstimated },
            onTimeBoardingPct = average { if (it.boardingDelay!! in -59..179) 100.0 else 0.0 },
            averageExtraWaitS = average { it.boardingDelay!!.coerceAtLeast(0).toDouble() },
            averageArrivalDelayS = average { it.arrivalDelay!!.toDouble() },
            maxArrivalDelayS = measured.maxOfOrNull { it.arrivalDelay!! },
            averageExtraRideS = average { (it.arrivalDelay!! - it.boardingDelay!!).toDouble() },
            totalCollectedDelayS = measured.sumOf { it.arrivalDelay!!.coerceAtLeast(0).toLong() },
        )
        val days = observations.map { RouteDayDto(it.date.toString(), it.tripId, it.plannedDeparture.toString(),
            it.boardingDelay != null && it.arrivalDelay != null, it.boardingDelay, it.boardingEstimated, it.arrivalDelay) }
        return routeCached(MyRouteResponse(line, from, to, timeText, dayType, selectedPeriod,
            Instant.now().toString(), ingest.lastSuccessAt?.toString(),
            routes.incomplete(since, today, dayType) || observations.any { it.boardingDelay == null || it.arrivalDelay == null },
            summary, days))
    }

}

private fun routeCached(entity: Any): Response = Response.ok(entity).header("Cache-Control", ROUTE_CACHE).build()
