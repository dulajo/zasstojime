package cz.zasstojime.config

import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.ScheduledExecution
import jakarta.inject.Singleton

/** Plánované úlohy se nespouští, když `app.jobs-enabled=false` (testy). */
@Singleton
class JobsDisabled(private val config: AppConfig) : Scheduled.SkipPredicate {
    override fun test(execution: ScheduledExecution): Boolean = !config.jobsEnabled()
}
