package no.fintlabs.provider.sync

import no.fintlabs.provider.links.LinkService
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
import no.novari.metamodel.MetamodelService
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
    private val linkService: LinkService,
    private val metamodelService: MetamodelService,
    private val relationEdgeStore: RelationEdgeStore,
) {
    val log = LoggerFactory.getLogger(BufferReader::class.java)

    @Suppress("ktlint:standard:no-consecutive-comments") // TODO: remove
    @KafkaListener(
        topics = ["#{topicBufferName}"],
        groupId = "consumer-service-group",
        containerFactory = "bufferKafkaListenerContainerFactory",
    )
    fun readMessage(records: List<ConsumerRecord<String, String>>) {
        log.debug("Read {} records from Kafka buffer", records.size)

        val resourceWrites = mutableListOf<ResourceWrite>()
        val edges = mutableListOf<StoredRelation>()

        records.forEach { record ->
            val coords = resourceCoordinate(record.headers())
            val resourceId = record.extractIdentifier() //
            val resource = resourceConverter.convert(coords, record.value())
            linkService.mapLinks(resource)

            val resourceModel =
                checkNotNull(
                    metamodelService.getResource(
                        coords.domainName,
                        coords.packageName,
                        coords.resourceName,
                    ),
                ) {
                    "Resource model not found for $coords"
                }
            // We assume any of the entries in _links never has a size bigger than 1
            resource.links.entries.forEach { (relationName, links) ->
                val relation = resourceModel.relations.first { it.name == relationName }
                val inverseName = relation.inverseName ?: return@forEach

                val (domainName, packageName, resourceName) =
                    relation.packageName.split(".").takeLast(3)

                val idEntry =
                    resource.identifikators.entries.first { (_, identifier) ->
                        identifier.identifikatorverdi == resourceId
                    }

                edges.add(
                    StoredRelation(
                        source =
                            RelationEndpoint(
                                coords,
                                IdentifierRef(idEntry.key.lowercase(), idEntry.value.identifikatorverdi), // Not totally sure if idEntry.key should be lowercase here.
                                relation.name,
                            ),
                        target =
                            RelationEndpoint(
                                ResourceCoordinate(
                                    coords.orgId,
                                    domainName.lowercase(),
                                    packageName.lowercase(),
                                    resourceName.lowercase(),
                                ),
                                IdentifierRef(
                                    links
                                        .first()
                                        .href
                                        .split("/")
                                        .first()
                                        .lowercase(),
                                    links.first().href.split("/")[1],
                                ),
                                inverseName,
                            ),
                    ),
                )
            }

            resourceWrites.add(ResourceWrite(resourceId, coords.toCollectionName(), resource))
        }
        resourceStore.saveAll(resourceWrites)
        relationEdgeStore.saveAll(edges)
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
