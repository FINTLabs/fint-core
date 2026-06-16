package no.fintlabs.consumer.links;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import no.fintlabs.consumer.links.nested.NestedLinkService;
import no.fintlabs.consumer.resource.context.ResourceContext;
import no.fintlabs.model.resource.FintResources;
import no.novari.fint.model.resource.FintResource;
import no.novari.fint.model.resource.Link;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class LinkService {

    private final LinkPaginator linkPaginator;
    private final LinkGenerator linkGenerator;
    private final NestedLinkService nestedLinkService;
    private final ResourceContext resourceContext;

    public FintResources toResources(String resourceName, List<FintResource> resources, int offset, int size, int totalItems) {
        Objects.requireNonNull(resources, "resources is required");

        FintResources fintResources = new FintResources(resources);
        linkPaginator.addPagination(resourceName, fintResources, offset, size, totalItems);
        return fintResources;
    }

    public void mapLinks(String resourceName, FintResource resource) {
        resource.getLinks().remove("self");

        resource.getLinks().entrySet().removeIf(entry -> {
            processLinkList(resourceName, entry.getKey(), entry.getValue());
            removeDuplicates(entry.getValue());
            return entry.getValue().isEmpty();
        });

        linkGenerator.resetSelfLinks(resourceName, resource);
        nestedLinkService.mapNestedLinks(resource);
    }

    private void removeDuplicates(List<Link> links) {
        Set<String> seen = new HashSet<>();
        links.removeIf(link -> !seen.add(link.getHref()));
    }

    private void processLinkList(String resourceName, String relationName, List<Link> links) {
        links.removeIf(link -> {
            if (link == null || link.getHref() == null)
                return true;

            link.setVerdi(processHref(resourceName, relationName, link.getHref()));
            return false;
        });
    }

    private String processHref(String resourceName, String relationName, String href) {
        if (href == null)
            return null;

        if (linkShouldBeProcessed(resourceName, relationName, href)) {
            return linkGenerator.createRelationLink(resourceName, relationName, href);
        } else return href;
    }

    private boolean linkShouldBeProcessed(String resourceName, String relationName, String href) {
        return resourceContext.relationExists(resourceName, relationName)
            && resourceContext.isNotFintReference(resourceName, relationName)
            && (isTemplated(href) || isRelative(href) || isNotAbsoluteUrl(href));
    }

    private boolean isNotAbsoluteUrl(String href) {
        return !href.startsWith("http");
    }

    private boolean isTemplated(String href) {
        return href.startsWith("${") && href.contains("}");
    }

    private boolean isRelative(String href) {
        return href.startsWith("/");
    }

}
