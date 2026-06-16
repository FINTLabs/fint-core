package no.fintlabs.autorelation.model

import no.novari.fint.model.FintIdentifikator
import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.Link
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("no.fintlabs.autorelation.model.RelationBinding")

data class RelationBinding(
    val relationName: String,
    val link: Link,
)

fun RelationSyncRule.toRelationBinding(
    resource: FintResource,
    resourceId: String,
) = RelationBinding(
    relationName = inverseRelation,
    link = resource.toLink(resourceId),
)

private fun FintResource.toLink(resourceId: String): Link {
    identifikators.entries
        .filter { it.value != null && !it.value.notNullOrEmpty() }
        .forEach { (key, _) ->
            logger.warn(
                "FintIdentifikator '{}' exists but has null identifikatorverdi for resource '{}'",
                key,
                resourceId,
            )
        }

    return identifikators
        .filter { it.value.notNullOrEmpty() }
        .entries
        .firstOrNull { it.value.identifikatorverdi == resourceId }
        ?.let { (idField, idValue) -> Link.with("${idField.lowercase()}/${idValue.identifikatorverdi}") }
        ?: throw ResourceIdMismatchException(resourceId)
}

private fun FintIdentifikator?.notNullOrEmpty() = this != null && !this.identifikatorverdi.isNullOrBlank()
