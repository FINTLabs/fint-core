package no.fintlabs.autorelation.cache

import no.fintlabs.autorelation.model.EntityDescriptor
import no.fintlabs.autorelation.model.RelationSyncRule
import no.fintlabs.autorelation.model.createEntityDescriptor
import no.novari.fint.model.FintMultiplicity
import no.novari.fint.model.FintRelation
import no.novari.metamodel.MetamodelService
import no.novari.metamodel.model.Component
import no.novari.metamodel.model.Resource
import org.springframework.stereotype.Component as SpringComponent

@SpringComponent
class RelationRuleBuilder(
    private val metamodelService: MetamodelService,
) {
    fun buildEntityDescriptorToRules() =
        everyResourceInFint { component, resource ->
            resource.relations
                .filter { relation -> relation.isManagedRelation(component.domainName) }
                .mapNotNull { targetRelation -> createRelationSyncRule(component, targetRelation) }
                .takeIf { it.isNotEmpty() }
                ?.let { rules -> component.toEntityDescriptor(resource.name) to rules }
        }.toMap()

    private fun FintRelation.isManagedRelation(domainName: String) =
        (this.belongsToDomain(domainName) || this.isCommonResource()) && inverseName != null

    private fun <T> everyResourceInFint(transform: (Component, Resource) -> T?): List<T> =
        metamodelService
            .getComponents()
            .flatMap { component ->
                component.resources.mapNotNull { resource ->
                    transform(component, resource)
                }
            }

    private fun createRelationSyncRule(
        component: Component,
        targetRelation: FintRelation,
    ): RelationSyncRule? {
        val targetEntity = targetRelation.toEntityDescriptor(component)
        val inverseRelation = targetEntity.findInverseRelation(targetRelation.inverseName) ?: return null

        // We dont support 1 to 1 right now
        if (targetRelation.isOneToOne(inverseRelation)) return null
        if (targetRelation.isNonSourceManyToMany(inverseRelation)) return null
        if (targetRelation.isManyToOne(inverseRelation)) return null

        return RelationSyncRule(
            targetRelation = targetRelation.name,
            inverseRelation = targetRelation.inverseName,
            targetType = targetEntity,
            targetMultiplicity = targetRelation.multiplicity,
            inverseMultiplicity = inverseRelation.multiplicity,
            isSource = targetRelation.isSource,
        )
    }

    private fun FintRelation.isManyToOne(inverseRelation: FintRelation) =
        this.isListMultiplicity() &&
            setOf(FintMultiplicity.ONE_TO_ONE, FintMultiplicity.NONE_TO_ONE).contains(
                inverseRelation.multiplicity,
            )

    private fun FintRelation.isNonSourceManyToMany(inverseRelation: FintRelation) =
        this.isListMultiplicity() && inverseRelation.isListMultiplicity() && !this.isSource

    private fun FintRelation.isOneToOne(inverseRelation: FintRelation) =
        setOf(FintMultiplicity.ONE_TO_ONE, FintMultiplicity.NONE_TO_ONE).let { set ->
            multiplicity in set && inverseRelation.multiplicity in set
        }

    private fun EntityDescriptor.findInverseRelation(inverseName: String): FintRelation? =
        metamodelService
            .getResource(domainName, packageName, resourceName)
            ?.relations
            ?.firstOrNull { it.name == inverseName }

    private fun Component.toEntityDescriptor(resource: String) =
        createEntityDescriptor(domainName, packageName, resource)

    private fun FintRelation.toEntityDescriptor(component: Component): EntityDescriptor =
        // Inherit domain and packageName from parent - since common resources do not have their own component
        if (isCommonResource()) {
            createEntityDescriptor(component.domainName, component.packageName, resourceName())
        } else {
            packageName
                .split(".") // packageName is actually a className
                .takeLast(3)
                .let { (domainName, pkg, resource) -> createEntityDescriptor(domainName, pkg, resource) }
        }

    private fun FintRelation.isListMultiplicity() =
        this.multiplicity in setOf(FintMultiplicity.ONE_TO_MANY, FintMultiplicity.NONE_TO_MANY)

    private fun FintRelation.belongsToDomain(domain: String): Boolean =
        this.packageName.startsWith("no.novari.fint.model.$domain")

    // packageName is actually className: example of common className = no.novari.fint.model.felles.Person
    private fun FintRelation.isCommonResource() = this.packageName.split(".").size == 6

    private fun FintRelation.resourceName() =
        this.packageName
            .split(".")
            .last()
}
