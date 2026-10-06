package no.fintlabs.adapter.gateway.register

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
 * Whether an adapter has a contract for one org, and if so which resources it covers.
 */
sealed interface ContractLookup {
    data class Found(
        val capabilities: Set<CapabilityKey>,
    ) : ContractLookup

    data object Absent : ContractLookup
}
