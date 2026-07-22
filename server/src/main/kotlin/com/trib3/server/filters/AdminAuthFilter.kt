package com.trib3.server.filters

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.FilterConfig
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.security.MessageDigest
import java.util.Base64

/**
 * Auth filter for the admin servlet to ensure that it checks against the
 * configured adminAuthToken
 */
class AdminAuthFilter : Filter {
    private var token: String? = null
    private var realm: String = "realm"
    private val base64 = Base64.getDecoder()

    /**
     * Extract the basic auth info from the [ServletRequest] and compare against
     * the configured [token].  If unable to match, then set [HttpServletResponse.SC_UNAUTHORIZED]
     * and throw an Exception to prevent the chain from processing.
     */
    private fun checkToken(
        request: ServletRequest,
        response: ServletResponse,
        expectedToken: String,
    ) {
        val credentials = (request as? HttpServletRequest)?.getHeader("Authorization")
        val parts = credentials?.split(' ', limit = 2)
        val scheme = parts?.getOrNull(0)
        val encoded = parts?.getOrNull(1)
        if (scheme != null && "basic" == scheme.lowercase() && encoded != null) {
            val decoded =
                try {
                    String(base64.decode(encoded))
                } catch (ignored: IllegalArgumentException) {
                    null
                }
            // HTTP Basic credentials are `userid:password`; the password may contain colons.
            val pass = decoded?.substringAfter(':', missingDelimiterValue = "")
            if (pass != null &&
                MessageDigest.isEqual(
                    pass.toByteArray(Charsets.UTF_8),
                    expectedToken.toByteArray(Charsets.UTF_8),
                )
            ) {
                return
            }
        }
        // boom
        if (response is HttpServletResponse) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"$realm\"")
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
        }
        throw IllegalArgumentException("Invalid credentials")
    }

    /**
     * If there's a configured auth [token], call [checkToken] before resuming the chain processing
     */
    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        token?.let { checkToken(request, response, it) }
        chain.doFilter(request, response)
    }

    /**
     * Read configured [token] and [realm] from the [FilterConfig]
     */
    override fun init(filterConfig: FilterConfig?) {
        filterConfig?.let { filtConfig ->
            token = filtConfig.getInitParameter("token")
            filtConfig.getInitParameter("realm")?.let {
                realm = it
            }
        }
    }

    override fun destroy() = Unit
}
