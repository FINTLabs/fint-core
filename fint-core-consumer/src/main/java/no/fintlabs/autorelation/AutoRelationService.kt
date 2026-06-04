package no.fintlabs.autorelation

import no.fintlabs.autorelation.cache.RelationRuleRegistry
import no.fintlabs.autorelation.model.AutoRelationException
import no.fintlabs.autorelation.model.EntityDescriptor
import no.fintlabs.autorelation.model.MetricReason
import no.fintlabs.autorelation.model.RelationState
import no.fintlabs.autorelation.model.createEntityDescriptor
import no.fintlabs.autorelation.model.toEmptyRelationState
import no.fintlabs.autorelation.model.toRelationState
import no.fintlabs.cache.CacheDocumentCodec
import no.fintlabs.cache.CacheService
import no.fintlabs.consumer.links.LinkService
import no.fintlabs.consumer.resource.ResourceRef
import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.Link
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Keeps bidirectional FINT relations in sync. Because one service now holds every component's cache
 * in the same Mongo, a source applies its back-links directly to the target documents via atomic
 * `$addToSet`/`$pull`-style updates — no Kafka relation topic, no buffer, no document lock. A
 * back-link to a not-yet-cached target upserts a data-less stub that a later entity refresh fills
 * without clobbering the back-link. Target documents are keyed by the qualified [ResourceRef.key].
 */
@Service
class AutoRelationService(
    private val linkService: LinkService,
    private val cacheService: CacheService,
    private val relationRuleRegistry: RelationRuleRegistry,
    private val metricService: MetricService,
) {
    /**
     * On source arrival: for each managed rule, diff the source's current target set against what
     * already points back and apply the additions/removals directly to the target documents.
     */
    fun applyRelations(
        sourceKey: String,
        resourceId: String,
        resource: FintResource,
    ) {
        val source = sourceKey.toDescriptor()
        relationRuleRegistry.getRules(source).forEach { rule ->
            runRule(source, resourceId, rule.inverseRelation) {
                process(rule.toRelationState(resource, resourceId))
            }
        }
    }

    /**
     * On source removal: apply empty state for every managed rule so targets drop back-links to it.
     */
    fun applyRemoval(
        sourceKey: String,
        resourceId: String,
        resource: FintResource,
    ) {
        val source = sourceKey.toDescriptor()
        relationRuleRegistry.getRules(source).forEach { rule ->
            runRule(source, resourceId, rule.inverseRelation) {
                process(rule.toEmptyRelationState(resource, resourceId))
            }
        }
    }

    private fun runRule(
        source: EntityDescriptor,
        resourceId: String,
        relationName: String,
        block: () -> Unit,
    ) = runCatching(block).onFailure { error ->
        val reason = if (error is AutoRelationException) error.metricReason else MetricReason.UNEXPECTED_ERROR
        metricService.incrementRuleSkipped(source.resourceName, reason)
        if (error is AutoRelationException) {
            logger.debug(
                "Skipped relation '{}' for {}/{}. Reason: {}",
                relationName,
                source.resourceName,
                resourceId,
                reason.tagValue,
            )
        } else {
            logger.error(
                "Failed to apply relation '{}' for {}/{}",
                relationName,
                source.resourceName,
                resourceId,
                error,
            )
        }
    }

    /**
     * Reconcile the target documents' back-links for one relation slot: diff the source's currently
     * desired target set against the targets that already hold the back-link, then apply the
     * difference with atomic per-target updates. Adds upsert a stub when the target is absent, so no
     * buffering of unresolved links is needed.
     */
    private fun process(state: RelationState) {
        val targetKey = state.targetEntity.toKey()
        val inverseRelation = state.binding.relationName
        val sourceLink = state.binding.link
        val sourceRef = CacheDocumentCodec.relationRef(sourceLink.href) ?: return
        val cache = cacheService.getCache(targetKey)

        val desired = state.targetIds.toSet()
        val resolved = cache.findIdsByBackLink(inverseRelation, sourceRef)

        (resolved - desired).forEach { id ->
            runApply(targetKey) {
                cache.removeBackLink(id, inverseRelation, sourceRef, state.timestamp)
                metricService.incrementUpdateApplied(targetKey, "removed")
            }
        }
        val mappedSourceLink = linkService.mapRelationLink(targetKey, inverseRelation, Link.with(sourceLink.href))
        (desired - resolved).forEach { id ->
            runApply(targetKey) {
                cache.addBackLink(id, inverseRelation, mappedSourceLink, state.timestamp)
                metricService.incrementUpdateApplied(targetKey, "added")
            }
        }
    }

    private fun runApply(
        targetKey: String,
        block: () -> Unit,
    ) = try {
        block()
    } catch (e: AutoRelationException) {
        metricService.incrementUpdateFailed(targetKey, e.metricReason)
        logger.warn("Failed to apply relation state for '{}'. Reason: {}", targetKey, e.metricReason.tagValue, e)
    } catch (e: Exception) {
        metricService.incrementUpdateFailed(targetKey, MetricReason.UNEXPECTED_ERROR)
        logger.error("Unexpected error applying relation state for '{}'", targetKey, e)
    }

    private fun String.toDescriptor(): EntityDescriptor =
        ResourceRef.fromKey(this).let { createEntityDescriptor(it.domain, it.packageName, it.name) }

    private fun EntityDescriptor.toKey(): String = ResourceRef.keyOf(domainName, packageName, resourceName)

    companion object {
        private val logger = LoggerFactory.getLogger(AutoRelationService::class.java)
    }
}
