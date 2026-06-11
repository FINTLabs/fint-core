package no.novari.fint.core.provider.datasync.ingest

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.config.TopicConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
import org.springframework.kafka.support.serializer.JsonDeserializer
import org.springframework.kafka.support.serializer.JsonSerializer
import org.springframework.util.backoff.FixedBackOff

@Configuration
class SyncIngestKafkaConfig(
    private val kafkaProperties: KafkaProperties,
    private val properties: SyncIngestProperties,
    private val topics: SyncIngestTopics,
    @Value("\${fint.kafka.enable-ssl:false}") private val enableSsl: Boolean,
) {
    @Bean
    fun syncIngestTopic(): NewTopic =
        TopicBuilder
            .name(topics.topic)
            .partitions(properties.partitions)
            .config(TopicConfig.RETENTION_MS_CONFIG, properties.topicRetention.toMillis().toString())
            .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
            .build()

    @Bean
    fun syncIngestDltTopic(): NewTopic =
        TopicBuilder
            .name(topics.dlt)
            .partitions(1)
            .config(TopicConfig.RETENTION_MS_CONFIG, properties.dltRetention.toMillis().toString())
            .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
            .build()

    @Bean
    fun syncIngestProducerFactory(objectMapper: ObjectMapper): ProducerFactory<String, SyncIngestRecord> {
        val config = HashMap<String, Any>(kafkaProperties.buildProducerProperties(null))
        config.putAll(securityProperties())
        config[ProducerConfig.ACKS_CONFIG] = "all"
        config[ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG] = true
        config[ProducerConfig.LINGER_MS_CONFIG] = 5
        config[ProducerConfig.BATCH_SIZE_CONFIG] = 131072
        config[ProducerConfig.COMPRESSION_TYPE_CONFIG] = "lz4"
        val valueSerializer = JsonSerializer<SyncIngestRecord>(objectMapper).apply { setAddTypeInfo(false) }
        return DefaultKafkaProducerFactory(config, StringSerializer(), valueSerializer)
    }

    @Bean
    fun syncIngestKafkaTemplate(syncIngestProducerFactory: ProducerFactory<String, SyncIngestRecord>): KafkaTemplate<String, SyncIngestRecord> =
        KafkaTemplate(syncIngestProducerFactory)

    @Bean
    fun syncIngestConsumerFactory(objectMapper: ObjectMapper): ConsumerFactory<String, SyncIngestRecord> {
        val config = HashMap<String, Any>(kafkaProperties.buildConsumerProperties(null))
        config.putAll(securityProperties())
        config[ConsumerConfig.MAX_POLL_RECORDS_CONFIG] = properties.maxPollRecords
        config[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
        config[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] = false
        val valueDeserializer = ErrorHandlingDeserializer(JsonDeserializer(SyncIngestRecord::class.java, objectMapper, false))
        return DefaultKafkaConsumerFactory(config, StringDeserializer(), valueDeserializer)
    }

    @Bean
    fun syncIngestListenerContainerFactory(
        syncIngestConsumerFactory: ConsumerFactory<String, SyncIngestRecord>,
    ): ConcurrentKafkaListenerContainerFactory<String, SyncIngestRecord> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, SyncIngestRecord>()
        factory.consumerFactory = syncIngestConsumerFactory
        factory.isBatchListener = true
        factory.containerProperties.idleBetweenPolls = properties.idleBetweenPolls.toMillis()
        factory.setCommonErrorHandler(DefaultErrorHandler(FixedBackOff(RETRY_BACKOFF_MS, FixedBackOff.UNLIMITED_ATTEMPTS)))
        return factory
    }

    private fun securityProperties(): Map<String, Any> {
        if (!enableSsl) return emptyMap()
        val ssl = kafkaProperties.ssl
        return mapOf(
            "security.protocol" to ssl.protocol,
            "ssl.truststore.location" to ssl.trustStoreLocation.file.absolutePath,
            "ssl.truststore.password" to ssl.trustStorePassword,
            "ssl.keystore.type" to ssl.keyStoreType,
            "ssl.keystore.location" to ssl.keyStoreLocation.file.absolutePath,
            "ssl.keystore.password" to ssl.keyStorePassword,
            "ssl.key.password" to ssl.keyPassword,
        )
    }

    companion object {
        private const val RETRY_BACKOFF_MS = 5_000L
    }
}
