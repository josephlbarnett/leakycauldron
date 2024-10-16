package com.trib3.graphql.websocket

import com.expediagroup.graphql.dataloader.KotlinDataLoaderRegistryFactory
import com.expediagroup.graphql.server.extensions.toExecutionInput
import com.expediagroup.graphql.server.extensions.toGraphQLError
import com.expediagroup.graphql.server.extensions.toGraphQLResponse
import com.expediagroup.graphql.server.types.GraphQLRequest
import com.trib3.graphql.GraphQLConfig
import com.trib3.graphql.execution.MessageGraphQLError
import com.trib3.graphql.modules.GraphQLWebSocketAuthenticator
import com.trib3.graphql.resources.getGraphQLContextMap
import com.trib3.server.filters.RequestIdFilter
import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.GraphQLContext
import jakarta.ws.rs.container.ContainerRequestContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.yield
import mu.KotlinLogging
import org.glassfish.jersey.internal.MapPropertiesDelegate
import org.glassfish.jersey.server.ContainerRequest
import java.security.Principal
import java.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

private val log = KotlinLogging.logger {}

/**
 * Base class for child coroutines of the [GraphQLWebSocketConsumer].  Allows
 * for sending messages back to the main coroutine channel for doing things like
 * sending messages back to the WebSocket client
 */
@OptIn(DelicateCoroutinesApi::class)
abstract class GraphQLCoroutine(
    private val channel: Channel<OperationMessage<*>>,
) {
    abstract suspend fun run()

    /**
     * Send the [message] to the [channel] to be processed in the main coroutine
     */
    suspend fun queueMessage(message: OperationMessage<*>) {
        if (!channel.isClosedForSend) {
            channel.send(message)
        }
    }
}

/**
 * Coroutine that sends a keepalive ping over the websocket every [GraphQLConfig.keepAliveIntervalSeconds]
 * seconds until it gets canceled by its parent
 */
class KeepAliveCoroutine(
    private val graphQLConfig: GraphQLConfig,
    channel: Channel<OperationMessage<*>>,
    private val message: OperationMessage<*>,
) : GraphQLCoroutine(channel) {
    override suspend fun run() {
        while (true) {
            delay(graphQLConfig.keepAliveIntervalSeconds.toDuration(DurationUnit.SECONDS))
            log.trace("WebSocket connection keepalive ping")
            queueMessage(
                OperationMessage(
                    OperationType.GQL_CONNECTION_KEEP_ALIVE,
                    message.id,
                ),
            )
        }
    }
}

/**
 * Coroutine that runs a GraphQL query and emits data messages to be sent back to the WebSocket client.
 * On completion of the query will send a GQL_COMPLETE message back.  On any error will send a GQL_ERROR.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueryCoroutine(
    private val graphQL: GraphQL,
    channel: Channel<OperationMessage<*>>,
    private val messageId: String,
    private val executionQuery: ExecutionInput,
) : GraphQLCoroutine(channel) {
    override suspend fun run() {
        try {
            val result = graphQL.executeAsync(executionQuery).await()
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
                    yield() // allow for cancellations to abort the coroutine
                    queueMessage(
                        OperationMessage(
                            OperationType.GQL_DATA,
                            messageId,
                            it.toGraphQLResponse(),
                        ),
                    )
                }.catch {
                    yield() // allow for cancellations to abort the coroutine
                    onChildError(messageId, it)
                }.onCompletion { maybeException ->
                    // Only send complete if there's no exception
                    if (maybeException == null) {
                        yield() // allow for cancellations to abort the coroutine
                        queueMessage(OperationMessage(OperationType.GQL_COMPLETE, messageId))
                    }
                }.collect()
        } catch (e: Throwable) {
            onChildError(messageId, e)
        }
    }

    /**
     * On any errors during the query execution, send an error message to the client, but leave
     * the WebSocket open for further use (ie, recoverable errors shouldn't get thrown).
     * Rethrows any CancellationExceptions used for coroutine shutdown.
     */
    private suspend fun onChildError(
        messageId: String?,
        cause: Throwable,
    ) {
        if (cause is CancellationException) {
            log.trace("Rethrowing cancellation")
            throw cause
        }
        log.error("Downstream error ${cause.message}", cause)
        queueMessage(
            OperationMessage(
                OperationType.GQL_ERROR,
                messageId,
                listOf(MessageGraphQLError(cause.message)),
            ),
        )
    }
}

/**
 * Coroutine based consumer that listens for events on coming from the WebSocket managed
 * by a [GraphQLWebSocketAdapter], and implements the apollo graphql-ws protocol
 * from https://github.com/apollographql/subscriptions-transport-ws/blob/HEAD/PROTOCOL.md
 *
 * Handling some WebSocket events launches a child coroutine (eg, starting a query or
 * a keepalive timer).  The handlers for those subscriptions must inject back into the
 * original coroutine via the [channel] if they need to send data to the WebSocket client.
 * Handlers for WebSocket events that don't launch child coroutines may use the [adapter]
 * to send data directly back to the WebSocket client.
 */
