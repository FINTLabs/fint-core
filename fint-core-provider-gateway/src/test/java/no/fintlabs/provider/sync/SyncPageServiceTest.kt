package no.fintlabs.provider.sync

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.adapter.models.sync.FullSyncPage
import no.fintlabs.adapter.models.sync.SyncPage
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.models.sync.SyncPageMetadata
import no.novari.core.shared.model.ResourceCoordinate
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class SyncPageServiceTest {
    private val bufferWriter = mockk<BufferWriter>(relaxed = true)
    private val metaDataKafkaProducer = mockk<MetaDataKafkaProducer>(relaxed = true)
    private val syncPageService = SyncPageService(bufferWriter, metaDataKafkaProducer)

    private val coordinate = ResourceCoordinate("fintlabs.no", "utdanning", "elev", "elevforhold")

    @Test
    fun `a full sync carrying nothing sends a marker instead of entities`() {
        every { bufferWriter.sendSyncMarker(any(), any()) } returns CompletableFuture.completedFuture(mockk())

        syncPageService.doSync(fullSync(totalSize = 0, resources = emptyList()), coordinate)

        verify(exactly = 1) { bufferWriter.sendSyncMarker(any(), coordinate) }
        verify(exactly = 0) { bufferWriter.sendSyncEntity(any(), any(), any()) }
    }

    @Test
    fun `a full sync carrying resources sends entities and no marker`() {
        every { bufferWriter.sendSyncEntity(any(), any(), any()) } returns CompletableFuture.completedFuture(mockk())

        val entry = SyncPageEntry.of("EF-1", mapOf("name" to "Test"))
        syncPageService.doSync(fullSync(totalSize = 1, resources = listOf(entry)), coordinate)

        verify(exactly = 1) { bufferWriter.sendSyncEntity(any(), entry, coordinate) }
        verify(exactly = 0) { bufferWriter.sendSyncMarker(any(), any()) }
    }

    private fun fullSync(
        totalSize: Long,
        resources: List<SyncPageEntry>,
    ): SyncPage =
        FullSyncPage().apply {
            metadata =
                SyncPageMetadata().apply {
                    adapterId = "test-adapter"
                    corrId = "S-1"
                    orgId = "fintlabs.no"
                    this.totalSize = totalSize
                    page = 1
                    pageSize = resources.size.toLong()
                    totalPages = 1
                    uriRef = "utdanning/elev/elevforhold"
                }
            this.resources = resources
        }
}
