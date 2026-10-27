package no.fintlabs.client.resource

import no.fint.antlr.FintFilterService
import no.fint.antlr.odata.ODataFilterService
import no.fintlabs.client.config.ConsumerConfiguration
import no.fintlabs.client.resource.dto.FintResourcesResponse
import no.fintlabs.client.resource.dto.createFintResourcesResponse
import no.fintlabs.client.resource.dto.fullReadSelfLink
import no.fintlabs.client.resource.paging.PageCursor
import no.fintlabs.client.resource.paging.PageCursorCodec
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
import kotlin.streams.asSequence

@Service
class ResourceService(
    private val consumerConfiguration: ConsumerConfiguration,
    private val resourceStore: ResourceStore,
    private val relationEdgeStore: RelationEdgeStore,
    private val cursorCodec: PageCursorCodec,
) {
    private val storageMapper = FintJson.storageMapper()
    private val filterService: FintFilterService = ODataFilterService()

    /**
     * Opens a full read: every resource of the collection, or every one modified at or after a
     * positive [sinceTimeStamp]. Nothing is read yet. Its batches read the store a batch
     * at a time, through a cursor, and each batch gets its back-links and the [filter] before it
     * is handed on. The caller closes the stream to release the cursor.
     *
     * The resource type is checked here, so an unknown one fails before any of it is written.
     */
    fun streamResources(
        resourceCoordinate: ResourceCoordinate,
        offset: Long,
        sinceTimeStamp: Long?,
        filter: String?,
    ): ResourceStream {
        val resourceClass = resourceCoordinate.toResourceClass()
        val since = sinceTimeStamp?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        val collectionName = resourceCoordinate.toCollectionName()
        val batchSize = consumerConfiguration.listRead.batchSize

        val entries = resourceStore.streamAll(since, batchSize, collectionName)
        val batches =
            entries
                .asSequence()
                .chunked(batchSize)
                .map { batch ->
                    ResourceBatch(batch.size, batch.toResponseResources(resourceCoordinate, resourceClass, filter))
                }

        return ResourceStream(
            offset = offset,
            selfLink =
                fullReadSelfLink(
                    consumerConfiguration.baseUrl,
                    resourceCoordinate.toResourceUri(),
                    since.toTimeStamp(),
                ),
            batches = batches,
            onClose = entries::close,
        )
    }

    /**
     * Serves one page of [size] entries. A positive [sinceTimeStamp] keeps only resources
     * modified at or after it. A page with a [cursor] starts where the cursor points; without
     * one it skips [offset] entries. A read without a size is a full read, see [streamResources].
     */
    fun getResources(
        resourceCoordinate: ResourceCoordinate,
        size: Int,
        offset: Long,
        sinceTimeStamp: Long?,
        filter: String?,
        cursor: PageCursor? = null,
    ): FintResourcesResponse {
        require(size > 0) { "A page needs a positive size, a read without one is a full read" }

        val since = sinceTimeStamp?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        val collectionName = resourceCoordinate.toCollectionName()
        val resourceClass = resourceCoordinate.toResourceClass()

        val totalItems = resourceStore.count(since, collectionName)
        val page =
            readPage(cursor, since?.let { SinceFilter(it, totalItems) }, size, offset, totalItems, collectionName)
        val resources = page.entries.toResponseResources(resourceCoordinate, resourceClass, filter)

        return createFintResourcesResponse(
            baseUrl = consumerConfiguration.baseUrl,
            resourceUri = resourceCoordinate.toResourceUri(),
            entries = resources,
            offset = offset,
            size = size,
            totalItems = totalItems.toInt(),
            sinceTimeStamp = since.toTimeStamp(),
            hasNext = page.hasNext,
            nextCursor = page.next?.let(cursorCodec::encode),
            prevCursor = page.prev?.let(cursorCodec::encode),
            selfCursor = cursor?.let(cursorCodec::encode),
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
        val resource = entry.toFintResource(resourceCoordinate.toResourceClass())
        mergeRelationEdges(resourceCoordinate, listOf(entry), listOf(resource))
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
     * Turns stored entries into the resources a response carries: converted to the model,
     * with their back-links merged in, and narrowed by the [filter]. The filter runs last so
     * it can see the back-links too. The edges are fetched for every entry of the batch, not
     * only the survivors, since the filter needs them to decide.
     */
    private fun List<ResourceEntry>.toResponseResources(
        resourceCoordinate: ResourceCoordinate,
        resourceClass: Class<out FintResource>,
        filter: String?,
    ): List<FintResource> {
        val resources = map { it.toFintResource(resourceClass) }
        mergeRelationEdges(resourceCoordinate, this, resources)
        return filter?.let { filterResources(resources, it) } ?: resources
    }

    /**
     * Attaches the back-links autorelation supplies onto the resources of a response, before the
     * response form renders `_links`: one query fetches the relation edges pointing at any of the
     * response's identifiers, and each edge becomes an ordinary id-based link on the resource it
     * points at, for example `elevforhold` on an Elev.
     */
    private fun mergeRelationEdges(
        resourceCoordinate: ResourceCoordinate,
        entries: List<ResourceEntry>,
        resources: List<FintResource>,
    ) {
        if (!consumerConfiguration.autorelation.enabled || entries.isEmpty()) return

        val edges =
            relationEdgeStore.findByTargets(
                resourceCoordinate.toEdgeCollectionName(),
                resourceCoordinate.toResourceUri(),
                entries.flatMap { it.identifiers },
            )

        edges.mergeInto(entries.zip(resources))
    }

    /**
     * Runs an OData `$filter` expression against each resource's own getters. Must run after
     * [mergeRelationEdges] to include all relations.
     */
    private fun filterResources(
        resources: List<FintResource>,
        filter: String,
    ): List<FintResource> = filterService.from(resources.stream(), filter).toList()

    private fun ResourceEntry.toFintResource(resourceClass: Class<out FintResource>): FintResource =
        storageMapper.convertValue(data, resourceClass)

    private fun Instant?.toTimeStamp(): Long = this?.toEpochMilli() ?: 0
}

/**
 * One page of entries and the bookmarks a client needs to move on from it. [next] points at the
 * last entry, so following it reads what comes after this page. [prev] points at the first entry,
 * so following it reads what comes before. Both are null for an empty page.
 */
private data class Page(
    val entries: List<ResourceEntry>,
    val hasNext: Boolean,
) {
    val next: PageCursor?
        get() = entries.lastOrNull()?.let { PageCursor(PageDirection.AFTER, it.toAnchor()) }

    val prev: PageCursor?
        get() = entries.firstOrNull()?.let { PageCursor(PageDirection.BEFORE, it.toAnchor()) }
}

private fun ResourceEntry.toAnchor() = PageAnchor(createdAt, id)
