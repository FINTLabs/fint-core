package no.fintlabs.adapter.gateway.event

import jakarta.validation.Valid
import no.fintlabs.adapter.gateway.ProviderApi
import no.fintlabs.adapter.gateway.config.EventLongPollProperties
import no.fintlabs.adapter.gateway.event.request.RequestEventService
import no.fintlabs.adapter.gateway.event.response.ResponseEventService
import no.fintlabs.adapter.gateway.security.EventAuthorization
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.novari.core.shared.event.EventScope
import no.novari.core.shared.web.PreferHeader
import no.novari.resource.server.authentication.CorePrincipal
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("${ProviderApi.PREFIX}/event")
class EventController(
    private val requestEventService: RequestEventService,
    private val responseEventService: ResponseEventService,
    private val eventAuthorization: EventAuthorization,
    private val longPollProperties: EventLongPollProperties,
) {
    /**
     * Pending events for every org in the token, filtered by the path. No role or contract is
     * needed; a contract narrows a listed resource and opens READ. With `Prefer: wait=<seconds>`
     * an empty answer is held until an event arrives or the wait, capped at max-wait, runs out.
     */
    @GetMapping("{domainName}", "{domainName}/{packageName}", "{domainName}/{packageName}/{resourceName}")
    fun getEvents(
        corePrincipal: CorePrincipal,
        @PathVariable domainName: String,
        @PathVariable(required = false) packageName: String?,
        @PathVariable(required = false) resourceName: String?,
        @RequestParam(defaultValue = "0") size: Int,
        @RequestHeader(name = PreferHeader.NAME, required = false) prefer: String?,
    ): ResponseEntity<List<RequestFintEvent>> {
        val orgs = eventAuthorization.readableOrgs(corePrincipal)
        val scope = EventScope.of(domainName, packageName, resourceName)

        val wait = PreferHeader.parse(prefer).wait?.let { minOf(it, longPollProperties.maxWait) }
        if (wait == null || wait.isZero) {
            return ResponseEntity.ok(requestEventService.getEvents(orgs, scope, size))
        }

        // Tells the adapter the poll was held, so an empty list did not come back at once.
        return ResponseEntity
            .ok()
            .header(PreferHeader.APPLIED, "${PreferHeader.WAIT}=${wait.seconds}")
            .body(requestEventService.awaitEvents(orgs, scope, size, wait))
    }

    /**
     * The org check runs here, on the body's orgId. Which resource and operation the answer is for
     * is only known from the stored request, so that is checked in [ResponseEventService].
     * Validation runs before the org check, so a missing field is a 400, not a 403.
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
