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
 * (inverseName, sourceIdField, sourceIdValue). Deletes filter on either end: on
 * (sourceType, sourceId) when the owning resource goes away, and on the target triple when the
 * resource the back-link points at goes away.
 */
data class RelationEdge(
    @field:Id
    val id: String,
    // Which resource owns this edge, used to delete it when that resource goes away.
    // sourceId is the owning document's _id, which is the sync identifier and is not
    // guaranteed to be one of the identifier values below.
    val sourceType: String, // utdanning/elev/elevforhold
    val sourceId: String, // EF-123
    // Fields that are used to generate back-link uri for example systemid/123
    val sourceIdField: String, // systemid
    val sourceIdValue: String, // 123
    val inverseName: String, // Relasjonen fra target til source (elevforhold)
    // TODO: Answer how we handle /utdanning/kodeverk/iso elements
    // Fields to query back-links
    val targetType: String, // targetPath == null (bruk coordinates fra elev istedenfor)
    val targetIdField: String,
    val targetIdValue: String,
    // relationName is part of the identity and lives in [id], but is not stored on its own:
    // nothing queries it, and the same source and target can be related through two relations,
    // so [id] already keeps those apart.
    val createdAt: Instant? = null,
)

const val RELATION_EDGE_ID_DELIMITER = "\u001F"

/**
 * Builds the deterministic identity of a relation edge.
 *
 * The six parameters are the identity: one source resource, declaring one relation, toward one
 * target identifier. The id doubles as the uniqueness guard, so writes are idempotent upserts
 * and Kafka's at-least-once redelivery can never duplicate an edge.
 *
 * All six parameters participate even though relationName is not a stored field: the identity
 * must be stable from day one, or every existing edge would change id (and thus duplicate) the
 * moment a field is added.
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
