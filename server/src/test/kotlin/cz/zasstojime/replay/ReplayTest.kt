package cz.zasstojime.replay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * AC-05: přehrání 30 min reálných snímků dává deterministický výsledek.
 * Po vědomé změně metodiky: `UPDATE_SNAPSHOTS=1 ./gradlew test --tests '*ReplayTest'` a zkontrolovat diff.
 */
class ReplayTest {
    private val expectedFile = Path.of("src/test/resources/replay/expected-summary.txt")

    @Test
    fun `prehrani zaznamu odpovida ulozenemu souhrnu`() {
        val summary = Replay.summary(Replay.run())
        if (System.getenv("UPDATE_SNAPSHOTS") == "1") {
            Files.writeString(expectedFile, summary)
        }
        assertEquals(Files.readString(expectedFile), summary)
    }

    @Test
    fun `prehrani je deterministicke a konzistentni`() {
        val a = Replay.run()
        val b = Replay.run()
        assertEquals(Replay.summary(a), Replay.summary(b))

        assertTrue(a.events.isNotEmpty())
        a.trips.forEach { t ->
            assertEquals(t.depTotal, t.depOnTime + t.depEarly + t.depLate, t.key.toString())
        }
        // každý změřený odjezd je v průjezdech právě jednou
        assertEquals(a.trips.sumOf { it.depTotal }, a.events.values.count { it.depDelay != null })
    }
}
