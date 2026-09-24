package no.novari.core.shared.relation

import no.novari.fint.core.model.FintModel
import no.novari.fint.core.model.FintRelation
import no.novari.fint.core.model.FintResourceRef
import no.novari.fint.core.model.targetIn

/**
 * One relation autorelation supplies back-links for. A resource served at [sourceRef] that
 * declares a link under [relationName] gets a back-link under [inverseName] on the resource
 * served at [targetRef].
 */
data class RelationRule(
    val sourceRef: FintResourceRef,
    val relationName: String,
    val targetRef: FintResourceRef,
    val inverseName: String,
    val kind: Kind,
) {
    enum class Kind { ONE_TO_MANY, MANY_TO_MANY }
}

/**
 * Decides which relations autorelation supplies back-links for. This is the only place that
 * decision is made. [RelationEdgeFactory] asks it for every link it derives edges from, and
 * `RELATION_RULES.md` at the repository root is generated from [all], so the document and the
 * write path cannot disagree.
 */
object RelationRules {
    /**
     * The rule for [relation] as declared by a resource served at [sourceRef], or null when the
     * relation produces no back-links.
     *
     * A relation qualifies when all of these hold:
     *
     * - The model declares an inverse. Without one there is no relation name on the target to
     *   attach anything under, so an edge could never be rendered.
     *
     * - The inverse side is list-valued. A single-valued slot on the target belongs to the
     *   target's own adapter data; writing our own link into it would let two sources race for
     *   one slot. This is why one-to-one and many-to-one relations never produce edges.
     *
     * - A list-valued source relation must be the owning side (`isSource`). In a many-to-many
     *   both sides declare each other, and exactly one side owns the list so the target's list
     *   is never fed from two disagreeing sources.
     *
     * - The target resolves into the source's own domain. Common resources resolve into the
     *   source's component via [targetIn] and pass naturally; cross-domain targets do not.
     */
    fun of(
        sourceRef: FintResourceRef,
        relation: FintRelation,
    ): RelationRule? {
        val bidirectional = relation.bidirectional ?: return null
        val targetRef = relation.targetIn(sourceRef) ?: return null
        if (!bidirectional.inverseMultiplicity.many) return null
        if (relation.multiplicity.many && !bidirectional.isSource) return null
        if (targetRef.domainName != sourceRef.domainName) return null

        return RelationRule(
            sourceRef = sourceRef,
            relationName = relation.name,
            targetRef = targetRef,
            inverseName = bidirectional.inverseName,
            kind = if (relation.multiplicity.many) RelationRule.Kind.MANY_TO_MANY else RelationRule.Kind.ONE_TO_MANY,
        )
    }

    /**
     * Every rule in the model, in the order the model serves resources and declares relations.
     * A common resource appears once per domain and package it is served under, since its
     * targets resolve differently in each.
     */
    fun all(): List<RelationRule> =
        FintModel.served.flatMap { served ->
            served.metadata.relations.mapNotNull { of(served.ref, it) }
        }
}
