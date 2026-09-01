package no.fintlabs.provider.event

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.provider.event.request.RequestEventService
import no.fintlabs.provider.event.response.ResponseEventService
import no.fintlabs.provider.security.AdapterRequestValidator
import no.novari.core.shared.event.EventScope
import no.novari.resource.server.authentication.CorePrincipal
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
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
    private val requestValidator: AdapterRequestValidator,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @GetMapping("{domainName}", "{domainName}/{packageName}", "{domainName}/{packageName}/{resourceName}")
    fun getEvents(
        corePrincipal: CorePrincipal,
        @PathVariable domainName: String,
        @PathVariable(required = false) packageName: String?,
        @PathVariable(required = false) resourceName: String?,
        @RequestParam(defaultValue = "0") size: Int,
    ): ResponseEntity<List<RequestFintEvent>> {
        if (corePrincipal.assets.isEmpty()) {
            logger.error("No assets present in principal for user: {}", corePrincipal.username)
            return ResponseEntity.ok(emptyList())
        }

        return ResponseEntity.ok(
            requestEventService.getEvents(
                corePrincipal.assets,
                EventScope.of(domainName, packageName, resourceName),
                size,
            ),
        )
    }

    @PostMapping
    fun postEvent(
        corePrincipal: CorePrincipal,
        @RequestBody responseFintEvent: ResponseFintEvent,
    ): ResponseEntity<Void> {
        requestValidator.validateOrgId(corePrincipal, responseFintEvent.orgId)
//        requestValidator.validateAdapterId(corePrincipal, responseFintEvent.getAdapterId());
        // TODO: Skal vi stoppe response hvis adapteret har ikke en kontrakt? Og skal vi sjekke capabilities til kontrakten?

        responseEventService.handleEvent(responseFintEvent)
        return ResponseEntity.ok().build()
    }
}
