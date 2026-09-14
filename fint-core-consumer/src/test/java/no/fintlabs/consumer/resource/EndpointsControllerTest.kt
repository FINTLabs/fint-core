package no.fintlabs.consumer.resource

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.novari.core.shared.json.FintJson
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class EndpointsControllerTest {
    private val endpointsService = mockk<EndpointsService>()

    private val mockMvc =
        MockMvcBuilders
            .standaloneSetup(EndpointsController(endpointsService))
            .setMessageConverters(
                JacksonJsonHttpMessageConverter(
                    FintJson.responseMapper("https://beta.felleskomponent.no"),
                ),
            ).build()

    @Test
    fun `package endpoint returns resource overview as top-level object`() {
        val domainName = "utdanning"
        val packageName = "vurdering"
        every {
            endpointsService.packageOverview(domainName, packageName)
        } returns
            linkedMapOf(
                "karakterverdi" to
                    ResourceEndpointsDto(
                        lastUpdatedUrl =
                            "https://beta.felleskomponent.no/utdanning/vurdering/karakterverdi/last-updated",
                        cacheSizeUrl =
                            "https://beta.felleskomponent.no/utdanning/vurdering/karakterverdi/cache/size",
                        collectionUrl =
                            "https://beta.felleskomponent.no/utdanning/vurdering/karakterverdi",
                        oneUrl =
                            listOf(
                                "https://beta.felleskomponent.no/utdanning/vurdering/karakterverdi/systemid/{id:.+}",
                            ),
                    ),
            )

        mockMvc
            .perform(get("/utdanning/vurdering"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(
                jsonPath("$.karakterverdi.collectionUrl")
                    .value(
                        "https://beta.felleskomponent.no/utdanning/vurdering/karakterverdi",
                    ),
            ).andExpect(
                jsonPath("$.karakterverdi.lastUpdatedUrl")
                    .value(
                        "https://beta.felleskomponent.no/utdanning/vurdering/karakterverdi/last-updated",
                    ),
            ).andExpect(
                jsonPath("$.karakterverdi.cacheSizeUrl")
                    .value(
                        "https://beta.felleskomponent.no/utdanning/vurdering/karakterverdi/cache/size",
                    ),
            ).andExpect(
                jsonPath("$.karakterverdi.oneUrl[0]")
                    .value(
                        "https://beta.felleskomponent.no/utdanning/vurdering/karakterverdi/systemid/{id:.+}",
                    ),
            )

        verify(exactly = 1) {
            endpointsService.packageOverview(domainName, packageName)
        }
    }
}
