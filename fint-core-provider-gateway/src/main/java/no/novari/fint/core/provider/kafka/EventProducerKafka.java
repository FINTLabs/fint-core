package no.novari.fint.core.provider.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;

@Slf4j
public abstract class EventProducerKafka<T> {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final EventTopicNames eventTopicNames;
    private String eventName;

    public EventProducerKafka(KafkaTemplate<String, Object> kafkaTemplate, EventTopicNames eventTopicNames) {
        this.kafkaTemplate = kafkaTemplate;
        this.eventTopicNames = eventTopicNames;
    }

    public EventProducerKafka(KafkaTemplate<String, Object> kafkaTemplate, EventTopicNames eventTopicNames, String eventName) {
        this(kafkaTemplate, eventTopicNames);
        this.eventName = eventName;
    }

    public void send(T value, String eventName) {
        kafkaTemplate.send(eventTopicNames.event(eventName), value);
    }

    public void send(T value) {
        send(value, eventName);
    }
}
