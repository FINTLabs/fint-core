package no.fintlabs.provider.kafka;

public abstract class EventProducerKafka<T> {

    private final EventPublisher eventPublisher;
    private String eventName;

    public EventProducerKafka(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public EventProducerKafka(EventPublisher eventPublisher, String eventName) {
        this(eventPublisher);
        this.eventName = eventName;
    }

    public void send(T value, String eventName) {
        eventPublisher.publish(eventName, value);
    }

    public void send(T value) {
        send(value, eventName);
    }

}
