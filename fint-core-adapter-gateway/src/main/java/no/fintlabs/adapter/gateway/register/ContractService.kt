package no.fintlabs.adapter.gateway.register

import no.fintlabs.adapter.models.AdapterContract
import no.fintlabs.adapter.operation.OperationType
import no.novari.core.shared.event.OrgEventCapabilities
import no.novari.core.shared.event.ResourceOperations
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.resourceRefOf
import no.novari.fint.core.model.FintResourceRef
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
        val contract =
            contractJpaRepository.findByUserNameAndOrgId(id.username, id.orgId) ?: return ContractLookup.Absent

        return ContractLookup.Found(contract.toRegisteredContract())
    }

    /**
     * What the adapters of [orgId] together answer events for: a resource is listed with every
     * operation any of them answers. An org without contracts gets an empty list.
     */
    fun eventCapabilitiesFor(orgId: OrgId): OrgEventCapabilities {
        val listed = HashMap<FintResourceRef, MutableSet<OperationType>>()
        contractsFor(orgId).forEach { contract ->
            contract.eventCapabilities.listedResources().forEach { (resource, operations) ->
                listed.getOrPut(resource) { HashSet() }.addAll(operations)
            }
        }

        return OrgEventCapabilities(
            orgId = orgId.value,
            resources = listed.map { (resource, operations) -> ResourceOperations.of(resource, operations) },
        )
    }

    private fun contractsFor(orgId: OrgId): List<RegisteredContract> =
        contractJpaRepository.findAllByOrgId(orgId.value).map { it.toRegisteredContract() }

    private fun ContractEntity.toRegisteredContract(): RegisteredContract =
        RegisteredContract(
            orgId = OrgId.from(orgId),
            syncResources =
                capabilityEntityset.mapTo(HashSet()) {
                    resourceRefOf(
                        it.domainName,
                        it.pkgName,
                        it.resourceName,
                    )
                },
            eventCapabilities = EventCapabilities(eventCapabilityEntityset.toOperationsByResource()),
        )

    private fun Collection<EventCapabilityEntity>.toOperationsByResource(): Map<FintResourceRef, Set<OperationType>> =
        groupBy({ resourceRefOf(it.domainName, it.pkgName, it.resourceName) }, { it.operation })
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
