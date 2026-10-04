package cz.zasstojime.gtfs

data class RouteInfo(
    val routeId: String,
    val shortName: String,
    val routeType: Int,
    val category: Category,
    val isNight: Boolean,
    val isSubstitute: Boolean,
    /** GTFS `route_long_name`, např. „Praha - Milovice“. */
    val longName: String? = null,
)

data class StopInfo(
    val stopId: String,
    val name: String,
    /** `asw_node_id`; `null` = technický bod (ADR-0045). */
    val nodeId: Int?,
    val platformCode: String?,
    val lat: Double?,
    val lon: Double?,
)

data class GtfsTrip(
    val tripId: String,
    val routeId: String,
    /** Výjezd z první zastávky v sekundách od půlnoci servisního dne; může být ≥ 86 400. */
    val firstDepartureS: Int,
)

/** Co potřebuje zpracování snímku z jízdního řádu. */
interface ScheduleLookup {
    fun route(routeId: String): RouteInfo?

    fun stop(stopId: String): StopInfo?

    fun firstDepartureS(tripId: String): Int?
}
