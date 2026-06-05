package no.novari.fint.core.provider.exception;

public class InvalidJwtException extends Exception{
    public InvalidJwtException(String message) {
        super(message);
    }
}
