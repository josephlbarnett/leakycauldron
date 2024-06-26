package com.trib3.server.modules

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import io.dropwizard.core.Configuration
import io.dropwizard.core.ConfiguredBundle
import jakarta.inject.Inject
import jakarta.inject.Named
import org.testng.annotations.Guice
import org.testng.annotations.Test

@Guice(modules = [DropwizardApplicationModule::class])
class DropwizardApplicationModuleTest
    @Inject
    constructor(
        val bundles: Set<ConfiguredBundle<Configuration>>,
        @Named(TribeApplicationModule.ADMIN_SERVLET_FILTERS_BIND_NAME)
        val adminFilters: Set<ServletFilterConfig>,
    ) {
        @Test
        fun testBindings() {
            assertThat(bundles).isEmpty()
            assertThat(adminFilters).isNotNull()
        }

        @Test
        fun testModuleEquals() {
            assertThat(DropwizardApplicationModule()).isEqualTo(DropwizardApplicationModule())
        }
    }
