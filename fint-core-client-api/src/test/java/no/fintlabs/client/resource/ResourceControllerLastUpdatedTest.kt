package no.fintlabs.client.resource

import io.mockk.every
import io.mockk.mockk
import no.fintlabs.client.admin.StatsService
import no.fintlabs.client.config.AutorelationConfig
import no.fintlabs.client.config.ConsumerConfiguration
import no.novari.core.shared.json.FintJson
import no.novari.core.shared.model.ResourceCoordinate
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ResourceControllerLastUpdatedTest {
    private val baseUrl = "https://api.felleskomponent.no"
    private val statsService = mockk<StatsService>()

    private val mockMvc =
        MockMvcBuilders
            .standaloneSetup(
                ResourceController(
                    mockk<ResourceService>(),
                    mockk(),
                    mockk(),
                    mockk(),
                    ConsumerConfiguration(
                        baseUrl = baseUrl,
                        orgIdValue = "fintlabs.no",
                        autorelation = AutorelationConfig(enabled = true),
                    ),
                    statsService,
                ),
            ).setMessageConverters(JacksonJsonHttpMessageConverter(FintJson.responseMapper(baseUrl)))
            .build()

    @Test
    fun `last updated is epoch millis as a string`() {
        every {
            statsService.getLastUpdated(ResourceCoordinate("fintlabs.no", "utdanning", "elev", "elev"))
        } returns 1767225600000

        mockMvc
            .perform(get("/utdanning/elev/elev/last-updated").header("x-org-id", "fintlabs.no"))
            .andExpect(status().isOk)
            .andExpect(content().json("""{"lastUpdated":"1767225600000"}""", true))
    }
}
