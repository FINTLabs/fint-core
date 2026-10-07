package no.novari.core.shared.event

import no.fintlabs.adapter.operation.OperationType
import no.novari.core.shared.model.resourceRefOf
import no.novari.fint.core.model.FintResourceRef

/**
 * What the adapters of one org answer events for, as the gateway answers it on `GET [PATH]?orgId=`.
 * It is the union over the org's contracts, so a resource is listed with every operation any of
 * its adapters answers. client-api asks for it to tell whether a request can be read live.
 */
data class OrgEventCapabilities(
    val orgId: String,
    val resources: List<ResourceOperations>,
) {
    fun operationsFor(resource: FintResourceRef): Set<OperationType> =
        resources
            .filter { it.toResourceRef() == resource }
            .flatMapTo(HashSet()) { it.operations }

    fun canRead(resource: FintResourceRef): Boolean = OperationType.READ in operationsFor(resource)

    companion object {
        /** Where the gateway answers, inside the cluster only. */
        const val PATH = "/internal/event-capabilities"
    }
}

data class ResourceOperations(
    val domainName: String,
    val packageName: String,
    val resourceName: String,
    val operations: Set<OperationType>,
) {
    fun toResourceRef(): FintResourceRef = resourceRefOf(domainName, packageName, resourceName)

    companion object {
        fun of(
            resource: FintResourceRef,
            operations: Set<OperationType>,
        ): ResourceOperations = ResourceOperations(resource.domainName, resource.packageName, resource.resourceName, operations)
    }
}
