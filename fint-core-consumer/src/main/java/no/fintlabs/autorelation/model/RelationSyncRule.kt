package no.fintlabs.autorelation.model

import no.novari.fint.model.FintMultiplicity

/**
 * Defines a synchronization rule with multiplicity context.
 */
data class RelationSyncRule(
    val targetRelation: String, // Relation we are pointing to
    val inverseRelation: String, // TargetType relation we are going to update
    val targetType: EntityDescriptor,
    val targetMultiplicity: FintMultiplicity,
    val inverseMultiplicity: FintMultiplicity,
    val isSource: Boolean,
) {
    /**
     * If `true`, a missing [targetRelation] link on the source resource constitutes
     * an error (MissingMandatoryLinkException).
     * * Derived logic: If the inverse relation (Target -> Source) is 1:1,
     * then every Target *must* have a Source.
     */
    val isMandatory: Boolean
        get() = targetMultiplicity == FintMultiplicity.ONE_TO_ONE || targetMultiplicity == FintMultiplicity.ONE_TO_MANY

    /**
     * Determines if we should remove (prune) old links that are missing from the current update.
     */
    fun shouldPruneLinks(): Boolean = isOneToMany() || (isManyToMany() && isSource)

    fun isOneToMany() = targetMultiplicity.isToOne() && inverseMultiplicity.isToMany()

    fun isManyToMany() = targetMultiplicity.isToMany() && inverseMultiplicity.isToMany()

    private fun FintMultiplicity.isToOne(): Boolean =
        this == FintMultiplicity.ONE_TO_ONE || this == FintMultiplicity.NONE_TO_ONE

    private fun FintMultiplicity.isToMany(): Boolean =
        this == FintMultiplicity.ONE_TO_MANY || this == FintMultiplicity.NONE_TO_MANY
}
