package com.trib3.graphql.modules

import com.expediagroup.graphql.dataloader.KotlinDataLoaderRegistryFactory
import com.expediagroup.graphql.generator.directives.KotlinSchemaDirectiveWiring
import com.google.inject.name.Names
import com.trib3.server.modules.DefaultApplicationModule
import com.trib3.server.modules.TribeApplicationModule
import dev.misfitlabs.kotlinguice4.multibindings.KotlinMapBinder
import dev.misfitlabs.kotlinguice4.multibindings.KotlinMultibinder
import dev.misfitlabs.kotlinguice4.multibindings.KotlinOptionalBinder
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.instrumentation.Instrumentation
import jakarta.ws.rs.container.ContainerRequestContext
import java.security.Principal

/**
 * Function that takes a [ContainerRequestContext] from the websocket upgrade request
 * or `connection_init` payload and returns an authorized [Principal], if any.
 */
typealias GraphQLWebSocketAuthenticator = Function1<
    @JvmSuppressWildcards ContainerRequestContext,
    @JvmSuppressWildcards Principal?,
>

/**
 * Base class for GraphQL application guice modules.  Provides
 * binders for GraphQL packages, queries, mutations, and subscriptions,
 * and ensures that a [DefaultGraphQLModule] is installed.
 */
@Suppress("TooManyFunctions") // All functions are graphQL binding related
abstract class GraphQLApplicationModule : TribeApplicationModule() {
    companion object {
        const val GRAPHQL_PACKAGES_BIND_NAME = "graphQLPackages"
        const val GRAPHQL_QUERIES_BIND_NAME = "graphQLQueries"
        const val GRAPHQL_MUTATIONS_BIND_NAME = "graphQLMutations"
        const val GRAPHQL_SUBSCRIPTIONS_BIND_NAME = "graphQLSubscriptions"
    }

    /**
     * Configure application bindings here
     */
    abstract fun configureApplication()

    final override fun configure() {
        super.configure()
        install(DefaultApplicationModule())
        install(DefaultGraphQLModule())
        configureApplication()
    }

    /**
     * Binder for graphql packages
     */
    fun graphQLPackagesBinder(): KotlinMultibinder<String> =
        KotlinMultibinder.newAnnotatedSetBinder(
            kotlinBinder,
            Names.named(GRAPHQL_PACKAGES_BIND_NAME),
        )

    /**
     * Binder for graphql queries
     */
    fun graphQLQueriesBinder(): KotlinMultibinder<Any> =
        KotlinMultibinder.newAnnotatedSetBinder(
            kotlinBinder,
            Names.named(GRAPHQL_QUERIES_BIND_NAME),
        )

    /**
     * Binder for graphql mutations
     */
    fun graphQLMutationsBinder(): KotlinMultibinder<Any> =
        KotlinMultibinder.newAnnotatedSetBinder(
            kotlinBinder,
            Names.named(GRAPHQL_MUTATIONS_BIND_NAME),
        )

    /**
     * Binder for graphql subscriptions
     */
    fun graphQLSubscriptionsBinder(): KotlinMultibinder<Any> =
        KotlinMultibinder.newAnnotatedSetBinder(
            kotlinBinder,
            Names.named(GRAPHQL_SUBSCRIPTIONS_BIND_NAME),
        )

    /**
     * Optional binder for the dataLoaderRegistryFactory
     */
    fun dataLoaderRegistryFactoryBinder(): KotlinOptionalBinder<KotlinDataLoaderRegistryFactory> =
        KotlinOptionalBinder.newOptionalBinder(kotlinBinder)

    /**
     * Optional binder for the graphQLWebSocketAuthenticator
     */
    fun graphQLWebSocketAuthenticatorBinder(): KotlinOptionalBinder<GraphQLWebSocketAuthenticator> =
        KotlinOptionalBinder.newOptionalBinder(kotlinBinder)

    /**
     * Binder for graphql Instrumentations.  RequestIdInstrumentation
     * will be registered by default, but additional ones can be registered
     * via this binder.
     */
    fun graphQLInstrumentationsBinder(): KotlinMultibinder<Instrumentation> =
        KotlinMultibinder.newSetBinder(kotlinBinder)

    /**
     * Binder for Schema Directives.  By default an "auth" directive is registered;
     * additional schema directives can be registered via this binder.
     */
    fun schemaDirectivesBinder(): KotlinMapBinder<String, KotlinSchemaDirectiveWiring> =
        KotlinMapBinder.newMapBinder(kotlinBinder)

    /**
     * Optional binder for specifying a [DataFetcherExceptionHandler] to be used by
     * queries, mutations, and subscriptions to handle errors.  Defaults to one that
     * skips printing of exception stack traces.
     */
    fun dataFetcherExceptionHandlerBinder(): KotlinOptionalBinder<DataFetcherExceptionHandler> =
        KotlinOptionalBinder.newOptionalBinder(kotlinBinder)
}
