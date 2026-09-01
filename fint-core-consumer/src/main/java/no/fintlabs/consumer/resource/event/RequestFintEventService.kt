package no.fintlabs.consumer.resource.event

import com.fasterxml.jackson.databind.ObjectMapper
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.operation.OperationType
import no.fintlabs.consumer.config.EventProperties
import no.fintlabs.consumer.kafka.event.RequestFintEventProducer
import no.novari.core.shared.event.EventStore
import no.novari.core.shared.event.toEventCollectionName
import no.novari.core.shared.json.FintJson
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.model.toResourceClass
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * The request's [RequestFintEvent.value] is serialized with the consumer's primary response
 * mapper, not the storage mapper: adapters read the value in the same shape clients get from
 * the consumer, where `_links` hold absolute hrefs (self included) and dates use the response
 * format. The inbound client body is
 * still parsed with the storage mapper, which is what normalizes incoming hrefs to id-based
 * links before the response mapper renders them back out. Links are rendered exactly once,
 * here, while the client's POST is being served: the response mapper resolves the component for
 * common resources from the current request, so this serialization must stay on the request
 * thread. From here on, the value is just a string; the provider never looks inside it.
 */
@Service
class RequestFintEventService(
    private val eventStore: EventStore,
    private val eventProperties: EventProperties,
    private val requestFintEventProducer: RequestFintEventProducer,
    private val responseMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val storageMapper = FintJson.storageMapper()

    fun createAndPublish(
        coordinate: ResourceCoordinate,
        resourceData: Any?,
        validateOnly: Boolean,
    ): RequestFintEvent =
        createAndPublish(
            coordinate,
            resourceData,
            if (validateOnly) OperationType.VALIDATE else OperationType.CREATE,
        )

    fun createAndPublish(
        coordinate: ResourceCoordinate,
        resourceData: Any?,
        operationType: OperationType,
    ): RequestFintEvent {
        val event = coordinate.toRequestFintEvent(resourceData, operationType)

        eventStore.save(
            event,
            Instant.ofEpochMilli(event.created).plus(eventProperties.retention),
            coordinate.toEventCollectionName(),
        )
        requestFintEventProducer.publish(event)

        return event
    }

    private fun ResourceCoordinate.toRequestFintEvent(
        resourceData: Any?,
        operation: OperationType,
    ): RequestFintEvent =
        RequestFintEvent().apply {
            corrId = UUID.randomUUID().toString()
            orgId = this@toRequestFintEvent.orgId
            domainName = this@toRequestFintEvent.domainName
            packageName = this@toRequestFintEvent.packageName
            resourceName = this@toRequestFintEvent.resourceName
            operationType = operation
            created = clock.millis()
            timeToLive = created + eventProperties.answerDeadline.toMillis()
            value = resourceData?.let { toAdapterJson(it) }
        }

    private fun ResourceCoordinate.toAdapterJson(resourceData: Any): String =
        responseMapper.writeValueAsString(
            storageMapper.convertValue(resourceData, toResourceClass()),
        )
}
