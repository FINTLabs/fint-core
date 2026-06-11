package no.novari.fint.core.provider.event.response

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.novari.fint.core.provider.kafka.EventTopicNames
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service

@Service
class ResponseFintEventProducer(
    private val eventKafkaTemplate: KafkaTemplate<String, Any>,
    private val eventTopicNames: EventTopicNames,
) {

    fun sendEvent(responseFintEvent: ResponseFintEvent, requestFintEvent: RequestFintEvent) {
        eventKafkaTemplate.send(requestFintEvent.toTopicName(), responseFintEvent)
    }

    private fun RequestFintEvent.toTopicName() =
        eventTopicNames.event("$domainName-$packageName-response", orgId.replace(".", "-"))
}
