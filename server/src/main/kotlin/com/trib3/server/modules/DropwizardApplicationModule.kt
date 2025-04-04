package com.trib3.server.modules

import com.google.inject.Provides
import com.trib3.config.modules.KMSModule
import com.trib3.server.config.TribeApplicationConfig
import com.trib3.server.filters.AdminAuthFilter
import com.trib3.server.swagger.JaxrsAppProcessor
import com.trib3.server.swagger.SwaggerInitializer
import dev.misfitlabs.kotlinguice4.multibindings.KotlinMultibinder
import io.dropwizard.core.Configuration
import io.dropwizard.core.ConfiguredBundle
import io.dropwizard.servlets.assets.AssetServlet
import io.swagger.v3.jaxrs2.integration.OpenApiServlet
import jakarta.inject.Named

/**
 * The default module for running as a dropwizard application.  Binds common functionality for all services.
 */
class DropwizardApplicationModule : TribeApplicationModule() {
    override fun configure() {
        install(KMSModule())

        // Bind admin servlets for swagger usage
        adminServletBinder().addBinding().toInstance(
            ServletConfig(
                OpenApiServlet::class.java.simpleName,
                OpenApiServlet(),
                listOf("/openapi"),
            ),
        )
        adminServletBinder().addBinding().toInstance(
            ServletConfig(
                "SwaggerAssetServlet",
                AssetServlet(
                    "swagger",
                    "/swagger",
                    "index.html",
                    Charsets.UTF_8,
                ),
                listOf("/swagger", "/swagger/*"),
            ),
        )

        KotlinMultibinder.newSetBinder<JaxrsAppProcessor>(kotlinBinder).addBinding().to<SwaggerInitializer>()

        // Set up a Bundle multibinder, but no Bundles to bind by default right now
        KotlinMultibinder.newSetBinder<ConfiguredBundle<Configuration>>(kotlinBinder)

        // Make sure the default set binders are set up
        appServletBinder()
        environmentCallbackBinder()
    }

    @Provides
    @Named(ADMIN_SERVLET_FILTERS_BIND_NAME)
    fun getAdminAuthFilter(config: TribeApplicationConfig): Set<ServletFilterConfig> =
        config.adminAuthToken
            ?.let {
                setOf(
                    ServletFilterConfig(
                        AdminAuthFilter::class.java.simpleName,
                        AdminAuthFilter::class.java,
                        mapOf("token" to it),
                    ),
                )
            }.orEmpty()

    // allow multiple installations so that multiple other modules can install this one
    override fun equals(other: Any?): Boolean = other is DropwizardApplicationModule

    override fun hashCode(): Int = this::class.hashCode()
}
