package com.trib3.server.swagger

import com.fasterxml.jackson.databind.ObjectMapper
import com.trib3.server.config.TribeApplicationConfig
import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.core.jackson.ModelResolver
import io.swagger.v3.jaxrs2.integration.JaxrsApplicationScanner
import io.swagger.v3.jaxrs2.integration.JaxrsOpenApiContextBuilder
import io.swagger.v3.jaxrs2.integration.OpenApiServlet
import io.swagger.v3.oas.integration.SwaggerConfiguration
import io.swagger.v3.oas.integration.api.OpenApiContext
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.servers.Server
import jakarta.inject.Inject
import jakarta.ws.rs.core.Application
import jakarta.ws.rs.core.UriBuilder

// JaxrsOpenApiContextBuilder<T> needs to be subclassed in order to instantiate with a <T>
private class SwaggerContextBuilder : JaxrsOpenApiContextBuilder<SwaggerContextBuilder>()

// TODO: find a better place for this interface
interface JaxrsAppProcessor {
    fun process(application: Application)
}

/**
 * Builds and registers an [OpenApiContext] for our application.
 *
 * We add Swagger's [OpenApiServlet] to our admin servlet context, not to
 * our application servlet context. [SwaggerInitializer] preregisters an
 * [OpenApiContext] under the [contextId] that the [OpenApiServlet] uses,
 * so that the admin-side servlet can serve documentation for our
 * application-side Jersey resources.
 */
class SwaggerInitializer(
    val contextId: String,
    val appConfig: TribeApplicationConfig,
    val objectMapper: ObjectMapper,
) : JaxrsAppProcessor {
    @Inject
    constructor(
        appConfig: TribeApplicationConfig,
        objectMapper: ObjectMapper,
    ) : this(
        OpenApiContext.OPENAPI_CONTEXT_ID_PREFIX + "servlet." +
            OpenApiServlet::class.simpleName,
        appConfig,
        objectMapper,
    )

    override fun process(application: Application) {
        ModelConverters.getInstance().addConverter(ModelResolver(objectMapper))
        val hostAndPath = UriBuilder.newInstance().host(appConfig.corsDomains[0]).path(appConfig.appContextPath)
        val baseUrls =
            listOf(
                hostAndPath.clone().scheme("https"),
                hostAndPath.clone().scheme("http"),
                hostAndPath.clone().scheme("http").port(appConfig.appPort),
            )
        SwaggerContextBuilder()
            .openApiConfiguration(
                SwaggerConfiguration()
                    .openAPI(
                        OpenAPI().servers(
                            baseUrls.map { Server().url(it.build().toString()) },
                        ),
                    ).scannerClass(JaxrsApplicationScanner::class.qualifiedName),
            ).application(application)
            .ctxId(contextId)
            .buildContext(true)
    }
}
