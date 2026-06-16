package no.fintlabs.consumer.endpoints;

import no.fintlabs.consumer.config.ConsumerConfiguration;
import no.fintlabs.consumer.resource.context.model.FintResourceInformation;
import no.fintlabs.consumer.resource.context.ResourceContext;
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

        resourceContext.getResources().forEach(fintResourceInformation -> {
            HashMap<String, Object> collectionNameToEndpoints = new HashMap<>();
            String resourceName = fintResourceInformation.name();

            collectionNameToEndpoints.put("collectionUrl", constructUrl(resourceName));
            craftOneUrls(collectionNameToEndpoints, fintResourceInformation);
            collectionNameToEndpoints.put("cacheSizeUrl", constructCacheSizeUrl(resourceName));
            collectionNameToEndpoints.put("lastUpdatedUrl", constructLastUpdatedUrl(resourceName));

            resourceEndpoints.put(resourceName, collectionNameToEndpoints);
        });

        return resourceEndpoints;
    }

    private void craftOneUrls(HashMap<String, Object> collectionNameToEndpoints, FintResourceInformation fintResourceInformation) {
        ArrayList<String> endpoints = new ArrayList<>();
        fintResourceInformation.idFieldNames().forEach(idField ->
                endpoints.add(constructOneUrl(idField, fintResourceInformation.name()))
        );
        collectionNameToEndpoints.put("oneUrl", endpoints);
    }

    private String constructUrl(String resourceName) {
        return configuration.getComponentUrl() + '/' + resourceName;
    }

    private String constructOneUrl(String idField, String resourceName) {
        return "%s/%s/{id:.+}".formatted(
                constructUrl(resourceName),
                idField
        );
    }

    private String constructCacheSizeUrl(String resourceName) {
        return constructUrl(resourceName) + "/cache/size";
    }

    private String constructLastUpdatedUrl(String resourceName) {
        return constructUrl(resourceName) +"/last-updated";
    }

}
