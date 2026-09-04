package no.fintlabs.provider.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

import static no.fintlabs.provider.kafka.topic.TopicNamesConstants.PROVIDER_ERROR_EVENT_NAME;

@Slf4j
@Service
public class ProviderErrorPublisher {

    private final EventPublisher eventPublisher;

    public ProviderErrorPublisher(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void publish(ProviderError providerError) {
        log.info("Publishing provider-error to Kafka!");
        eventPublisher.publish(PROVIDER_ERROR_EVENT_NAME, UUID.randomUUID().toString(), providerError);
    }
}
