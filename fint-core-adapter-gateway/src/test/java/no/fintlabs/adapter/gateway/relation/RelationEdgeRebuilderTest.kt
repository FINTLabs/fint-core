package no.fintlabs.adapter.gateway.relation

import io.mockk.every
import io.mockk.mockk
import no.fintlabs.adapter.gateway.storage.MongoTransactions
import no.fintlabs.adapter.gateway.storage.ResourceWritePipeline
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.relation.RelationEdgeStore
import no.novari.core.shared.relation.RelationEdgeWriteResult
import no.novari.core.shared.store.ResourceStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals

class RelationEdgeRebuilderTest {
    private val resourceStore = mockk<ResourceStore>()
    private val relationEdgeStore = mockk<RelationEdgeStore>()
    private val transactions = mockk<MongoTransactions>()
    private val rebuilder =
        RelationEdgeRebuilder(resourceStore, relationEdgeStore, mockk<ResourceWritePipeline>(relaxed = true), transactions, batchSize = 500)

    private val person = ResourceCoordinate("fintlabs.no", "utdanning", "elev", "person")
    private val elev = ResourceCoordinate("fintlabs.no", "utdanning", "elev", "elev")

    init {
        every { transactions.inTransaction<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
        every { relationEdgeStore.applyAll(any()) } returns RelationEdgeWriteResult.NONE
        every { relationEdgeStore.findSourceIds(any(), any()) } returns emptyList()
    }

    @Test
    fun `a second rebuild of the same org and type is refused while the first is running`() {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { resourceStore.findPageAfter(any(), any(), any(), person.toCollectionName()) } answers {
            inside.countDown()
            release.await(10, TimeUnit.SECONDS)
            emptyList()
        }

        val first = thread { rebuilder.rebuild(person) }
        inside.await(10, TimeUnit.SECONDS)

        assertThrows<RelationEdgeRebuildRunningException> { rebuilder.rebuild(person) }

        release.countDown()
        first.join()
    }

    @Test
    fun `a rebuild of another type runs beside it`() {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { resourceStore.findPageAfter(any(), any(), any(), person.toCollectionName()) } answers {
            inside.countDown()
            release.await(10, TimeUnit.SECONDS)
            emptyList()
        }
        every { resourceStore.findPageAfter(any(), any(), any(), elev.toCollectionName()) } returns emptyList()

        val first = thread { rebuilder.rebuild(person) }
        inside.await(10, TimeUnit.SECONDS)

        assertEquals(RelationEdgeRebuild.NONE, rebuilder.rebuild(elev))

        release.countDown()
        first.join()
    }

    @Test
    fun `a rebuild that failed can be started again`() {
        every { resourceStore.findPageAfter(any(), any(), any(), person.toCollectionName()) } throws
            IllegalStateException("read failed") andThen emptyList()

        assertThrows<IllegalStateException> { rebuilder.rebuild(person) }

        assertEquals(RelationEdgeRebuild.NONE, rebuilder.rebuild(person))
    }
}
