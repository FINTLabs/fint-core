package no.fintlabs.adapter.gateway.register;

public record ResourceMetadata(
        String domainName,
        String packageName,
        String resourceName,
        boolean writeable
) {
}
