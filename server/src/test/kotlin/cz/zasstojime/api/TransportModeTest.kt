package cz.zasstojime.api

import cz.zasstojime.gtfs.Category
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Popisek linky v žebříčcích rozlišuje i druhy, které kategorie slučuje do `other`. */
class TransportModeTest {
    @Test
    fun `druh podle route_type`() {
        assertEquals("tram", transportMode(0, Category.TRAM))
        assertEquals("bus", transportMode(3, Category.BUS_REGIONAL))
        assertEquals("ferry", transportMode(4, Category.OTHER))
        assertEquals("funicular", transportMode(7, Category.OTHER))
        assertEquals("trolleybus", transportMode(11, Category.OTHER))
    }

    @Test
    fun `bez jizdniho radu podle kategorie`() {
        assertEquals("bus", transportMode(null, Category.BUS_CITY))
        assertEquals("train", transportMode(null, Category.TRAIN))
        assertEquals("other", transportMode(null, Category.OTHER))
    }
}
