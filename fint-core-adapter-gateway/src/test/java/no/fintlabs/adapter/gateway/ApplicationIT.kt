package no.fintlabs.adapter.gateway

import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class ApplicationIT : GatewayIntegrationTestBase() {
    @Test
    fun `OpenAPI docs endpoint returns the spec without authentication`() {
        mockMvc
            .perform(get("/adapter/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.openapi").exists())
            .andExpect(jsonPath("$.paths").exists())
    }

    @Test
    fun `Actuator health endpoint reports UP without authentication`() {
        mockMvc
            .perform(get("/adapter/actuator/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
    }

    @Test
    fun `Swagger UI paths are not blocked by security`() {
        listOf("/adapter/swagger-ui", "/adapter/swagger-ui/index.html", "/adapter/swagger-ui/swagger-ui.css").forEach { path ->
            mockMvc
                .perform(get(path))
                .andExpect { result ->
                    val code = result.response.status
                    check(code != 401 && code != 403) { "expected $path to be open, got $code" }
                }
        }
    }

    @Test
    fun `Swagger UI is served under the adapter path`() {
        mockMvc
            .perform(get("/adapter/swagger-ui/index.html"))
            .andExpect(status().isOk)
    }
}
