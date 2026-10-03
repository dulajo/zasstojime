package cz.zasstojime.golemio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class SnapshotParserTest {
    private val json = """
        {"features":[
          {"geometry":{"type":"Point","coordinates":[14.5,49.9]},"properties":{
            "last_position":{"bearing":null,"delay":{"actual":null,"last_stop_arrival":16,"last_stop_departure":null},
              "is_canceled":null,
              "last_stop":{"arrival_time":"2026-10-03T10:29:00+02:00","departure_time":"2026-10-03T10:29:30+02:00","id":"U1330Z2","sequence":18},
              "origin_timestamp":"2026-10-03T10:44:03+02:00","state_position":"after_track","tracking":false,"nove_pole":1},
            "trip":{"gtfs":{"route_id":"L327","route_short_name":"327","route_type":3,"trip_headsign":"Jesenice","trip_id":"327_729_250628"},
              "start_timestamp":"2026-10-03T10:04:00+02:00","vehicle_registration_number":4147}}},
          {"properties":{"last_position":{"state_position":"on_track"},"trip":{"gtfs":{"route_id":"L1"}}}},
          {"properties":{"last_position":{"state_position":"on_track","is_canceled":true,"delay":null,"last_stop":null},
            "trip":{"gtfs":{"route_id":"L991","trip_id":"991_1","route_type":"1"},"start_timestamp":null,"vehicle_registration_number":null}}}
        ],"type":"FeatureCollection"}
    """.trimIndent()

    @Test
    fun `parsuje potrebna pole a toleruje chybejici a nova`() {
        val r = SnapshotParser.parse(json)
        assertEquals(3, r.total)
        assertEquals(1, r.skipped) // bez trip_id
        val f = r.features[0]
        assertEquals("327_729_250628", f.tripId)
        assertEquals("L327", f.routeId)
        assertEquals("327", f.routeShortName)
        assertEquals(3, f.routeType)
        assertEquals("Jesenice", f.headsign)
        assertEquals(Instant.parse("2026-10-03T08:04:00Z"), f.startTimestamp)
        assertEquals("4147", f.vehicleId)
        assertEquals("after_track", f.statePosition)
        assertNull(f.isCanceled)
        assertEquals(Instant.parse("2026-10-03T08:44:03Z"), f.originTimestamp)
        assertEquals(18, f.lastStopSequence)
        assertEquals("U1330Z2", f.lastStopId)
        assertEquals(Instant.parse("2026-10-03T08:29:00Z"), f.lastStopArrivalTime)
        assertEquals(Instant.parse("2026-10-03T08:29:30Z"), f.lastStopDepartureTime)
        assertEquals(16, f.delayLastStopArrival)
        assertNull(f.delayLastStopDeparture)

        val g = r.features[1]
        assertEquals(true, g.isCanceled)
        assertEquals(1, g.routeType)
        assertNull(g.startTimestamp)
        assertNull(g.vehicleId)
        assertNull(g.lastStopSequence)
    }

    @Test
    fun `odpoved bez features je chyba`() {
        assertThrows<IllegalArgumentException> { SnapshotParser.parse("""{"error":"Unauthorized"}""") }
    }
}
