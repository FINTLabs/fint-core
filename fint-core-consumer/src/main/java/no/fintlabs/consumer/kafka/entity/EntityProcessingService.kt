package no.fintlabs.consumer.kafka.entity

import no.fintlabs.autorelation.AutoRelationService
import no.fintlabs.autorelation.MetricService
import no.fintlabs.cache.CacheService
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.kafka.sync.SyncTrackerService
import no.fintlabs.consumer.links.LinkService
import no.novari.fint.model.resource.FintResource
import org.springframework.stereotype.Service

@Service
class EntityProcessingService(
    private val linkService: LinkService,
    private val cacheService: CacheService,
    private val autoRelationService: AutoRelationService,
    private val consumerConfiguration: ConsumerConfiguration,
    private val syncTrackerService: SyncTrackerService,
    private val metricService: MetricService,
) {
    /**
     * No document lock is needed: the entity's own write is a single atomic conditional upsert
     * ([CacheService] / `MongoDBFintCache.put`) and relation changes to OTHER documents are applied
     * with independent atomic per-target updates in [AutoRelationService]. This keeps replicas
     * stateless — any replica may process any record concurrently.
     */
    fun processEntityConsumerRecord(record: EntityConsumerRecord) {
        val removed = if (record.resource == null) deleteEntity(record) else null
        if (record.resource != null) {
            addToCache(record)
        }

        if (record.type != null) {
            syncTrackerService.processRecordMetadata(record)
        }

        if (consumerConfiguration.autorelation.enabled) {
            applyRelations(record, removed)
        }
    }

    private fun applyRelations(
        record: EntityConsumerRecord,
        removed: FintResource?,
    ) {
        val resource = record.resource
        if (resource != null) {
            autoRelationService.applyRelations(record.resourceKey, record.key, resource)
        } else if (removed != null) {
            autoRelationService.applyRemoval(record.resourceKey, record.key, removed)
        }
    }

    private fun deleteEntity(record: EntityConsumerRecord): FintResource? {
        val cache = cacheService.getCache(record.resourceKey)
        val existing = cache.get(record.key)
        cache.remove(record.key, record.timestamp)
        return existing
    }

    private fun addToCache(record: EntityConsumerRecord) {
        val resource = requireNotNull(record.resource)
        val cache = cacheService.getCache(record.resourceKey)

        linkService.mapLinks(record.resourceKey, resource)
        val accepted = cache.put(record.key, resource, record.timestamp)
        if (!accepted) {
            metricService.incrementCachePutRejectedOlderTimestamp(record.resourceKey)
        }
    }
}
