package com.trib3.graphql.resources

import com.codahale.metrics.annotation.Timed
import com.expediagroup.graphql.dataloader.KotlinDataLoaderRegistryFactory
import com.expediagroup.graphql.server.extensions.toExecutionInput
import com.expediagroup.graphql.server.extensions.toGraphQLError
import com.expediagroup.graphql.server.extensions.toGraphQLResponse
import com.expediagroup.graphql.server.types.GraphQLRequest
import com.fasterxml.jackson.databind.ObjectMapper
import com.trib3.graphql.GraphQLConfig
import com.trib3.server.coroutine.AsyncDispatcher
import com.trib3.server.filters.RequestIdFilter
import graphql.ExecutionResult
import graphql.ExecutionResultImpl
import graphql.GraphQL
import graphql.GraphQLContext
import io.dropwizard.auth.Auth
import io.swagger.v3.oas.annotations.Parameter
import jakarta.inject.Inject
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.sse.OutboundSseEvent
import jakarta.ws.rs.sse.Sse
import jakarta.ws.rs.sse.SseEventSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.yield
import mu.KotlinLogging
import org.eclipse.jetty.http.HttpStatus
import java.security.Principal
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.annotation.Nullable
import kotlin.time.DurationUnit
import kotlin.time.toDuration

private val log = KotlinLogging.logger {}

private const val STREAM_TOKEN_HEADER = "x-graphql-event-stream-token"
private const val STREAM_TOKEN_QUERY_PARAM = "token"

/**
 * Connection level metadata for active streams to facilitate asynchronous processing
 */
internal data class StreamInfo(
    val scope: CoroutineScope,
    val principal: Principal?,
    val eventSink: SseEventSink,
    val sse: Sse,
)

/**
 * Implements the graphql-sse protocol from https://github.com/enisdenjo/graphql-sse/blob/master/PROTOCOL.md
 * Both "Single connection mode" and "Distinct connections mode" are supported at the /app/graphql/stream endpoint.
 * "Distinct connections mode" will set a [ContainerRequestContext] on the GraphQLContext, however
 * "Single connection mode" will not, as there are multiple requests involved in the execution of a query.
 *
 * Single connection mode client request flow:
 * PUT -> x-graphql-event-stream-token 201 response
 * GET text/event-stream w/ x-graphql-event-stream-token -> open sse stream 200 response
 * POST {query, extensions: {operationId: xyz}}w/ x-graphql-event-stream-token -> 202 response
 */
