package no.fintlabs.adapter.gateway

import no.fintlabs.adapter.models.AdapterHeartbeat
import no.fintlabs.adapter.models.sync.FullSyncPage
import no.fintlabs.adapter.models.sync.SyncPageMetadata
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

class AdapterApiV2PathsIT : GatewayIntegrationTestBase() {
    @Test
    fun `status answers on the v2 path`() {
        mockMvc
            .perform(get("/adapter/v2/status").with(authentication(mockPrincipal)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.corePrincipal.username").value(username))
    }

    @Test
    fun `status on the v2 path needs a token`() {
        mockMvc
            .perform(get("/adapter/v2/status"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `an adapter registers on the v2 path`() {
        mockMvc
            .perform(
                post("/adapter/v2/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(adapterContract()))
                    .with(authentication(mockPrincipal)),
            ).andExpect(status().isOk)
    }

    @Test
    fun `an adapter sends a heartbeat on the v2 path`() {
        val heartbeat =
            AdapterHeartbeat().apply {
                this.adapterId = this@AdapterApiV2PathsIT.adapterId
                this.orgId = this@AdapterApiV2PathsIT.orgId
                this.username = this@AdapterApiV2PathsIT.username
            }

        mockMvc
            .perform(
                post("/adapter/v2/heartbeat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(heartbeat))
                    .with(authentication(mockPrincipal)),
            ).andExpect(status().isOk)
    }

    @Test
    fun `a registered adapter sends a full sync on the v2 path`() {
        registerAdapter()

        mockMvc
            .perform(
                post("/adapter/v2/sync/$domainName/$packageName/$resourceName")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(emptyFullSync()))
                    .with(authentication(mockPrincipal)),
            ).andExpect(status().isCreated)
    }

    @Test
    fun `a sync on the v2 path for a package the adapter has no role for is forbidden`() {
        mockMvc
            .perform(
                post("/adapter/v2/sync/$domainName/vurdering/fravar")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(emptyFullSync()))
                    .with(authentication(mockPrincipal)),
            ).andExpect(status().isForbidden)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/adapter/v2/adapter",
            "/adapter/v2/admin/relation-edges/jobs/5d0c7a3e-8f41-4c55-9a55-2f6b1f0e6c11",
            "/adapter/v2/actuator/health",
        ],
    )
    fun `endpoints that are not part of v2 are not found on the v2 path`(path: String) {
        mockMvc
            .perform(get(path).with(authentication(mockPrincipal)))
            .andExpect(status().isNotFound)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/provider/admin/relation-edges/jobs/5d0c7a3e-8f41-4c55-9a55-2f6b1f0e6c11",
            "/provider/actuator/health",
            "/provider/v3/api-docs",
        ],
    )
    fun `admin, actuator and the API docs are no longer under the provider path`(path: String) {
        mockMvc
            .perform(get(path).with(authentication(mockPrincipal)))
            .andExpect(status().isNotFound)
    }

    private fun emptyFullSync(): FullSyncPage =
        FullSyncPage().apply {
            metadata =
                SyncPageMetadata
                    .builder()
                    .orgId(orgId)
                    .corrId(UUID.randomUUID().toString())
                    .totalSize(0)
                    .page(0)
                    .pageSize(0)
                    .totalPages(1)
                    .build()
            resources = emptyList()
        }
}
