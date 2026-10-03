package cz.zasstojime.gtfs

/** Kategorie linky pro semafor (metodika – Kategorie, ADR-0002). */
enum class Category(val db: String) {
    METRO("metro"),
    TRAM("tram"),
    BUS_CITY("bus_city"),
    BUS_REGIONAL("bus_regional"),
    TRAIN("train"),
    OTHER("other");

    companion object {
        fun of(routeType: Int, isRegional: Boolean): Category = when (routeType) {
            0 -> TRAM
            1 -> METRO
            2 -> TRAIN
            3 -> if (isRegional) BUS_REGIONAL else BUS_CITY
            else -> OTHER // trolejbus (11), přívoz (4), lanovka (7)
        }

        fun fromDb(value: String): Category = entries.firstOrNull { it.db == value } ?: OTHER
    }
}
