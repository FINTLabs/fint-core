package no.novari.fint.core.shared.sync

import no.fintlabs.adapter.models.sync.SyncType

/** Discriminator persisted with a [SyncState] so it can be rebuilt on any replica. */
enum class SyncKind {
    INIT,
    IN_PROGRESS,
    COMPLETED,
    CONCURRENT_FULL,
    RESOURCE_NAME_CHANGED,
    TOTAL_SIZE_CHANGED,
    FAILED_AND_UNTRACKED,
}

/**
 * Synchronization state modelled as a state machine. The [transition] function
 * returns the next state based on metadata from received entity records.
 */
sealed class SyncState {
    abstract val resourceName: String?
    abstract var timestamp: Long
    abstract val totalSize: Long
    abstract val processedCount: Long
    abstract val syncType: SyncType
    abstract val description: String

    /** Discriminator used to persist and rebuild this state across replicas (see [rebuild]). */
    abstract val kind: SyncKind

    /**
     * Transition the state machine to another state based on arguments
     */
    abstract fun transition(
        resourceName: String,
        timestamp: Long,
        totalSize: Long,
    ): SyncState

    interface Failed

    companion object {
        /**
         * Rebuild a persisted state so a replica can resume a correlation's state machine it did not
         * itself start. The Mongo-backed progress store persists [kind] plus the primitive fields.
         */
        fun rebuild(
            kind: SyncKind,
            resourceName: String?,
            timestamp: Long,
            totalSize: Long,
            processedCount: Long,
            syncType: SyncType,
            description: String,
        ): SyncState =
            when (kind) {
                SyncKind.INIT -> {
                    Init(resourceName, totalSize, syncType).also { it.timestamp = timestamp }
                }

                SyncKind.IN_PROGRESS -> {
                    InProgress(resourceName!!, timestamp, totalSize, processedCount, syncType)
                }

                SyncKind.COMPLETED -> {
                    Completed(resourceName!!, timestamp, totalSize, processedCount, syncType)
                }

                SyncKind.CONCURRENT_FULL -> {
                    ConcurrentFullSync(resourceName, timestamp, totalSize, processedCount, syncType)
                }

                SyncKind.RESOURCE_NAME_CHANGED -> {
                    ResourceNameChanged(resourceName, timestamp, totalSize, processedCount, syncType, description)
                }

                SyncKind.TOTAL_SIZE_CHANGED -> {
                    TotalSizeChanged(resourceName, timestamp, totalSize, processedCount, syncType, description)
                }

                SyncKind.FAILED_AND_UNTRACKED -> {
                    FailedAndUntracked(resourceName, timestamp, totalSize, processedCount, syncType, description)
                }
            }
    }

    data class Init(
        override val resourceName: String? = null,
        override val totalSize: Long = 0,
        override val syncType: SyncType,
    ) : SyncState() {
        override var timestamp: Long = 0
        override val processedCount: Long = 0
        override val description: String = "Initialized"
        override val kind = SyncKind.INIT

        override fun transition(
            resourceName: String,
            timestamp: Long,
            totalSize: Long,
        ): SyncState =
            if (totalSize == 1L) {
                Completed(resourceName, timestamp, totalSize, 1L, syncType)
            } else {
                InProgress(resourceName, timestamp, totalSize, 1L, syncType)
            }
    }

