package no.novari.core.shared.relation

import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.store.IdentifierRef
import org.bson.Document
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.BulkOperations
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.AggregationOperation
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Date

/**
 * A stored resource whose back-links are set from the edges. [isNew] says the resource was
 * stored for the first time, so it holds no back-links yet.
 */
data class BackLinkFill(
    val resourceId: String,
    val identifiers: List<IdentifierRef>,
    val isNew: Boolean,
)

/**
 * Stores back-links on the resources they belong to. A back-link is the link from a target back
 * to a source that points at it. It is stored next to `data`, under `backLinks` and the name of
 * the relation, as the id field and id value of the source. The relation edges decide which
 * back-links there are, and this store makes the stored resources match them.
 */
@Service
class BackLinkStore(
    private val template: MongoTemplate,
    private val relationEdgeStore: RelationEdgeStore,
) {
    /**
     * Gives the targets of the added edges their back-links and takes away the back-links of the
     * removed edges. `lastModified` of a target moves to [at] when the links a reader sees on it
     * change, and never back. A target that is not stored is left alone, since [fill] gives it
     * its back-links when it arrives.
     *
     * Must run in the transaction that wrote the edges, after they are written.
     */
    fun patch(
        orgId: OrgId,
        changes: RelationEdgeChanges,
        at: Instant,
    ) {
        val added = changes.added.groupBy { it.targetType }
        val removed = changes.removed.groupBy { it.targetType }

        (added.keys + removed.keys).forEach { targetType ->
            patchTargetsOf(orgId, targetType, added[targetType].orEmpty(), removed[targetType].orEmpty(), at)
        }
    }

    /**
     * Sets the back-links of [resources] to what the edges that point at them say. This is for a
     * resource that was stored for the first time, or whose identifiers changed, since the edges
     * of its sources can have been stored before it. `lastModified` is left alone, as the write
     * that stored the resource has already moved it.
     *
     * Must run in the transaction that stored the resources, after the edges are written.
     */
    fun fill(
        coordinate: ResourceCoordinate,
        resources: List<BackLinkFill>,
    ) {
        // A few resources at a time, since one of them can have thousands of edges pointing at it.
        resources.chunked(FILLS_PER_QUERY).forEach { fillChunk(coordinate, it) }
    }

    private fun patchTargetsOf(
        orgId: OrgId,
        targetType: String,
        added: List<RelationEdge>,
        removed: List<RelationEdge>,
        at: Instant,
    ) {
        val collectionName = collectionNameOf(orgId, targetType)
        val targets = findTargets(collectionName, (added + removed).mapTo(HashSet()) { it.targetRef() })
        if (targets.isEmpty()) return

        val changesById = LinkedHashMap<String, MutableMap<String, LinkChange>>()

        fun changeOf(
            target: StoredTarget,
            relation: String,
        ) = changesById.getOrPut(target.id) { LinkedHashMap() }.getOrPut(relation) { LinkChange() }

        added.forEach { edge ->
            val target = targets[edge.targetRef()] ?: return@forEach
            changeOf(target, edge.inverseName).adds += edge.link()
        }

        val stillGiven = linksStillGiven(orgId, targetType, removed, targets)
        removed.forEach { edge ->
            val target = targets[edge.targetRef()] ?: return@forEach
            if (GivenLink(target.id, edge.inverseName, edge.link()) in stillGiven) return@forEach
            changeOf(target, edge.inverseName).removes += edge.link()
        }
        if (changesById.isEmpty()) return

        val bulkOps = template.bulkOps(BulkOperations.BulkMode.UNORDERED, collectionName)
        changesById.forEach { (id, changes) -> bulkOps.updateOne(byId(id), patchOf(changes, Date.from(at))) }
        bulkOps.execute()
    }

    /**
     * The links of the removed edges that a stored edge still gives its target. Two edges can
     * give one target the same back-link, for example when a source points at it through two of
     * its identifiers. The back-link has to stay until the last of them is gone, so the edges
     * are asked before a link is taken away.
     */
    private fun linksStillGiven(
        orgId: OrgId,
        targetType: String,
        removed: List<RelationEdge>,
        targets: Map<IdentifierRef, StoredTarget>,
    ): Set<GivenLink> {
        val losing = removed.mapNotNull { targets[it.targetRef()] }.distinctBy { it.id }
        if (losing.isEmpty()) return emptySet()

        val targetOf = losing.flatMap { target -> target.identifiers.map { it to target } }.toMap()
        return relationEdgeStore
            .findByTargetsAndSourceValues(
                ResourceCoordinate.edgeCollectionNameOf(orgId),
                targetType,
                targetOf.keys,
                removed.map { it.sourceIdValue },
            ).mapNotNullTo(HashSet()) { edge -> targetOf[edge.targetRef()]?.let { GivenLink(it.id, edge.inverseName, edge.link()) } }
    }

    /**
     * One update that adds and removes back-links and moves `lastModified` only when the links a
     * reader sees change. A reader sees the back-links together with the links the adapter
     * delivered on the resource itself, so a back-link the adapter already delivered changes
     * nothing for the reader.
     */
    private fun patchOf(
        changes: Map<String, LinkChange>,
        at: Date,
    ): AggregationUpdate {
        val set = Document()
        val sameForTheReader = ArrayList<Document>()

        changes.forEach { (relation, change) ->
            val path = "$BACK_LINKS.$relation"
            val current = Document("\$ifNull", listOf("\$$path", emptyList<Any>()))
            val withoutRemoved = Document("\$setDifference", listOf(current, literal(change.removes)))
            val next = Document("\$setUnion", listOf(withoutRemoved, literal(change.adds)))
            val fromAdapter =
                Document(
                    "\$map",
                    Document("input", Document("\$ifNull", listOf("\$data._links.$relation", emptyList<Any>())))
                        .append("as", "link")
                        // Back-links hold the id field in lower case, and the adapter may not.
                        .append("in", Document("idField", Document("\$toLower", "\$\$link.idField")).append("idValue", "\$\$link.idValue")),
                )

            set.append(path, next)
            sameForTheReader +=
                Document(
                    "\$setEquals",
                    listOf(Document("\$setUnion", listOf(current, fromAdapter)), Document("\$setUnion", listOf(next, fromAdapter))),
                )
        }

        val moved = Document("\$max", listOf("\$lastModified", at))
        set.append("lastModified", Document("\$cond", listOf(Document("\$and", sameForTheReader), "\$lastModified", moved)))

        return AggregationUpdate.from(listOf(AggregationOperation { Document("\$set", set) }))
    }

    private fun fillChunk(
        coordinate: ResourceCoordinate,
        resources: List<BackLinkFill>,
    ) {
        val edges =
            relationEdgeStore
                .findByTargets(coordinate.toEdgeCollectionName(), coordinate.toResourceUri(), resources.flatMap { it.identifiers })
                .groupBy { it.targetRef() }

        val bulkOps = template.bulkOps(BulkOperations.BulkMode.UNORDERED, coordinate.toCollectionName())
        var updates = 0
        resources.forEach { resource ->
            val links =
                resource.identifiers
                    .flatMap { edges[it].orEmpty() }
                    .groupBy({ it.inverseName }, { it.link() })
                    .mapValues { (_, links) -> links.distinct().map { it.toDocument() } }

            // A new resource holds nothing to clear.
            if (links.isEmpty() && resource.isNew) return@forEach

            val update = if (links.isEmpty()) Update().unset(BACK_LINKS) else Update().set(BACK_LINKS, Document(links))
            bulkOps.updateOne(byId(resource.resourceId), update)
            updates++
        }
        if (updates > 0) bulkOps.execute()
    }

    /**
     * The stored resources that hold any of [refs], by the identifier that led to them. One read
     * asks for the identifier values through their index. That index does not know the field,
     * so the field is checked here.
     */
    private fun findTargets(
        collectionName: String,
        refs: Set<IdentifierRef>,
    ): Map<IdentifierRef, StoredTarget> {
        val found = HashMap<IdentifierRef, StoredTarget>()
        refs.map { it.value }.distinct().chunked(VALUES_PER_QUERY).forEach { values ->
            val query = Query.query(Criteria.where("identifiers.value").`in`(values))
            query.fields().include("identifiers")

            template.find(query, StoredTarget::class.java, collectionName).forEach { target ->
                target.identifiers.filter { it in refs }.forEach { found.putIfAbsent(it, target) }
            }
        }
        return found
    }

    private fun collectionNameOf(
        orgId: OrgId,
        targetType: String,
    ): String {
        val (domainName, packageName, resourceName) = targetType.split("/")
        return ResourceCoordinate(orgId.value, domainName, packageName, resourceName).toCollectionName()
    }

    private fun byId(id: String): Query = Query.query(Criteria.where("_id").`is`(id))

    // The values go in as literals, since an identifier that starts with a dollar sign would otherwise be read as a field path.
    private fun literal(links: Collection<StoredLink>): Document = Document("\$literal", links.map { it.toDocument() })

    private fun BackLinkEdge.targetRef() = IdentifierRef(targetIdField, targetIdValue)

    private fun BackLinkEdge.link() = StoredLink(sourceIdField.lowercase(), sourceIdValue)

    private class LinkChange {
        val adds = LinkedHashSet<StoredLink>()
        val removes = LinkedHashSet<StoredLink>()
    }

    private data class StoredLink(
        val idField: String,
        val idValue: String,
    ) {
        fun toDocument(): Document = Document("idField", idField).append("idValue", idValue)
    }

    private data class GivenLink(
        val resourceId: String,
        val relation: String,
        val link: StoredLink,
    )

    private data class StoredTarget(
        @Id val id: String,
        val identifiers: List<IdentifierRef> = emptyList(),
    )

    companion object {
        const val BACK_LINKS = "backLinks"
        private const val VALUES_PER_QUERY = 500
        private const val FILLS_PER_QUERY = 50
    }
}
