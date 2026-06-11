package no.novari.fint.core.provider.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.novari.fint.core.provider.config.KafkaProducerProperties
import no.novari.fint.core.shared.kafka.OriginHeaderProducerInterceptor
import no.novari.fint.core.shared.kafka.kafkaSecurityProperties
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
import org.springframework.kafka.support.serializer.JsonDeserializer
import org.springframework.kafka.support.serializer.JsonSerializer

@Configuration
@EnableConfigurationProperties(KafkaProducerProperties::class)
class CoreKafkaConfig(
    private val kafkaProperties: KafkaProperties,
    private val producerProperties: KafkaProducerProperties,
    @Value("\${fint.kafka.enable-ssl:false}") private val enableSsl: Boolean,
    @Value("\${novari.kafka.application-id:provider}") private val applicationId: String,
) {
    @Bean
    fun eventKafkaTemplate(objectMapper: ObjectMapper): KafkaTemplate<String, Any> {
        val config = HashMap<String, Any>(kafkaProperties.buildProducerProperties(null))
        config.putAll(kafkaSecurityProperties(kafkaProperties, enableSsl))
        config[ProducerConfig.INTERCEPTOR_CLASSES_CONFIG] = OriginHeaderProducerInterceptor::class.java.name
        config[OriginHeaderProducerInterceptor.ORIGIN_APPLICATION_ID_PRODUCER_CONFIG] = applicationId
        producerProperties.linger?.let { config[ProducerConfig.LINGER_MS_CONFIG] = it.toMillis().toInt() }
        producerProperties.batchSize?.let { config[ProducerConfig.BATCH_SIZE_CONFIG] = it.toBytes().toInt() }
        return KafkaTemplate(DefaultKafkaProducerFactory(config, StringSerializer(), JsonSerializer(objectMapper)))
    }

    @Bean
    fun requestFintEventConsumerFactory(objectMapper: ObjectMapper): ConsumerFactory<String, RequestFintEvent> {
        val config = HashMap<String, Any>(kafkaProperties.buildConsumerProperties(null))
        config.putAll(kafkaSecurityProperties(kafkaProperties, enableSsl))
        config[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
        config[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] = false
        val valueDeserializer = ErrorHandlingDeserializer(JsonDeserializer(RequestFintEvent::class.java, objectMapper, false))
        return DefaultKafkaConsumerFactory(config, StringDeserializer(), valueDeserializer)
    }

    @Bean
    fun coreKafkaAdmin(): KafkaAdmin {
        val config = HashMap<String, Any>()
        config[AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG] = kafkaProperties.bootstrapServers
        config.putAll(kafkaSecurityProperties(kafkaProperties, enableSsl))
        return KafkaAdmin(config).apply { setModifyTopicConfigs(true) }
    }
}
