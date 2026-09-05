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
 * (inverseName, sourceIdField, sourceIdValue).
 */
data class RelationEdge(
    @field:Id
    val id: String,
    val sourceType: String,
    val sourceId: String,
    // Fields that are used to generate back-link uri for example systemid/123
    val sourceIdField: String, // systemid
    val sourceIdValue: String, // 123
    val inverseName: String, // Relasjonen fra target til source (elevforhold)
    // TODO: Answer how we handle /utdanning/kodeverk/iso elements
    // Fields to query back-links
    val targetType: String, // targetPath == null (bruk coordinates fra elev istedenfor)
    val targetIdField: String,
    val targetIdValue: String,
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
