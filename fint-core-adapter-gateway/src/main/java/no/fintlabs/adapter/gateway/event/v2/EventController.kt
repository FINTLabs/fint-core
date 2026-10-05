package no.fintlabs.adapter.gateway.event.v2

import jakarta.validation.Valid
import no.fintlabs.adapter.gateway.AdapterApiPaths
import no.fintlabs.adapter.gateway.event.request.RequestEventService
import no.fintlabs.adapter.gateway.security.EventAuthorization
import no.fintlabs.adapter.models.v2.event.EventRequest
import no.fintlabs.adapter.models.v2.event.EventResponse
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

@RestController("eventControllerV2")
@RequestMapping(AdapterApiPaths.V2 + "/event")
class EventController(
    private val requestEventService: RequestEventService,
    private val eventAnswerService: EventAnswerService,
    private val eventAuthorization: EventAuthorization,
) {
    /**
     * Serves pending events with the same role and contract checks as v1, and on top of that
     * only for the resources and operations the adapter lists in eventCapabilities.
     */
    @GetMapping("{domainName}", "{domainName}/{packageName}", "{domainName}/{packageName}/{resourceName}")
    fun getEvents(
        corePrincipal: CorePrincipal,
        @PathVariable domainName: String,
        @PathVariable(required = false) packageName: String?,
        @PathVariable(required = false) resourceName: String?,
        @RequestParam(defaultValue = "0") size: Int,
    ): ResponseEntity<List<EventRequest>> {
        val scopes = eventAuthorization.readableScopes(corePrincipal, domainName, packageName, resourceName)
        val orgs = eventAuthorization.readableOrgs(corePrincipal)
        val scopesByOrg = eventAuthorization.readableOperationScopes(corePrincipal, orgs, scopes)
        return ResponseEntity.ok(requestEventService.getEventsByCapability(scopesByOrg, size))
    }

    /**
     * The body is checked with Bean Validation before this runs, so an answer without a status or
     * with a resource without an identifier gets a 400 listing the fields, whatever its corrId.
     */
    @PostMapping
    @PreAuthorize("@adapterAuth.canAnswerFor(authentication, #eventResponse.orgId)")
    fun postEvent(
        @Valid @RequestBody eventResponse: EventResponse,
    ): ResponseEntity<Void> {
        eventAnswerService.answer(eventResponse)
        return ResponseEntity.ok().build()
    }
}
