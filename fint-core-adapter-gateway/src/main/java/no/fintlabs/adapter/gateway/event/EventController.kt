package no.fintlabs.adapter.gateway.event

import no.fintlabs.adapter.gateway.ProviderApi
import no.fintlabs.adapter.gateway.event.request.RequestEventService
import no.fintlabs.adapter.gateway.event.response.ResponseEventService
import no.fintlabs.adapter.gateway.security.EventAuthorization
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
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
     * Serves pending events only for the packages the adapter holds a role for, the orgs it
     * has a contract for, and the operations that contract covers, so it never receives an
     * event it would be refused to answer. The role is checked before the contract, because a
     * missing role is not fixed by registering.
     */
    @GetMapping("{domainName}", "{domainName}/{packageName}", "{domainName}/{packageName}/{resourceName}")
    fun getEvents(
        corePrincipal: CorePrincipal,
        @PathVariable domainName: String,
        @PathVariable(required = false) packageName: String?,
        @PathVariable(required = false) resourceName: String?,
        @RequestParam(defaultValue = "0") size: Int,
    ): ResponseEntity<List<RequestFintEvent>> {
        val scopes = eventAuthorization.readableScopes(corePrincipal, domainName, packageName, resourceName)
        val contracts = eventAuthorization.readableContracts(corePrincipal)
        return ResponseEntity.ok(requestEventService.getEvents(contracts, scopes, size))
    }

    /**
     * The org check runs here because the body carries the orgId. The role and contract checks
     * cannot: the response body does not say which resource or operation it answers, so those
     * are checked against the stored request inside [ResponseEventService].
     */
    @PostMapping
    @PreAuthorize("@adapterAuth.canAnswerFor(authentication, #responseFintEvent.orgId)")
    fun postEvent(
        @RequestBody responseFintEvent: ResponseFintEvent,
    ): ResponseEntity<Void> {
        responseEventService.handleEvent(responseFintEvent)
        return ResponseEntity.ok().build()
    }
}
