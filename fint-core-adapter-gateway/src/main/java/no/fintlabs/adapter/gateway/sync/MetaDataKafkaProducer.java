package no.fintlabs.adapter.gateway.sync;

import lombok.extern.slf4j.Slf4j;
import no.fintlabs.adapter.models.sync.SyncPageMetadata;
import no.fintlabs.adapter.gateway.kafka.EventProducerKafka;
import no.fintlabs.adapter.gateway.kafka.EventPublisher;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MetaDataKafkaProducer extends EventProducerKafka<SyncPageMetadata> {
    public MetaDataKafkaProducer(EventPublisher eventPublisher) {
        super(eventPublisher);
    }
}
