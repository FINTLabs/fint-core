package no.fintlabs.adapter.gateway.event;

public class InvalidEventNameException extends RuntimeException {
    public InvalidEventNameException(String message) {
        super(message);
    }
}
