package no.fintlabs.provider.sync

import no.fintlabs.adapter.models.sync.SyncType
import no.fintlabs.provider.storage.EvictionService
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service

/**
 * Counts a full sync's records as they land in storage and evicts what the sync left behind once
 * they all have.
 *
 * Only full syncs are counted. A delta says nothing about what it left out, and a delete sync
 * names what it removes, so neither one can decide that anything else is stale.
 *
 * Called after the records of a batch are written, never before. Kafka commits the batch's offset
 * after that, so a crash in between makes the batch arrive again, which can only ever fold the
 * same records in twice. Counting is guarded against that; counting ahead of the write would not
 * be, and would evict data the sync was still about to write.
 */
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

    /**
     * Folds one partition's share of a batch into the sync's progress, then evicts if that
     * finished it.
     *
     * The read decides which records are fresh and the write only lands if the partition's slot
     * still holds what the read saw, so a redelivery that overlaps what is already folded in
     * counts only the part beyond it. Reading first is what makes a partly overlapping
     * redelivery work: skipping the whole batch instead would drop the records past the overlap,
     * and a sync that undercounts never finishes and never evicts.
     */
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

    /**
     * Sweeps, if this is the replica that got the claim. A failed sweep is logged and left alone:
     * the claim stays taken so a redelivery does not sweep the same sync again, and the next full
     * sync of the resource evicts against a newer threshold, which covers everything this one
     * would have.
     */
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
