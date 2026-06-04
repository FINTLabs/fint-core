package no.fintlabs.consumer.kafka.sync

import no.fintlabs.adapter.models.sync.SyncType
import no.fintlabs.cache.CacheEvictionService
import no.fintlabs.consumer.kafka.entity.EntityConsumerRecord
import no.fintlabs.consumer.kafka.sync.SyncState.Completed
import no.fintlabs.consumer.kafka.sync.SyncState.ConcurrentFullSync
import no.fintlabs.consumer.kafka.sync.SyncState.Init
import no.fintlabs.consumer.kafka.sync.SyncState.ResourceNameChanged
import no.fintlabs.consumer.kafka.sync.SyncState.TotalSizeChanged
import no.fintlabs.consumer.kafka.sync.model.SyncStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Tracks synchronization progress and triggers cache eviction when a [SyncType.FULL] sync completes.
 *
 * State lives in a shared [SyncProgressStore] (Mongo) rather than in-JVM, so progress aggregates
 * correctly when records of one sync are spread across replicas. Each record advances the
 * correlation's [SyncState] machine via optimistic compare-and-set with retry; the side effects of a
 * transition (eviction, status publish, concurrent-full-sync handling) are fired exactly once by the
 * replica that wins the CAS, guarded by comparing the previous state to the new one.
 *
 * Concurrent FULL syncs of the same resource are detected through a per-resource active-full record:
 * the second correlation to claim it fails the first. Stale entries are reaped by the store's TTL.
 */
@Service
class SyncTrackerService(
    private val syncStatusProducer: SyncStatusProducer,
    private val evictionService: CacheEvictionService,
    private val fullSyncCache: LastCompletedFullSyncCache,
    private val progressStore: SyncProgressStore,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun processRecordMetadata(consumerRecord: EntityConsumerRecord) {
        val resourceName = consumerRecord.resourceKey
        val syncType = consumerRecord.type ?: throw IllegalStateException("No sync-type provided")
        val correlationId = consumerRecord.corrId ?: throw IllegalStateException("No correlation id provided")
        val totalSize = consumerRecord.totalSize ?: throw IllegalStateException("No total size provided")
        val timestamp = consumerRecord.timestamp

        val (previous, newState) = advance(correlationId, resourceName, syncType, totalSize, timestamp)

        if (syncType == SyncType.FULL && newState !is SyncState.Failed) {
            detectConcurrentFullSync(resourceName, correlationId)
        }

        applySideEffects(previous, newState, correlationId, resourceName)
    }

    /**
     * Advance the correlation's state machine by one record using optimistic compare-and-set. Returns
     * the (previous, new) state pair so callers can fire transition side effects exactly once.
     */
    private fun advance(
        correlationId: String,
        resourceName: String,
        syncType: SyncType,
        totalSize: Long,
        timestamp: Long,
    ): Pair<SyncState, SyncState> {
        while (true) {
            val current = progressStore.read(correlationId)
            val previous = current?.state ?: Init(resourceName, totalSize, syncType)
            val newState = previous.transition(resourceName, timestamp, totalSize)
            if (progressStore.compareAndSet(correlationId, current?.version, newState)) {
                return previous to newState
            }
        }
    }

    private fun detectConcurrentFullSync(
        resourceName: String,
        correlationId: String,
    ) {
        val previousOwner = progressStore.claimActiveFullSync(resourceName, correlationId)
        if (previousOwner != null && previousOwner != correlationId) {
            logger.warn(
                "Concurrent full sync detected: resource={}, existingCorrelationId={}, newCorrelationId={}",
                resourceName,
                previousOwner,
                correlationId,
            )
            failAsConcurrent(previousOwner)
        }
    }

    /** Force the existing full sync into [ConcurrentFullSync] and publish, retrying on a lost CAS. */
    private fun failAsConcurrent(correlationId: String) {
        while (true) {
            val current = progressStore.read(correlationId) ?: return
            val concurrent =
                ConcurrentFullSync(
                    current.state.resourceName,
                    current.state.timestamp,
                    current.state.totalSize,
                    current.state.processedCount,
                    current.state.syncType,
                )
            if (progressStore.compareAndSet(correlationId, current.version, concurrent)) {
                syncStatusProducer.publish(SyncStatus(correlationId, SyncType.FULL, concurrent.description))
                return
            }
        }
    }

    private fun applySideEffects(
        previous: SyncState,
        newState: SyncState,
        correlationId: String,
        resourceName: String,
    ) {
        when {
            newState is Completed && previous !is Completed -> {
                logger.debug(
                    "Completed {} sync with correlation ID {} and {} resources",
                    newState.syncType,
                    correlationId,
                    newState.processedCount,
                )
                if (newState.syncType == SyncType.FULL) {
                    logger.info(
                        "Full sync completed, starting cache eviction: correlationId={}, resource={}, processedCount={}",
                        correlationId,
                        resourceName,
                        newState.processedCount,
                    )
                    evictionService.evictExpired(resourceName, newState.timestamp)
                    progressStore.clearActiveFullSync(resourceName, correlationId)
                    syncStatusProducer.publish(SyncStatus(correlationId, newState.syncType, "Completed"))
                    fullSyncCache.registerTimestamp(resourceName, newState.timestamp)
                }
                progressStore.delete(correlationId)
            }

            newState is ResourceNameChanged && previous !is ResourceNameChanged -> {
                logger.warn(
                    "Sync state validation failed: correlationId={}, resource={}, reason={}",
                    correlationId,
                    resourceName,
                    newState.description,
                )
                syncStatusProducer.publish(SyncStatus(correlationId, newState.syncType, newState.description))
            }

            newState is TotalSizeChanged && previous !is TotalSizeChanged -> {
                logger.warn(
                    "Sync state validation failed: correlationId={}, resource={}, reason={}",
                    correlationId,
                    resourceName,
                    newState.description,
                )
                syncStatusProducer.publish(SyncStatus(correlationId, newState.syncType, newState.description))
            }
        }
    }
}
