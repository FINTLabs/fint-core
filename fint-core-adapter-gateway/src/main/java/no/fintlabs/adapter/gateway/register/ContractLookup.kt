package no.fintlabs.adapter.gateway.register

import no.fintlabs.adapter.models.v2.event.EventOperation

data class CapabilityKey(
    val domainName: String,
    val packageName: String,
    val resourceName: String,
) {
    companion object {
        fun of(
            domainName: String,
            packageName: String,
            resourceName: String,
        ): CapabilityKey =
            CapabilityKey(
                domainName.trim().lowercase(),
                packageName.trim().lowercase(),
                resourceName.trim().lowercase(),
            )
    }
}

/**
 * Whether an adapter has a contract for one org, and if so which resources it syncs and which
 * event operations it answers for each resource.
 */
sealed interface ContractLookup {
    data class Found(
        val capabilities: Set<CapabilityKey>,
        val eventCapabilities: Map<CapabilityKey, Set<EventOperation>> = emptyMap(),
    ) : ContractLookup

    data object Absent : ContractLookup
}
