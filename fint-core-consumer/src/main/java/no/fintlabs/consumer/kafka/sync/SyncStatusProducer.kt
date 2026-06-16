package no.fintlabs.consumer.kafka.sync

import no.fintlabs.consumer.config.OrgId
import no.fintlabs.consumer.kafka.sync.model.SyncStatus
import no.novari.kafka.producing.ParameterizedProducerRecord
import no.novari.kafka.producing.ParameterizedTemplateFactory
import no.novari.kafka.topic.name.EventTopicNameParameters
import no.novari.kafka.topic.name.TopicNamePrefixParameters
import org.springframework.kafka.support.SendResult
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture

@Component
class SyncStatusProducer(
    parameterizedTemplateFactory: ParameterizedTemplateFactory,
) {
    private val eventProducer = parameterizedTemplateFactory.createTemplate(SyncStatus::class.java)
    private val eventTopic = createEventTopic()

    fun publish(syncStatus: SyncStatus): CompletableFuture<SendResult<String?, SyncStatus?>?>? {
        return eventProducer.send(
            ParameterizedProducerRecord
                .builder<SyncStatus>()
                .topicNameParameters(eventTopic)
                .value(syncStatus)
                .build(),
        )
    }

    private fun createEventTopic() =
        EventTopicNameParameters
            .builder()
            .topicNamePrefixParameters(
                TopicNamePrefixParameters
                    .stepBuilder()
                    .orgId(FINTLABS_ORG_ID.asTopicSegment)
                    .domainContextApplicationDefault()
                    .build(),
            ).eventName("sync-status")
            .build()

    companion object {
        private val FINTLABS_ORG_ID = OrgId.from("fintlabs.no")
    }
}
