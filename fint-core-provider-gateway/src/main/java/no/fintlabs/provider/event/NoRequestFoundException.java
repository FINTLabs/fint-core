package no.fintlabs.provider.event;

public class NoRequestFoundException extends Exception{

    public NoRequestFoundException(String corrId) {
        super("Could not found request with corr-id: " + corrId);
    }
}
