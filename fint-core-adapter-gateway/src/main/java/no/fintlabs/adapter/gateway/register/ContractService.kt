package no.fintlabs.adapter.gateway.register

import no.fintlabs.adapter.models.AdapterContract
import no.fintlabs.adapter.models.v2.event.EventOperation
import no.novari.core.shared.model.OrgId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The contract store. One contract per username and orgId pair, so an adapter that delivers
 * for several orgs registers once per org and each registration stands on its own.
 */
@Service
class ContractService(
    private val contractJpaRepository: ContractJpaRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun getAdapterIds(): Set<String> = contractJpaRepository.getAdapterIds()

    @Transactional
    fun saveContract(adapterContract: AdapterContract) {
        val id = contractId(adapterContract.username, adapterContract.orgId)

        val entity =
            contractJpaRepository.findByUserNameAndOrgId(id.username, id.orgId)
                ?: ContractEntity().apply {
                    userName = id.username
                    orgId = id.orgId
                }

        entity.applyContract(adapterContract)
        contractJpaRepository.save(entity)

        log.info("Contract saved for '{}' on '{}'", id.username, id.orgId)
    }

    fun lookup(
        username: String,
        orgId: String,
    ): ContractLookup {
        val id = contractId(username, orgId)
        val contract = contractJpaRepository.findByUserNameAndOrgId(id.username, id.orgId) ?: return ContractLookup.Absent

        return ContractLookup.Found(contract.toCapabilityKeys(), contract.toEventCapabilities())
    }

    private fun ContractEntity.toCapabilityKeys(): Set<CapabilityKey> =
        capabilityEntityset
            .map { CapabilityKey.of(it.domainName, it.pkgName, it.resourceName) }
            .toSet()

    private fun ContractEntity.toEventCapabilities(): Map<CapabilityKey, Set<EventOperation>> =
        eventCapabilityEntitySet
            .groupBy({ CapabilityKey.of(it.domainName, it.pkgName, it.resourceName) }, { it.operation })
            .mapValues { (_, operations) -> operations.toSet() }

    private fun contractId(
        username: String,
        orgId: String,
    ): ContractId = ContractId(username, OrgId.from(orgId).value)

    private data class ContractId(
        val username: String,
        val orgId: String,
    )
}
