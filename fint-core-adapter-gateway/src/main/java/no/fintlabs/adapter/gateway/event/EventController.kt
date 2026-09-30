package no.fintlabs.adapter.gateway.event

import no.fintlabs.adapter.gateway.event.request.RequestEventService
import no.fintlabs.adapter.gateway.event.response.ResponseEventService
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
@RequestMapping("/event")
class EventController(
    private val requestEventService: RequestEventService,
    private val responseEventService: ResponseEventService,
) {
    @GetMapping("{domainName}", "{domainName}/{packageName}", "{domainName}/{packageName}/{resourceName}")
    fun getEvents(
        corePrincipal: CorePrincipal,
        @PathVariable domainName: String,
        @PathVariable(required = false) packageName: String?,
        @PathVariable(required = false) resourceName: String?,
        @RequestParam(defaultValue = "0") size: Int,
    ): ResponseEntity<List<RequestFintEvent>> =
        ResponseEntity.ok(
            requestEventService.getEvents(
                corePrincipal.assets,
                EventScope.of(domainName, packageName, resourceName),
                size,
            ),
        )

    /**
     * The org check runs here because the body carries the orgId. The role check cannot: the
     * response body does not say which resource it answers, so that is checked against the
     * stored request inside [ResponseEventService].
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
