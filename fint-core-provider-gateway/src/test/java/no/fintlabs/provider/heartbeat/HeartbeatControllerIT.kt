package no.fintlabs.provider.heartbeat

import no.fintlabs.adapter.models.AdapterHeartbeat
import no.fintlabs.provider.GatewayIntegrationTestBase
import org.apache.kafka.common.utils.Time
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class HeartbeatControllerIT : GatewayIntegrationTestBase() {
    @Test
    fun `Should successfully send heartbeat`() {
        val heartbeat =
            AdapterHeartbeat().apply {
                this.adapterId = this@HeartbeatControllerIT.adapterId
                this.orgId = this@HeartbeatControllerIT.orgId
                this.username = this@HeartbeatControllerIT.username
            }

        mockMvc
            .perform(
                post("/heartbeat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(heartbeat))
                    .with(authentication(mockPrincipal)),
            ).andExpect(status().isOk)
    }

    @Test
    fun `should accept heartbeat when adapter is registered`() {
        registerAdapter()
        val heartbeat =
            AdapterHeartbeat().apply {
                this.adapterId = this@HeartbeatControllerIT.adapterId
                this.username = this@HeartbeatControllerIT.username
                this.orgId = this@HeartbeatControllerIT.orgId
                this.time = Time.SYSTEM.milliseconds()
            }

        mockMvc
            .perform(
                post("/heartbeat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(heartbeat))
                    .with(authentication(mockPrincipal)),
            ).andExpect(status().isOk)
    }

    @Test
    @Disabled("Will be enabled later")
    fun `should reject heartbeat when adapter is not registered`() {
        val heartbeat =
            AdapterHeartbeat().apply {
                this.adapterId = "random-adapter-id"
                this.username = "random-username"
                this.orgId = "whatEverOrgId"
                this.time = Time.SYSTEM.milliseconds()
            }

        mockMvc
            .perform(
                post("/heartbeat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(heartbeat))
                    .with(authentication(mockPrincipal)),
            ).andExpect(status().isForbidden)
    }
}
