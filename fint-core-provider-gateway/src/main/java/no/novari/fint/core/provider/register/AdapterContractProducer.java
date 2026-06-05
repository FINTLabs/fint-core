package no.novari.fint.core.provider.register;

import no.fintlabs.adapter.models.AdapterContract;
import no.novari.kafka.producing.ParameterizedTemplateFactory;
import no.novari.fint.core.provider.kafka.EventProducerKafka;
import org.springframework.stereotype.Service;

import static no.novari.fint.core.provider.kafka.topic.TopicNamesConstants.ADAPTER_REGISTER_EVENT_NAME;

@Service
public class AdapterContractProducer extends EventProducerKafka<AdapterContract> {
    public AdapterContractProducer(ParameterizedTemplateFactory parameterizedTemplateFactory) {
        super(parameterizedTemplateFactory, AdapterContract.class, ADAPTER_REGISTER_EVENT_NAME);
    }
}