    private data class InProgress(
        override val resourceName: String,
        override var timestamp: Long,
        override val totalSize: Long,
        override val processedCount: Long,
        override val syncType: SyncType,
    ) : SyncState() {
        override val kind = SyncKind.IN_PROGRESS
        override val description = """
                In Progress: resource = $resourceName,
                total size = $totalSize,
                processed count = $processedCount,
                sync-type = $syncType
            """

        override fun transition(
            resourceName: String,
            timestamp: Long,
            totalSize: Long,
        ): SyncState {
            val newStartTimestamp = this.timestamp.coerceAtMost(timestamp)
            return when {
                resourceName != this.resourceName -> {
                    ResourceNameChanged(
                        resourceName = this.resourceName,
                        timestamp = newStartTimestamp,
                        totalSize = this.totalSize,
                        processedCount = processedCount + 1,
                        syncType = syncType,
                        description = "Resource name changed from ${this.resourceName} to $resourceName",
                    )
                }

                totalSize != this.totalSize -> {
                    TotalSizeChanged(
                        resourceName = this.resourceName,
                        timestamp = newStartTimestamp,
                        totalSize = this.totalSize,
                        processedCount = processedCount + 1,
                        syncType = syncType,
                        description = "Total size changed from ${this.totalSize} to $totalSize",
                    )
                }

                processedCount + 1 == this.totalSize -> {
                    Completed(
                        resourceName = this.resourceName,
                        timestamp = newStartTimestamp,
                        totalSize = this.totalSize,
                        processedCount = this.totalSize,
                        syncType = syncType,
                    )
                }

                else -> {
                    copy(processedCount = processedCount + 1, timestamp = newStartTimestamp)
                }
            }
        }
    }

    data class ConcurrentFullSync(
        override val resourceName: String?,
        override var timestamp: Long,
        override val totalSize: Long,
        override val processedCount: Long,
        override val syncType: SyncType,
    ) : SyncState(),
        Failed {
        override val kind = SyncKind.CONCURRENT_FULL
        override val description: String = "Concurrent full-sync of $resourceName resource"

        override fun transition(
            resourceName: String,
            timestamp: Long,
            totalSize: Long,
        ): SyncState =
            // Continue counting transitions, stay in failed state
            copy(processedCount = processedCount + 1, timestamp = this.timestamp.coerceAtMost(timestamp))
    }

    data class ResourceNameChanged(
        override val resourceName: String?,
        override var timestamp: Long,
        override val totalSize: Long,
        override val processedCount: Long,
        override val syncType: SyncType,
        override val description: String = "Resource name changed",
    ) : SyncState(),
        Failed {
        override val kind = SyncKind.RESOURCE_NAME_CHANGED

        override fun transition(
            resourceName: String,
            timestamp: Long,
            totalSize: Long,
        ): SyncState =
            FailedAndUntracked(
                resourceName = this.resourceName,
                timestamp = this.timestamp.coerceAtMost(timestamp),
                totalSize = this.totalSize,
                processedCount = processedCount + 1,
                syncType = syncType,
                description = description,
            )
    }

    data class TotalSizeChanged(
        override val resourceName: String?,
        override var timestamp: Long,
        override val totalSize: Long,
        override val processedCount: Long,
        override val syncType: SyncType,
        override val description: String,
    ) : SyncState(),
        Failed {
        override val kind = SyncKind.TOTAL_SIZE_CHANGED

        override fun transition(
            resourceName: String,
            timestamp: Long,
            totalSize: Long,
        ): SyncState =
            FailedAndUntracked(
                resourceName = this.resourceName,
                timestamp = this.timestamp.coerceAtMost(timestamp),
                totalSize = this.totalSize,
                processedCount = processedCount + 1,
                syncType = syncType,
                description = description,
            )
    }

    data class FailedAndUntracked(
        override val resourceName: String?,
        override var timestamp: Long,
        override val totalSize: Long,
        override val processedCount: Long,
        override val syncType: SyncType,
        override val description: String,
    ) : SyncState(),
        Failed {
        override val kind = SyncKind.FAILED_AND_UNTRACKED

        override fun transition(
            resourceName: String,
            timestamp: Long,
            totalSize: Long,
        ): SyncState =
            copy(
                processedCount = processedCount + 1,
                timestamp = this.timestamp.coerceAtMost(timestamp),
                description = "Failed and untracked: $description",
            )
    }

    data class Completed(
        override val resourceName: String,
        override var timestamp: Long,
        override val totalSize: Long,
        override val processedCount: Long,
        override val syncType: SyncType,
    ) : SyncState() {
        override val kind = SyncKind.COMPLETED
        override val description = "Completed"

        override fun transition(
            resourceName: String,
            timestamp: Long,
            totalSize: Long,
        ): SyncState =
            // No further transitions from completed
            this
    }
}
