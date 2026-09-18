package no.fintlabs.client.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import no.fintlabs.client.exception.resource.ResourceNotFoundException
import no.novari.fint.core.model.FintModel
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping

/**
 * Confirms a request's {domainName}/{packageName}(/{resourceName}) path segments name something
 * FintModel actually serves, before the request reaches a controller. A request whose path carries
 * no packageName, such as an actuator or swagger route, has no such variables and passes through
 * untouched.
 */
@Component
class FintPathInterceptor : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val variables = request.fetchVariables() ?: return true
        val domainName = variables["domainName"] ?: return true
        val packageName = variables["packageName"] ?: return true
        val resourceName = variables["resourceName"]

        if (!resourcePathExists(domainName, packageName, resourceName)) {
            val path = listOfNotNull(domainName, packageName, resourceName).joinToString("/")
            throw ResourceNotFoundException("Unknown FINT path: $path")
        }

        return true
    }

    /**
     * The {domainName}/{packageName}/{resourceName} values Spring resolved for this request, if any.
     */
    @Suppress("UNCHECKED_CAST")
    private fun HttpServletRequest.fetchVariables(): Map<String, String>? =
        getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<String, String>

    private fun resourcePathExists(domainName: String, packageName: String, resourceName: String?): Boolean =
        if (resourceName != null) FintModel.byPath(domainName, packageName, resourceName) != null
        else FintModel.refsIn(domainName, packageName).isNotEmpty()

}
