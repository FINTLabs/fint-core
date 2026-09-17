package no.fintlabs.adapter.gateway.storage

import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.org.OrgStore
import no.novari.core.shared.relation.RelationEdgeStore
import no.novari.core.shared.store.ResourceStore
import no.novari.fint.core.model.FintModel
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Component

/**
 * This exists to ensure that any of our new indexes gets applied to every collection that already exists
 * [ResourceStore] will handle the indexing only on insert, so it will also apply them on new collections.
 */
@Component
@ConditionalOnProperty(prefix = "fint.provider", name = ["ensure-indexes"], havingValue = "true", matchIfMissing = true)
class ResourceIndexEnsurer(
    private val template: MongoTemplate,
    private val orgStore: OrgStore,
    private val resourceStore: ResourceStore,
    private val relationEdgeStore: RelationEdgeStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun ensureIndexes() {
        val existing = template.collectionNames

        orgStore.findAll().forEach { org ->
            val coordinates =
                FintModel.refs.map { ResourceCoordinate(org.id, it.domainName, it.packageName, it.resourceName) }
            val resourceCollections = coordinates.map { it.toCollectionName() }.filter { it in existing }
            val edgeCollections = coordinates.map { it.toEdgeCollectionName() }.distinct().filter { it in existing }

            resourceCollections.forEach { prepare(it, resourceStore::prepareCollection) }
            edgeCollections.forEach { prepare(it, relationEdgeStore::prepareCollection) }

            log.info(
                "Ensured indexes for org {}: {} resource collections, {} relation edge collections",
                org.id,
                resourceCollections.size,
                edgeCollections.size,
            )
        }
    }

    private fun prepare(
        collectionName: String,
        prepareCollection: (String) -> Unit,
    ) {
        try {
            prepareCollection(collectionName)
        } catch (e: RuntimeException) {
            log.warn("Could not ensure indexes on {}: {}", collectionName, e.message)
        }
    }
}
