package cz.zasstojime.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.util.Optional

/** Konfigurace z proměnných prostředí (docs/technicke.md – Konfigurace). */
@ConfigMapping(prefix = "app")
interface AppConfig {
    /** Spouštět sběr, import GTFS a noční úlohy. V testech vypnuto. */
    @WithDefault("true")
    fun jobsEnabled(): Boolean

    /** Interval sběru, `POLL_INTERVAL_S` (FR-10). Čte ho přímo `@Scheduled`. */
    fun pollInterval(): java.time.Duration

    /** Prodleva prvního cyklu po startu. */
    fun pollDelay(): java.time.Duration

    fun tripTimeoutS(): Long

    fun maxDelayJumpS(): Long

    fun alertDelayS(): Int

    @WithDefault("10m")
    fun healthMaxAge(): java.time.Duration

    fun golemio(): Golemio

    fun gtfs(): Gtfs

    interface Golemio {
        fun url(): String

        fun apiKey(): Optional<String>

        @WithDefault("25s")
        fun timeout(): java.time.Duration
    }

    interface Gtfs {
        fun url(): String

        /** Import při startu, pokud je poslední starší (FR-01). */
        @WithDefault("24h")
        fun maxAge(): java.time.Duration
    }
}
