package no.novari.fint.core.provider.datasync;

import lombok.extern.slf4j.Slf4j;
import no.fintlabs.adapter.models.sync.SyncPageMetadata;
import no.novari.fint.core.provider.kafka.EventProducerKafka;
import no.novari.fint.core.provider.kafka.EventTopicNames;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MetaDataKafkaProducer extends EventProducerKafka<SyncPageMetadata> {
    public MetaDataKafkaProducer(KafkaTemplate<String, Object> eventKafkaTemplate, EventTopicNames eventTopicNames) {
        super(eventKafkaTemplate, eventTopicNames);
    }
}
