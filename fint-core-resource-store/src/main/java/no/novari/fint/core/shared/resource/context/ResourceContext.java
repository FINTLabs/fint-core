package no.novari.fint.core.shared.resource.context;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import no.novari.fint.core.shared.resource.ResourceRef;
import no.novari.fint.core.shared.resource.context.model.FintResourceInformation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Read-only view over the per-component resource metadata, keyed by the qualified
 * {@link ResourceRef#getKey()} (`domain_package_resource`) so distinct components that share a
 * resource name stay separate.
 */
@Slf4j
@Getter
@Configuration
public class ResourceContext {

    private final ResourceContextCache contextCache;
    private final Set<String> writeableResources;

    public ResourceContext(@Value("${fint.consumer.writeable:test}") String writeable, ResourceContextCache contextCache) {
        this.writeableResources = new HashSet<>(Arrays.asList(writeable.split(",")));
        this.contextCache = contextCache;
    }

    public boolean isFintReference(String key, String relationName) {
        return contextCache.resourceMap.get(key)
                .referenceNames()
                .contains(relationName.toLowerCase());
    }

    public boolean isNotFintReference(String key, String relationName) {
        return !isFintReference(key, relationName);
    }

    public Set<String> getResourceNames() {
        return contextCache.resourceMap.keySet();
    }

    public Collection<FintResourceInformation> getResources() {
        return contextCache.resourceMap.values();
    }

    public FintResourceInformation getResource(String key) {
        return contextCache.resourceMap.get(key);
    }

    public boolean resourceHasIdField(String key, String idField) {
        return contextCache.resourceMap.get(key)
                .idFieldNames()
                .contains(idField.toLowerCase());
    }

    public boolean resourceIsWriteable(String key) {
        FintResourceInformation resourceInformation = getResourceInformation(key);
        if (resourceInformation == null) {
            return false;
        }
        return resourceInformation.isWriteable() || writeableResources.contains(ResourceRef.fromKey(key).getName());
    }

    public String getRelationUri(String key, String relationName) {
        return contextCache.resourceMap.get(key)
                .relations()
                .get(relationName.toLowerCase())
                .uri();
    }

    public boolean relationExists(String key, String relationName) {
        FintResourceInformation resourceInformation = getResourceInformation(key);
        if (resourceInformation == null) {
            return false;
        }
        return resourceInformation.relations().containsKey(relationName.toLowerCase());
    }

    private FintResourceInformation getResourceInformation(String key) {
        return contextCache.resourceMap.get(key);
    }

}
