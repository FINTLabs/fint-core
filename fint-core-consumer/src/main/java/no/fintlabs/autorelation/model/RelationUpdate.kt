package no.fintlabs.autorelation.model

import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.Link

data class RelationUpdate(
    val targetEntity: EntityDescriptor,
    val targetIds: List<String>,
    val binding: RelationBinding,
    val operation: RelationOperation,
    val timestamp: Long = System.currentTimeMillis(),
)

fun RelationSyncRule.toRelationUpdate(
    resource: FintResource,
    resourceId: String,
    operation: RelationOperation,
): RelationUpdate? {
    val targetIds = getTargetIds(resource) ?: return null

    return RelationUpdate(
        targetEntity = targetType,
        targetIds = targetIds,
        binding = toRelationBinding(resource, resourceId),
        operation = operation,
    )
}

private fun RelationSyncRule.getTargetIds(resource: FintResource): List<String>? {
    val links = resource.links[targetRelation]

    if (links.isNullOrEmpty()) {
        if (isMandatory) throw MissingMandatoryLinkException(targetRelation)
        return null
    }

    val linksToProcess = if (isManyToMany()) links else links.take(1)

    val ids =
        linksToProcess.mapNotNull { link ->
            if (link.href.isNullOrBlank()) {
                if (isMandatory) throw MissingMandatoryLinkException(targetRelation)
                null
            } else {
                link.getIdentifier()
            }
        }

    if (ids.isEmpty() && isMandatory) {
        throw MissingMandatoryLinkException(targetRelation)
    }

    return ids.ifEmpty { null }
}

fun Link.getIdentifier() =
    href
        .split("/")
        .takeLast(2)
        .takeIf { segments -> segments.size > 1 && segments.all { it.isNotBlank() } }
        ?.last()
        ?: throw InvalidLinkException("Invalid link format for relation: '$href'. Could not extract valid ID segments.")
