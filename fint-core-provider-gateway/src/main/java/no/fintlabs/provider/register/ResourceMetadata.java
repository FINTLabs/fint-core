package no.fintlabs.provider.register;

public record ResourceMetadata(
        String domainName,
        String packageName,
        String resourceName,
        boolean writeable
) {
}
