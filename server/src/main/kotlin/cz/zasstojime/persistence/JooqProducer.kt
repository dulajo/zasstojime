package cz.zasstojime.persistence

import io.agroal.api.AgroalDataSource
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.conf.Settings
import org.jooq.impl.DSL

@Singleton
class JooqProducer {
    init {
        System.setProperty("org.jooq.no-logo", "true")
        System.setProperty("org.jooq.no-tips", "true")
    }

    @Produces
    @Singleton
    fun dslContext(dataSource: AgroalDataSource): DSLContext =
        DSL.using(dataSource, SQLDialect.POSTGRES, Settings().withExecuteLogging(false))
}
