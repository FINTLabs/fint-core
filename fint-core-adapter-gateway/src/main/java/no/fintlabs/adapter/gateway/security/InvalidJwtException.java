package no.fintlabs.adapter.gateway.security;

public class InvalidJwtException extends Exception{
    public InvalidJwtException(String message) {
        super(message);
    }
}
