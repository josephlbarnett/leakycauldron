package com.trib3.server.modules

import assertk.all
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import com.codahale.metrics.health.HealthCheck
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.trib3.server.config.dropwizard.HoconConfigurationFactoryFactory
import com.trib3.server.filters.RequestIdFilter
import com.trib3.server.healthchecks.PingHealthCheck
import com.trib3.server.healthchecks.VersionHealthCheck
import io.dropwizard.configuration.ConfigurationFactoryFactory
import io.dropwizard.core.Configuration
import jakarta.inject.Inject
import jakarta.inject.Named
import org.eclipse.jetty.servlets.CrossOriginFilter
import org.eclipse.jetty.servlets.HeaderFilter
import org.testng.annotations.Guice
import org.testng.annotations.Test

@Guice(modules = [DefaultApplicationModule::class])
class DefaultApplicationModuleTest
    @Inject
    constructor(
        val configurationFactoryFactory: ConfigurationFactoryFactory<Configuration>,
        val servletFilterConfigs: Set<ServletFilterConfig>,
        val healthChecks: Set<HealthCheck>,
        val objectMapper: ObjectMapper,
        @Named(TribeApplicationModule.APPLICATION_RESOURCES_BIND_NAME)
        val resources: Set<Any>,
    ) {
        @Test
        fun testBindings() {
            assertThat(healthChecks.map { it::class }).all {
                contains(VersionHealthCheck::class)
                contains(PingHealthCheck::class)
            }
            assertThat(configurationFactoryFactory).isInstanceOf(HoconConfigurationFactoryFactory::class)
            assertThat(servletFilterConfigs.map { it.filterClass }).all {
                contains(RequestIdFilter::class.java)
                contains(CrossOriginFilter::class.java)
            }
            assertThat(servletFilterConfigs.map { it.name }).all {
                contains(RequestIdFilter::class.simpleName)
                contains(CrossOriginFilter::class.simpleName)
                contains(HeaderFilter::class.simpleName)
            }
            assertThat(objectMapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse()
        }
    }
