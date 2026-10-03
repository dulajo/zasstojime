package cz.zasstojime.golemio

import java.time.Instant

/** Jedno vozidlo ze snímku Golemia, jen pole, která potřebuje metodika. */
data class VehicleFeature(
    val tripId: String,
    val routeId: String,
    val routeShortName: String?,
    val routeType: Int?,
    val headsign: String?,
    val startTimestamp: Instant?,
    val vehicleId: String?,
    val statePosition: String?,
    val isCanceled: Boolean?,
    val originTimestamp: Instant?,
    val lastStopSequence: Int?,
    val lastStopId: String?,
    val lastStopArrivalTime: Instant?,
    val lastStopDepartureTime: Instant?,
    val delayLastStopArrival: Int?,
    val delayLastStopDeparture: Int?,
)

object StatePosition {
    const val ON_TRACK = "on_track"
    const val AT_STOP = "at_stop"
    const val AFTER_TRACK = "after_track"
    const val AFTER_TRACK_DELAYED = "after_track_delayed"
    const val CANCELED = "canceled"

    val MEASURED = setOf(ON_TRACK, AT_STOP)
    val FINISHED = setOf(AFTER_TRACK, AFTER_TRACK_DELAYED)
}
