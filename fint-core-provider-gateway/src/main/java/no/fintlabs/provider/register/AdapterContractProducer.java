package no.fintlabs.provider.register;

import no.fintlabs.adapter.models.AdapterContract;
import no.fintlabs.provider.kafka.EventProducerKafka;
import no.fintlabs.provider.kafka.EventPublisher;
import org.springframework.stereotype.Service;

import static no.fintlabs.provider.kafka.topic.TopicNamesConstants.ADAPTER_REGISTER_EVENT_NAME;

@Service
public class AdapterContractProducer extends EventProducerKafka<AdapterContract> {
    public AdapterContractProducer(EventPublisher eventPublisher) {
        super(eventPublisher, ADAPTER_REGISTER_EVENT_NAME);
    }
}
