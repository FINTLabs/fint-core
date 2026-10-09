package no.fintlabs.adapter.gateway.register

import no.novari.core.shared.event.OrgEventCapabilities
import no.novari.core.shared.model.OrgId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Tells client-api what the adapters of an org answer events for, so it can decide whether a
 * read goes live. The path takes no token, because client-api has none, and it is reachable
 * inside the cluster only: the ingress routes `/provider` and nothing else.
 */
@RestController
class EventCapabilityController(
    private val contractService: ContractService,
) {
    @GetMapping(OrgEventCapabilities.PATH)
    fun eventCapabilities(
        @RequestParam orgId: OrgId,
    ): OrgEventCapabilities = contractService.eventCapabilitiesFor(orgId)
}
