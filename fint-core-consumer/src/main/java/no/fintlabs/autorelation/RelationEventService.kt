package no.fintlabs.autorelation

import no.fintlabs.autorelation.cache.RelationRuleRegistry
import no.fintlabs.autorelation.kafka.RelationUpdateProducer
import no.fintlabs.autorelation.model.AutoRelationException
import no.fintlabs.autorelation.model.MetricReason
import no.fintlabs.autorelation.model.RelationOperation
import no.fintlabs.autorelation.model.RelationSyncRule
import no.fintlabs.autorelation.model.RelationUpdate
import no.fintlabs.autorelation.model.getIdentifier
import no.fintlabs.autorelation.model.toRelationBinding
import no.fintlabs.autorelation.model.toRelationUpdate
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.resource.ResourceConverter
import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.Link
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class RelationEventService(
    private val resourceConverter: ResourceConverter,
    private val relationRuleRegistry: RelationRuleRegistry,
    private val consumerConfiguration: ConsumerConfiguration,
    private val relationUpdateProducer: RelationUpdateProducer,
    private val metricService: MetricService,
) {
    fun addRelations(
        resourceName: String,
        resourceId: String,
        resource: Any,
    ) {
        val rules = fetchRules(resourceName).ifEmpty { return }
        val converted = convertOrReport(resourceName, resourceId, resource) ?: return
        publishAll(resourceName, rules, converted, resourceId, RelationOperation.ADD)
    }

    fun removeRelations(
        resourceName: String,
        resourceId: String,
        resource: FintResource,
    ) {
        val rules = fetchRules(resourceName).ifEmpty { return }
        publishAll(resourceName, rules, resource, resourceId, RelationOperation.DELETE)
    }

    fun removeObsoleteRelations(
        resourceName: String,
        resourceId: String,
        currentResource: FintResource,
        obsoleteLinks: Map<String, List<Link>>,
        rules: List<RelationSyncRule>,
    ) {
        obsoleteLinks.forEach { (relationName, linksToDelete) ->
            val rule = rules.firstOrNull { it.targetRelation == relationName } ?: return@forEach
            val targetIds = linksToDelete.map { it.getIdentifier() }.ifEmpty { return@forEach }

            val update =
                RelationUpdate(
                    targetEntity = rule.targetType,
                    targetIds = targetIds,
                    binding = rule.toRelationBinding(currentResource, resourceId),
                    operation = RelationOperation.DELETE,
                )

            publish(resourceName, resourceId, relationName) {
                relationUpdateProducer.publishRelationUpdate(update, resourceName, resourceId)
            }
        }
    }

    private fun publishAll(
        resourceName: String,
        rules: List<RelationSyncRule>,
        resource: FintResource,
        resourceId: String,
        operation: RelationOperation,
    ) = rules.forEach { rule ->
        publish(resourceName, resourceId, rule.targetRelation) {
            val update = rule.toRelationUpdate(resource, resourceId, operation)
            if (update == null) {
                metricService.incrementRuleSkipped(resourceName, MetricReason.NO_TARGETS)
            } else {
                relationUpdateProducer.publishRelationUpdate(update, resourceName, resourceId)
            }
        }
    }

    private fun publish(
        resourceName: String,
        resourceId: String,
        relationName: String? = null,
        block: () -> Unit,
    ) = runCatching(block)
        .onFailure { error ->
            val reason = error.toMetricReason()
            metricService.incrementRuleSkipped(resourceName, reason)
            logRelationError(error, resourceName, resourceId, reason, relationName)
        }

    private fun convertOrReport(
        resourceName: String,
        resourceId: String,
        resource: Any,
    ): FintResource? =
        runCatching { resourceConverter.convert(resourceName, resource) }
            .onFailure {
                metricService.incrementRuleSkipped(resourceName, MetricReason.CONVERSION_FAILED)
                logRelationError(it, resourceName, resourceId, MetricReason.CONVERSION_FAILED)
            }.getOrNull()

    private fun logRelationError(
        error: Throwable,
        resourceName: String,
        resourceId: String,
        reason: MetricReason,
        relationName: String? = null,
    ) {
        val context = relationName?.let { " Relation: $it" } ?: ""
        val msg = "Failed to publish update for '$resourceName' ($resourceId). Reason: ${reason.tagValue}.$context"

        if (error is AutoRelationException) {
            logger.error("{} Error: {}", msg, error.message)
        } else {
            logger.error("{}", error.message)
        }
    }

    private fun Throwable.toMetricReason() =
        when (this) {
            is AutoRelationException -> metricReason
            else -> MetricReason.UNEXPECTED_ERROR
        }

    private fun fetchRules(resourceName: String) =
        relationRuleRegistry.getRules(
            domainName = consumerConfiguration.domain,
            packageName = consumerConfiguration.packageName,
            resourceName = resourceName,
        )

    companion object {
        private val logger = LoggerFactory.getLogger(RelationEventService::class.java)
    }
}
