package com.trib3.db.modules

import assertk.all
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import com.google.inject.AbstractModule
import com.google.inject.Module
import com.trib3.db.config.DbConfig
import com.zaxxer.hikari.HikariDataSource
import org.jooq.DSLContext
import org.jooq.impl.DataSourceConnectionProvider
import org.testng.IModuleFactory
import org.testng.ITestContext
import org.testng.annotations.Guice
import org.testng.annotations.Test
import javax.inject.Inject
import javax.inject.Named
import javax.sql.DataSource

class ModuleFactory : IModuleFactory {
    override fun createModule(context: ITestContext?, testClass: Class<*>?): Module {
        return object : AbstractModule() {
            override fun configure() {
                install(DbModule())
                install(NamedDbModule("test"))
                install(NamedDbModule("test2"))
            }
        }
    }
}

@Guice(moduleFactory = ModuleFactory::class)
class NamedDbModuleTest
@Inject constructor(
    val defaultDbConfig: DbConfig,
    val defaultDataSource: DataSource,
    val defaultDSLContext: DSLContext,

    @Named("test") val testDbConfig: DbConfig,
    @Named("test") val testDataSource: DataSource,
    @Named("test") val testDSLContext: DSLContext,

    @Named("test2") val test2DbConfig: DbConfig,
    @Named("test2") val test2DataSource: DataSource,
    @Named("test2") val test2DSLContext: DSLContext
) {
    @Test
    fun testConfigs() {
        assertThat((defaultDbConfig.dataSource as HikariDataSource).jdbcUrl).contains("localhost")
        assertThat((testDbConfig.dataSource as HikariDataSource).jdbcUrl).all {
            contains("test")
            doesNotContain("test2")
        }
        assertThat((test2DbConfig.dataSource as HikariDataSource).jdbcUrl).contains("test2")
    }

    @Test
    fun testDataSources() {
        assertThat((defaultDataSource as HikariDataSource).jdbcUrl).contains("localhost")
        assertThat((testDataSource as HikariDataSource).jdbcUrl).all {
            contains("test")
            doesNotContain("test2")
        }
        assertThat((test2DataSource as HikariDataSource).jdbcUrl).contains("test2")
    }

    @Test
    fun testDSLContexts() {
        val defaultDS =
            (defaultDSLContext.configuration().connectionProvider() as DataSourceConnectionProvider).dataSource()
        val testDS =
            (testDSLContext.configuration().connectionProvider() as DataSourceConnectionProvider).dataSource()
        val test2DS =
            (test2DSLContext.configuration().connectionProvider() as DataSourceConnectionProvider).dataSource()
        assertThat((defaultDS as HikariDataSource).jdbcUrl).contains("localhost")
        assertThat((testDS as HikariDataSource).jdbcUrl).all {
            contains("test")
            doesNotContain("test2")
        }
        assertThat((test2DS as HikariDataSource).jdbcUrl).contains("test2")
    }
}