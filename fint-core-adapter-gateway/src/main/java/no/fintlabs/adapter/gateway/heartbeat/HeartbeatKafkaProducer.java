package no.fintlabs.adapter.gateway.heartbeat;

import no.fintlabs.adapter.models.AdapterHeartbeat;
import no.fintlabs.adapter.gateway.kafka.EventProducerKafka;
import no.fintlabs.adapter.gateway.kafka.EventPublisher;
import org.springframework.stereotype.Service;

import static no.fintlabs.adapter.gateway.kafka.topic.TopicNamesConstants.ADAPTER_HEARTBEAT;

@Service
public class HeartbeatKafkaProducer extends EventProducerKafka<AdapterHeartbeat> {
    public HeartbeatKafkaProducer(EventPublisher eventPublisher) {
        super(eventPublisher, ADAPTER_HEARTBEAT);
    }
}
