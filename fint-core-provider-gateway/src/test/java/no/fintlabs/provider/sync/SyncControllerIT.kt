package no.fintlabs.provider.sync

import no.fintlabs.adapter.models.sync.DeleteSyncPage
import no.fintlabs.adapter.models.sync.DeltaSyncPage
import no.fintlabs.adapter.models.sync.FullSyncPage
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.models.sync.SyncPageMetadata
import no.fintlabs.provider.GatewayIntegrationTestBase
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

class SyncControllerIT : GatewayIntegrationTestBase() {

    // TODO: This test should happen in the security layer of spring, not the controller
    @Test
    @Disabled("Enable in next iteration - where we enable contract validation")
    fun `Should reject sync request if adapter is not registered`() {
        val syncPage =
            FullSyncPage().apply {
                this.metadata = syncPageMetadata(totalSize = 0, pageSize = 0)
                this.resources = emptyList()
            }

        mockMvc
            .perform(
                post("/$domainName/$packageName/$resourceName")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(syncPage))
                    .with(authentication(mockPrincipal)),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `Should successfully perform fullSync`() {
        registerAdapter()

        val syncPage =
            FullSyncPage().apply {
                this.metadata = syncPageMetadata(totalSize = 2, pageSize = 2)
                this.resources =
                    listOf(
                        SyncPageEntry.of("$domainName.$packageName.$resourceName/systemid/1", mapOf("name" to "Test1")),
                        SyncPageEntry.of("$domainName.$packageName.$resourceName/systemid/2", mapOf("name" to "Test2")),
                    )
            }

        mockMvc
            .perform(
                post("/$domainName/$packageName/$resourceName")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(syncPage))
                    .with(authentication(mockPrincipal)),
            ).andExpect(status().isCreated)
    }

    @Test
    fun `Should successfully perform deltaSync`() {
        registerAdapter()

        val syncPage =
            DeltaSyncPage().apply {
                this.metadata = syncPageMetadata(totalSize = 1, pageSize = 1)
                this.resources =
                    listOf(
                        SyncPageEntry.of("$domainName.$packageName.$resourceName/systemid/1", mapOf("name" to "Updated")),
                    )
            }

        mockMvc
            .perform(
                patch("/$domainName/$packageName/$resourceName")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(syncPage))
                    .with(authentication(mockPrincipal)),
            ).andExpect(status().isCreated)
    }

    @Test
    fun `Should successfully perform deleteSync`() {
        registerAdapter()

        val syncPage =
            DeleteSyncPage().apply {
                this.metadata = syncPageMetadata(totalSize = 1, pageSize = 1)
                this.resources =
                    listOf(
                        SyncPageEntry.of("$domainName.$packageName.$resourceName/systemid/1", mapOf("name" to "ToDelete")),
                    )
            }

        mockMvc
            .perform(
                delete("/$domainName/$packageName/$resourceName")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(syncPage))
                    .with(authentication(mockPrincipal)),
            ).andExpect(status().isOk)
    }

    @Test
    fun `Should reject sync when orgId does not match JWT`() {
        registerAdapter()

        val syncPage =
            FullSyncPage().apply {
                this.metadata = syncPageMetadata(totalSize = 0, pageSize = 0, orgId = "wrong.org.no", uriRef = null)
                this.resources = emptyList()
            }

        mockMvc
            .perform(
                post("/$domainName/$packageName/$resourceName")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(syncPage))
                    .with(authentication(mockPrincipal)),
            ).andExpect(status().isForbidden)
    }

    private fun syncPageMetadata(
        totalSize: Long,
        pageSize: Long,
        orgId: String = this.orgId,
        uriRef: String? = "/$domainName/$packageName/$resourceName",
    ): SyncPageMetadata =
        SyncPageMetadata
            .builder()
            .adapterId(adapterId)
            .orgId(orgId)
            .corrId(UUID.randomUUID().toString())
            .totalSize(totalSize)
            .page(0)
            .pageSize(pageSize)
            .totalPages(1)
            .uriRef(uriRef)
            .time(System.currentTimeMillis())
            .build()
}
