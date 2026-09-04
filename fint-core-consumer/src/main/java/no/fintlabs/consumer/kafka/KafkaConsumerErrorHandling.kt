package no.fintlabs.consumer.kafka

import org.slf4j.Logger
import org.springframework.kafka.listener.CommonErrorHandler
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

object KafkaConsumerErrorHandling {
    @JvmStatic
    fun loggingErrorHandler(
        log: Logger,
        consumerName: String,
    ): CommonErrorHandler =
        DefaultErrorHandler(
            { consumerRecord, exception ->
                log.error(
                    "Kafka consumer {} failed topic={} partition={} offset={} key={} value={}",
                    consumerName,
                    consumerRecord.topic(),
                    consumerRecord.partition(),
                    consumerRecord.offset(),
                    consumerRecord.key(),
                    consumerRecord.value(),
                    exception,
                )
            },
            FixedBackOff(0L, 0L),
        )
}
