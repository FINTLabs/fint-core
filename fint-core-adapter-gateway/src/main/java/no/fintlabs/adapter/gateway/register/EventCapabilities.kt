package no.fintlabs.adapter.gateway.register

import no.fintlabs.adapter.operation.OperationType
import no.novari.fint.core.model.FintResourceRef

/**
 * What one contract says the adapter answers events for. A listed resource is served exactly the
 * operations it lists. A resource that is not listed is served every operation except READ, as
 * it was before contracts could list event capabilities, so READ is always opt-in.
 */
class EventCapabilities(
    private val listed: Map<FintResourceRef, Set<OperationType>>,
) {
    fun covers(
        resource: FintResourceRef,
        operation: OperationType,
    ): Boolean = operation in operationsFor(resource)

    fun operationsFor(resource: FintResourceRef): Set<OperationType> = listed[resource] ?: UNLISTED

    fun listedResources(): Map<FintResourceRef, Set<OperationType>> = listed

    override fun equals(other: Any?): Boolean = other is EventCapabilities && other.listed == listed

    override fun hashCode(): Int = listed.hashCode()

    override fun toString(): String = "EventCapabilities($listed)"

    companion object {
        val NONE = EventCapabilities(emptyMap())

        private val UNLISTED = OperationType.entries.toSet() - OperationType.READ
    }
}
