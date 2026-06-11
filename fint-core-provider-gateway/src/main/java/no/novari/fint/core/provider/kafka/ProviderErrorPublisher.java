package no.novari.fint.core.provider.kafka;

import lombok.extern.slf4j.Slf4j;
import no.novari.fint.core.shared.kafka.KafkaTopics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

import static no.novari.fint.core.provider.kafka.topic.TopicNamesConstants.PROVIDER_ERROR_EVENT_NAME;

@Slf4j
@Service
public class ProviderErrorPublisher {

    private static final Duration RETENTION_TIME = Duration.ofDays(7);
    private static final int PARTITIONS = 1;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public ProviderErrorPublisher(
            KafkaTemplate<String, Object> eventKafkaTemplate,
            EventTopicNames eventTopicNames,
            KafkaAdmin coreKafkaAdmin,
            @Value("${novari.kafka.default-replicas:2}") int defaultReplicas
    ) {
        this.kafkaTemplate = eventKafkaTemplate;
        this.topic = eventTopicNames.event(PROVIDER_ERROR_EVENT_NAME);
        coreKafkaAdmin.createOrModifyTopics(
                KafkaTopics.INSTANCE.eventTopic(topic, PARTITIONS, defaultReplicas, RETENTION_TIME)
        );
    }

    public void publish(ProviderError providerError) {
        log.info("Publishing provider-error to Kafka!");
        kafkaTemplate.send(topic, UUID.randomUUID().toString(), providerError);
    }
}
