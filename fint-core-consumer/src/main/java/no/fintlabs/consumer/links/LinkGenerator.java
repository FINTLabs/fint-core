package no.fintlabs.consumer.links;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import no.fintlabs.consumer.config.ConsumerConfiguration;
import no.fintlabs.consumer.resource.context.ResourceContext;
import no.novari.fint.model.resource.FintResource;
import no.novari.fint.model.resource.Link;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

@Slf4j
@Component
@RequiredArgsConstructor
public class LinkGenerator {

    private final ConsumerConfiguration configuration;
    private final ResourceContext resourceContext;

    public void resetSelfLinks(String resourceName, FintResource resource) {
        resource.getLinks().put("self", new ArrayList<>());

        String[] selfHrefs = createSelfHrefs(resourceName, resource);
        if (selfHrefs.length < 1) {
            log.error("Resource has no selfLinks: {} - {}", resourceName, resource);
        }

        for (String selfHref : selfHrefs) {
            resource.addSelf(Link.with(selfHref));
        }
    }

    private String[] createSelfHrefs(String resourceName, FintResource resource) {
        return resource.getIdentifikators().entrySet().stream()
            .filter(entry -> entry.getValue() != null)
            .filter(entry -> entry.getValue().getIdentifikatorverdi() != null)
            .map(entry ->
                configuration.getComponentUrl() + '/' +
                    resourceName + '/' +
                    entry.getKey().toLowerCase() + '/' +
                    entry.getValue().getIdentifikatorverdi())
            .toArray(String[]::new);
    }

    public String createRelationLink(String resourceName, String relationName, String href) {
        return configuration.getBaseUrl() + '/' +
            resourceContext.getRelationUri(resourceName, relationName) + '/' +
            getLastTwoSegments(href);
    }

    private String getLastTwoSegments(String href) {
        String[] split = href.split("/");

        if (split.length < 2) {
            log.error("Link href is invalid: {}", href);
            return href;
        }

        return split[split.length - 2].toLowerCase() + '/' + split[split.length - 1];
    }

}
