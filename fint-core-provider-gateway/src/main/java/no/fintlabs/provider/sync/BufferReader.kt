package no.fintlabs.provider.sync

import no.novari.core.shared.json.FintJson
import no.novari.core.shared.kafka.EntityHeaders.DOMAIN_NAME
import no.novari.core.shared.kafka.EntityHeaders.ORG_ID
import no.novari.core.shared.kafka.EntityHeaders.PACKAGE_NAME
import no.novari.core.shared.kafka.EntityHeaders.RESOURCE_NAME
import no.novari.core.shared.kafka.stringValue
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.model.toResourceClass
import no.novari.core.shared.relation.RelationEdge
import no.novari.core.shared.relation.RelationEdgeFactory
import no.novari.core.shared.relation.RelationEdgeStore
import no.novari.core.shared.store.ResourceStore
import no.novari.core.shared.store.ResourceWrite
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.Headers
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

// READs Kafka buffer, and writes to database.
@Component
class BufferReader(
    private val resourceStore: ResourceStore,
    private val relationEdgeStore: RelationEdgeStore,
) {
    val log = LoggerFactory.getLogger(BufferReader::class.java)

    private val objectMapper = FintJson.storageMapper()

    @KafkaListener(
        topics = ["#{topicBufferName}"],
        groupId = "consumer-service-group",
        containerFactory = "bufferKafkaListenerContainerFactory",
    )
    fun readMessage(records: List<ConsumerRecord<String, String>>) {
        log.debug("Read {} records from Kafka buffer", records.size)

        val resourceWrites = mutableListOf<ResourceWrite>()
        val edgesByCollection = mutableMapOf<String, MutableList<RelationEdge>>()

        records.forEach { record ->
            val json = record.value()
            if (json == null) {
                // TODO: Since json is null we should delete it (tombstone)
                log.warn("Skipping delition for key '{}' until the delete phase lands", record.key())
                return@forEach
            }

            val coords = resourceCoordinate(record.headers())
            val resourceId = record.extractIdentifier()
            val resource = objectMapper.readValue(json, coords.toResourceClass())
            resource.removeSelfLinks()

            edgesByCollection
                .getOrPut(coords.toEdgeCollectionName()) { mutableListOf() }
                .addAll(RelationEdgeFactory.createRelationEdges(coords, resourceId, resource))

            resourceWrites.add(ResourceWrite(resourceId, coords.toCollectionName(), resource))
        }

        resourceStore.saveAll(resourceWrites)
        edgesByCollection.forEach { (collectionName, edges) ->
            relationEdgeStore.upsertAll(collectionName, edges)
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