class GraphQLWebSocketConsumer(
    val graphQL: GraphQL,
    val graphQLConfig: GraphQLConfig,
    private val upgradeContainerRequestContext: ContainerRequestContext,
    val channel: Channel<OperationMessage<*>>,
    val adapter: GraphQLWebSocketAdapter,
    val keepAliveDispatcher: CoroutineDispatcher = Dispatchers.Default, // default to default for the KA interval
    private val dataLoaderRegistryFactory: KotlinDataLoaderRegistryFactory? = null,
    private val graphQLWebSocketAuthenticator: GraphQLWebSocketAuthenticator? = null,
) {
    private var keepAliveStarted = false // only allow one keepalive coroutine to launch
    private var connectionInitContainerRequest: ContainerRequestContext? = null
    private val queries = mutableMapOf<String, Job>() // will contain child queries that are currently running

    /**
     * Consume WebSocket API events from the [channel], should be called from a
     * coroutine launched in the adapter's scope
     */
    suspend fun consume(scope: CoroutineScope) {
        scope.launch(keepAliveDispatcher) {
            delay(Duration.ofSeconds(graphQLConfig.connectionInitWaitTimeout).toMillis())
            if (!keepAliveStarted) {
                adapter.session?.close(GraphQLWebSocketCloseReason.TIMEOUT_INIT)
            }
        }
        channel.consumeAsFlow().collect {
            handleMessage(it, scope)
        }
    }

    /**
     * Upon receiving WebSocket events, process them as appropriate
     */
    suspend fun handleMessage(
        message: OperationMessage<*>,
        scope: CoroutineScope,
    ) {
        RequestIdFilter.withRequestId(message.id) {
            try {
                log.trace("WebSocket connection subscription processing $message")
                when (message.type) {
                    // Connection control messages from the client
                    OperationType.GQL_CONNECTION_INIT -> handleConnectionInit(message, scope)
                    OperationType.GQL_CONNECTION_TERMINATE -> handleConnectionTerminate(message)

                    // Query control messages from the client
                    OperationType.GQL_START -> handleQueryStart(message, scope)
                    OperationType.GQL_STOP -> handleQueryStop(message)

                    // Query finished messages from child coroutines
                    OperationType.GQL_COMPLETE,
                    OperationType.GQL_ERROR,
                    -> {
                        log.info("Query ${message.id} completed: $message")
                        if (message.id != null) {
                            queries.remove(message.id)?.cancel()
                        }
                        handleClientBoundMessage(message)
                    }

                    // Respond to pings with pongs, ignore pongs
                    OperationType.GQL_PING ->
                        handleClientBoundMessage(
                            OperationMessage(
                                OperationType.GQL_PONG,
                                message.id,
                                message.payload as Map<*, *>,
                            ),
                        )

                    OperationType.GQL_PONG -> {
                        // do nothing
                    }

                    // Any client bound messages from child coroutines
                    OperationType.GQL_CONNECTION_ERROR,
                    OperationType.GQL_CONNECTION_ACK,
                    OperationType.GQL_DATA,
                    OperationType.GQL_CONNECTION_KEEP_ALIVE,
                    ->
                        handleClientBoundMessage(
                            message,
                        )

                    // Unknown message type
                    else -> adapter.subProtocol.onInvalidMessage(message.id, message.toString(), adapter)
                }
            } catch (cancellation: CancellationException) {
                log.trace("Rethrowing cancellation")
                throw cancellation
            } catch (error: Throwable) {
                log.error("Error processing message ${error.message}", error)
                adapter.sendMessage(OperationType.GQL_ERROR, message.id, listOf(error.toGraphQLError()))
            }
        }
    }

    /**
     * Translate a [OperationType.GQL_CONNECTION_INIT] payload into a [ContainerRequestContext]
     * in order to filter the connection for auth
     */
    private fun getContainerRequestContext(connectionParams: Map<String, *>?): ContainerRequestContext {
        val context =
            ContainerRequest(
                upgradeContainerRequestContext.uriInfo.baseUri,
                upgradeContainerRequestContext.uriInfo.requestUri,
                upgradeContainerRequestContext.method,
                upgradeContainerRequestContext.securityContext,
                MapPropertiesDelegate(emptyMap()),
                null,
            )
        // treat connectionParams as HTTP headers
        connectionParams?.forEach {
            context.header(it.key, it.value)
        }
        return context
    }

    /**
     * Makes a copy of the [ContainerRequestContext] set by the websocket upgrade
     * request, so we can re-authenticate the credentials from its headers.
     * (Required because of statefulness of the request context object)
     */
    private fun getReusableUpgradeContainerRequestContext(): ContainerRequestContext {
        val context =
            ContainerRequest(
                upgradeContainerRequestContext.uriInfo.baseUri,
                upgradeContainerRequestContext.uriInfo.requestUri,
                upgradeContainerRequestContext.method,
                upgradeContainerRequestContext.securityContext,
                MapPropertiesDelegate(emptyMap()),
                null,
            )
        // copy the headers (cookies are set in headers so don't need to handle explicitly)
        upgradeContainerRequestContext.headers.forEach {
            context.headers(it.key, it.value)
        }
        return context
    }

    /**
     * Gets the authenticated [Principal] for the websocket, if any.
     * First checks any creds from the [OperationType.GQL_CONNECTION_INIT] payload,
     * then falls back to creds from the websocket upgrade request
     */
    private fun getSocketPrincipal(): Principal? =
        connectionInitContainerRequest?.let {
            graphQLWebSocketAuthenticator?.invoke(it)
        } ?: graphQLWebSocketAuthenticator?.invoke(getReusableUpgradeContainerRequestContext())

    /**
     * Process an [OperationType.GQL_CONNECTION_INIT] message.  If the connection
     * has already initialized, send an error back to the client.  Otherwise acknowledge
     * the connection and start a keepalive timer.
     */
    private suspend fun handleConnectionInit(
        message: OperationMessage<*>,
        scope: CoroutineScope,
    ) {
        if (!keepAliveStarted) {
            val payload = message.payload as? Map<*, *>
            val payloadRequestContext = getContainerRequestContext(payload?.mapKeys { it.key.toString() })
            connectionInitContainerRequest = payloadRequestContext
            val principal = getSocketPrincipal()
            if (principal == null && graphQLConfig.checkAuthorization) {
                adapter.session?.close(GraphQLWebSocketCloseReason.UNAUTHORIZED)
                return
            }
            adapter.sendMessage(OperationType.GQL_CONNECTION_ACK, message.id)
            keepAliveStarted = true
            if (graphQLConfig.keepAliveIntervalSeconds > 0) {
                adapter.sendMessage(OperationType.GQL_CONNECTION_KEEP_ALIVE, message.id)
                val keepAliveCoroutine = KeepAliveCoroutine(graphQLConfig, channel, message)
                scope.launch(keepAliveDispatcher + MDCContext()) {
                    keepAliveCoroutine.run()
                }
            }
        } else {
            adapter.subProtocol.onDuplicateInit(message, adapter)
        }
    }

    /**
     * Process an [OperationType.GQL_CONNECTION_TERMINATE] message.  Closes the WebSocket session
     * which will close the socket and cancel all associated coroutines.
     */
    private fun handleConnectionTerminate(message: OperationMessage<*>) {
        log.info("WebSocket connection termination requested by message ${message.id}!")
        adapter.session?.close(GraphQLWebSocketCloseReason.NORMAL)
    }

    /**
     * Process an [OperationType.GQL_START] message.  Executes the graphQL query in a child
     * coroutine which will asynchronously emit new events as the query returns data or errors.
     * Tracks running queries by client specified id, and only allows one running query for a given id.
     */
    private suspend fun handleQueryStart(
        message: OperationMessage<*>,
        scope: CoroutineScope,
    ) {
        val messageId = message.id
        // re-evaluate socket Principal on each query, in case creds have expired
        val socketPrincipal = getSocketPrincipal()
        // must connection_init before starting a query in new protocol spec
        if (!keepAliveStarted && adapter.subProtocol == GraphQLWebSocketSubProtocol.GRAPHQL_WS_PROTOCOL) {
            adapter.session?.close(GraphQLWebSocketCloseReason.UNAUTHORIZED)
        } else if (messageId == null || message.payload !is GraphQLRequest) {
            adapter.subProtocol.onInvalidMessage(messageId, message.toString(), adapter)
        } else if (queries.containsKey(messageId)) {
            adapter.subProtocol.onDuplicateQuery(message, adapter)
        } else if (socketPrincipal == null && graphQLConfig.checkAuthorization) {
            adapter.session?.close(GraphQLWebSocketCloseReason.UNAUTHORIZED)
        } else {
            val job =
                scope.launch(MDCContext()) {
                    val context = GraphQLContext.of(getGraphQLContextMap(this, socketPrincipal))
                    val queryCoroutine =
                        QueryCoroutine(
                            graphQL,
                            channel,
                            message.id,
                            message.payload.toExecutionInput(context, dataLoaderRegistryFactory?.generate(context)),
                        )
                    queryCoroutine.run()
                }
            queries[messageId] = job
        }
    }

    /**
     * Process an [OperationType.GQL_STOP] message.  If the query specified by id is running, will cancel of
     * the child coroutine and notify the client of completion, else will notify the client of an error.
     *
     * A stopped query may return data packets after completion if the data was already in flight to the client
     * before the query was stopped.
     */
    private fun handleQueryStop(message: OperationMessage<*>) {
        val toStop = queries[message.id]
        if (toStop != null) {
            log.info("Stopping WebSocket query: ${message.id}!")
            toStop.cancel()
            handleClientBoundMessage(OperationMessage(OperationType.GQL_COMPLETE, message.id))
            queries.remove(message.id)
        } else {
            handleClientBoundMessage(
                OperationMessage(
                    OperationType.GQL_ERROR,
                    message.id,
                    listOf(MessageGraphQLError("Query not running")),
                ),
            )
        }
    }

    /**
     * Send the [message] to the client via the [adapter]
     */
    private fun handleClientBoundMessage(message: OperationMessage<*>) {
        log.trace("WebSocket connection sending $message")
        adapter.sendMessage(message)
    }
}
