package no.novari.fint.core.shared.kafka

import org.apache.kafka.clients.producer.ProducerInterceptor
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.header.internals.RecordHeader
import java.nio.charset.StandardCharsets

/**
 * Stamps every outgoing record with the `origin.application.id` header, byte-identical to the
 * interceptor the fint-kafka library applied — downstream services may rely on the header.
 */
class OriginHeaderProducerInterceptor : ProducerInterceptor<String, Any> {
    private lateinit var originApplicationIdHeader: RecordHeader

    override fun onSend(record: ProducerRecord<String, Any>): ProducerRecord<String, Any> {
        record.headers().add(originApplicationIdHeader)
        return record
    }

    override fun onAcknowledgement(
        metadata: RecordMetadata?,
        exception: Exception?,
    ) {
    }

    override fun close() {
    }

    override fun configure(configs: Map<String, *>) {
        val applicationId = configs[ORIGIN_APPLICATION_ID_PRODUCER_CONFIG] as String?
        originApplicationIdHeader =
            RecordHeader(
                ORIGIN_APPLICATION_ID_RECORD_HEADER,
                applicationId?.toByteArray(StandardCharsets.UTF_8),
            )
    }

    companion object {
        const val ORIGIN_APPLICATION_ID_PRODUCER_CONFIG = "origin.application.id"
        const val ORIGIN_APPLICATION_ID_RECORD_HEADER = "origin.application.id"
    }
}
