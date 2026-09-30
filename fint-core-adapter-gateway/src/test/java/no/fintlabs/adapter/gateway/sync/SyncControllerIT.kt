package no.fintlabs.adapter.gateway.sync

import no.fintlabs.adapter.gateway.GatewayIntegrationTestBase
import no.fintlabs.adapter.models.sync.DeleteSyncPage
import no.fintlabs.adapter.models.sync.DeltaSyncPage
import no.fintlabs.adapter.models.sync.FullSyncPage
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.models.sync.SyncPageMetadata
import no.novari.resource.server.authentication.CorePrincipal
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

class SyncControllerIT : GatewayIntegrationTestBase() {
    @Test
    fun `Should reject sync request if adapter is not registered`() {
        // A dedicated, never-registered username: registerAdapter() in the other tests of this
        // class shares mockPrincipal's username, so reusing it here would pass or fail purely
        // by test execution order once some sibling test has registered it.
        val unregisteredUsername = "never-registered@adapter.$orgId"
        val unregisteredPrincipal = principalFor(unregisteredUsername)
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
                    .with(authentication(unregisteredPrincipal)),
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

    private fun principalFor(username: String): CorePrincipal {
        val jwt =
            Jwt
                .withTokenValue("mock-token-value")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("cn", username)
                .claim("fintAssetIDs", orgId)
                .claim("scope", listOf("fint-adapter"))
                .claim("Roles", listOf("FINT_Adapter_${domainName}_$packageName"))
                .build()
        return CorePrincipal(jwt, emptyList())
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
