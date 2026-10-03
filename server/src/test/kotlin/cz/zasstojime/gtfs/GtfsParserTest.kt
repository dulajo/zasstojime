package cz.zasstojime.gtfs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream

class GtfsParserTest {
    @TempDir
    lateinit var dir: Path

    private fun zip(files: Map<String, String>): Path {
        val path = dir.resolve("gtfs.zip")
        ZipOutputStream(path.outputStream()).use { z ->
            files.forEach { (name, content) ->
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray())
                z.closeEntry()
            }
        }
        return path
    }

    @Test
    fun `cte linky, zastavky a vyjezdy spoju`() {
        val bom = "﻿"
        val data = GtfsParser.parse(
            zip(
                mapOf(
                    "routes.txt" to bom + """
                        route_id,agency_id,route_short_name,route_long_name,route_type,route_url,route_color,route_text_color,is_night,is_regional,is_substitute_transport
                        L991,99,A,"Nemocnice Motol - Depo Hostivař",1,,00A562,FFFFFF,0,0,0
                        L22,99,22,"Bílá Hora - Nádraží Hostivař",0,,,,0,0,0
                        L136,99,136,"Jižní Město - Sídliště Čakovice",3,,,,0,0,0
                        L327,99,327,"Opatov - Jesenice",3,,,,0,1,0
                        L907,99,907,"noční",3,,,,1,0,0
                        LX22,99,X22,"náhradní",3,,,,0,0,1
                        L58,99,58,"Smíchovské nádraží - Rabyně",11,,,,0,0,0
                        LS1,99,S1,"Praha - Kolín",2,,,,0,1,0
                    """.trimIndent(),
                    "stops.txt" to """
                        stop_id,stop_name,stop_lat,stop_lon,zone_id,stop_url,location_type,parent_station,wheelchair_boarding,level_id,platform_code,asw_node_id,asw_stop_id,zone_region_type
                        U1Z1P,"Boletická",50.132732,14.513702,"P",,0,,0,,A,1,1,1
                        T53051,"Km 2,800",50.163822,15.424174,,,0,,0,,,,,
                    """.trimIndent(),
                    "trips.txt" to """
                        route_id,service_id,trip_id,trip_headsign
                        L22,1,22_1,"Bílá Hora"
                        L907,1,907_1,"Noc"
                        L22,1,22_bez_casu,"X"
                    """.trimIndent(),
                    "stop_times.txt" to """
                        trip_id,arrival_time,departure_time,stop_id,stop_sequence
                        22_1,05:02:00,05:02:30,U1Z1P,2
                        22_1,05:00:00,05:01:00,U1Z1P,1
                        907_1,24:13:00,24:13:00,U1Z1P,1
                        907_1,24:20:00,24:20:00,U1Z1P,2
                    """.trimIndent(),
                ),
            ),
        )

        val routes = data.routes.associateBy { it.routeId }
        assertEquals(Category.METRO, routes.getValue("L991").category)
        assertEquals(Category.TRAM, routes.getValue("L22").category)
        assertEquals(Category.BUS_CITY, routes.getValue("L136").category)
        assertEquals(Category.BUS_REGIONAL, routes.getValue("L327").category)
        assertEquals(Category.OTHER, routes.getValue("L58").category)
        assertEquals(Category.TRAIN, routes.getValue("LS1").category)
        assertEquals(true, routes.getValue("L907").isNight)
        assertEquals(true, routes.getValue("LX22").isSubstitute)
        assertEquals("A", routes.getValue("L991").shortName)

        val stops = data.stops.associateBy { it.stopId }
        assertEquals(1, stops.getValue("U1Z1P").nodeId)
        assertEquals("A", stops.getValue("U1Z1P").platformCode)
        assertEquals(50.132732, stops.getValue("U1Z1P").lat)
        assertNull(stops.getValue("T53051").nodeId)
        assertEquals("Km 2,800", stops.getValue("T53051").name)

        assertEquals(
            listOf(GtfsTrip("22_1", "L22", 5 * 3600 + 60), GtfsTrip("907_1", "L907", 24 * 3600 + 13 * 60)),
            data.trips,
        )
    }

    @Test
    fun `cas jizdniho radu`() {
        assertEquals(24 * 3600 + 13 * 60 + 5, GtfsParser.parseGtfsTime("24:13:05"))
        assertEquals(5 * 3600, GtfsParser.parseGtfsTime("5:00:00"))
        assertNull(GtfsParser.parseGtfsTime(""))
        assertNull(GtfsParser.parseGtfsTime("xx:00:00"))
    }
}
