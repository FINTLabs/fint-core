package no.novari.fint.core.shared.link;

import lombok.RequiredArgsConstructor;
import no.novari.fint.core.shared.resource.FintResources;
import no.novari.fint.model.resource.Link;
import no.novari.fint.core.shared.resource.ResourceRef;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
public class LinkPaginator {

    private final LinkConfiguration configuration;

    public void addPagination(String resourceName, FintResources resources, int offset, int size, int totalItems) {
        String selfUrl = selfUrl(resourceName);
        UriComponentsBuilder baseBuilder = UriComponentsBuilder.fromUriString(selfUrl);

        if (size > 0) {
            addLink(resources, "self", baseBuilder, offset, size);

            if (offset > 0) {
                int prevOffset = Math.max(0, offset - size);
                addLink(resources, "prev", baseBuilder, prevOffset, size);
            }

            if (offset + size < totalItems) {
                int nextOffset = offset + size;
                addLink(resources, "next", baseBuilder, nextOffset, size);
            }
        } else {
            resources.addSelf(Link.with(selfUrl));
        }

        resources.setOffset(offset);
        resources.setTotalItems(totalItems);
    }

    private void addLink(FintResources resources, String rel, UriComponentsBuilder builder, int offset, int size) {
        String uri = builder.replaceQueryParam("offset", offset)
                .replaceQueryParam("size", size)
                .toUriString();

        switch (rel) {
            case "self":
                resources.addSelf(Link.with(uri));
                break;
            case "prev":
                resources.addPrev(Link.with(uri));
                break;
            case "next":
                resources.addNext(Link.with(uri));
                break;
        }
    }

    private String selfUrl(String resourceKey) {
        ResourceRef ref = ResourceRef.fromKey(resourceKey);
        return configuration.getBaseUrl() + "/" + ref.getComponentPath() + "/" + ref.getName();
    }

}
