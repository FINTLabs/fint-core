package no.fintlabs.consumer.kafka.sync

import no.fintlabs.adapter.models.sync.SyncType

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

    /**
     * Transition the state machine to another state based on arguments
     */
    abstract fun transition(
        resourceName: String,
        timestamp: Long,
        totalSize: Long,
    ): SyncState

    interface Failed

    data class Init(
        override val resourceName: String? = null,
        override val totalSize: Long = 0,
        override val syncType: SyncType,
    ) : SyncState() {
        override var timestamp: Long = 0
        override val processedCount: Long = 0
        override val description: String = "Initialized"

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
