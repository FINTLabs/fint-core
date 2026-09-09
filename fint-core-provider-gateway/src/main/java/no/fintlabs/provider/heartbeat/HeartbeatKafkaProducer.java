package no.fintlabs.provider.heartbeat;

import no.fintlabs.adapter.models.AdapterHeartbeat;
import no.fintlabs.provider.kafka.EventProducerKafka;
import no.fintlabs.provider.kafka.EventPublisher;
import org.springframework.stereotype.Service;

import static no.fintlabs.provider.kafka.topic.TopicNamesConstants.ADAPTER_HEARTBEAT;

@Service
public class HeartbeatKafkaProducer extends EventProducerKafka<AdapterHeartbeat> {
    public HeartbeatKafkaProducer(EventPublisher eventPublisher) {
        super(eventPublisher, ADAPTER_HEARTBEAT);
    }
}
