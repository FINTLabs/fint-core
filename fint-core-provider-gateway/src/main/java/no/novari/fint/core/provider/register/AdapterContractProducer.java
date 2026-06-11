package no.novari.fint.core.provider.register;

import no.fintlabs.adapter.models.AdapterContract;
import no.novari.fint.core.provider.kafka.EventProducerKafka;
import no.novari.fint.core.provider.kafka.EventTopicNames;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import static no.novari.fint.core.provider.kafka.topic.TopicNamesConstants.ADAPTER_REGISTER_EVENT_NAME;

@Service
public class AdapterContractProducer extends EventProducerKafka<AdapterContract> {
    public AdapterContractProducer(KafkaTemplate<String, Object> eventKafkaTemplate, EventTopicNames eventTopicNames) {
        super(eventKafkaTemplate, eventTopicNames, ADAPTER_REGISTER_EVENT_NAME);
    }
}
