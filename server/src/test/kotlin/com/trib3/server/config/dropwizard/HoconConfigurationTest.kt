package com.trib3.server.config.dropwizard

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.fasterxml.jackson.databind.ObjectMapper
import com.trib3.config.ConfigLoader
import com.trib3.server.config.BootstrapConfig
import com.trib3.server.modules.DefaultApplicationModule
import io.dropwizard.configuration.ConfigurationValidationException
import io.dropwizard.configuration.FileConfigurationSourceProvider
import io.dropwizard.core.Configuration
import io.dropwizard.core.server.SimpleServerFactory
import io.dropwizard.core.setup.Bootstrap
import io.dropwizard.jetty.HttpConnectorFactory
import jakarta.validation.constraints.Min
import org.testng.annotations.Test

/**
 * Configuration with a constraint that the default/test config violates, used to exercise validation.
 */
class ValidatedConfig : Configuration() {
    @get:Min(10)
    val minValue: Int = 0
}

class HoconConfigurationTest {
    private val configLoader = ConfigLoader()
    private val injector = BootstrapConfig(configLoader).getInjector(listOf(DefaultApplicationModule()))

    @Test
    fun testHoconFactory() {
        val factoryFactory = HoconConfigurationFactoryFactory<Configuration>(configLoader)
        val mapper = injector.getInstance(ObjectMapper::class.java)
        val factory =
            factoryFactory.create(
                Configuration::class.java,
                Bootstrap<Configuration>(null).validatorFactory.validator,
                mapper,
                "dw",
            )
        val config = factory.build(FileConfigurationSourceProvider(), "ignored")
        // Ensure the admin port is set to test hocon's 9080 instead of default 8080
        assertThat(
            (
                (config.serverFactory as SimpleServerFactory)
                    .connector as HttpConnectorFactory
            ).port,
        ).isEqualTo(9080)
    }

    @Test
    fun testHoconFactoryRejectsInvalidConfig() {
        val factoryFactory = HoconConfigurationFactoryFactory<ValidatedConfig>(configLoader)
        val mapper = injector.getInstance(ObjectMapper::class.java)
        val factory =
            factoryFactory.create(
                ValidatedConfig::class.java,
                Bootstrap<ValidatedConfig>(null).validatorFactory.validator,
                mapper,
                "dw",
            )
        assertFailure {
            factory.build(FileConfigurationSourceProvider(), "ignored")
        }.isInstanceOf(ConfigurationValidationException::class)
    }

    @Test
    fun testHoconFactoryAcceptsValidConfig() {
        val factoryFactory = HoconConfigurationFactoryFactory<ValidatedConfig>(ConfigLoader("validConfigTestCase"))
        val mapper = injector.getInstance(ObjectMapper::class.java)
        val factory =
            factoryFactory.create(
                ValidatedConfig::class.java,
                Bootstrap<ValidatedConfig>(null).validatorFactory.validator,
                mapper,
                "dw",
            )
        assertThat(factory.build(FileConfigurationSourceProvider(), "ignored").minValue).isEqualTo(10)
    }
}
