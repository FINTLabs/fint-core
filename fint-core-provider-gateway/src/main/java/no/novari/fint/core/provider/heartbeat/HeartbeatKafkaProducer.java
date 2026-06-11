package no.novari.fint.core.provider.heartbeat;

import no.fintlabs.adapter.models.AdapterHeartbeat;
import no.novari.fint.core.provider.kafka.EventProducerKafka;
import no.novari.fint.core.provider.kafka.EventTopicNames;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import static no.novari.fint.core.provider.kafka.topic.TopicNamesConstants.HEARTBEAT_EVENT_NAME;

@Service
public class HeartbeatKafkaProducer extends EventProducerKafka<AdapterHeartbeat> {
    public HeartbeatKafkaProducer(KafkaTemplate<String, Object> eventKafkaTemplate, EventTopicNames eventTopicNames) {
        super(eventKafkaTemplate, eventTopicNames, HEARTBEAT_EVENT_NAME);
    }
}
