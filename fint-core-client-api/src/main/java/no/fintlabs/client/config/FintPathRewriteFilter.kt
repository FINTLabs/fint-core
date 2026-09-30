package no.fintlabs.client.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import no.novari.fint.core.model.FintModel
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * FintModel serves a few resources (today, everything under felles/kodeverk/iso) one path segment longer
 * than {domainName}/{packageName}/{resourceName}, because their source folder sits one level
 * deeper than their model identity. No controller mapping accepts that extra segment, so a
 * request following the served link would never reach a handler. This forwards such a
 * request onto its routable identity before Spring resolves one, turning
 * "felles/kodeverk/iso/landkode/last-updated" into "felles/kodeverk/landkode/last-updated".
 *
 * Runs first in the filter chain, ahead of any handler mapping, so a HandlerInterceptor
 * (which only sees a request after a handler has already been chosen) could not do this.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class FintPathRewriteFilter : OncePerRequestFilter() {
    public override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val segments = request.pathSegments()
        val extraPrefix = segments.take(3).joinToString("/").lowercase()

        if (segments.size <= 3 || extraPrefix !in extraSegmentPrefixes) {
            filterChain.doFilter(request, response)
            return
        }

        request.getRequestDispatcher("/${identityPath(segments)}").forward(request, response)
    }

    private fun HttpServletRequest.pathSegments(): List<String> =
        requestURI.removePrefix(contextPath).split('/').filter { it.isNotEmpty() }

    /**
     * Drops the iso segment, keeps domainName and packageName, then everything from the
     * real resourceName onwards (the resource name itself, and any suffix after it, such as
     * an id lookup or "last-updated").
     */
    private fun identityPath(segments: List<String>): String =
        segments[0] + "/" + segments[1] + "/" + segments.drop(3).joinToString("/")

    companion object {
        /** The domainName/packageName/extraSegment triples FintModel's paths carry today. */
        private val extraSegmentPrefixes: Set<String> =
            FintModel.paths
                .map { it.split('/') }
                .filter { it.size > 3 }
                .map { it.take(3).joinToString("/").lowercase() }
                .toSet()
    }
}
