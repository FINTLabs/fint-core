package no.fintlabs.client.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import no.novari.core.shared.model.OrgId
import no.novari.resource.server.authentication.CorePrincipal
import no.novari.resource.server.enums.FintScope
import no.novari.resource.server.enums.FintType
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.net.URI

@Component
class SecurityProblemDetailHandler(
    private val jsonMapper: JsonMapper,
) : AccessDeniedHandler,
    AuthenticationEntryPoint {
    private val logger = LoggerFactory.getLogger(javaClass)

    // Fired off when authorization fails
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        val detail = describeDenial(request)
        logger.warn("Access denied on {} {}: {}", request.method, request.requestURI, detail)
        writeProblemDetail(request, response, HttpStatus.FORBIDDEN, "Forbidden", detail)
    }

    private fun describeDenial(request: HttpServletRequest): String {
        val auth = SecurityContextHolder.getContext().authentication
        val orgId = request.getHeader(ORG_ID_HEADER)
        return when {
            auth !is CorePrincipal -> "Principal is not a FINT client"
            auth.type != FintType.CLIENT -> "Principal type must be CLIENT"
            FintScope.FINT_CLIENT !in auth.scopes -> "JWT is missing required 'fint-client' scope"
            orgId == null -> "Missing $ORG_ID_HEADER header"
            auth.assets.none { it.isSameOrgAs(orgId) } -> "Client does not have access to organisation '$orgId'"
            else -> "Client is missing the required role for the requested component"
        }
    }

    // Transforms org-id string into OrgId type to normalize and validate the org-id
    private fun String.isSameOrgAs(orgId: String): Boolean = OrgId.from(this) == OrgId.from(orgId)

    // Fired off when authentication fails
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        logger.warn("Authentication failed on {} {}: {}", request.method, request.requestURI, authException.message)
        writeProblemDetail(
            request,
            response,
            HttpStatus.UNAUTHORIZED,
            "Unauthorized",
            authException.message ?: "Authentication is required",
        )
    }

    private fun writeProblemDetail(
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: HttpStatus,
        title: String,
        detail: String,
    ) {
        val problem =
            ProblemDetail.forStatusAndDetail(status, detail).apply {
                this.title = title
                this.instance = URI.create(request.requestURI)
            }
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        jsonMapper.writeValue(response.outputStream, problem)
    }

    companion object {
        private const val ORG_ID_HEADER = "x-org-id"
    }
}
