package no.fintlabs.consumer.admin

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.novari.core.shared.json.FintJson
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.Date

class AdminControllerTest {
    private val objectMapper = FintJson.responseMapper("https://api.felleskomponent.no")
    private val statsService = mockk<StatsService>()
    private val mockMvc =
        MockMvcBuilders
            .standaloneSetup(AdminController(consumerConfiguration(), statsService))
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()

    @Test
    fun `assets returns configured org id`() {
        mockMvc
            .perform(get("/utdanning/elev/admin/assets"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().json("""["fintlabs.no"]"""))
    }

    @Test
    fun `cache status returns stats service response`() {
        val response =
            mapOf(
                "elev" to CacheEntry(Date.from(Instant.parse("2026-02-03T04:05:06Z")), 2),
                "person" to CacheEntry(null, 0),
            )
        every { statsService.cacheStatus() } returns response

        mockMvc
            .perform(get("/utdanning/elev/admin/cache/status"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().json(objectMapper.writeValueAsString(response)))

        verify(exactly = 1) { statsService.cacheStatus() }
    }

    private fun consumerConfiguration() =
        ConsumerConfiguration(
            baseUrl = "https://api.felleskomponent.no",
            orgIdValue = "fintlabs.no",
            domain = "utdanning",
            packageName = "elev",
            podUrl = "http://localhost",
        )
}
