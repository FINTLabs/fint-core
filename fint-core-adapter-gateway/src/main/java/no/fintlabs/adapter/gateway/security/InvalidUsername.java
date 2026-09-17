package no.fintlabs.adapter.gateway.security;

import lombok.Getter;

@Getter
public class InvalidUsername extends RuntimeException {
    private final String message;

    public InvalidUsername(String message) {
        this.message = message;
    }
}
