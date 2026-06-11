package no.novari.fint.core.consumer.kafka.event

import com.fasterxml.jackson.databind.ObjectMapper
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.operation.OperationType
import no.novari.fint.core.consumer.config.ConsumerConfiguration
import no.novari.fint.core.consumer.config.EventCacheProperties
import no.novari.fint.core.shared.event.EventStatusStore
import no.novari.fint.core.shared.resource.ResourceConverter
import no.novari.fint.core.shared.resource.ResourceRef
import no.novari.fint.model.resource.FintResource
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID
import java.util.concurrent.TimeUnit

@Service
class RequestFintEventService(
    private val objectMapper: ObjectMapper,
    private val props: EventCacheProperties,
    private val config: ConsumerConfiguration,
    private val clock: Clock = Clock.systemUTC(),
    private val resourceConverter: ResourceConverter,
    private val requestFintEventProducer: RequestFintEventProducer,
    private val eventStatusStore: EventStatusStore,
) {
    fun createAndPublish(
        resourceKey: String,
        resourceData: Any?,
        operationType: OperationType,
    ): RequestFintEvent =
        ResourceRef.fromKey(resourceKey).let { ref ->
            resourceData
                .toFintResource(resourceKey)
                .toRequestFintEvent(ref, operationType)
                .also { event ->
                    requestFintEventProducer
                        .publish(event, ref.domain, ref.packageName)
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    eventStatusStore.storeRequest(event, statusRetentionDeadline(ref.name))
                }
        }

    fun createAndPublish(
        resourceKey: String,
        resourceData: Any?,
        validateOnly: Boolean = false,
    ): RequestFintEvent =
        validateOnly
            .toOperationType()
            .let { operationType -> createAndPublish(resourceKey, resourceData, operationType) }

    private fun Boolean.toOperationType() = if (this) OperationType.VALIDATE else OperationType.CREATE

    private fun statusRetentionDeadline(resourceName: String): Long =
        System.currentTimeMillis() + props.getLifeCycleConfig(resourceName).eviction.toMillis()

    private fun FintResource?.toRequestFintEvent(
        ref: ResourceRef,
        operationType: OperationType,
    ) = RequestFintEvent().apply {
        corrId = UUID.randomUUID().toString()
        orgId = config.orgId.value
        domainName = ref.domain
        packageName = ref.packageName
        this.resourceName = ref.name
        this.operationType = operationType
        created = clock.millis()
        timeToLive = created + props.getLifeCycleConfig(ref.name).ttl.toMillis()
        value = this@toRequestFintEvent.toJson()
    }

    private fun Any?.toFintResource(resourceName: String) =
        this?.let {
            resourceConverter.convertAndMapLinks(resourceName, it)
        }

    private fun FintResource?.toJson() = objectMapper.writeValueAsString(this)

    companion object {
        private const val SEND_TIMEOUT_SECONDS = 10L
    }
}
