package no.fintlabs.consumer.resource

import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.model.resource.FintResources
import no.fintlabs.model.resource.createFintResources
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.store.ResourceStore
import no.novari.fint.model.resource.FintResource
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.stereotype.Service
import kotlin.time.Instant

@Service
class ResourceService(
    private val consumerConfiguration: ConsumerConfiguration,
    private val resourceStore: ResourceStore,
) {
    fun getResources(
        resourceCoordinate: ResourceCoordinate,
        size: Int,
        offset: Long,
        sinceTimeStamp: Long?,
        filter: String?,
    ): FintResources {
        val criteria = sinceTimeStamp.toCriteria()
        val resources =
            if (size == 0) {
                // find all resources in a collection
                // This should not be allowed, but since our customers already uses it, we have to implement it
                // to not break the contract.
                resourceStore.findAll(resourceCoordinate.toCollectionName())
            } else {
                resourceStore.findPage(criteria, size, offset, resourceCoordinate.toCollectionName())
            }
        // pagination
        return createFintResources(
            consumerConfiguration.baseUrl,
            resourceCoordinate.toResourceUri(),
            resources.map { it.data },
            offset,
            size,
            resources.size,
        )
    }

    fun Long?.toCriteria() =
        this?.let {
            Criteria.where("lastModified").gte(Instant.fromEpochSeconds(this))
        }

    fun getResourceById(
        resourceName: String,
        idField: String,
        idValue: String,
    ): FintResource? = cacheService.getCache(resourceName).getByIdField(idField, idValue)

    fun getLastUpdated(resourceName: String): Long = cacheService.getCache(resourceName).lastUpdated

    fun getCacheSize(resourceName: String): Int = cacheService.getCache(resourceName).size
}
