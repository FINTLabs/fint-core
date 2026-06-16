package no.fintlabs.autorelation

import com.fasterxml.jackson.databind.ObjectMapper
import no.fintlabs.autorelation.buffer.UnresolvedRelationCache
import no.fintlabs.autorelation.cache.RelationRuleRegistry
import no.fintlabs.autorelation.model.AutoRelationException
import no.fintlabs.autorelation.model.MetricReason
import no.fintlabs.autorelation.model.RelationOperation
import no.fintlabs.autorelation.model.RelationSyncRule
import no.fintlabs.autorelation.model.RelationUpdate
import no.fintlabs.cache.CacheService
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.links.LinkService
import no.fintlabs.consumer.resource.ResourceLockService
import no.fintlabs.consumer.resource.context.ResourceContext
import no.novari.fint.model.resource.FintResource
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class AutoRelationService(
    private val linkService: LinkService,
    private val cacheService: CacheService,
    private val consumerConfig: ConsumerConfiguration,
    private val relationRuleRegistry: RelationRuleRegistry,
    private val relationEventService: RelationEventService,
    private val unresolvedRelationCache: UnresolvedRelationCache,
    private val resourceContext: ResourceContext,
    private val objectMapper: ObjectMapper,
    private val resourceLockService: ResourceLockService,
    private val metricService: MetricService,
) {
    fun process(relationUpdate: RelationUpdate) =
        relationUpdate.targetIds.forEach { resourceId ->
            resourceLockService.withLock(relationUpdate.targetEntity.resourceName, resourceId) {
                val existingResource = getResourceFromCache(relationUpdate.targetEntity.resourceName, resourceId)

                if (existingResource != null) {
                    relationUpdate.apply(existingResource, resourceId)
                } else {
                    relationUpdate.buffer(resourceId)
                }
            }
        }

    private fun RelationUpdate.apply(
        existingResource: FintResource,
        resourceId: String,
    ) = try {
        val resourceCopy = existingResource.deepCopy(objectMapper, getResourceClass())
        resourceCopy.applyUpdate(this)
        linkService.mapLinks(targetEntity.resourceName, resourceCopy)
        putInCache(this, resourceId, resourceCopy)
        metricService.incrementUpdateApplied(targetEntity.resourceName, operation)
    } catch (e: AutoRelationException) {
        metricService.incrementUpdateFailed(targetEntity.resourceName, operation, e.metricReason)
        logger.warn(
            "Failed to apply relation update for '{}' ({}). Reason: {}",
            targetEntity.resourceName,
            resourceId,
            e.metricReason.tagValue,
            e,
        )
    } catch (e: Exception) {
        metricService.incrementUpdateFailed(
            targetEntity.resourceName,
            operation,
            MetricReason.UNEXPECTED_ERROR,
        )
        logger.error(
            "Unexpected error applying relation update for '{}' ({})",
            targetEntity.resourceName,
            resourceId,
            e,
        )
    }

    /**
     * Main reconciliation entry point.
     * Handles Pruning (removals), Preservation (old links), and Hydration (pending links).
     */
    fun reconcileLinks(
        resourceName: String,
        resourceId: String,
        fintResource: FintResource,
    ) {
        val oldResource = getResourceFromCache(resourceName, resourceId)
        val managedRules = getManagedRelations(resourceName)

        if (oldResource != null) {
            fintResource.pruneObsoleteLinks(resourceName, resourceId, oldResource, managedRules)
        }

        val managedRelationNames = managedRules.map { it.targetRelation }.toSet()

        relationRuleRegistry
            .getInverseRelations(consumerConfig.domain, consumerConfig.packageName, resourceName)
            .filter { it !in managedRelationNames } // Safety net
            .forEach { relation ->
                fintResource.preserveExistingLinks(oldResource, resourceName, relation)
                fintResource.applyPendingLinks(resourceName, resourceId, relation)
            }
    }

    private fun getManagedRelations(resourceName: String) =
        relationRuleRegistry.getRules(consumerConfig.domain, consumerConfig.packageName, resourceName)

    private fun FintResource.pruneObsoleteLinks(
        resourceName: String,
        resourceId: String,
        oldResource: FintResource,
        managedRules: List<RelationSyncRule>,
    ) {
        val pruningRules = managedRules.filter { it.shouldPruneLinks() }
        if (pruningRules.isEmpty()) return

        val relationsToCheck = pruningRules.map { it.targetRelation }
        val obsoleteLinksMap = this.findObsoleteLinks(oldResource, relationsToCheck)

        if (obsoleteLinksMap.isNotEmpty()) {
            relationEventService.removeObsoleteRelations(
                resourceName,
                resourceId,
                this,
                obsoleteLinksMap,
                pruningRules,
            )
        }
    }

    private fun FintResource.preserveExistingLinks(
        oldResource: FintResource?,
        resourceName: String,
        relation: String,
    ) = oldResource?.links?.get(relation)?.let { oldLinks ->
        if (oldLinks.isNotEmpty()) {
            metricService.incrementPreservedLinks(resourceName, relation, oldLinks.size)
        }
        this.addUniqueLinks(relation, oldLinks)
    }

    private fun FintResource.applyPendingLinks(
        resourceName: String,
        resourceId: String,
        relationName: String,
    ) = unresolvedRelationCache
        .takeRelations(resourceName, resourceId, relationName)
        .let { linksToAttach ->
            if (linksToAttach.isNotEmpty()) {
                metricService.incrementHydratedLinks(resourceName, relationName, linksToAttach.size)
            }
            addUniqueLinks(relationName, linksToAttach)
        }

    private fun RelationUpdate.buffer(id: String) {
        with(binding) {
            when (operation) {
                RelationOperation.ADD -> {
                    unresolvedRelationCache.registerRelation(
                        targetEntity.resourceName,
                        id,
                        relationName,
                        link,
                        timestamp,
                    )
                }

                RelationOperation.DELETE -> {
                    unresolvedRelationCache.removeRelation(
                        targetEntity.resourceName,
                        id,
                        relationName,
                        link,
                    )
                }
            }
        }
        metricService.incrementUpdateBuffered(targetEntity.resourceName, operation)
    }

    private fun getResourceFromCache(
        resource: String,
        resourceId: String,
    ): FintResource? =
        cacheService
            .getCache(resource)
            .get(resourceId)

    private fun putInCache(
        relationUpdate: RelationUpdate,
        id: String,
        resource: FintResource,
    ) {
        val cache = cacheService.getCache(relationUpdate.targetEntity.resourceName)
        val timestamp = maxOf(relationUpdate.timestamp, cache.lastUpdatedByResourceId(id) ?: 0L)
        if (!cache.put(id, resource, timestamp)) {
            metricService.incrementCachePutRejectedOlderTimestamp(relationUpdate.targetEntity.resourceName)
        }
    }

    // use !! to fail-fast if an unknown resource enters the system
    private fun RelationUpdate.getResourceClass() = resourceContext.getResource(targetEntity.resourceName)!!.clazz

    companion object {
        private val logger = LoggerFactory.getLogger(AutoRelationService::class.java)
    }
}
