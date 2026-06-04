package no.fintlabs.provider.event.response

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.operation.OperationType
import no.fintlabs.provider.datasync.EntityProducer
import no.fintlabs.provider.datasync.ResourceCacheWriter
import no.fintlabs.provider.event.request.RequestEventService
import no.novari.resource.server.authentication.CorePrincipal
import org.junit.jupiter.api.Test
import java.util.Optional

class ResponseEventServiceTest {
    private val responseFintEventProducer = mockk<ResponseFintEventProducer>(relaxed = true)
    private val requestEventService = mockk<RequestEventService>(relaxed = true)
    private val entityProducer = mockk<EntityProducer>(relaxed = true)
    private val resourceCacheWriter = mockk<ResourceCacheWriter>(relaxed = true)
    private val corePrincipal = mockk<CorePrincipal>(relaxed = true)

    private val sut =
        ResponseEventService(
            responseFintEventProducer,
            requestEventService,
            entityProducer,
            resourceCacheWriter,
        )

    private fun request(corrId: String) =
        RequestFintEvent().apply {
            this.corrId = corrId
            orgId = "fintlabs.no"
            domainName = "utdanning"
            packageName = "vurdering"
            resourceName = "elevfravar"
        }

    private fun entry() =
        SyncPageEntry().apply {
            identifier = "systemid/1"
            resource = mapOf("name" to "x")
        }

    @Test
    fun `create event writes the resulting resource to the cache via the shared engine`() {
        val corrId = "corr-create"
        val syncEntry = entry()
        val response =
            ResponseFintEvent().apply {
                this.corrId = corrId
                orgId = "fintlabs.no"
                operationType = OperationType.CREATE
                value = syncEntry
                handledAt = 123L
            }
        every { requestEventService.getEvent(corrId) } returns Optional.of(request(corrId))

        sut.handleEvent(response, corePrincipal)

        verify(exactly = 1) {
            resourceCacheWriter.write("utdanning_vurdering_elevfravar", "systemid/1", syncEntry.resource, 123L)
        }
    }

    @Test
    fun `validate event does not touch the cache`() {
        val corrId = "corr-validate"
        val response =
            ResponseFintEvent().apply {
                this.corrId = corrId
                orgId = "fintlabs.no"
                operationType = OperationType.VALIDATE
                value = entry()
                handledAt = 1L
            }
        every { requestEventService.getEvent(corrId) } returns Optional.of(request(corrId))

        sut.handleEvent(response, corePrincipal)

        verify(exactly = 0) { resourceCacheWriter.write(any(), any(), any(), any()) }
    }
}
