package no.novari.fint.core.synthetic.output

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.novari.fint.core.shared.autorelation.model.EntityDescriptor
import no.novari.fint.core.synthetic.config.SyntheticProperties
import no.novari.fint.core.synthetic.graph.SyntheticEntity
import no.novari.metamodel.MetamodelService
import org.instancio.Instancio
import org.instancio.Select
import org.springframework.stereotype.Service

/**
 * Builds the sync-page entry for one entity. Field values come from Instancio, which fills the
 * typed FINT resource class with seeded random data — same seed, same values. Identity and links
 * are then overwritten with the generator's own: `systemId.identifikatorverdi` is always the
 * bare entity id, and `_links` only contains what [SyntheticEntity] wired.
 */
@Service
class EntryFactory(
    objectMapper: ObjectMapper,
    private val metamodelService: MetamodelService,
) {
    private val bodyMapper = objectMapper.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL)
    private val mapType = object : TypeReference<MutableMap<String, Any?>>() {}

    fun toEntry(
        descriptor: EntityDescriptor,
        entity: SyntheticEntity,
        config: SyntheticProperties,
    ): SyncPageEntry {
        val body = if (config.populateFields) populatedBody(descriptor, entity, config.seed) else linkedMapOf()
        body["systemId"] = mapOf("identifikatorverdi" to entity.id)
        val links = entity.emittedLinks(config.materializeBackLinks)
        if (links.isNotEmpty()) {
            body["_links"] = links
        } else {
            body.remove("_links")
        }
        return SyncPageEntry.of(entity.id, body)
    }

    private fun populatedBody(
        descriptor: EntityDescriptor,
        entity: SyntheticEntity,
        seed: Long,
    ): MutableMap<String, Any?> {
        val resourceClass =
            metamodelService
                .getResource(descriptor.domainName, descriptor.packageName, descriptor.resourceName)!!
                .resourceClass
        val populated =
            Instancio
                .of(resourceClass)
                .ignore(Select.fields().named("links"))
                .withSeed(seed + entity.id.hashCode())
                .create()
        return bodyMapper.convertValue(populated, mapType)
    }
}
