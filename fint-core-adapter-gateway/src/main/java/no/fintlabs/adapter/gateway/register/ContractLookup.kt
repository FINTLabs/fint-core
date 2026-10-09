package no.fintlabs.adapter.gateway.register

import no.novari.core.shared.model.OrgId
import no.novari.fint.core.model.FintResourceRef

/**
 * The contract one adapter registered for one org: the resources it delivers on sync and the
 * events it answers.
 */
data class RegisteredContract(
    val orgId: OrgId,
    val syncResources: Set<FintResourceRef>,
    val eventCapabilities: EventCapabilities,
)

/**
 * Whether an adapter has a contract for one org, and if so what it says.
 */
sealed interface ContractLookup {
    data class Found(
        val contract: RegisteredContract,
    ) : ContractLookup

    data object Absent : ContractLookup
}
