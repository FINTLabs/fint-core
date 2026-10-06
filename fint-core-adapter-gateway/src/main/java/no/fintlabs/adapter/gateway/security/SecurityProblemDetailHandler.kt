package no.fintlabs.adapter.gateway.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.net.URI

@Component
class SecurityProblemDetailHandler(
    private val objectMapper: JsonMapper,
) : AccessDeniedHandler,
    AuthenticationEntryPoint {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        val reason = DenialRecorder.read(request)
        val detail = reason?.detail ?: "The adapter is not allowed to perform this request."
        logger.warn("Access denied on {} {}: {}", request.method, request.requestURI, detail)
        writeProblemDetail(
            request = request,
            response = response,
            status = HttpStatus.FORBIDDEN,
            title = "Forbidden",
            detail = detail,
            type = reason?.type,
        )
    }

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        logger.warn("Authentication failed on {} {}: {}", request.method, request.requestURI, authException.message)
        writeProblemDetail(
            request = request,
            response = response,
            status = HttpStatus.UNAUTHORIZED,
            title = "Unauthorized",
            detail = authException.message ?: "Authentication is required",
        )
    }

    private fun writeProblemDetail(
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: HttpStatus,
        title: String,
        detail: String,
        type: URI? = null,
    ) {
        val problem =
            ProblemDetail.forStatusAndDetail(status, detail).apply {
                this.title = title
                this.instance = URI.create(request.requestURI)
                if (type != null) this.type = type
            }
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(response.outputStream, problem)
    }
}
