package no.fintlabs.provider.event;

public class InvalidEventNameException extends RuntimeException {
    public InvalidEventNameException(String message) {
        super(message);
    }
}
