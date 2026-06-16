package no.fintlabs.consumer.exception.resource;

import lombok.Getter;

public class ResourceNotWriteableException extends RuntimeException {
    @Getter
    private final String resourceName;

    public ResourceNotWriteableException(String resourceName) {
        super("%s is not writeable, therefore it does not have access to writeable actions.".formatted(resourceName));
        this.resourceName = resourceName;
    }
}
