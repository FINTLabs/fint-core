package no.fintlabs.reflection;

import lombok.extern.slf4j.Slf4j;
import no.novari.fint.model.FintAbstractObject;
import no.novari.fint.model.FintModelObject;
import no.novari.fint.model.FintReference;
import no.novari.fint.model.resource.FintResource;
import org.reflections.Reflections;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.function.BiConsumer;

@Slf4j
@Service
public class ReflectionInitializer {

    public ReflectionInitializer(ReflectionCache reflectionCache) {
        Reflections reflections = new Reflections("no.novari.fint.model");
        registerSubTypes(reflections, FintModelObject.class, (p, s) -> reflectionCache.addMetaSubtype(p, s));
        registerSubTypes(reflections, FintResource.class, (p, s) -> reflectionCache.addResourceSubtype(p, s));
        registerSubTypes(reflections, FintAbstractObject.class, (p, s) -> reflectionCache.addAbstractSubtype(p, s));
        registerSubTypes(reflections, FintReference.class, (p, s) -> reflectionCache.addReferenceSubtype(p, s));
    }

    public FintModelObject initializeFintModelObject(Class<? extends FintModelObject> metaSubType) {
        try {
            return metaSubType.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private <T> void registerSubTypes(Reflections reflections, Class<T> type, BiConsumer<String, Class<? extends T>> cacheAdder) {
        Set<Class<? extends T>> subTypes = reflections.getSubTypesOf(type);
        subTypes.forEach(subType -> cacheAdder.accept(subType.getName(), subType));
    }

}
