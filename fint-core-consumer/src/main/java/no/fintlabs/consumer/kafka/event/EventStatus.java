package no.fintlabs.consumer.kafka.event;

public enum EventStatus {
    NOT_FOUND, // Request & Response is null
    NO_RESPONSE, // Response is null
    OK, // Response is found
    FAILED, // Response has status failed
    REJECTED // Response has status rejected
}
