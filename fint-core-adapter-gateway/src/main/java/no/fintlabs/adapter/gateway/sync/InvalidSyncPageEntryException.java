package no.fintlabs.adapter.gateway.sync;

public class InvalidSyncPageEntryException extends RuntimeException {
    public InvalidSyncPageEntryException(String message) {
        super(message);
    }
}
