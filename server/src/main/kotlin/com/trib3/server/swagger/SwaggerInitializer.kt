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

class SwaggerInitializer
    @Inject
    constructor(
        // this is tricky, but we want to document the jersey exposed APIs
        // from within the admin servlet, and the swagger libraries don't
        // make things easy.  Fake out the servlet context -> swagger context
        // with what ServletConfigContextUtils.getContextIdFromServletConfig
        // will generate for the actual servlet, then add the swagger servlet
        val contextId: String =
            OpenApiContext.OPENAPI_CONTEXT_ID_PREFIX + "servlet." +
                OpenApiServlet::class.simpleName,
        val appConfig: TribeApplicationConfig,
        val objectMapper: ObjectMapper,
    ) : JaxrsAppProcessor {
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
