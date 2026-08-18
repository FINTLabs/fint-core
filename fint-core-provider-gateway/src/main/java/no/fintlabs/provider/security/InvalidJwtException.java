package no.fintlabs.provider.security;

public class InvalidJwtException extends Exception{
    public InvalidJwtException(String message) {
        super(message);
    }
}
