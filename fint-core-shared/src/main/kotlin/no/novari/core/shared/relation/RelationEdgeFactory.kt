package no.novari.core.shared.relation

import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.model.toResourceUri
import no.novari.fint.core.model.FintRelation
import no.novari.fint.core.model.FintResource
import no.novari.fint.core.model.FintResourceRef
import no.novari.fint.core.model.targetIn
import org.slf4j.LoggerFactory

/**
 * Turns a source resource's stored `_links` into the relation edges it owns.
 */
object RelationEdgeFactory {
    private val log = LoggerFactory.getLogger(RelationEdgeFactory::class.java)

    /**
     * Derives the relation edges a source resource owns, from its stored `_links`.
     *
     * Every declared link on a relation that [qualifiedInverseName] accepts becomes one edge. A
     * single-valued relation carrying more than one link is adapter noise, so only its first
     * link counts. A link without a resolved idField/idValue pair cannot be rendered on the
     * target and produces no edge. The rendered back-link is the source identifier pair carrying
     * the sync id: the sync identifier is contractually one of the resource's identifier values,
     * so a resource where it matches none is a breached contract and derives nothing, loudly.
     */
    fun createRelationEdges(
        coordinate: ResourceCoordinate,
        resourceId: String,
        resource: FintResource,
    ): List<RelationEdge> {
        val sourceRef = coordinate.toResourceRef()
        val sourceType = coordinate.toResourceUri()
        val (sourceIdField, sourceIdValue) =
            resource.idFor(resourceId) ?: run {
                // TODO: Use error metric instead of logging error
                log.warn(
                    "Sync identifier '{}' matches no identifier value on {}, deriving no edges",
                    resourceId,
                    sourceType,
                )
                return emptyList()
            }

        return resource.links.entries.flatMap { (relationName, links) ->
            val relation = resource.metadata.relation(relationName) ?: return@flatMap emptyList()
            val targetRef = relation.targetIn(sourceRef) ?: return@flatMap emptyList()
            val inverseName = relation.qualifiedInverseName(sourceRef, targetRef) ?: return@flatMap emptyList()

            val targetType = targetRef.toResourceUri()
            val declaredLinks = if (relation.multiplicity.many) links else links.take(1)

            declaredLinks.mapNotNull { link ->
                val targetIdField = link.idField?.lowercase() ?: return@mapNotNull null
                val targetIdValue = link.idValue ?: return@mapNotNull null

                RelationEdge(
                    id =
                        relationEdgeId(
                            sourceType = sourceType,
                            sourceId = resourceId,
                            relationName = relation.name,
                            targetType = targetType,
                            targetIdField = targetIdField,
                            targetIdValue = targetIdValue,
                        ),
                    sourceType = sourceType,
                    sourceId = resourceId,
                    sourceIdField = sourceIdField.lowercase(),
                    sourceIdValue = sourceIdValue,
                    inverseName = inverseName,
                    targetType = targetType,
                    targetIdField = targetIdField,
                    targetIdValue = targetIdValue,
                )
            }
        }
    }

    /**
     * Decides whether this relation is one we supply back-links for, and under which relation
     * name they land on the target: the inverse name to attach under, or null when the relation
     * produces no edges.
     *
     * A relation qualifies when all of these hold:
     *
     * - The model declares an inverse. Without one there is no relation name on the target to
     *   attach anything under, so an edge could never be rendered.
     *
     * - The inverse side is list-valued. A single-valued slot on the target belongs to the
     *   target's own adapter data; synthesizing into it would let two sources race for one slot.
     *   This is why one-to-one and many-to-one relations never produce edges.
     *
     * - A list-valued source relation must be the owning side (`isSource`). In a many-to-many
     *   both sides declare each other, and exactly one side is authoritative so the target's
     *   list is never fed from two disagreeing sources.
     *
     * - The target resolves into the source's own domain. Common resources resolve into the
     *   source's component via [targetIn] and pass naturally; cross-domain targets do not.
     */
    private fun FintRelation.qualifiedInverseName(
        sourceRef: FintResourceRef,
        targetRef: FintResourceRef,
    ): String? {
        val bidirectional = bidirectional ?: return null
        if (!bidirectional.inverseMultiplicity.many) return null
        if (multiplicity.many && !bidirectional.isSource) return null
        if (targetRef.domainName != sourceRef.domainName) return null
        return bidirectional.inverseName
    }
}
