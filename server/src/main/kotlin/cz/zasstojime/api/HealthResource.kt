package cz.zasstojime.api

import cz.zasstojime.config.AppConfig
import cz.zasstojime.gtfs.GtfsCache
import cz.zasstojime.ingest.IngestService
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

const val SOURCE_ATTRIBUTION = "Golemio (golemio.cz), CC BY 4.0"

@Serializable
data class HealthResponse(
    val status: String,
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("data_as_of") val dataAsOf: String?,
    @SerialName("data_age_s") val dataAgeS: Long?,
    @SerialName("running_trips") val runningTrips: Long,
    @SerialName("gtfs_loaded") val gtfsLoaded: Boolean,
    val source: String = SOURCE_ATTRIBUTION,
)

/** FR-33: 503, pokud poslední úspěšný cyklus je starší než 10 min. */
@Path("/api/health")
class HealthResource(
    private val ingest: IngestService,
    private val gtfs: GtfsCache,
    private val config: AppConfig,
) {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    fun health(): Response {
        val now = Instant.now()
        val last = ingest.lastSuccessAt
        val age = last?.let { Duration.between(it, now).seconds }
        val healthy = age != null && age <= config.healthMaxAge().seconds
        val body = HealthResponse(
            status = if (healthy) "ok" else "stale",
            generatedAt = now.toString(),
            dataAsOf = last?.toString(),
            dataAgeS = age,
            runningTrips = ingest.runningTripCount,
            gtfsLoaded = gtfs.loaded,
        )
        return Response.status(if (healthy) 200 else 503)
            .entity(body)
            .header("Cache-Control", "no-store")
            .build()
    }
}
