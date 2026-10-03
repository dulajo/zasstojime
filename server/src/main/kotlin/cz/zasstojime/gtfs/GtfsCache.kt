package cz.zasstojime.gtfs

import jakarta.enterprise.context.ApplicationScoped

/** Jízdní řád v paměti pro zpracování snímků; po importu se vymění celý najednou. */
@ApplicationScoped
class GtfsCache : ScheduleLookup {
    private class Snapshot(
        val routes: Map<String, RouteInfo>,
        val stops: Map<String, StopInfo>,
        val firstDepartures: Map<String, Int>,
    )

    @Volatile
    private var current = Snapshot(emptyMap(), emptyMap(), emptyMap())

    val loaded: Boolean get() = current.firstDepartures.isNotEmpty()

    fun replace(routes: Collection<RouteInfo>, stops: Collection<StopInfo>, trips: Collection<GtfsTrip>) {
        current = Snapshot(
            routes.associateBy { it.routeId },
            stops.associateBy { it.stopId },
            trips.associate { it.tripId to it.firstDepartureS },
        )
    }

    override fun route(routeId: String): RouteInfo? = current.routes[routeId]

    override fun stop(stopId: String): StopInfo? = current.stops[stopId]

    override fun firstDepartureS(tripId: String): Int? = current.firstDepartures[tripId]
}
