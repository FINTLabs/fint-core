package no.fintlabs.autorelation.cache

import no.fintlabs.autorelation.model.EntityDescriptor
import no.fintlabs.autorelation.model.RelationSyncRule
import no.fintlabs.autorelation.model.createEntityDescriptor
import org.springframework.stereotype.Component

@Component
class RelationRuleRegistry(
    ruleBuilder: RelationRuleBuilder,
) {
    private val rulesByTrigger: Map<EntityDescriptor, List<RelationSyncRule>> =
        ruleBuilder.buildEntityDescriptorToRules()

    private val inverseRelationsIndex: Map<EntityDescriptor, Set<String>> by lazy {
        indexInverseRelationsByTarget()
    }

    fun getRules(sourceEntity: EntityDescriptor): List<RelationSyncRule> = rulesByTrigger[sourceEntity] ?: emptyList()

    fun getRules(
        domainName: String,
        packageName: String,
        resourceName: String,
    ): List<RelationSyncRule> =
        rulesByTrigger[createEntityDescriptor(domainName, packageName, resourceName)] ?: emptyList()

    fun getInverseRelations(entityDescriptor: EntityDescriptor): Set<String> =
        inverseRelationsIndex[entityDescriptor] ?: emptySet()

    fun getInverseRelations(
        domain: String,
        pkg: String,
        resource: String,
    ): Set<String> = getInverseRelations(createEntityDescriptor(domain, pkg, resource))

    private fun indexInverseRelationsByTarget(): Map<EntityDescriptor, Set<String>> =
        rulesByTrigger.values
            .asSequence()
            .flatten()
            .groupBy { it.targetType }
            .mapValues { (_, rules) -> rules.map { it.inverseRelation }.toSet() }
}
