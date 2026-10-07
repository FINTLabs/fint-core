package no.fintlabs.adapter.gateway.register

import no.fintlabs.adapter.operation.OperationType
import no.novari.core.shared.event.EventCapabilityStore
import no.novari.core.shared.event.OrgEventCapabilities
import no.novari.core.shared.event.ResourceOperations
import no.novari.core.shared.model.OrgId
import no.novari.fint.core.model.FintResourceRef
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * Tells client-api what the adapters of an org answer events for, by writing one document per
 * org to Mongo. The document is the union over the org's contracts, so a resource is listed with
 * every operation any of its adapters answers. It is written after every registration, and all
 * of them are written again on start, so the collection can be dropped and heals itself.
 */
@Service
class EventCapabilityPublisher(
    private val contractService: ContractService,
    private val eventCapabilityStore: EventCapabilityStore,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun publish(orgId: OrgId) {
        val listed = HashMap<FintResourceRef, MutableSet<OperationType>>()
        contractService.contractsFor(orgId).forEach { contract ->
            contract.eventCapabilities.listedResources().forEach { (resource, operations) ->
                listed.getOrPut(resource) { HashSet() }.addAll(operations)
            }
        }

        eventCapabilityStore.save(
            OrgEventCapabilities(
                orgId = orgId.value,
                resources = listed.map { (resource, operations) -> ResourceOperations.of(resource, operations) },
                updatedAt = clock.instant(),
            ),
        )
    }

    @EventListener(ApplicationReadyEvent::class)
    fun publishAll() {
        val orgs = contractService.registeredOrgs()
        orgs.forEach(::publish)
        logger.info("Published event capabilities for {} orgs", orgs.size)
    }
}
