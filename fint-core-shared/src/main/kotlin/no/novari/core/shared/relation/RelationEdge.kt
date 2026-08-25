package no.novari.core.shared.relation

import org.springframework.data.annotation.Id
import java.time.Instant

/**
 * One synthesized back-link, owned by the source resource that declared the outgoing link.
 *
 * For an Elevforhold EF-123 declaring `_links.elev = [elevnummer/E-456]`, the edge says:
 * when an Elev matching (elevnummer, E-456) is served, attach `Link(systemid, EF-123)` under
 * its `_links.elevforhold` before response links are rendered. Edges live in one collection
 * per org, `<org>_relation_edges`, so no field carries the org: the collection name is the org.
 *
 * Reads filter on (targetType, targetIdField, targetIdValue) and read out
 * (inverseName, sourceIdField, sourceIdValue). Nothing else is queried yet.
 */
data class RelationEdge(
    @field:Id
    val id: String,
    // Fields that are used to generate back-link uri for example systemid/123
    val sourceIdField: String, // systemid
    val sourceIdValue: String, // 123
    val inverseName: String, // Relasjonen fra target til source (elevforhold)
    // TODO: Answer how we handle /utdanning/kodeverk/iso elements
    // Fields to query back-links
    val targetType: String, // targetPath == null (bruk coordinates fra elev istedenfor)
    val targetIdField: String,
    val targetIdValue: String,
    // Fields below are part of the design but not stored until the delete phase needs them.
    // They are already baked into [id], so enabling them later self-heals on the next sync:
    // the upsert matches the same _id and $set adds the missing fields.
    //
    // val sourceType: String,
    //     The owning resource's type, e.g. "utdanning/elev/elevforhold". Delete phase: reconcile
    //     prune, tombstone handling and full-sync eviction all filter on (sourceType, sourceId),
    //     backed by an index on that pair. sourceId alone is not unique across types, so deletes
    //     without the type fence would hit other resources' edges that share the same id.
    //
    // val sourceId: String,
    //     The owning source doc's _id in its resource collection (the sync identifier). The other
    //     half of every delete/prune filter. Distinct from sourceIdValue: this is the ownership
    //     key, sourceIdField/sourceIdValue is the rendered link, and the sync key is not
    //     guaranteed to be an identifier value.
    //
    // val relationName: String,
    //     The source-side relation the edge was derived from, e.g. "elev". Part of the identity
    //     (same source and target can be related through two different relations), so it lives in
    //     [id]; storing it separately is only for debugging queries.
    val createdAt: Instant? = null,
)

const val RELATION_EDGE_ID_DELIMITER = "\u001F"

/**
 * Builds the deterministic identity of a relation edge.
 *
 * The six parameters are the identity: one source resource, declaring one relation, toward one
 * target identifier. The id doubles as the uniqueness guard, so writes are idempotent upserts
 * and Kafka's at-least-once redelivery can never duplicate an edge. The delete phase will also
 * rely on it to prune with `_id $nin desiredIds` without reading first.
 *
 * All six fields participate even though not all are stored on the document yet: the identity
 * must be stable from day one, or every existing edge would change id (and thus duplicate) the
 * moment the remaining fields are enabled.
 *
 * The delimiter is the same unit separator the provider uses for Kafka keys. Identifier values
 * are adapter-controlled strings, so a printable delimiter like `|` could appear inside one and
 * make two different identities collide.
 */
fun relationEdgeId(
    sourceType: String,
    sourceId: String,
    relationName: String,
    targetType: String,
    targetIdField: String,
    targetIdValue: String,
): String =
    listOf(sourceType, sourceId, relationName, targetType, targetIdField, targetIdValue)
        .joinToString(RELATION_EDGE_ID_DELIMITER)
