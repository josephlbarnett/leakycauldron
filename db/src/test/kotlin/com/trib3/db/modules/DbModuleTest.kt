package com.trib3.db.modules

import assertk.all
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import com.trib3.db.config.DbConfig
import com.zaxxer.hikari.HikariDataSource
import jakarta.inject.Inject
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DataSourceConnectionProvider
import org.testng.annotations.Guice
import org.testng.annotations.Test
import javax.sql.DataSource

/**
 * Test that ensures that the module enables injection of a [DataSource] and
 * a [DSLContext] from [DbConfig] with default values.
 */
@Guice(modules = [DbModule::class])
class DbModuleTest
    @Inject
    constructor(
        val dbConfig: DbConfig,
        val dslContext: DSLContext,
        val dataSource: DataSource,
    ) {
        @Test
        fun testConfig() {
            val ctxDataSource =
                (dslContext.configuration().connectionProvider() as DataSourceConnectionProvider).dataSource()
            assertThat(dbConfig.dataSource).all {
                isEqualTo(dataSource)
                isEqualTo(ctxDataSource)
            }
            assertThat(dbConfig.dslContext).isEqualTo(this.dslContext)
            assertThat(dbConfig.dialect).isEqualTo(SQLDialect.POSTGRES)
            assertThat((dataSource as HikariDataSource).jdbcUrl).contains("localhost:5432")
            assertThat(dataSource.username).isEqualTo("tribe")
            assertThat(dataSource.healthCheckRegistry).isNotNull()
            assertThat(dataSource.metricRegistry).isNotNull()
            assertThat(dataSource.isAutoCommit).isFalse()
            // these settings are set in config to be slightly different than hikariCP defaults
            assertThat(dataSource.connectionTimeout).isEqualTo(30001)
            assertThat(dataSource.idleTimeout).isEqualTo(600001)
            assertThat(dataSource.maxLifetime).isEqualTo(1800001)
            assertThat(dataSource.connectionTestQuery).isEqualTo("select 1")
            assertThat(dataSource.minimumIdle).isEqualTo(11)
            assertThat(dataSource.maximumPoolSize).isEqualTo(11)
        }

        @Test
        fun testEquals() {
            assertThat(DbModule()).isEqualTo(DbModule())
        }
    }
