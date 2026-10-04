package cz.zasstojime.api

import cz.zasstojime.aggregation.AggregationService
import cz.zasstojime.aggregation.DailyTotals
import cz.zasstojime.aggregation.DashboardRepository
import cz.zasstojime.aggregation.DaySnapshot
import cz.zasstojime.aggregation.RecordTrip
import cz.zasstojime.aggregation.RouteTotals
import cz.zasstojime.gtfs.Category
import cz.zasstojime.gtfs.GtfsCache
import cz.zasstojime.gtfs.RouteInfo
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

private const val DASHBOARD_CACHE = "public, s-maxage=30, stale-while-revalidate=60"

@Serializable
data class TotalsDto(
    @SerialName("trips_total") val tripsTotal: Int,
    @SerialName("trips_finished") val tripsFinished: Int,
    @SerialName("trips_canceled") val tripsCanceled: Int,
    @SerialName("dep_total") val depTotal: Int,
    @SerialName("dep_on_time") val depOnTime: Int,
    @SerialName("dep_early") val depEarly: Int,
    @SerialName("dep_late") val depLate: Int,
    @SerialName("dep_late_sum") val depLateSum: Long,
    @SerialName("sum_max_delay") val sumMaxDelay: Long,
    @SerialName("punctuality_pct") val punctualityPct: Double?,
    @SerialName("average_late_delay_s") val averageLateDelayS: Double?,
)

private fun DailyTotals.dto() = TotalsDto(tripsTotal, tripsFinished, tripsCanceled, depTotal,
    depOnTime, depEarly, depLate, depLateSum, sumMaxDelay, punctuality, averageLateDelay)

@Serializable
data class RecordDto(
    @SerialName("trip_id") val tripId: String,
    val line: String,
    val headsign: String?,
    @SerialName("delay_s") val delayS: Int,
    val at: String?,
    @SerialName("stop_id") val stopId: String?,
    val flagged: Boolean,
    val verified: Boolean,
)

private fun RecordTrip.dto() = RecordDto(tripId, routeShort, headsign, delay, at?.toString(), stopId, flagged, verified)

@Serializable
data class DayResponse(
    val date: String,
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("data_as_of") val dataAsOf: String?,
    val incomplete: Boolean,
    val network: TotalsDto,
    val categories: Map<String, TotalsDto>,
    @SerialName("running_trips") val runningTrips: Int?,
    @SerialName("running_late_trips") val runningLateTrips: Int?,
    val source: String = SOURCE_ATTRIBUTION,
)

@Serializable
data class RouteRankDto(
    val line: String,
    val category: String,
    /** Druh dopravy pro popisek linky: tram, bus, train, trolleybus, ferry, funicular, metro, other. */
    val mode: String,
    /** Trasa linky z jízdního řádu, např. „Praha - Milovice“; `null`, dokud ji import nedoplní. */
    @SerialName("route_name") val routeName: String?,
    @SerialName("route_id") val routeId: String,
    val totals: TotalsDto,
    @SerialName("average_max_delay_s") val averageMaxDelayS: Double?,
)

private fun RouteTotals.dto(route: RouteInfo?) = RouteRankDto(routeShort, category.db,
    transportMode(route?.routeType, category), route?.longName, routeId, totals.dto(), if (totals.tripsTotal == 0) null else totals.sumMaxDelay.toDouble() / totals.tripsTotal)

/** Kategorie slučuje trolejbus, přívoz a lanovku do `other`, popisek linky je ale potřebuje rozlišit. */
internal fun transportMode(routeType: Int?, category: Category): String = when (routeType) {
    0 -> "tram"
    1 -> "metro"
    2 -> "train"
    3 -> "bus"
    4 -> "ferry"
    7 -> "funicular"
    11 -> "trolleybus"
    else -> when (category) {
        Category.TRAM -> "tram"
        Category.METRO -> "metro"
        Category.TRAIN -> "train"
        Category.BUS_CITY, Category.BUS_REGIONAL -> "bus"
        Category.OTHER -> "other"
    }
}

@Serializable
data class LeaderboardResponse(
    val date: String,
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("data_as_of") val dataAsOf: String?,
    val incomplete: Boolean,
    @SerialName("most_total_delay") val mostTotalDelay: List<RouteRankDto>,
    @SerialName("highest_delay_per_trip") val highestDelayPerTrip: List<RouteRankDto>,
    @SerialName("worst_punctuality") val worstPunctuality: List<RouteRankDto>,
    val source: String = SOURCE_ATTRIBUTION,
)

