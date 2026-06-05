package no.novari.fint.core.shared.autorelation

import no.novari.fint.core.shared.autorelation.cache.RelationRuleRegistry
import no.novari.fint.core.shared.autorelation.model.AutoRelationException
import no.novari.fint.core.shared.autorelation.model.EntityDescriptor
import no.novari.fint.core.shared.autorelation.model.MetricReason
import no.novari.fint.core.shared.autorelation.model.RelationState
import no.novari.fint.core.shared.autorelation.model.RelationSyncRule
import no.novari.fint.core.shared.autorelation.model.createEntityDescriptor
import no.novari.fint.core.shared.autorelation.model.toEmptyRelationState
import no.novari.fint.core.shared.autorelation.model.toRelationState
import no.novari.fint.core.shared.cache.BackLinkOp
import no.novari.fint.core.shared.cache.CacheDocumentCodec
import no.novari.fint.core.shared.cache.CacheService
import no.novari.fint.core.shared.link.LinkService
import no.novari.fint.core.shared.resource.ResourceRef
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

    /**
     * Batch variant of [applyRelations] for a whole sync page: reconciles every source's relations,
     * accumulating the resulting back-link changes per target collection and flushing each target as
     * a single bulk write instead of one update per source.
     */
    fun applyRelations(
        sourceKey: String,
        resources: List<Pair<String, FintResource>>,
        timestamp: Long,
    ) = batchReconcile(sourceKey, resources, timestamp) { rule, resource, id ->
        rule.toRelationState(resource, id)
    }

    /**
     * Batch variant of [applyRemoval]: applies empty state for every source so targets drop their
     * back-links, grouped into one bulk write per target collection.
     */
    fun applyRemoval(
        sourceKey: String,
        removed: List<Pair<String, FintResource>>,
        timestamp: Long,
    ) = batchReconcile(sourceKey, removed, timestamp) { rule, resource, id ->
        rule.toEmptyRelationState(resource, id)
    }

    private fun batchReconcile(
        sourceKey: String,
        items: List<Pair<String, FintResource>>,
        timestamp: Long,
        toState: (RelationSyncRule, FintResource, String) -> RelationState,
    ) {
        if (items.isEmpty()) return
        val source = sourceKey.toDescriptor()
        val rules = relationRuleRegistry.getRules(source)
        if (rules.isEmpty()) return

        val requestsByGroup = HashMap<GroupKey, MutableList<ReconcileRequest>>()
        items.forEach { (resourceId, resource) ->
            rules.forEach { rule ->
                runRule(source, resourceId, rule.inverseRelation) {
                    val state = toState(rule, resource, resourceId)
                    val sourceRef = CacheDocumentCodec.relationRef(state.binding.link.href) ?: return@runRule
                    requestsByGroup
                        .getOrPut(GroupKey(state.targetEntity.toKey(), state.binding.relationName)) { mutableListOf() }
                        .add(ReconcileRequest(sourceRef, state.targetIds.toSet(), state.binding.link))
                }
            }
        }

        val opsByTarget = HashMap<String, MutableList<BackLinkOp>>()
        requestsByGroup.forEach { (group, requests) ->
            runApply(group.targetKey) { collectGroupOps(group, requests, opsByTarget) }
        }
        opsByTarget.forEach { (targetKey, ops) ->
            if (ops.isNotEmpty()) {
                runApply(targetKey) { cacheService.getCache(targetKey).applyBackLinkOps(ops, timestamp) }
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

    /**
     * Accumulates a whole (target, relation) group's diff into [opsByTarget] using a single batched
     * lookup of the current back-link holders for every source ref in the group, instead of one
     * lookup per source. Mirrors [process]'s diff (removals first, then mapped additions) but defers
     * the writes so the page flushes as one bulk write per target collection.
     */
    private fun collectGroupOps(
        group: GroupKey,
        requests: List<ReconcileRequest>,
        opsByTarget: MutableMap<String, MutableList<BackLinkOp>>,
    ) {
        val cache = cacheService.getCache(group.targetKey)
        val resolvedByRef = cache.findIdsByBackLinks(group.relation, requests.mapTo(mutableSetOf()) { it.sourceRef })
        val ops = opsByTarget.getOrPut(group.targetKey) { mutableListOf() }
        requests.forEach { request ->
            val resolved = resolvedByRef[request.sourceRef] ?: emptySet()
            (resolved - request.desired).forEach { id ->
                ops += BackLinkOp.Remove(id, group.relation, request.sourceRef)
                metricService.incrementUpdateApplied(group.targetKey, "removed")
            }
            val mappedLink = linkService.mapRelationLink(group.targetKey, group.relation, Link.with(request.sourceLink.href))
            (request.desired - resolved).forEach { id ->
                ops += BackLinkOp.Add(id, group.relation, mappedLink)
                metricService.incrementUpdateApplied(group.targetKey, "added")
            }
        }
    }

    private data class GroupKey(
        val targetKey: String,
        val relation: String,
    )

    private data class ReconcileRequest(
        val sourceRef: String,
        val desired: Set<String>,
        val sourceLink: Link,
    )

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
