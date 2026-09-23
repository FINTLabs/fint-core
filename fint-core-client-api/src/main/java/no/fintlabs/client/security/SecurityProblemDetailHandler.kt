package no.fintlabs.client.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authorization.AuthorizationDeniedException
import org.springframework.security.core.AuthenticationException
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
        val detail = accessDeniedException.denial?.detail ?: "Access denied"
        logger.warn("Access denied on {} {}: {}", request.method, request.requestURI, detail)
        writeProblemDetail(request, response, HttpStatus.FORBIDDEN, "Forbidden", detail)
    }

    /**
     * The reason travels on the decision our `AuthorizationManager` returned: Spring wraps that
     * decision in an [AuthorizationDeniedException]. This handler only receives the base
     * [AccessDeniedException] type, so the decision has to be cast back before the [Denial] can
     * be read. Anything else that denies, without one of our decisions attached, gets no reason.
     */
    private val AccessDeniedException.denial: Denial?
        get() = ((this as? AuthorizationDeniedException)?.authorizationResult as? Denied)?.denial

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
}
