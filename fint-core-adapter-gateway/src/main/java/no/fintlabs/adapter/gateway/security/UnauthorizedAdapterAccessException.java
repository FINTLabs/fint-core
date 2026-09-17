package no.fintlabs.adapter.gateway.security;

public class UnauthorizedAdapterAccessException extends RuntimeException {

    public UnauthorizedAdapterAccessException(String message) {
        super(message);
    }

}
