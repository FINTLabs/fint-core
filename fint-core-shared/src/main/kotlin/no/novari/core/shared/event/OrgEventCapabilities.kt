package no.novari.core.shared.event

import no.fintlabs.adapter.operation.OperationType
import no.novari.core.shared.model.resourceRefOf
import no.novari.fint.core.model.FintResourceRef
import org.springframework.data.annotation.Id
import java.time.Instant

/**
 * What the adapters of one org answer events for, as the gateway publishes it after every
 * registration. It is the union over the org's contracts, so a resource is listed with every
 * operation any of its adapters answers. client-api reads it to tell whether a request can be
 * read live.
 */
data class OrgEventCapabilities(
    @Id val orgId: String,
    val resources: List<ResourceOperations>,
    val updatedAt: Instant,
) {
    fun operationsFor(resource: FintResourceRef): Set<OperationType> =
        resources
            .filter { it.toResourceRef() == resource }
            .flatMapTo(HashSet()) { it.operations }

    fun canRead(resource: FintResourceRef): Boolean = OperationType.READ in operationsFor(resource)
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
