package com.trib3.graphql.websocket

import com.trib3.graphql.modules.GraphQLWebSocketAuthenticator
import io.dropwizard.auth.AuthFilter
import jakarta.inject.Inject
import jakarta.ws.rs.container.ContainerRequestContext
import java.security.Principal
import javax.annotation.Nullable

/**
 * A [GraphQLWebSocketAuthenticator] that delegates authentication
 * to a Dropwizard [AuthFilter] from the Guice injector.
 */
class GraphQLWebSocketDropwizardAuthenticator
    @Inject
    constructor(
        @Nullable val authFilter: AuthFilter<*, *>?,
    ) : GraphQLWebSocketAuthenticator {
        override fun invoke(containerRequestContext: ContainerRequestContext): Principal? =
            runCatching {
                authFilter?.filter(containerRequestContext)
                containerRequestContext.securityContext?.userPrincipal
            }.getOrNull()
    }
