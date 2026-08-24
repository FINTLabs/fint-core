package no.fintlabs.provider.sync

import no.novari.core.shared.kafka.EntityHeaders.DOMAIN_NAME
import no.novari.core.shared.kafka.EntityHeaders.ORG_ID
import no.novari.core.shared.kafka.EntityHeaders.PACKAGE_NAME
import no.novari.core.shared.kafka.EntityHeaders.RESOURCE_NAME
import no.novari.core.shared.kafka.stringValue
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.relation.RelationEdgeStore
import no.novari.core.shared.relation.RelationEndpoint
import no.novari.core.shared.relation.StoredRelation
import no.novari.core.shared.store.IdentifierRef
import no.novari.core.shared.store.ResourceStore
import no.novari.core.shared.store.ResourceWrite
import no.novari.fint.core.model.FintModel
import no.novari.fint.core.model.FintRelation
import no.novari.fint.core.model.FintResource
import no.novari.fint.core.model.FintResourceMetadata
import no.novari.fint.core.model.FintResourceRef
import no.novari.fint.core.model.targetIn
import no.novari.fint.core.model.targetName
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.Headers
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

// READs Kafka buffer, and writes to database.
@Component
class BufferReader(
    private val resourceStore: ResourceStore,
    private val resourceConverter: ResourceConverter,
    private val relationEdgeStore: RelationEdgeStore,
) {
    val log = LoggerFactory.getLogger(BufferReader::class.java)

    @KafkaListener(
        topics = ["#{topicBufferName}"],
        groupId = "consumer-service-group",
        containerFactory = "bufferKafkaListenerContainerFactory",
    )
    fun readMessage(records: List<ConsumerRecord<String, String>>) {
        log.debug("Read {} records from Kafka buffer", records.size)

        val resourceWrites = mutableListOf<ResourceWrite>()

        records.forEach { record ->
            val coords = resourceCoordinate(record.headers())
            val resourceId = record.extractIdentifier() //
            val resource = resourceConverter.convert(coords, record.value())
            resource.removeSelfLinks()

            val resourceMetaData = FintModel.byPath(coords.domainName, coords.packageName, coords.resourceName)
                ?: throw IllegalArgumentException("Resource model not found for $coords")

            // We assume any of the entries in _links never has a size bigger than 1
            createRelationEdges(resource, resourceMetaData, coords, resourceId, coords.toRescourceRef())

            resourceWrites.add(ResourceWrite(resourceId, coords.toCollectionName(), resource))
        }
        resourceStore.saveAll(resourceWrites)
        relationEdgeStore.saveAll(storedRelations)
    }

    private fun createRelationEdges(
        resource: FintResource,
        resourceModel: FintResourceMetadata,
        coords: ResourceCoordinate,
        resourceId: String,
        resourceRef: FintResourceRef
    ): List<StoredRelation> {
        resource.links.entries.forEach { (relationName, links) ->
            // Get the relation between A and B
            val relation: FintRelation = resourceModel.relation(relationName) ?: return@forEach

            val targetRef = relation.targetIn(resourceRef)

            val idEntry = resource.idFor(resourceId) ?: return@forEach // håndter null!
            return links.map { link ->
                    StoredRelation(
                        source =
                            RelationEndpoint(
                                coords,
                                IdentifierRef(
                                    idEntry.first.lowercase(),
                                    idEntry.second
                                ), // Not totally sure if idEntry.key should be lowercase here.
                                relation.name,
                            ),
                        target =
                            RelationEndpoint(
                                targetRef,
                                IdentifierRef(
                                    requireNotNull(link.idField),
                                    requireNotNull(link.idValue),
                                ),
                                resourceName,
                            ),
                    )
            }
        }
    }

    private fun resourceCoordinate(headers: Headers): ResourceCoordinate =
        ResourceCoordinate(
            orgId = headers.requiredStringValue(ORG_ID),
            domainName = headers.requiredStringValue(DOMAIN_NAME),
            packageName = headers.requiredStringValue(PACKAGE_NAME),
            resourceName = headers.requiredStringValue(RESOURCE_NAME),
        )

    private fun Headers.requiredStringValue(name: String): String =
        stringValue(name) ?: throw IllegalArgumentException("Missing required Kafka header '$name'")
}
