package no.fintlabs.consumer.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** Adds the core version header to every HTTP response. */
@Component
class CoreVersionHeaderFilter(
    private val consumerConfiguration: ConsumerConfiguration,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!response.containsHeader(CORE_VERSION_HEADER)) {
            response.addHeader(CORE_VERSION_HEADER, consumerConfiguration.coreVersionHeader)
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        private const val CORE_VERSION_HEADER = "x-core-version"
    }
}
