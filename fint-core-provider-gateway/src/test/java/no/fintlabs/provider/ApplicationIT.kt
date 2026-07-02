package no.fintlabs.provider

import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class ApplicationIT : GatewayIntegrationTestBase() {
    @Test
    fun `OpenAPI docs endpoint returns the spec without authentication`() {
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.openapi").exists())
            .andExpect(jsonPath("$.paths").exists())
    }

    @Test
    fun `Actuator health endpoint reports UP without authentication`() {
        mockMvc
            .perform(get("/actuator/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
    }

    @Test
    fun `Swagger UI paths are not blocked by security`() {
        listOf("/swagger-ui", "/swagger-ui/index.html", "/swagger-ui/swagger-ui.css").forEach { path ->
            mockMvc
                .perform(get(path))
                .andExpect { result ->
                    val code = result.response.status
                    check(code != 401 && code != 403) { "expected $path to be open, got $code" }
                }
        }
    }
}