@Serializable
data class RecordResponse(
    val date: String,
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("data_as_of") val dataAsOf: String?,
    val incomplete: Boolean,
    val record: RecordDto?,
    val source: String = SOURCE_ATTRIBUTION,
)

@Serializable
data class HourlyDto(val hour: Int, val category: String, val totals: TotalsDto)

@Serializable
data class HourlyResponse(
    val date: String,
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("data_as_of") val dataAsOf: String?,
    val incomplete: Boolean,
    val hours: List<HourlyDto>,
    val source: String = SOURCE_ATTRIBUTION,
)

@Path("/api/day")
@Produces(MediaType.APPLICATION_JSON)
class DayResource(
    private val aggregation: AggregationService,
    private val repository: DashboardRepository,
    private val gtfs: GtfsCache,
) {
    @GET
    @Path("{date}")
    @APIResponse(responseCode = "200", content = [Content(schema = Schema(implementation = DayResponse::class))])
    fun day(@PathParam("date") raw: String): Response {
        val date = parseDate(raw)
        val snapshot = load(date) ?: return Response.status(404).build()
        val coverage = repository.coverage(date)
        val live = aggregation.snapshot(date) != null
        return cached(DayResponse(date.toString(), Instant.now().toString(),
            snapshot.dataAsOf?.toString() ?: coverage.dataAsOf?.toString(), coverage.incomplete,
            snapshot.publicNetwork.dto(),
            snapshot.categories.filterKeys { it.public }.mapKeys { it.key.db }.mapValues { it.value.dto() },
            if (live) snapshot.runningTrips else null, if (live) snapshot.runningLateTrips else null))
    }

    @GET
    @Path("{date}/leaderboard")
    @APIResponse(responseCode = "200", content = [Content(schema = Schema(implementation = LeaderboardResponse::class))])
    fun leaderboard(@PathParam("date") raw: String): Response {
        val date = parseDate(raw)
        val snapshot = load(date) ?: return Response.status(404).build()
        val coverage = repository.coverage(date)
        val routes = snapshot.routes.values.filter { it.category.public }
        val eligible = routes.filter { it.totals.tripsFinished >= 20 }
        val dto = { route: RouteTotals -> route.dto(gtfs.route(route.routeId)) }
        return cached(LeaderboardResponse(date.toString(), Instant.now().toString(),
            snapshot.dataAsOf?.toString() ?: coverage.dataAsOf?.toString(), coverage.incomplete,
            routes.sortedByDescending { it.totals.sumMaxDelay }.take(10).map(dto),
            eligible.sortedByDescending { it.totals.sumMaxDelay.toDouble() / it.totals.tripsTotal }.take(10).map(dto),
            eligible.filter { it.totals.punctuality != null }
                .sortedBy { it.totals.punctuality }.take(10).map(dto)))
    }

    @GET
    @Path("{date}/record")
    @APIResponse(responseCode = "200", content = [Content(schema = Schema(implementation = RecordResponse::class))])
    fun record(@PathParam("date") raw: String): Response {
        val date = parseDate(raw)
        val snapshot = load(date) ?: return Response.status(404).build()
        val coverage = repository.coverage(date)
        return cached(RecordResponse(date.toString(), Instant.now().toString(),
            snapshot.dataAsOf?.toString() ?: coverage.dataAsOf?.toString(), coverage.incomplete,
            repository.record(date)?.dto()))
    }

    @GET
    @Path("{date}/hourly")
    @APIResponse(responseCode = "200", content = [Content(schema = Schema(implementation = HourlyResponse::class))])
    fun hourly(@PathParam("date") raw: String): Response {
        val date = parseDate(raw)
        val snapshot = load(date) ?: return Response.status(404).build()
        val coverage = repository.coverage(date)
        return cached(HourlyResponse(date.toString(), Instant.now().toString(),
            snapshot.dataAsOf?.toString() ?: coverage.dataAsOf?.toString(), coverage.incomplete,
            repository.hourly(date).map { HourlyDto(it.hour, it.category, it.totals.dto()) }))
    }

    private fun load(day: LocalDate): DaySnapshot? = aggregation.snapshot(day) ?: repository.loadDay(day)

    private fun parseDate(raw: String): LocalDate = if (raw == "today") LocalDate.now(PragueTime.ZONE) else try {
        LocalDate.parse(raw)
    } catch (e: DateTimeParseException) {
        throw BadRequestException("Datum musí být YYYY-MM-DD nebo today")
    }

    private fun cached(entity: Any): Response = Response.ok(entity).header("Cache-Control", DASHBOARD_CACHE).build()
}
