package no.novari.fint.core.consumer.resource

import no.novari.fint.core.shared.cache.CacheService
import no.novari.fint.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.model.resource.Link
import no.novari.fint.model.resource.utdanning.elev.ElevResource
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["fint.security.enabled=false"],
)
@EmbeddedKafka
@ActiveProfiles("utdanning-elev")
class ContentLengthFilterIT {
    @Autowired
    private lateinit var cacheService: CacheService

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `collection response larger than the tomcat buffer carries a Content-Length and is not chunked`() {
        val cache = cacheService.getCache("utdanning_elev_elev")
        val now = System.currentTimeMillis()
        repeat(200) { i -> cache.put(i.toString(), elev(i), now) }

        val response =
            HttpClient
                .newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build()
                .send(
                    HttpRequest.newBuilder(URI.create("http://localhost:$port/utdanning/elev/elev")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray(),
                )

        assertEquals(200, response.statusCode())
        assertTrue(
            response.body().size > 8192,
            "body must exceed Tomcat's 8KB buffer so it would otherwise be chunked; was ${response.body().size}",
        )

        val contentLength = response.headers().firstValue("Content-Length")
        assertTrue(contentLength.isPresent, "Content-Length must be present")
        assertEquals(response.body().size.toLong(), contentLength.get().toLong())
        assertTrue(
            response.headers().allValues("Transfer-Encoding").none { it.contains("chunked", ignoreCase = true) },
            "response must not be chunked",
        )
    }

    private fun elev(i: Int): ElevResource =
        ElevResource().apply {
            systemId = identifikator("system-$i")
            brukernavn = identifikator("brukernavn-$i")
            feidenavn = identifikator("feidenavn-$i")
            addLink("elevforhold", Link.with("link/elevforhold/$i"))
            addLink("basisgruppemedlemskap", Link.with("link/basisgruppemedlemskap/$i"))
        }

    private fun identifikator(value: String): Identifikator =
        object : Identifikator() {
            init {
                identifikatorverdi = value
            }
        }
}
