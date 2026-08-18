package no.fintlabs.provider

import org.junit.jupiter.api.Test
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class ProviderControllerIT : GatewayIntegrationTestBase() {
    @Test
    fun `Status endpoint should return 200 with CorePrincipal`() {
        mockMvc
            .perform(
                get("/status").with(authentication(mockPrincipal)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("Greetings form FINTLabs 👋"))
            .andExpect(jsonPath("$.corePrincipal.username").value(username))
    }

    @Test
    fun `Trailing slash on status endpoint is accepted`() {
        mockMvc
            .perform(
                get("/status/").with(authentication(mockPrincipal)),
            ).andExpect(status().isOk)
    }
}
