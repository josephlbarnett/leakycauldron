package com.trib3.server.config.dropwizard

import ch.qos.logback.access.common.spi.AccessEvent
import ch.qos.logback.access.common.spi.IAccessEvent
import ch.qos.logback.access.jetty.JettyServerAdapter
import ch.qos.logback.access.jetty.RequestWrapper
import ch.qos.logback.access.jetty.ResponseWrapper
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.pattern.PatternLayoutBase
import ch.qos.logback.core.spi.FilterReply
import com.fasterxml.jackson.annotation.JsonTypeName
import io.dropwizard.logging.common.async.AsyncAppenderFactory
import io.dropwizard.logging.common.filter.LevelFilterFactory
import io.dropwizard.logging.common.filter.NullLevelFilterFactory
import io.dropwizard.logging.common.layout.LayoutFactory
import io.dropwizard.request.logging.LogbackAccessRequestLog
import io.dropwizard.request.logging.LogbackAccessRequestLogFactory
import io.dropwizard.request.logging.async.AsyncAccessEventAppenderFactory
import io.dropwizard.request.logging.layout.LogbackAccessRequestLayout
import jakarta.servlet.http.HttpServletResponse
import org.eclipse.jetty.http.HttpFields
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.RequestLog
import org.eclipse.jetty.server.Response
import org.slf4j.LoggerFactory
import java.util.TimeZone
import java.util.TreeMap

private const val FAST_RESPONSE_TIME = 200

/**
 * [LogbackAccessRequestLayout] that also includes the request Id read from the response
 * headers, and timestamp in the same layout we use for the regular application log.
 */
class RequestIdLogbackAccessRequestLayout(
    context: LoggerContext,
    timeZone: TimeZone,
) : LogbackAccessRequestLayout(context, timeZone) {
    init {
        pattern = "%t{ISO8601,UTC} [%responseHeader{X-Request-Id}] ${this.pattern}"
    }
}

/**
 * Layout factory that returns a [RequestIdLogbackAccessRequestLayout]
 */
class RequestIdLogbackAccessRequestLayoutFactory : LayoutFactory<IAccessEvent> {
    override fun build(
        context: LoggerContext,
        timeZone: TimeZone,
    ): PatternLayoutBase<IAccessEvent> = RequestIdLogbackAccessRequestLayout(context, timeZone)
}

/**
 * Configure the requestLog to skip logging of fast, OK ping responses to avoid
 * cluttering the logs with, eg, ELB health check requests.  Also set the requestLog
 * layout pattern to include timestamp and requestId prefix.
 */
@JsonTypeName("filtered-logback-access")
class FilteredLogbackAccessRequestLogFactory : LogbackAccessRequestLogFactory() {
    override fun build(name: String): RequestLog {
        // almost the same as super.build(), differences are commented
        val logger =
            LoggerFactory.getLogger("http.request") as Logger
        logger.isAdditive = false

        val context = logger.loggerContext

        val requestLog = LeakyLogbackAccessRequestLog()

        val levelFilterFactory: LevelFilterFactory<IAccessEvent> = NullLevelFilterFactory()
        val asyncAppenderFactory: AsyncAppenderFactory<IAccessEvent> = AsyncAccessEventAppenderFactory()
        // set layout factory to include timestamp and requestId prefix
        val layoutFactory: LayoutFactory<IAccessEvent> = RequestIdLogbackAccessRequestLayoutFactory()

        for (output in appenders) {
            requestLog.addAppender(output.build(context, name, layoutFactory, levelFilterFactory, asyncAppenderFactory))
        }

        // add successful ping filter
        requestLog.addFilter(
            object : Filter<IAccessEvent>() {
                override fun decide(event: IAccessEvent): FilterReply {
                    if (
                        event.requestURI == "/app/ping" &&
                        event.statusCode == HttpServletResponse.SC_OK &&
                        event.elapsedTime < FAST_RESPONSE_TIME
                    ) {
                        return FilterReply.DENY
                    }
                    return FilterReply.NEUTRAL
                }
            },
        )
        return requestLog
    }
}

// TODO: remove when https://github.com/dropwizard/dropwizard/issues/9773 is fixed upstream
// this is basically a kotlinized copy of https://github.com/dropwizard/dropwizard/pull/9970
class LeakyLogbackAccessRequestLog : LogbackAccessRequestLog() {
    private fun buildHeaderMap(headers: HttpFields): Map<String, String> {
        val headerMap = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)

        for (f in headers) {
            val existing = headerMap.get(f.name)
            val value = existing?.let { it + "," + f.value } ?: f.value
            headerMap.put(f.name, value)
        }
        return headerMap
    }

    override fun log(
        jettyRequest: Request,
        jettyResponse: Response,
    ) {
        val httpServletRequest =
            object : RequestWrapper(jettyRequest) {
                override fun buildRequestHeaderMap(): Map<String, String> = buildHeaderMap(jettyRequest.headers)
            }

        val httpServletResponse = ResponseWrapper(jettyResponse)

        val adapter =
            object : JettyServerAdapter(jettyRequest, jettyResponse) {
                override fun buildResponseHeaderMap(): Map<String, String> = buildHeaderMap(jettyResponse.headers)
            }

        val accessEvent = AccessEvent(this, httpServletRequest, httpServletResponse, adapter)

        if (getFilterChainDecision(accessEvent) == FilterReply.DENY) {
            return
        }

        appendLoopOnAppenders(accessEvent)
    }

    private fun appendLoopOnAppenders(iAccessEvent: IAccessEvent?) {
        val appenderIterator = this.iteratorForAppenders()
        while (appenderIterator.hasNext()) {
            appenderIterator.next()?.doAppend(iAccessEvent)
        }
    }
}
