package no.novari.fint.core.shared.autorelation.model

import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.Link

/**
 * Full current state of the relation slot identified by
 * `{source}/{id}#{targetEntity}#{binding.relationName}`: the complete set of target ids the source
 * currently links to via this relation, plus the link back to the source. Consumers reconcile by
 * diffing [targetIds] against what they already hold, so add/remove is derived, not transmitted.
 *
 * An empty [targetIds] means the source links nothing here (drives removal of all back-links).
 */
data class RelationState(
    val targetEntity: EntityDescriptor,
    val targetIds: List<String>,
    val binding: RelationBinding,
    val timestamp: Long = System.currentTimeMillis(),
)

fun RelationSyncRule.toRelationState(
    resource: FintResource,
    resourceId: String,
): RelationState =
    RelationState(
        targetEntity = targetType,
        targetIds = currentTargetIds(resource),
        binding = toRelationBinding(resource, resourceId),
    )

fun RelationSyncRule.toEmptyRelationState(
    resource: FintResource,
    resourceId: String,
): RelationState =
    RelationState(
        targetEntity = targetType,
        targetIds = emptyList(),
        binding = toRelationBinding(resource, resourceId),
    )

/**
 * The ids the source currently links to via [targetRelation] — empty if none. Mandatory-ness is
 * intentionally NOT enforced here: a missing link is a valid (empty) state that must be published
 * so the consumer can remove stale back-links. A malformed href still fails via [getIdentifier].
 */
private fun RelationSyncRule.currentTargetIds(resource: FintResource): List<String> {
    val links = resource.links[targetRelation] ?: return emptyList()
    val linksToProcess = if (isManyToMany()) links else links.take(1)
    return linksToProcess.mapNotNull { link ->
        if (link.href.isNullOrBlank()) null else link.getIdentifier()
    }
}

fun Link.getIdentifier() =
    href
        .split("/")
        .takeLast(2)
        .takeIf { segments -> segments.size > 1 && segments.all { it.isNotBlank() } }
        ?.last()
        ?: throw InvalidLinkException("Invalid link format for relation: '$href'. Could not extract valid ID segments.")
