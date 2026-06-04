package no.fintlabs.consumer.kafka.sync

import no.fintlabs.adapter.models.sync.SyncType
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SyncStateTest {
    private fun initFull() = SyncState.Init(syncType = SyncType.FULL)

    private fun initDelta() = SyncState.Init(syncType = SyncType.DELTA)

    @Nested
    inner class InitTransitions {
        @Test
        fun `single-record sync transitions directly to Completed`() {
            val next = initFull().transition("resource", timestamp = 100, totalSize = 1)
            assertIs<SyncState.Completed>(next)
            assertEquals(1L, next.processedCount)
            assertEquals(100L, next.timestamp)
        }

        @Test
        fun `multi-record sync transitions to InProgress`() {
            val next = initFull().transition("resource", timestamp = 100, totalSize = 3)
            // InProgress is private – verify it is neither Completed nor Failed
            assertIs<SyncState>(next)
            assertTrue(next !is SyncState.Completed)
            assertTrue(next !is SyncState.Failed)
            assertEquals(1L, next.processedCount)
            assertEquals(100L, next.timestamp)
        }
    }

    @Nested
    inner class InProgressTransitions {
        private fun inProgress(
            totalSize: Long = 4,
            startTs: Long = 100,
        ): SyncState = initFull().transition("resource", startTs, totalSize)

        @Test
        fun `processedCount increments on each transition`() {
            var state = inProgress(totalSize = 4)
            repeat(2) { state = state.transition("resource", 200, 4) }
            assertEquals(3L, state.processedCount)
        }

        @Test
        fun `transitions to Completed when last record arrives`() {
            var state = inProgress(totalSize = 3) // processedCount = 1
            state = state.transition("resource", 200, 3) // processedCount = 2
            state = state.transition("resource", 300, 3) // processedCount = 3 == totalSize
            assertIs<SyncState.Completed>(state)
            assertEquals(3L, state.processedCount)
        }

        @Test
        fun `transitions to ResourceNameChanged when resource name differs`() {
            val state =
                inProgress(totalSize = 3)
                    .transition("other-resource", 200, 3)
            assertIs<SyncState.ResourceNameChanged>(state)
        }

        @Test
        fun `transitions to TotalSizeChanged when totalSize changes`() {
            val state =
                inProgress(totalSize = 3)
                    .transition("resource", 200, 99)
            assertIs<SyncState.TotalSizeChanged>(state)
        }
    }

    @Nested
    inner class TimestampAlwaysEarliest {
        @Test
        fun `InProgress keeps earliest timestamp when later records arrive`() {
            var state = initFull().transition("resource", timestamp = 100, totalSize = 5)
            state = state.transition("resource", timestamp = 200, totalSize = 5)
            state = state.transition("resource", timestamp = 50, totalSize = 5)
            assertEquals(50L, state.timestamp)
        }

        @Test
        fun `Completed carries earliest timestamp`() {
            var state: SyncState = initFull().transition("resource", timestamp = 300, totalSize = 2)
            state = state.transition("resource", timestamp = 100, totalSize = 2) // completes here
            assertIs<SyncState.Completed>(state)
            assertEquals(100L, state.timestamp)
        }

        @Test
        fun `ConcurrentFullSync keeps earliest timestamp`() {
            val concurrent =
                SyncState.ConcurrentFullSync(
                    resourceName = "resource",
                    timestamp = 100,
                    totalSize = 3,
                    processedCount = 1,
                    syncType = SyncType.FULL,
                )
            val next = concurrent.transition("resource", timestamp = 200, totalSize = 3)
            assertIs<SyncState.ConcurrentFullSync>(next)
            assertEquals(100L, next.timestamp)
        }

        @Test
        fun `ConcurrentFullSync updates timestamp when earlier record arrives`() {
            val concurrent =
                SyncState.ConcurrentFullSync(
                    resourceName = "resource",
                    timestamp = 100,
                    totalSize = 3,
                    processedCount = 1,
                    syncType = SyncType.FULL,
                )
            val next = concurrent.transition("resource", timestamp = 50, totalSize = 3)
            assertIs<SyncState.ConcurrentFullSync>(next)
            assertEquals(50L, next.timestamp)
        }

        @Test
        fun `ResourceNameChanged keeps earliest timestamp`() {
            val state =
                SyncState.ResourceNameChanged(
                    resourceName = "resource",
                    timestamp = 80,
                    totalSize = 3,
                    processedCount = 1,
                    syncType = SyncType.FULL,
                )
            val next = state.transition("other", timestamp = 200, totalSize = 3)
            assertEquals(80L, next.timestamp)
        }

        @Test
        fun `TotalSizeChanged keeps earliest timestamp`() {
            val state =
                SyncState.TotalSizeChanged(
                    resourceName = "resource",
                    timestamp = 80,
                    totalSize = 3,
                    processedCount = 1,
                    syncType = SyncType.FULL,
                    description = "size mismatch",
                )
            val next = state.transition("resource", timestamp = 200, totalSize = 3)
            assertEquals(80L, next.timestamp)
        }

        @Test
        fun `FailedAndUntracked keeps earliest timestamp`() {
            val state =
                SyncState.FailedAndUntracked(
                    resourceName = "resource",
                    timestamp = 80,
                    totalSize = 3,
                    processedCount = 2,
                    syncType = SyncType.FULL,
                    description = "some failure",
                )
            val next = state.transition("resource", timestamp = 200, totalSize = 3)
            assertEquals(80L, next.timestamp)
        }
    }

    @Nested
    inner class FailedStateTransitions {
        @Test
        fun `ResourceNameChanged transitions to FailedAndUntracked on next record`() {
            val state =
                SyncState.ResourceNameChanged(
                    resourceName = "resource",
                    timestamp = 100,
                    totalSize = 3,
                    processedCount = 1,
                    syncType = SyncType.FULL,
                )
            val next = state.transition("resource", 200, 3)
            assertIs<SyncState.FailedAndUntracked>(next)
            assertEquals(2L, next.processedCount)
        }

        @Test
        fun `TotalSizeChanged transitions to FailedAndUntracked on next record`() {
            val state =
                SyncState.TotalSizeChanged(
                    resourceName = "resource",
                    timestamp = 100,
                    totalSize = 3,
                    processedCount = 1,
                    syncType = SyncType.FULL,
                    description = "size mismatch",
                )
            val next = state.transition("resource", 200, 3)
            assertIs<SyncState.FailedAndUntracked>(next)
            assertEquals(2L, next.processedCount)
        }

        @Test
        fun `ConcurrentFullSync stays in ConcurrentFullSync through all further transitions`() {
            var state: SyncState =
                SyncState.ConcurrentFullSync(
                    resourceName = "resource",
                    timestamp = 100,
                    totalSize = 3,
                    processedCount = 1,
                    syncType = SyncType.FULL,
                )
            repeat(5) { state = state.transition("resource", 200, 3) }
            assertIs<SyncState.ConcurrentFullSync>(state)
            assertEquals(6L, state.processedCount)
        }

        @Test
        fun `FailedAndUntracked stays in FailedAndUntracked through all further transitions`() {
            var state: SyncState =
                SyncState.FailedAndUntracked(
                    resourceName = "resource",
                    timestamp = 100,
                    totalSize = 3,
                    processedCount = 2,
                    syncType = SyncType.FULL,
                    description = "initial failure",
                )
            repeat(3) { state = state.transition("resource", 200, 3) }
            assertIs<SyncState.FailedAndUntracked>(state)
            assertEquals(5L, state.processedCount)
        }

        @Test
        fun `all Failed states implement Failed interface`() {
            val states: List<SyncState> =
                listOf(
                    SyncState.ConcurrentFullSync("r", 0, 1, 0, SyncType.FULL),
                    SyncState.ResourceNameChanged("r", 0, 1, 0, SyncType.FULL),
                    SyncState.TotalSizeChanged("r", 0, 1, 0, SyncType.FULL, ""),
                    SyncState.FailedAndUntracked("r", 0, 1, 0, SyncType.FULL, ""),
                )
            assertTrue(states.all { it is SyncState.Failed })
        }
    }

    @Nested
    inner class CompletedTransitions {
        @Test
        fun `Completed ignores further transitions`() {
            val completed =
                SyncState.Completed(
                    resourceName = "resource",
                    timestamp = 100,
                    totalSize = 3,
                    processedCount = 3,
                    syncType = SyncType.FULL,
                )
            val next = completed.transition("resource", 200, 3)
            assertIs<SyncState.Completed>(next)
            assertEquals(completed, next)
        }
    }

    @Nested
    inner class SyncTypePreserved {
        @Test
        fun `DELTA sync type is preserved through InProgress to Completed`() {
            var state: SyncState = initDelta().transition("resource", 100, 2)
            state = state.transition("resource", 200, 2)
            assertIs<SyncState.Completed>(state)
            assertEquals(SyncType.DELTA, state.syncType)
        }

        @Test
        fun `FULL sync type is preserved through failure states`() {
            var state: SyncState = initFull().transition("resource", 100, 3)
            state = state.transition("other-resource", 200, 3) // ResourceNameChanged
            assertEquals(SyncType.FULL, state.syncType)
        }
    }
}
