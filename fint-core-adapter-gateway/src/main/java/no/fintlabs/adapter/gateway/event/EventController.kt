package no.fintlabs.adapter.gateway.event

import jakarta.validation.Valid
import no.fintlabs.adapter.gateway.ProviderApi
import no.fintlabs.adapter.gateway.event.request.RequestEventService
import no.fintlabs.adapter.gateway.event.response.ResponseEventService
import no.fintlabs.adapter.gateway.security.EventAuthorization
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.novari.core.shared.event.EventScope
import no.novari.resource.server.authentication.CorePrincipal
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("${ProviderApi.PREFIX}/event")
class EventController(
    private val requestEventService: RequestEventService,
    private val responseEventService: ResponseEventService,
    private val eventAuthorization: EventAuthorization,
) {
    /**
     * Serves pending events for every org in the token that this gateway serves. The path only
     * filters, as on provider-gateway: no role or contract is needed. A contract narrows what a
     * listed resource gets and is the only way to be served a READ.
     */
    @GetMapping("{domainName}", "{domainName}/{packageName}", "{domainName}/{packageName}/{resourceName}")
    fun getEvents(
        corePrincipal: CorePrincipal,
        @PathVariable domainName: String,
        @PathVariable(required = false) packageName: String?,
        @PathVariable(required = false) resourceName: String?,
        @RequestParam(defaultValue = "0") size: Int,
    ): ResponseEntity<List<RequestFintEvent>> {
        val orgs = eventAuthorization.readableOrgs(corePrincipal)
        val scope = EventScope.of(domainName, packageName, resourceName)
        return ResponseEntity.ok(requestEventService.getEvents(orgs, scope, size))
    }

    /**
     * The org check runs here because the body carries the orgId. Whether the adapter may answer
     * the event's resource and operation cannot: the response body does not say which resource
     * it answers, so that is checked against the stored request inside [ResponseEventService].
     * The body is validated before the org check, so a missing field is a 400, not a 403.
     */
    @PostMapping
    @PreAuthorize("@adapterAuth.hasOrg(authentication, #responseFintEvent.orgId)")
    fun postEvent(
        @Valid @RequestBody responseFintEvent: ResponseFintEvent,
    ): ResponseEntity<Void> {
        responseEventService.handleEvent(responseFintEvent)
        return ResponseEntity.ok().build()
    }
}
