package no.fintlabs.client.security.opa

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.servlet.http.HttpServletRequest
import no.novari.resource.server.authentication.CorePrincipal

data class OpaRequest(
    val input: OpaInput,
)

/**
 * [domainName], [packageName] and [resourceName] are passed in rather than read from [request]
 * here, because the security layer already reads them from the request's path variables.
 */
fun createOpaRequest(
    principal: CorePrincipal,
    request: HttpServletRequest,
    domainName: String,
    packageName: String,
    resourceName: String?,
): OpaRequest =
    OpaRequest(
        OpaInput(
            username = principal.username,
            env = request.serverName.substringBefore('.'),
            domainName = domainName,
            packageName = packageName,
            resourceName = resourceName,
        ),
    )

@JsonInclude(JsonInclude.Include.NON_NULL)
data class OpaInput(
    val username: String,
    val env: String,
    val domainName: String,
    val packageName: String,
    val resourceName: String?,
)

/**
 * Unknown properties are ignored on purpose. With decision logging on, which the real deployments
 * use, OPA adds a `decision_id` next to `result`, and a strict mapper would turn that into a
 * deserialization error and therefore a denial of every request.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class OpaResponse(
    val result: OpaResult = OpaResult(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpaResult(
    val allow: Boolean = false,
    val fields: Set<String> = emptySet(),
    val relations: Set<String> = emptySet(),
)
