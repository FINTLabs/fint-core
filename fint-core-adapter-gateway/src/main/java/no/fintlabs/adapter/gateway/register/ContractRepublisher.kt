package no.fintlabs.adapter.gateway.register

import no.fintlabs.adapter.models.AdapterCapability
import no.fintlabs.adapter.models.AdapterContract
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Publishes every stored contract to the contract topic when the gateway starts. Adapters
 * only register when they start, so without this a contract registered before the topic was
 * compacted would not be on the topic until the adapter restarts. Publishing again is safe:
 * the topic is keyed, so compaction keeps one record per contract.
 */
@Component
@ConditionalOnProperty(prefix = "fint.provider", name = ["republish-contracts"], havingValue = "true", matchIfMissing = true)
class ContractRepublisher(
    private val contractJpaRepository: ContractJpaRepository,
    private val adapterContractProducer: AdapterContractProducer,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Order(10)
    @EventListener(ApplicationReadyEvent::class)
    fun republishContracts() {
        val contracts = contractJpaRepository.findAllWithCapabilities()
        contracts.forEach { adapterContractProducer.send(it.toAdapterContract()) }
        logger.info("Republished {} contracts to the contract topic", contracts.size)
    }

    private fun ContractEntity.toAdapterContract(): AdapterContract =
        AdapterContract
            .builder()
            .adapterId(adapterId)
            .orgId(orgId)
            .username(userName)
            .heartbeatIntervalInMinutes(heartbeatIntervalInMinutes)
            .capabilities(capabilityEntityset.map { it.toAdapterCapability() }.toSet())
            .time(clock.millis())
            .build()

    private fun CapabilityEntity.toAdapterCapability(): AdapterCapability =
        AdapterCapability
            .builder()
            .domainName(domainName)
            .packageName(pkgName)
            .resourceName(resourceName)
            .fullSyncIntervalInDays(fullSyncIntervalInDays)
            .deltaSyncInterval(AdapterCapability.DeltaSyncInterval.entries.firstOrNull { it.name == deltaSyncInterval })
            .build()
}
