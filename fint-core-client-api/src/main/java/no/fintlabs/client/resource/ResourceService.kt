package no.fintlabs.client.resource

import no.fintlabs.client.config.ConsumerConfiguration
import no.fintlabs.client.resource.dto.FintResourcesResponse
import no.fintlabs.client.resource.dto.createFintResourcesResponse
import no.fintlabs.client.resource.paging.PageCursor
import no.fintlabs.client.resource.paging.PageDirection
import no.novari.core.shared.json.FintJson
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.model.toResourceClass
import no.novari.core.shared.relation.RelationEdgeStore
import no.novari.core.shared.relation.mergeInto
import no.novari.core.shared.store.PageAnchor
import no.novari.core.shared.store.ResourceEntry
import no.novari.core.shared.store.ResourceStore
import no.novari.core.shared.store.SinceFilter
import no.novari.fint.core.model.FintResource
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ResourceService(
    private val consumerConfiguration: ConsumerConfiguration,
    private val resourceStore: ResourceStore,
    private val relationEdgeStore: RelationEdgeStore,
) {
    private val storageMapper = FintJson.storageMapper()

    /**
     * Serves a list read. A `size` above zero gives one page, otherwise everything. A positive
     * `sinceTimeStamp` keeps only resources modified at or after it. A page with a [cursor] starts
     * where the cursor points; without one it skips [offset] entries.
     */
    fun getResources(
        resourceCoordinate: ResourceCoordinate,
        size: Int,
        offset: Long,
        sinceTimeStamp: Long?,
        filter: String?,
        cursor: PageCursor? = null,
    ): FintResourcesResponse {
        val since = sinceTimeStamp?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        val collectionName = resourceCoordinate.toCollectionName()
        val baseUrl = consumerConfiguration.baseUrl
        val resourceUri = resourceCoordinate.toResourceUri()

        if (size <= 0) {
            val entries = resourceStore.findAll(since, collectionName)
            val resources = entries.toFintResources(resourceCoordinate)
            mergeRelationEdges(resourceCoordinate, entries, resources, fullDump = since == null)
            return createFintResourcesResponse(
                baseUrl,
                resourceUri,
                resources,
                offset,
                size,
                entries.size,
                since.toTimeStamp(),
            )
        }

        val totalItems = resourceStore.count(since, collectionName)
        val page =
            readPage(cursor, since?.let { SinceFilter(it, totalItems) }, size, offset, totalItems, collectionName)
        val resources = page.entries.toFintResources(resourceCoordinate)
        mergeRelationEdges(resourceCoordinate, page.entries, resources, fullDump = false)

        return createFintResourcesResponse(
            baseUrl = baseUrl,
            resourceUri = resourceUri,
            entries = resources,
            offset = offset,
            size = size,
            totalItems = totalItems.toInt(),
            sinceTimeStamp = since.toTimeStamp(),
            hasNext = page.hasNext,
            nextCursor = page.nextCursor,
            prevCursor = page.prevCursor,
            selfCursor = cursor?.encode(),
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
     * Reads one entry more than the page size. If that extra entry comes back, there is another
     * page in the direction we read. It is dropped before the page is returned.
     */
    private fun readPage(
        cursor: PageCursor?,
        filter: SinceFilter?,
        size: Int,
        offset: Long,
        totalItems: Long,
        collectionName: String,
    ): Page {
        val rows =
            when (cursor?.direction) {
                null -> resourceStore.findPage(filter, size + 1, offset, collectionName)
                PageDirection.AFTER -> resourceStore.findPageAfter(cursor.anchor, filter, size + 1, collectionName)
                PageDirection.BEFORE -> resourceStore.findPageBefore(cursor.anchor, filter, size + 1, collectionName)
            }
        val hasNext = rows.size > size

        return when (cursor?.direction) {
            PageDirection.BEFORE -> Page(if (hasNext) rows.drop(1) else rows, hasNext = offset + size < totalItems)
            else -> Page(if (hasNext) rows.dropLast(1) else rows, hasNext)
        }
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

    private fun Instant?.toTimeStamp(): Long = this?.toEpochMilli() ?: 0
}

/**
 * One page of entries and the bookmarks a client needs to move on from it. [nextCursor] points at
 * the last entry, so following it reads what comes after this page. [prevCursor] points at the
 * first entry, so following it reads what comes before. Both are null for an empty page.
 */
private data class Page(
    val entries: List<ResourceEntry>,
    val hasNext: Boolean,
) {
    val nextCursor: String?
        get() = entries.lastOrNull()?.let { PageCursor(PageDirection.AFTER, it.toAnchor()).encode() }

    val prevCursor: String?
        get() = entries.firstOrNull()?.let { PageCursor(PageDirection.BEFORE, it.toAnchor()).encode() }
}

private fun ResourceEntry.toAnchor() = PageAnchor(createdAt, id)
