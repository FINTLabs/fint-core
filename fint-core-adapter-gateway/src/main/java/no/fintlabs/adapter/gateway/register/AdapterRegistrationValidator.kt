package no.fintlabs.adapter.gateway.register

import no.fintlabs.adapter.models.AdapterCapability
import no.fintlabs.adapter.models.EventCapability
import no.novari.fint.core.model.FintModel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class AdapterRegistrationValidator {
    companion object {
        const val MAX_FULL_SYNC_INTERVAL_DAYS = 7
    }

    private val logger = LoggerFactory.getLogger(javaClass)

    fun validateCapabilities(capabilities: Collection<AdapterCapability>) =
        capabilities.forEach { capability ->
            if (invalidComponentResource(capability)) {
                logger.warn("Validation failed: Capability '$capability' from '${capability.entityUri}' is not a valid resource.")
                throw InvalidAdapterCapabilityException("Invalid capability resource: ${capability.entityUri} - Component does not exist")
            } else if (invalidFullSyncInterval(capability.fullSyncIntervalInDays)) {
                logger.warn("Validation failed: Capability '$capability' has an invalid FullSyncIntervalInDays value")
                throw InvalidAdapterCapabilityException(
                    "Invalid capability resource: ${capability.entityUri} - FullSyncIntervalInDays value is invalid",
                )
            }
        }

    /**
     * An event capability must name a resource in the model and at least one operation.
     */
    fun validateEventCapabilities(eventCapabilities: Collection<EventCapability>) =
        eventCapabilities.forEach { capability ->
            val uri = capability.entityUri
            if (FintModel.byPath(capability.domainName, capability.packageName, capability.resourceName) == null) {
                logger.warn("Validation failed: Event capability '{}' is not a valid resource.", uri)
                throw InvalidAdapterCapabilityException("Invalid event capability: $uri - Component does not exist")
            }
            if (capability.operations.isNullOrEmpty()) {
                logger.warn("Validation failed: Event capability '{}' has no operations.", uri)
                throw InvalidAdapterCapabilityException("Invalid event capability: $uri - At least one operation is required")
            }
        }

    private fun invalidComponentResource(capability: AdapterCapability): Boolean =
        FintModel.byPath(capability.domainName, capability.packageName, capability.resourceName) == null

    private fun invalidFullSyncInterval(fullSyncIntervalInDays: Int): Boolean = fullSyncIntervalInDays !in 1..MAX_FULL_SYNC_INTERVAL_DAYS
}
