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

            val resourceModel = metamodelService.getResource(coords.domainName, coords.packageName, coords.resourceName)
            // _links.noe er et array. Er _links.noe.size > 0 noen gang? Antar nei
            resource.links.entries.forEach { (relationName, links) ->
                val relation = resourceModel?.relations?.first { it.name == relationName } // Find which identifikator to use
                if (relation?.inverseName == null) {
                    return@forEach // If inversename is null, then we should not care about the link
                }

                val (domainName, packageName, resourceName) = // get coordinates of the target
                    relation!!.packageName.split(".").takeLast(3) // get domain and package // TODO: håndter felles
                val idEntry = // identifikator key and value for the source
                    resource.identifikators.entries.first { (_, identifikator) ->
                        identifikator.identifikatorverdi ==
                            resourceId
                    }
                edges.add(
                    StoredRelation(
                        RelationEndpoint(
                            coords,
                            IdentifierRef(idEntry.key, idEntry.value.identifikatorverdi),
                            relation.inverseName,
                        ),
                        RelationEndpoint(
                            ResourceCoordinate(
                                record.headers().requiredStringValue(ORG_ID),
                                domainName.lowercase(),
                                packageName.lowercase(),
                                resourceName.lowercase(),
                            ),
                            IdentifierRef(
                                links
                                    .first()
                                    .href
                                    .split("/")
                                    .first(),
                                links.first().href.split("/")[1],
                            ), // Hva er id value og id for target?
                            relation.name,
                        ),
                    ),
                )
            }

            resourceWrites.add(ResourceWrite(resourceId, coords.toCollectionName(), resource))
        }
        resourceStore.saveAll(resourceWrites)
        relationEdgeStore.saveAll(edges)
        // TODO: save edges
        /*
        Edge(
            sourceIdentifierType = record.extractIdentifier().split("-").first() // This is used to decide which type of identifier the backlink should use.
            sourceId = record.extractIdentifier().substringAfter("-")
            targetId = ... // Get from Resource somehow
            sourceCoordinate = ... // for example is it in utdanning-vurdering?
            targetCoordinate = ... // For example is it in utdanning-elev?

        )
         */
        // When we construct back link, it should be the same field as specified in record.extractIdentifier()
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
