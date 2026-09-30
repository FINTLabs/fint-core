package no.fintlabs.adapter.gateway.register;

import no.fintlabs.adapter.models.AdapterContract;
import no.fintlabs.adapter.gateway.kafka.EventProducerKafka;
import no.fintlabs.adapter.gateway.kafka.EventPublisher;
import org.springframework.stereotype.Service;

import static no.fintlabs.adapter.gateway.kafka.topic.TopicNamesConstants.ADAPTER_CONTRACT;

@Service
public class AdapterContractProducer extends EventProducerKafka<AdapterContract> {
    public AdapterContractProducer(EventPublisher eventPublisher) {
        super(eventPublisher, ADAPTER_CONTRACT);
    }
}
