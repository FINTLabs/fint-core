package no.novari.fint.core.provider.security.resource;

public record ResourceMetadata(
        String domainName,
        String packageName,
        String resourceName,
        boolean writeable
) {
}
