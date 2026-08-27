package no.fintlabs.consumer.resource

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.operation.OperationType
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.config.JacksonConfiguration
import no.fintlabs.consumer.config.TomcatConfiguration
import no.fintlabs.consumer.resource.event.RequestAccepted
import no.fintlabs.consumer.resource.event.RequestFailed
import no.fintlabs.consumer.resource.event.RequestFintEventService
import no.fintlabs.consumer.resource.event.RequestGone
import no.fintlabs.consumer.resource.event.RequestStatusService
import no.fintlabs.consumer.resource.event.RequestValidated
import no.fintlabs.consumer.resource.event.ResourceCreated
import no.fintlabs.consumer.resource.event.ResourceDeleted
import no.novari.core.shared.model.ResourceCoordinate
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ResourceEventEndpointsIT.SliceApplication::class],
    properties = [
        "fint.consumer.base-url=https://api.felleskomponent.no",
        "fint.consumer.org-id=fintlabs.no",
        "fint.consumer.domain=utdanning",
        "fint.consumer.package-name=elev",
        "fint.consumer.pod-url=http://localhost",
    ],
)
class ResourceEventEndpointsIT {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableConfigurationProperties(ConsumerConfiguration::class)
    @Import(ResourceController::class, JacksonConfiguration::class, TomcatConfiguration::class)
    open class SliceApplication

    @MockitoBean
    private lateinit var resourceService: ResourceService

    @MockitoBean
    private lateinit var requestFintEventService: RequestFintEventService

    @MockitoBean
    private lateinit var requestStatusService: RequestStatusService

    @LocalServerPort
    private var port = 0

    private val client = HttpClient.newHttpClient()
    private val coordinate = ResourceCoordinate("fintlabs.no", "utdanning", "elev", "elev")
    private val statusUrlBase = "https://api.felleskomponent.no/utdanning/elev/elev/status"

    @Test
    fun `posting a resource returns 202 with a status location`() {
        given(requestFintEventService.createAndPublish(coordinate, mapOf("navn" to "Testesen"), false))
            .willReturn(eventWith("corr-1"))

        val response = post("/utdanning/elev/elev", """{"navn":"Testesen"}""")

        assertEquals(202, response.statusCode())
        assertEquals("$statusUrlBase/corr-1", locationOf(response))
    }

    @Test
    fun `posting with validate=true requests validation instead of creation`() {
        given(requestFintEventService.createAndPublish(coordinate, mapOf("navn" to "Testesen"), true))
            .willReturn(eventWith("corr-2"))

        val response = post("/utdanning/elev/elev?validate=true", """{"navn":"Testesen"}""")

        assertEquals(202, response.statusCode())
        assertEquals("$statusUrlBase/corr-2", locationOf(response))
    }

    @Test
    fun `updating by id returns 202 with a status location`() {
        given(
            requestFintEventService.createAndPublish(coordinate, mapOf("navn" to "Testesen"), OperationType.UPDATE),
        ).willReturn(eventWith("corr-3"))

        val response = put("/utdanning/elev/elev/systemid/42", """{"navn":"Testesen"}""")

        assertEquals(202, response.statusCode())
        assertEquals("$statusUrlBase/corr-3", locationOf(response))
    }

    @Test
    fun `status of a created resource is 201 with location and body`() {
        val selfLink = URI.create("https://api.felleskomponent.no/utdanning/elev/elev/systemid/42")
        given(requestStatusService.getStatusResponse(coordinate, "corr-4"))
            .willReturn(ResourceCreated(mapOf("navn" to "Testesen"), selfLink))

        val response = statusOf("corr-4")

        assertEquals(201, response.statusCode())
        assertEquals(selfLink.toString(), locationOf(response))
        assertTrue(response.body().contains("Testesen"))
    }

    @Test
    fun `status of a validated request is 200 with the validation body`() {
        given(requestStatusService.getStatusResponse(coordinate, "corr-5"))
            .willReturn(RequestValidated(mapOf("message" to "ok")))

        val response = statusOf("corr-5")

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("ok"))
    }

    @Test
    fun `status of a pending request is 202`() {
        given(requestStatusService.getStatusResponse(coordinate, "corr-6")).willReturn(RequestAccepted)

        assertEquals(202, statusOf("corr-6").statusCode())
    }

    @Test
    fun `status of a deleted resource is 204`() {
        given(requestStatusService.getStatusResponse(coordinate, "corr-7")).willReturn(ResourceDeleted)

        assertEquals(204, statusOf("corr-7").statusCode())
    }

    @Test
    fun `status of a purged request is 410`() {
        given(requestStatusService.getStatusResponse(coordinate, "corr-8")).willReturn(RequestGone)

        assertEquals(410, statusOf("corr-8").statusCode())
    }

    @Test
    fun `each failure type maps to its own http status`() {
        given(requestStatusService.getStatusResponse(coordinate, "rejected"))
            .willReturn(RequestFailed(mapOf("errorMessage" to "no"), RequestFailed.FailureType.REJECTED))
        given(requestStatusService.getStatusResponse(coordinate, "conflict"))
            .willReturn(RequestFailed(mapOf("navn" to "Testesen"), RequestFailed.FailureType.CONFLICT))
        given(requestStatusService.getStatusResponse(coordinate, "error"))
            .willReturn(RequestFailed(mapOf("errorMessage" to "boom"), RequestFailed.FailureType.ERROR))

        assertEquals(400, statusOf("rejected").statusCode())
        assertEquals(409, statusOf("conflict").statusCode())
        assertEquals(500, statusOf("error").statusCode())
    }

    private fun eventWith(id: String): RequestFintEvent =
        RequestFintEvent().apply {
            corrId = id
            resourceName = "elev"
        }

    private fun post(
        path: String,
        body: String,
    ): HttpResponse<String> =
        send(
            HttpRequest
                .newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)),
        )

    private fun put(
        path: String,
        body: String,
    ): HttpResponse<String> =
        send(
            HttpRequest
                .newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)),
        )

    private fun statusOf(corrId: String): HttpResponse<String> =
        send(HttpRequest.newBuilder(uri("/utdanning/elev/elev/status/$corrId")).GET())

    private fun send(request: HttpRequest.Builder): HttpResponse<String> =
        client.send(
            request.header("x-org-id", "fintlabs.no").build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun uri(path: String): URI = URI.create("http://localhost:$port$path")

    private fun locationOf(response: HttpResponse<String>): String =
        response.headers().firstValue("Location").orElse("")
}
