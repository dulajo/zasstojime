package cz.zasstojime.api

import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@QuarkusTest
class HealthResourceTest {
    private val client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()

    private fun get(path: String): HttpResponse<String> =
        client.send(HttpRequest.newBuilder(URI.create("http://localhost:8081$path")).build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun `bez uspesneho behu vraci 503`() {
        val r = get("/api/health")
        assertEquals(503, r.statusCode())
        assertTrue(r.body().contains("\"status\":\"stale\""), r.body())
        assertTrue(r.body().contains("CC BY 4.0"))
    }

    @Test
    fun `openapi a metriky jsou dostupne`() {
        assertEquals(200, get("/q/openapi").statusCode())
        val metrics = get("/q/metrics")
        assertEquals(200, metrics.statusCode())
        assertTrue(metrics.body().contains("ingest_cycle_seconds"))
    }
}
