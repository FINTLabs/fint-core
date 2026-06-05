package no.novari.fint.core.consumer.endpoints;

import no.novari.fint.core.consumer.config.ConsumerConfiguration;
import no.novari.fint.core.shared.resource.ResourceRef;
import no.novari.fint.core.shared.resource.context.model.FintResourceInformation;
import no.novari.fint.core.shared.resource.context.ResourceContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@Service
public class EndpointsCache {

    private final ResourceContext resourceContext;
    private final ConsumerConfiguration configuration;
    private final Map<String, Map<String, Object>> resourceEndpoints;

    public EndpointsCache(ResourceContext resourceContext, ConsumerConfiguration configuration) {
        this.resourceContext = resourceContext;
        this.configuration = configuration;
        this.resourceEndpoints = createEndPoints();
    }

    public Map<String, Map<String, Object>> getEndpoints() {
        return resourceEndpoints;
    }

    private Map<String, Map<String, Object>> createEndPoints() {
        HashMap<String, Map<String, Object>> resourceEndpoints = new HashMap<>();

        resourceContext.getResourceNames().forEach(key -> {
            FintResourceInformation info = resourceContext.getResource(key);
            String url = constructUrl(key);

            HashMap<String, Object> collectionNameToEndpoints = new HashMap<>();
            collectionNameToEndpoints.put("collectionUrl", url);
            craftOneUrls(collectionNameToEndpoints, url, info);
            collectionNameToEndpoints.put("cacheSizeUrl", url + "/cache/size");
            collectionNameToEndpoints.put("lastUpdatedUrl", url + "/last-updated");

            resourceEndpoints.put(key, collectionNameToEndpoints);
        });

        return resourceEndpoints;
    }

    private void craftOneUrls(HashMap<String, Object> collectionNameToEndpoints, String url, FintResourceInformation info) {
        ArrayList<String> endpoints = new ArrayList<>();
        info.idFieldNames().forEach(idField ->
                endpoints.add("%s/%s/{id:.+}".formatted(url, idField))
        );
        collectionNameToEndpoints.put("oneUrl", endpoints);
    }

    private String constructUrl(String key) {
        ResourceRef ref = ResourceRef.fromKey(key);
        return configuration.getBaseUrl() + '/' + ref.getComponentPath() + '/' + ref.getName();
    }

}
