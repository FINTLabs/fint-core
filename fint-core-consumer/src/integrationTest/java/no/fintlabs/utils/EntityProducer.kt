package no.fintlabs.utils

import no.fintlabs.adapter.models.sync.SyncType
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.kafka.KafkaConstants.LAST_MODIFIED
import no.fintlabs.consumer.kafka.KafkaConstants.RESOURCE_NAME
import no.fintlabs.consumer.kafka.KafkaConstants.SYNC_CORRELATION_ID
import no.fintlabs.consumer.kafka.KafkaConstants.SYNC_TOTAL_SIZE
import no.fintlabs.consumer.kafka.KafkaConstants.SYNC_TYPE
import no.fintlabs.consumer.kafka.entity.ENTITY_KEY_DELIMITER
import no.novari.kafka.producing.ParameterizedProducerRecord
import no.novari.kafka.producing.ParameterizedTemplateFactory
import no.novari.kafka.topic.name.EntityTopicNameParameters
import no.novari.kafka.topic.name.TopicNamePrefixParameters
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders
import org.springframework.boot.test.context.TestComponent
import org.springframework.kafka.support.SendResult
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture

@TestComponent
class EntityProducer(
    parameterizedTemplateFactory: ParameterizedTemplateFactory,
    private val consumerConfig: ConsumerConfiguration,
) {
    private val producer = parameterizedTemplateFactory.createTemplate(Any::class.java)

    fun publish(
        resourceName: String,
        resource: Any?,
        resourceId: String,
        syncType: SyncType,
        syncCorrId: String,
        syncTotalSize: Long,
        timestamp: Long = System.currentTimeMillis(),
        domainName: String = consumerConfig.domain,
        packageName: String = consumerConfig.packageName,
    ): CompletableFuture<SendResult<String, in Any?>> =
        producer.send(
            ParameterizedProducerRecord
                .builder<Any>()
                .key("$resourceName$ENTITY_KEY_DELIMITER$resourceId")
                .headers(createSyncHeaders(resourceName, syncType, syncCorrId, syncTotalSize, timestamp))
                .topicNameParameters(createEntityTopicNameParameters(domainName, packageName))
                .value(resource)
                .build(),
        )

    fun publishToLegacyResourceTopic(
        resourceName: String,
        resource: Any?,
        resourceId: String,
        syncType: SyncType,
        syncCorrId: String,
        syncTotalSize: Long,
        timestamp: Long = System.currentTimeMillis(),
        includeResourceNameHeader: Boolean = true,
        domainName: String = consumerConfig.domain,
        packageName: String = consumerConfig.packageName,
    ): CompletableFuture<SendResult<String, in Any?>> =
        producer.send(
            ParameterizedProducerRecord
                .builder<Any>()
                .key("$resourceName$ENTITY_KEY_DELIMITER$resourceId")
                .headers(
                    createSyncHeaders(
                        if (includeResourceNameHeader) resourceName else null,
                        syncType,
                        syncCorrId,
                        syncTotalSize,
                        timestamp,
                    ),
                ).topicNameParameters(createEntityTopicNameParameters(domainName, "$packageName-$resourceName"))
                .value(resource)
                .build(),
        )

    private fun createSyncHeaders(
        resourceName: String?,
        syncType: SyncType,
        syncCorrId: String,
        syncTotalSize: Long,
        timestamp: Long,
    ) = RecordHeaders().apply {
        if (resourceName != null) add(RecordHeader(RESOURCE_NAME, resourceName.toByteArray()))
        add(SYNC_TYPE, byteArrayOf(syncType.ordinal.toByte()))
        add(SYNC_CORRELATION_ID, syncCorrId.toByteArray())
        add(SYNC_TOTAL_SIZE, ByteBuffer.allocate(Long.SIZE_BYTES).putLong(syncTotalSize).array())
        add(LAST_MODIFIED, ByteBuffer.allocate(8).putLong(timestamp).array())
    }

    private fun createEntityTopicNameParameters(
        domainName: String,
        packageName: String,
    ) = EntityTopicNameParameters
        .builder()
        .topicNamePrefixParameters(
            TopicNamePrefixParameters
                .stepBuilder()
                .orgId(consumerConfig.orgId.asTopicSegment)
                .domainContextApplicationDefault()
                .build(),
        ).resourceName("$domainName-$packageName")
        .build()
}
