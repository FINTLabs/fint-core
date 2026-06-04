package no.fintlabs.consumer.resource.context;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import no.novari.fint.model.FintModelObject;
import no.novari.fint.model.FintMultiplicity;
import no.novari.fint.model.FintRelation;
import no.fintlabs.consumer.resource.ResourceRef;
import no.fintlabs.consumer.resource.context.model.FintRelationInformation;
import no.fintlabs.consumer.resource.context.model.FintResourceInformation;
import no.fintlabs.reflection.ReflectionCache;
import no.fintlabs.reflection.ReflectionInitializer;
import no.novari.metamodel.MetamodelService;
import no.novari.metamodel.model.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@org.springframework.stereotype.Component
@RequiredArgsConstructor
public class ResourceContextCache {

    private final ReflectionCache reflectionCache;
    private final ReflectionInitializer reflectionInitializer;
    private final MetamodelService metamodelService;

    protected final Map<String, FintResourceInformation> resourceMap = new HashMap<>();

    @PostConstruct
    private void init() {
        metamodelService.getComponents().forEach(this::fillComponent);
    }

    private void fillComponent(Component component) {
        reflectionCache.getAllMetaSubtypes().stream()
                .filter(metaSubType -> belongsToComponent(metaSubType, component))
                .map(metaSubType -> createFintResourceInformation(metaSubType, component))
                .forEach(resource -> {
                    addResourceInformation(component, resource);
                    checkRelationsForCommonResources(component, resource.relations().values());
                });
    }

    private FintResourceInformation createFintResourceInformation(Class<? extends FintModelObject> metaSubType, Component component) {
        FintModelObject fintModelObject = reflectionInitializer.initializeFintModelObject(metaSubType);
        return FintResourceInformation.byMetaSubType(
                metaSubType,
                reflectionCache.getResourceSubtype(metaSubType.getName()),
                fintModelObject,
                getReferenceNames(fintModelObject),
                getRequiredRelations(fintModelObject),
                getAbstractRelations(fintModelObject),
                createFintRelationInformations(fintModelObject, component)
        );
    }

    private Map<String, FintRelationInformation> createFintRelationInformations(FintModelObject fintModelObject, Component component) {
        return fintModelObject.getRelations().stream()
                .filter(this::isValidReference)
                .map(relation -> createFintRelationInformation(relation, component))
                .collect(Collectors.toMap(
                        FintRelationInformation::name,
                        relation -> relation
                ));
    }

    private boolean isValidReference(FintRelation fintRelation) {
        return !reflectionCache.isAnAbstractObject(fintRelation.getPackageName())
                && !reflectionCache.isAReference(fintRelation.getPackageName());
    }

    private void checkRelationsForCommonResources(Component component, Collection<FintRelationInformation> fintRelations) {
        fintRelations.stream()
                .filter(relation -> isACommonResource(relation.packageName()))
                .map(relation -> reflectionCache.getMetaSubtype(relation.packageName()))
                .map(metaSubType -> createFintResourceInformation(metaSubType, component))
                .filter(resourceInformation -> !resourceMap.containsKey(keyFor(component, resourceInformation)))
                .forEach(fintResourceInformation -> {
                    addResourceInformation(component, fintResourceInformation);
                    checkRelationsForCommonResources(component, fintResourceInformation.relations().values());
                });
    }

    private FintRelationInformation createFintRelationInformation(FintRelation fintRelation, Component component) {
        Class<? extends FintModelObject> metaSubtype = reflectionCache.getMetaSubtype(fintRelation.getPackageName());
        return FintRelationInformation.byModelObject(
                fintRelation.getName(),
                fintRelation.getPackageName(),
                reflectionInitializer.initializeFintModelObject(metaSubtype),
                createRelationUri(fintRelation.getPackageName(), component)
        );
    }

    private String createRelationUri(String packageName, Component component) {
        if (isACommonResource(packageName)) {
            String resourceName = packageName.substring(packageName.lastIndexOf('.') + 1);
            return "%s/%s/%s".formatted(
                    component.getDomainName(),
                    component.getPackageName(),
                    resourceName.toLowerCase()
            );
        } else {
            return packageName.replaceFirst("no.novari.fint.model.", "")
                    .replace(".", "/")
                    .toLowerCase();
        }
    }

    private Set<String> getRequiredRelations(FintModelObject fintModelObject) {
        return fintModelObject.getRelations().stream()
                .filter(relation -> relation.getMultiplicity().equals(FintMultiplicity.ONE_TO_ONE))
                .map(FintRelation::getName)
                .collect(Collectors.toSet());
    }

    private Set<String> getReferenceNames(FintModelObject fintModelObject) {
        return fintModelObject.getRelations().stream()
                .filter(relation -> reflectionCache.isAReference(relation.getPackageName()))
                .map(relation -> relation.getName().toLowerCase())
                .collect(Collectors.toSet());
    }

    private Set<String> getAbstractRelations(FintModelObject fintModelObject) {
        return fintModelObject.getRelations().stream()
                .filter(relation -> reflectionCache.isAnAbstractObject(relation.getPackageName()))
                .map(relation -> relation.getName().toLowerCase())
                .collect(Collectors.toSet());
    }

    private boolean isACommonResource(String packageName) {
        return packageName.split("\\.").length == 6;
    }

    private boolean belongsToComponent(Class<? extends FintModelObject> metaSubType, Component component) {
        return metaSubType.getPackageName().toLowerCase().contains(
                "%s.%s".formatted(
                        component.getDomainName(),
                        component.getPackageName()).toLowerCase()
        );
    }

    private String keyFor(Component component, FintResourceInformation info) {
        return ResourceRef.keyOf(component.getDomainName(), component.getPackageName(), info.name());
    }

    private void addResourceInformation(Component component, FintResourceInformation fintResourceInformation) {
        resourceMap.put(keyFor(component, fintResourceInformation), fintResourceInformation);
    }

}
