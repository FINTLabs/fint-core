package no.fintlabs.provider.sync

import no.fintlabs.adapter.models.sync.SyncType
import no.fintlabs.provider.storage.EvictionService
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service

@Service
class SyncCompletionTracker(
    private val progressStore: SyncProgressStore,
    private val evictionService: EvictionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun track(records: List<SyncRecord>) {
        records
            .filter { it.metadata.type == SyncType.FULL }
            .groupBy { it.metadata.corrId to it.partition }
            .forEach { (key, group) -> advance(key.first, key.second, group) }
    }

    private fun advance(
        corrId: String,
        partition: Int,
        group: List<SyncRecord>,
    ) {
        val first = group.first()
        val highestOffset = group.maxOf { it.offset }

        repeat(FOLD_ATTEMPTS) {
            val observed = progressStore.find(corrId)?.offsets?.get(partition.toString())
            val fresh = group.filter { observed == null || it.offset > observed }

            val progress =
                try {
                    progressStore.fold(
                        corrId = corrId,
                        coordinate = first.coordinate,
                        totalSize = first.metadata.totalSize,
                        partition = partition,
                        expectedOffset = observed,
                        highestOffset = highestOffset,
                        freshCount = fresh.size,
                        startedAt = group.minOf { it.writtenAt },
                    )
                } catch (conflict: DuplicateKeyException) {
                    log.debug("Lost a race folding sync {} partition {}, reading again", corrId, partition, conflict)
                    return@repeat
                }

            if (progress.complete) evict(progress)
            return
        }

        throw IllegalStateException("Gave up folding sync $corrId partition $partition after $FOLD_ATTEMPTS attempts")
    }

    private fun evict(progress: SyncProgress) {
        val claimed = progressStore.claimEviction(progress.corrId) ?: return

        log.info(
            "Full sync {} of {} is fully stored, evicting resources older than {}",
            claimed.corrId,
            claimed.coordinate.toResourceUri(),
            claimed.startedAt,
        )

        try {
            evictionService.evict(claimed.coordinate, claimed.startedAt)
        } catch (failure: RuntimeException) {
            log.error(
                "Eviction failed for sync {} of {}, leaving it to the next full sync",
                claimed.corrId,
                claimed.coordinate.toResourceUri(),
                failure,
            )
        }
    }

    companion object {
        private const val FOLD_ATTEMPTS = 5
    }
}
