package no.fintlabs.adapter.gateway.security

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder
import java.net.URI

/**
 * Why a request was denied. Every rule names its own reason instead of the error handler
 * guessing one from the exception type, so adding a rule cannot silently give an old message
 * to a new denial.
 */
enum class DenialReason(
    val detail: String,
) {
    NOT_ADAPTER(
        "The token belongs to a FINT client, and this service only accepts FINT adapters. " +
            "Use the adapter's credentials instead of the client's.",
    ),
    MISSING_SCOPE(
        "The token was issued without the 'fint-adapter' scope. Request the token with scope 'fint-adapter'.",
    ),
    MISSING_COMPONENT_ROLE(
        "The adapter has no access to the requested component. " +
            "Give the adapter access to the component in Kundeportalen.",
    ),
    ORG_MISSING("The request has no orgId. Set orgId to the organization the data belongs to."),
    ORG_NOT_IN_ASSETS(
        "The adapter has no access to the organization in the request. " +
            "Check the orgId, or add the organization to the adapter's assets in Kundeportalen.",
    ),
    ORG_NOT_SERVED(
        "The organization in the request does not belong to the organization this service serves. " +
            "Send the request to that organization's service instead.",
    ),
    USERNAME_MISMATCH(
        "The username in the contract is not the username of the token. " +
            "Register the contract with the adapter's own username.",
    ),
    NO_CONTRACT(
        "The adapter has no contract for the organization. " +
            "Register a contract for it with POST /provider/register before syncing or answering events.",
    ),
    RESOURCE_NOT_IN_CONTRACT(
        "The adapter's contract for the organization does not include the requested resource. " +
            "Add the resource to the contract's capabilities and register again.",
    ),
    NO_REGISTERED_CONTRACT(
        "The adapter has not registered a contract for any of its organizations. " +
            "Register a contract with POST /provider/register for each organization before fetching events.",
    ),
    ;

    val type: URI get() = URI.create(TYPE_PREFIX + name.lowercase().replace('_', '-'))

    private companion object {
        const val TYPE_PREFIX = "https://fintlabs.no/problem/"
    }
}

/**
 * Carries the reason from the rule that denied a request to the handler that renders the
 * ProblemDetail. A request attribute is used because a `@PreAuthorize` expression can only
 * return a boolean, so the reason cannot travel with the return value.
 */
object DenialRecorder {
    private const val ATTRIBUTE = "no.fintlabs.adapter.gateway.security.denialReason"

    fun record(reason: DenialReason) {
        RequestContextHolder.getRequestAttributes()?.setAttribute(ATTRIBUTE, reason, RequestAttributes.SCOPE_REQUEST)
    }

    fun record(
        request: HttpServletRequest,
        reason: DenialReason,
    ) {
        request.setAttribute(ATTRIBUTE, reason)
    }

    fun read(request: HttpServletRequest): DenialReason? = request.getAttribute(ATTRIBUTE) as? DenialReason
}
