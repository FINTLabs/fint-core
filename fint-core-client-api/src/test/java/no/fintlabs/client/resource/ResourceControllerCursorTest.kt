package no.fintlabs.client.resource

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.client.admin.StatsService
import no.fintlabs.client.config.AutorelationConfig
import no.fintlabs.client.config.ConsumerConfiguration
import no.fintlabs.client.resource.dto.createFintResourcesResponse
import no.fintlabs.client.resource.event.RequestFintEventService
import no.fintlabs.client.resource.event.RequestStatusService
import no.fintlabs.client.resource.paging.PageCursor
import no.fintlabs.client.resource.paging.PageCursorCodec
import no.fintlabs.client.resource.paging.PageCursorConverter
import no.fintlabs.client.resource.paging.PageDirection
import no.novari.core.shared.json.FintJson
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.store.PageAnchor
import org.junit.jupiter.api.Test
import org.springframework.format.support.DefaultFormattingConversionService
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

/**
 * Checks how the `cursor` query parameter reaches the service. A valid token is decoded by
 * [PageCursorConverter] before the controller runs, a request without one hands the service
 * null, and a token that is not a cursor is answered with 400 before the service is called.
 */
class ResourceControllerCursorTest {
    private val baseUrl = "https://api.felleskomponent.no"
    private val resourceService = mockk<ResourceService>()
    private val cursorCodec = PageCursorCodec.withRandomKey()

    private val mockMvc =
        MockMvcBuilders
            .standaloneSetup(
                ResourceController(
                    resourceService,
                    mockk<RequestFintEventService>(),
                    mockk<RequestStatusService>(),
                    consumerConfiguration(),
                    mockk<StatsService>(),
                ),
            ).setConversionService(
                DefaultFormattingConversionService().apply { addConverter(PageCursorConverter(cursorCodec)) },
            ).setMessageConverters(JacksonJsonHttpMessageConverter(FintJson.responseMapper(baseUrl)))
            .build()

    private val coordinate = ResourceCoordinate("fintlabs.no", "utdanning", "vurdering", "elevfravar")

    @Test
    fun `a valid cursor is decoded and handed to the service`() {
        val cursor = PageCursor(PageDirection.AFTER, PageAnchor(Instant.ofEpochMilli(20), "B"))
        every { resourceService.getResources(coordinate, 2, 2, 0, null, cursor) } returns emptyPage()

        mockMvc
            .perform(
                get(
                    "/utdanning/vurdering/elevfravar?size=2&offset=2&cursor=${cursorCodec.encode(cursor)}",
                ).header("x-org-id", "fintlabs.no"),
            ).andExpect(status().isOk)

        verify(exactly = 1) { resourceService.getResources(coordinate, 2, 2, 0, null, cursor) }
    }

    @Test
    fun `a request without a cursor hands the service null`() {
        every { resourceService.getResources(coordinate, 2, 0, 0, null, null) } returns emptyPage()

        mockMvc
            .perform(get("/utdanning/vurdering/elevfravar?size=2").header("x-org-id", "fintlabs.no"))
            .andExpect(status().isOk)

        verify(exactly = 1) { resourceService.getResources(coordinate, 2, 0, 0, null, null) }
    }

    @Test
    fun `a token that is not a cursor is a bad request`() {
        mockMvc
            .perform(
                get("/utdanning/vurdering/elevfravar?size=2&cursor=not-a-cursor").header("x-org-id", "fintlabs.no"),
            ).andExpect(status().isBadRequest)

        verify(exactly = 0) { resourceService.getResources(any(), any(), any(), any(), any(), any()) }
    }

    private fun emptyPage() =
        createFintResourcesResponse(baseUrl, "utdanning/vurdering/elevfravar", emptyList(), 0, 2, 0)

    private fun consumerConfiguration() =
        ConsumerConfiguration(
            baseUrl = baseUrl,
            orgIdValue = "fintlabs.no",
            domain = "utdanning",
            packageName = "vurdering",
            podUrl = "http://localhost",
            autorelation = AutorelationConfig(enabled = true),
        )
}
