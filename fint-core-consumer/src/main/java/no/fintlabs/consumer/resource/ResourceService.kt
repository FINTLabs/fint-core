package no.fintlabs.consumer.resource

import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.resource.dto.FintResourcesResponse
import no.fintlabs.consumer.resource.dto.createFintResourcesResponse
import no.novari.core.shared.json.FintJson
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.model.toResourceClass
import no.novari.core.shared.relation.RelationEdgeStore
import no.novari.core.shared.relation.mergeInto
import no.novari.core.shared.store.ResourceEntry
import no.novari.core.shared.store.ResourceStore
import no.novari.fint.core.model.FintResource
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ResourceService(
    private val consumerConfiguration: ConsumerConfiguration,
    private val resourceStore: ResourceStore,
    private val relationEdgeStore: RelationEdgeStore,
) {
    private val storageMapper = FintJson.storageMapper()

    fun getResources(
        resourceCoordinate: ResourceCoordinate,
        size: Int,
        offset: Long,
        sinceTimeStamp: Long?,
        filter: String?,
    ): FintResourcesResponse { // Can return an empty response
        val criteria = sinceTimeStamp.toCriteria()
        val entries: List<ResourceEntry> =
            if (size == 0) {
                // TODO: can be removed in the future once we force pagination in the API
                resourceStore.findAll(criteria, resourceCoordinate.toCollectionName())
            } else {
                resourceStore.findPage(criteria, size, offset, resourceCoordinate.toCollectionName())
            }

        val resources = entries.toFintResources(resourceCoordinate)
        val fullDump = size == 0 && (sinceTimeStamp == null || sinceTimeStamp == 0L)
        mergeRelationEdges(resourceCoordinate, entries, resources, fullDump)

        return createFintResourcesResponse(
            consumerConfiguration.baseUrl,
            resourceCoordinate.toResourceUri(),
            resources,
            offset,
            size,
            resources.size,
        )
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
        val resource = entry.toFintResource(resourceCoordinate)
        mergeRelationEdges(resourceCoordinate, listOf(entry), listOf(resource), fullDump = false)
        return resource
    }

    /**
     * Attaches the back-links autorelation supplies onto the resources of a response, before the
     * response form renders `_links`: one query fetches the relation edges pointing at any of the
     * response's identifiers, and each edge becomes an ordinary id-based link on the resource it
     * points at, for example `elevforhold` on an Elev. A full dump covers the whole collection,
     * so its identifier filter would narrow nothing and only bloat the query; there the read
     * fetches every edge for the type in one range scan and lets the in-memory join route them.
     */
    private fun mergeRelationEdges(
        resourceCoordinate: ResourceCoordinate,
        entries: List<ResourceEntry>,
        resources: List<FintResource>,
        fullDump: Boolean,
    ) {
        // TODO: the fetch-everything branch can be removed once the API forces pagination
        if (!consumerConfiguration.autorelation.enabled || entries.isEmpty()) return

        val edges =
            if (fullDump) {
                relationEdgeStore.findAllByTargetType(
                    resourceCoordinate.toEdgeCollectionName(),
                    resourceCoordinate.toResourceUri(),
                )
            } else {
                relationEdgeStore.findByTargets(
                    resourceCoordinate.toEdgeCollectionName(),
                    resourceCoordinate.toResourceUri(),
                    entries.flatMap { it.identifiers },
                )
            }

        edges.mergeInto(entries.zip(resources))
    }

    private fun ResourceEntry.toFintResource(resourceCoordinate: ResourceCoordinate): FintResource =
        storageMapper.convertValue(data, resourceCoordinate.toResourceClass())

    private fun List<ResourceEntry>.toFintResources(resourceCoordinate: ResourceCoordinate): List<FintResource> =
        map { it.toFintResource(resourceCoordinate) }

    private fun Long?.toCriteria() = this?.let { Criteria.where("lastModified").gte(Instant.ofEpochMilli(this)) }
}
