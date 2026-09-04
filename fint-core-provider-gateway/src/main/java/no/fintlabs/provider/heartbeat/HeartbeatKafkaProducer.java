package no.fintlabs.provider.heartbeat;

import no.fintlabs.adapter.models.AdapterHeartbeat;
import no.fintlabs.provider.kafka.EventProducerKafka;
import no.fintlabs.provider.kafka.EventPublisher;
import org.springframework.stereotype.Service;

import static no.fintlabs.provider.kafka.topic.TopicNamesConstants.HEARTBEAT_EVENT_NAME;

@Service
public class HeartbeatKafkaProducer extends EventProducerKafka<AdapterHeartbeat> {
    public HeartbeatKafkaProducer(EventPublisher eventPublisher) {
        super(eventPublisher, HEARTBEAT_EVENT_NAME);
    }
}
