package no.fintlabs.consumer.resource

import com.fasterxml.jackson.databind.ObjectMapper
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.model.resource.FintResources
import no.fintlabs.model.resource.createFintResources
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.store.ResourceEntry
import no.novari.core.shared.store.ResourceStore
import no.novari.fint.model.resource.FintResource
import no.novari.metamodel.MetamodelService
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ResourceService(
    private val consumerConfiguration: ConsumerConfiguration,
    private val resourceStore: ResourceStore,
    private val metamodelService: MetamodelService,
    private val objectMapper: ObjectMapper,
) {
    fun getResources(
        resourceCoordinate: ResourceCoordinate,
        size: Int,
        offset: Long,
        sinceTimeStamp: Long?,
        filter: String?,
    ): FintResources { // Can return empty FintResources
        val criteria = sinceTimeStamp.toCriteria()
        val entries: List<ResourceEntry> =
            if (size == 0) {
                // find all resources in a collection
                // This should not be allowed, but since our customers already uses it, we have to implement it
                // to not break the contract.

                resourceStore.findAll(criteria, resourceCoordinate.toCollectionName())
            } else {
                resourceStore.findPage(criteria, size, offset, resourceCoordinate.toCollectionName())
            }

        val resources = entries.toFintResources(resourceCoordinate)
        return createFintResources(
            consumerConfiguration.baseUrl,
            resourceCoordinate.toResourceUri(),
            resources,
            offset,
            size,
            resources.size,
        )
    }

    private fun List<ResourceEntry>.toFintResources(resourceCoordinate: ResourceCoordinate): List<FintResource> {
        val resourceClass =
            metamodelService
                .getResource(
                    resourceCoordinate.domainName,
                    resourceCoordinate.packageName,
                    resourceCoordinate.resourceName,
                )?.resourceClass
                ?: throw IllegalArgumentException("Unknown resource: $resourceCoordinate")

        return map { entry ->
            objectMapper.convertValue(entry.data, resourceClass)
        }
    }

    fun Long?.toCriteria() =
        this?.let {
            Criteria.where("lastModified").gte(Instant.ofEpochMilli(this))
        }

    /**
     * Retrieves a single resource based on its identifier from a specific collection.
     *
     * The method fetches a resource from the database using a combination of resource coordinate,
     * identifier field, and identifier value. If the matching resource is found, it is converted
     * to a `FintResource` object. If no match is found, the method returns null.
     *
     * @param resourceCoordinate The coordinate defining the resource's collection and structure.
     * @param idField The name of the identifier field used to query the resource.
     * @param idValue The value of the specified identifier field used to find the resource.
     * @return The matching `FintResource` if found, or null if no match is found.
     */
    fun getResourceById(
        resourceCoordinate: ResourceCoordinate,
        idField: String,
        idValue: String,
    ): FintResource? {
        val collectionName = resourceCoordinate.toCollectionName()
        val entry = resourceStore.findByIdentifier(idField, idValue, collectionName) ?: return null
        return listOf(entry).toFintResources(resourceCoordinate).single()
    }

    fun getLastUpdated(resourceCoordinate: ResourceCoordinate): Long =
        TODO("Get lastUpdated of resource coordinate in MongoDB")

    fun getCacheSize(resourceCoordinate: ResourceCoordinate): Int = TODO("Get size of resource coordinate in MongoDB")
}
