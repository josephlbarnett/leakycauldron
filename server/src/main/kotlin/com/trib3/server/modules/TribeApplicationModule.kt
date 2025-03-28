package com.trib3.server.modules

import com.google.inject.name.Names
import dev.misfitlabs.kotlinguice4.KotlinModule
import dev.misfitlabs.kotlinguice4.multibindings.KotlinMultibinder
import dev.misfitlabs.kotlinguice4.multibindings.KotlinOptionalBinder
import io.dropwizard.auth.AuthFilter
import io.dropwizard.auth.Authorizer
import io.dropwizard.core.setup.Environment
import jakarta.servlet.Servlet
import java.security.Principal

/**
 * Configuration information for binding [Servlet] instances to either the dropwizard
 * application context or admin context.
 */
data class ServletConfig(
    val name: String,
    val servlet: Servlet,
    val mappings: List<String>,
)

/**
 * On startup, any bound implementation will be called against the dropwizard [Environment].
 */
fun interface EnvironmentCallback {
    fun invoke(environment: Environment)
}

/**
 * Base class for modules that bind things for TribeApplication.
 * Provides binder methods for commonly bound members of the TribeApplication.
 */
open class TribeApplicationModule : KotlinModule() {
    companion object {
        const val APPLICATION_RESOURCES_BIND_NAME = "ApplicationResources"
        const val APPLICATION_SERVLETS_BIND_NAME = "ApplicationServlets"
        const val ADMIN_SERVLETS_BIND_NAME = "AdminServlets"
        const val ADMIN_SERVLET_FILTERS_BIND_NAME = "AdminFilters"
    }

    /**
     * Binder for jersey resources
     */
    fun resourceBinder(): KotlinMultibinder<Any> =
        KotlinMultibinder.newAnnotatedSetBinder(
            kotlinBinder,
            Names.named(APPLICATION_RESOURCES_BIND_NAME),
        )

    /**
     * Binder for app servlets
     */
    fun appServletBinder(): KotlinMultibinder<ServletConfig> =
        KotlinMultibinder.newAnnotatedSetBinder(
            kotlinBinder,
            Names.named(APPLICATION_SERVLETS_BIND_NAME),
        )

    /**
     * Binder for admin servlets
     */
    fun adminServletBinder(): KotlinMultibinder<ServletConfig> =
        KotlinMultibinder.newAnnotatedSetBinder(
            kotlinBinder,
            Names.named(ADMIN_SERVLETS_BIND_NAME),
        )

    /**
     * Optional binder for dropwizard AuthFilter
     */
    fun authFilterBinder(): KotlinOptionalBinder<AuthFilter<*, *>> =
        KotlinOptionalBinder.newOptionalBinder(kotlinBinder)

    /**
     * Optional binder for the role based principal authorizer
     */
    fun authorizerBinder(): KotlinOptionalBinder<Authorizer<Principal>> =
        KotlinOptionalBinder.newOptionalBinder(kotlinBinder)

    /**
     * Binder for [EnvironmentCallback]s
     */
    fun environmentCallbackBinder(): KotlinMultibinder<EnvironmentCallback> =
        KotlinMultibinder.newSetBinder(kotlinBinder)
}
