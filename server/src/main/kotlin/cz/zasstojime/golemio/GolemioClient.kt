package cz.zasstojime.golemio

import cz.zasstojime.config.AppConfig
import jakarta.enterprise.context.ApplicationScoped
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.GZIPInputStream

class GolemioException(message: String, val kind: String) : RuntimeException(message)

/** FR-10: `/v2/vehiclepositions?limit=10000&includeNotTracking=true` s gzipem a `X-Access-Token`. */
@ApplicationScoped
class GolemioClient(private val config: AppConfig) {
    /** Vrátí rozbalené JSON tělo odpovědi. */
    fun fetchVehiclePositions(): ByteArray {
        val builder = HttpRequest.newBuilder(URI.create(config.golemio().url()))
            .timeout(config.golemio().timeout())
            .header("Accept", "application/json")
            .header("Accept-Encoding", "gzip")
            .GET()
        config.golemio().apiKey().filter { it.isNotBlank() }.ifPresent { builder.header("X-Access-Token", it) }

        val response = try {
            HttpClients.default.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: java.net.http.HttpTimeoutException) {
            throw GolemioException("Golemio timeout: ${e.message}", "timeout")
        } catch (e: java.io.IOException) {
            throw GolemioException("Golemio nedostupné: ${e.message}", "io")
        }
        response.body().use { body ->
            if (response.statusCode() != 200) {
                throw GolemioException("Golemio HTTP ${response.statusCode()}", "http_${response.statusCode()}")
            }
            val gzip = response.headers().firstValue("Content-Encoding").map { it.equals("gzip", true) }.orElse(false)
            return (if (gzip) GZIPInputStream(body) else body).readAllBytes()
        }
    }
}
