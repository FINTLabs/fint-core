package no.novari.fint.core.provider.heartbeat;

import no.fintlabs.adapter.models.AdapterHeartbeat;
import no.novari.kafka.producing.ParameterizedTemplateFactory;
import no.novari.fint.core.provider.kafka.EventProducerKafka;
import org.springframework.stereotype.Service;

import static no.novari.fint.core.provider.kafka.topic.TopicNamesConstants.HEARTBEAT_EVENT_NAME;

@Service
public class HeartbeatKafkaProducer extends EventProducerKafka<AdapterHeartbeat> {
    public HeartbeatKafkaProducer(ParameterizedTemplateFactory parameterizedTemplateFactory) {
        super(parameterizedTemplateFactory, AdapterHeartbeat.class, HEARTBEAT_EVENT_NAME);
    }
}
