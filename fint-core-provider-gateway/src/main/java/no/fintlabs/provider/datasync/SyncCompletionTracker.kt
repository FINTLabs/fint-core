package no.fintlabs.provider.datasync

import no.fintlabs.adapter.models.sync.SyncType
import no.fintlabs.consumer.kafka.sync.SyncProgressStore
import no.fintlabs.consumer.kafka.sync.SyncState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Tracks full-sync progress across pages and replicas and triggers cache eviction when a sync
 * completes. Reuses the consumer's proven [SyncState] machine + [SyncProgressStore]: each page reads
 * the correlation's state, replays one transition per resource in-memory (so the per-record counting
 * and min-timestamp tracking are unchanged), then persists with one optimistic compare-and-set.
 *
 * Only [SyncType.FULL] is tracked — deltas and deletes never evict. Concurrent full syncs of the same
 * resource are detected and the earlier one is forced into a failed state so it does not evict.
 */
@Service
class SyncCompletionTracker(
    private val progressStore: SyncProgressStore,
    private val evictionService: ProviderEvictionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun track(
        resourceKey: String,
        correlationId: String,
        totalSize: Long,
        timestamp: Long,
        count: Int,
    ) {
        if (count <= 0) return
        val (previous, newState) = advance(resourceKey, correlationId, totalSize, timestamp, count)
        if (newState !is SyncState.Failed) {
            detectConcurrentFullSync(resourceKey, correlationId)
        }
        if (newState is SyncState.Completed && previous !is SyncState.Completed) {
            log.info("Full sync completed, starting eviction: correlationId={}, resource={}", correlationId, resourceKey)
            evictionService.evictExpired(resourceKey, newState.timestamp)
            progressStore.clearActiveFullSync(resourceKey, correlationId)
            progressStore.delete(correlationId)
        }
    }

    private fun advance(
        resourceKey: String,
        correlationId: String,
        totalSize: Long,
        timestamp: Long,
        count: Int,
    ): Pair<SyncState, SyncState> {
        while (true) {
            val current = progressStore.read(correlationId)
            val previous = current?.state ?: SyncState.Init(resourceKey, totalSize, SyncType.FULL)
            var newState: SyncState = previous
            repeat(count) { newState = newState.transition(resourceKey, timestamp, totalSize) }
            if (progressStore.compareAndSet(correlationId, current?.version, newState)) {
                return previous to newState
            }
        }
    }

    private fun detectConcurrentFullSync(
        resourceKey: String,
        correlationId: String,
    ) {
        val previousOwner = progressStore.claimActiveFullSync(resourceKey, correlationId)
        if (previousOwner != null && previousOwner != correlationId) {
            log.warn(
                "Concurrent full sync detected: resource={}, existingCorrelationId={}, newCorrelationId={}",
                resourceKey,
                previousOwner,
                correlationId,
            )
            failAsConcurrent(previousOwner)
        }
    }

    private fun failAsConcurrent(correlationId: String) {
        while (true) {
            val current = progressStore.read(correlationId) ?: return
            val concurrent =
                SyncState.ConcurrentFullSync(
                    current.state.resourceName,
                    current.state.timestamp,
                    current.state.totalSize,
                    current.state.processedCount,
                    current.state.syncType,
                )
            if (progressStore.compareAndSet(correlationId, current.version, concurrent)) return
        }
    }
}
