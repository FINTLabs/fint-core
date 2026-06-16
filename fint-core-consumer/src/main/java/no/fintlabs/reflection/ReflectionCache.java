package no.fintlabs.reflection;

import no.novari.fint.model.FintAbstractObject;
import no.novari.fint.model.FintModelObject;
import no.novari.fint.model.FintReference;
import no.novari.fint.model.resource.FintResource;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

@Component
public class ReflectionCache {

    private final Map<String, Class<? extends FintModelObject>> metaSubtypeMap = new HashMap<>();
    private final Map<String, Class<? extends FintResource>> resourceSubtypeMap = new HashMap<>();
    private final Map<String, Class<? extends FintAbstractObject>> abstractSubtypeMap = new HashMap<>();
    private final Map<String, Class<? extends FintReference>> referenceSubtypeMap = new HashMap<>();

    public void addMetaSubtype(String packageName, Class<? extends FintModelObject> metaSubtype) {
        metaSubtypeMap.put(packageName, metaSubtype);
    }

    public Class<? extends FintModelObject> getMetaSubtype(String packageName) {
        return metaSubtypeMap.get(packageName);
    }

    public void addResourceSubtype(String packageName, Class<? extends FintResource> resourceSubtype) {
        resourceSubtypeMap.put(
                packageName.replaceFirst(".resource", "")
                        .replaceFirst("Resource", ""),
                resourceSubtype
        );
    }

    public Class<? extends FintResource> getResourceSubtype(String packageName) {
        return resourceSubtypeMap.get(packageName);
    }

    public void addAbstractSubtype(String packageName, Class<? extends FintAbstractObject> abstractSubtype) {
        if (!metaSubtypeMap.containsKey(packageName))
            abstractSubtypeMap.put(packageName, abstractSubtype);
    }

    public boolean isAnAbstractObject(String packageName) {
        return abstractSubtypeMap.containsKey(packageName);
    }

    public void addReferenceSubtype(String packageName, Class<? extends FintReference> referenceSubtype) {
        referenceSubtypeMap.put(packageName, referenceSubtype);
    }

    public boolean isAReference(String packageName) {
        return referenceSubtypeMap.containsKey(packageName);
    }

    public Collection<Class<? extends FintModelObject>> getAllMetaSubtypes() {
        return metaSubtypeMap.values();
    }

    public Collection<Class<? extends FintResource>> getAllResourceSubtypes() {
        return resourceSubtypeMap.values();
    }

}