@Path("/graphql/stream")
@Produces(MediaType.APPLICATION_JSON)
open class GraphQLSseResource
    @Inject
    constructor(
        private val graphQL: GraphQL,
        private val graphQLConfig: GraphQLConfig,
        private val objectMapper: ObjectMapper,
        @Nullable val dataLoaderRegistryFactory: KotlinDataLoaderRegistryFactory? = null,
    ) {
        private val reservedStreams = ConcurrentHashMap<UUID, Optional<Principal>>()
        internal val activeStreams = ConcurrentHashMap<UUID, StreamInfo>()
        internal val runningOperations = ConcurrentHashMap<UUID, MutableMap<String, CoroutineScope>>()

        /**
         * Launch a coroutine that sends periodic SSE comments to keep the connection alive.
         * This will get cancelled when the surrounding scope is (eg client disconnection)
         */
        private fun CoroutineScope.launchKeepAlive(
            eventSink: SseEventSink,
            sse: Sse,
        ): Job =
            launch(MDCContext()) {
                while (isActive) {
                    if (graphQLConfig.keepAliveIntervalSeconds > 0) {
                        eventSink.send(sse.newEventBuilder().comment("ka").build())
                    }
                    delay(graphQLConfig.keepAliveIntervalSeconds.toDuration(DurationUnit.SECONDS))
                }
            }

        private fun buildEvent(
            sse: Sse,
            name: String,
            data: Any,
        ): OutboundSseEvent =
            sse
                .newEventBuilder()
                .name(name)
                .data(data)
                .build()

        /**
         * Execute a graphql query and send results to the [eventSink]
         */
        private suspend fun runQuery(
            query: GraphQLRequest,
            graphQLContext: GraphQLContext,
            eventSink: SseEventSink,
            sse: Sse,
            operationId: String?,
        ) {
            try {
                val input = query.toExecutionInput(graphQLContext, dataLoaderRegistryFactory?.generate(graphQLContext))
                val result = graphQL.executeAsync(input).await()
                // if result data is a Flow, collect it as a flow
                // if it's not, just collect the result itself
                val flow =
                    try {
                        result.getData<Flow<ExecutionResult>>() ?: flowOf(result)
                    } catch (e: Exception) {
                        log.debug("Could not get Flow result, collecting result directly", e)
                        flowOf(result)
                    }
                flow
                    .onEach {
                        yield()
                        val response = it.toGraphQLResponse()
                        val nextMessage: Any =
                            if (operationId != null) {
                                mapOf("id" to operationId, "payload" to response)
                            } else {
                                response
                            }
                        eventSink.send(
                            buildEvent(sse, "next", objectMapper.writeValueAsString(nextMessage)),
                        )
                    }.collect()
            } catch (e: Exception) {
                log.warn("Error running sse query: ${e.message}", e)
                val gqlError =
                    ExecutionResultImpl
                        .newExecutionResult()
                        .addError(e.toGraphQLError())
                        .build()
                        .toGraphQLResponse()
                val nextMessage: Any =
                    if (operationId != null) {
                        mapOf(
                            "id" to operationId,
                            "payload" to gqlError,
                        )
                    } else {
                        gqlError
                    }
                eventSink.send(
                    buildEvent(sse, "next", objectMapper.writeValueAsString(nextMessage)),
                )
            } finally {
                log.info("Query ${operationId ?: RequestIdFilter.getRequestId()} completed.")
                val completeMessage =
                    if (operationId != null) {
                        objectMapper.writeValueAsString(mapOf("id" to operationId))
                    } else {
                        ""
                    }
                eventSink.send(
                    buildEvent(sse, "complete", completeMessage),
                )
            }
        }

        /**
         * Creates a stream reservation and returns the stream token as text
         */
        @PUT
        @Produces(MediaType.TEXT_PLAIN)
        @Timed
        open fun reserveEventStream(
            @Parameter(hidden = true) @Auth
            principal: Optional<Principal>,
        ): Response {
            if (principal.isEmpty && graphQLConfig.checkAuthorization) {
                return unauthorizedResponse()
            }
            val reservation = UUID.randomUUID()
            reservedStreams[reservation] = principal
            return Response.status(HttpStatus.CREATED_201).entity(reservation.toString()).build()
        }

        /**
         * Given a stream reservation, open an SSE stream that will publish SSE events for
         * queries that are executed on the stream
         */
        @GET
        @Produces(MediaType.SERVER_SENT_EVENTS)
        @AsyncDispatcher("IO")
        open suspend fun eventStream(
            @Context eventSink: SseEventSink,
            @Context sse: Sse,
            @HeaderParam(STREAM_TOKEN_HEADER) headerToken: UUID?,
            @QueryParam(STREAM_TOKEN_QUERY_PARAM) paramToken: UUID?,
        ) {
            val streamToken = headerToken ?: paramToken
            val principal =
                if (streamToken != null) {
                    reservedStreams.remove(streamToken)
                } else {
                    null
                }
            if (streamToken == null || principal == null) {
                eventSink.close()
                return
            }
            try {
                coroutineScope {
                    activeStreams[streamToken] = StreamInfo(this, principal.orElse(null), eventSink, sse)
                    launchKeepAlive(eventSink, sse)
                }
            } finally {
                activeStreams.remove(streamToken)
                runningOperations.remove(streamToken)
            }
        }

        /**
         * Given a stream reservation with an active SSE connection, execute a query
         * and send its result(s) to the SSE connection.  The [query] should have an
         * `operationId` specified in its `extensions` map, which will be used in the
         * streamed results.  If no `operationId` is specified, the response `X-Request-Id`
         * header will contain a generated `operationId`.
         */
        @POST
        @Timed
        open fun queryOpenStream(
            @Parameter(hidden = true) @Auth
            principal: Optional<Principal>,
            query: GraphQLRequest,
            @HeaderParam(STREAM_TOKEN_HEADER) headerToken: UUID?,
            @QueryParam(STREAM_TOKEN_QUERY_PARAM) paramToken: UUID?,
        ): Response {
            val streamToken = headerToken ?: paramToken ?: throw WebApplicationException(unauthorizedResponse())
            val streamInfo = activeStreams[streamToken]
            val contextPrincipal = principal.orElse(streamInfo?.principal)
            if (streamInfo == null || (contextPrincipal == null && graphQLConfig.checkAuthorization)) {
                return unauthorizedResponse()
            }
            val operationId =
                query.extensions
                    ?.get(
                        "operationId",
                    )?.toString() ?: RequestIdFilter.getRequestId().toString()
            val newQueryMap = ConcurrentHashMap<String, CoroutineScope>()
            val queryMap = runningOperations.putIfAbsent(streamToken, newQueryMap) ?: newQueryMap
            // launch in the connection's scope so this POST can return immediately with 202
            streamInfo.scope.launch(MDCContext()) {
                try {
                    queryMap[operationId] = this
                    val graphQLContext = GraphQLContext.of(getGraphQLContextMap(this, contextPrincipal))
                    runQuery(query, graphQLContext, streamInfo.eventSink, streamInfo.sse, operationId)
                } finally {
                    queryMap.remove(operationId)
                }
            }
            return Response.status(HttpStatus.ACCEPTED_202).build()
        }

        /**
         * Given a stream reservation and `operationId`, cancel any running query.
         */
        @DELETE
        open fun cancelOperation(
            @QueryParam("operationId") operationId: String,
            @HeaderParam(STREAM_TOKEN_HEADER) headerToken: UUID?,
            @QueryParam(STREAM_TOKEN_QUERY_PARAM) paramToken: UUID?,
        ) {
            val streamToken = headerToken ?: paramToken ?: throw WebApplicationException(unauthorizedResponse())
            runningOperations[streamToken]?.get(operationId)?.cancel()
        }

        /**
         * Implements the "Distinct connections mode" SSE request.  Accepts a POST body
         * containing the graphql request, and executes it, sending results as SSE events.
         */
        @POST
        @Produces(MediaType.SERVER_SENT_EVENTS)
        @Timed
        @AsyncDispatcher("IO")
        open suspend fun querySse(
            @Context eventSink: SseEventSink,
            @Context sse: Sse,
            @Parameter(hidden = true) @Auth
            principal: Optional<Principal>,
            query: GraphQLRequest,
            @Context containerRequestContext: ContainerRequestContext? = null,
        ) = coroutineScope {
            val graphQLContext =
                GraphQLContext.of(
                    getGraphQLContextMap(this, principal.orElse(null)) +
                        contextMap(containerRequestContext),
                )
            val ka = launchKeepAlive(eventSink, sse)
            try {
                runQuery(query, graphQLContext, eventSink, sse, null)
            } finally {
                ka.cancel()
                eventSink.close()
            }
        }
    }
