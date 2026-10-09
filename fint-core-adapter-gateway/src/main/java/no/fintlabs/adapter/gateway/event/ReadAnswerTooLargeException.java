package no.fintlabs.adapter.gateway.event;

public class ReadAnswerTooLargeException extends RuntimeException {

    public ReadAnswerTooLargeException(long bytes, long maxBytes) {
        super("The read answer is " + bytes + " bytes, the most allowed is " + maxBytes + ". Answer rejected with a reason instead.");
    }
}
