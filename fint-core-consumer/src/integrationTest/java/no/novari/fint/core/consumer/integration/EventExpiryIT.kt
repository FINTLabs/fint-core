package no.novari.fint.core.consumer.integration

import no.novari.fint.core.consumer.Application
import org.awaitility.kotlin.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration

@SpringBootTest(classes = [Application::class])
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1)
@TestPropertySource(
    properties = [
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        "novari.kafka.default-replicas=1",
        "fint.relation.base-url=https://test.felleskomponent.no",
        "fint.org-id=foo.org",
        "fint.consumer.org-id=foo.org",
        "fint.consumer.domain=utdanning",
        "fint.consumer.package=elev",
        "fint.security.enabled=false",
        "fint.consumer.event.defaults.eviction=1s",
    ],
)
@DirtiesContext
class EventExpiryIT {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private val base = "/utdanning/elev/elev"

    @Test
    fun `corrId expires before adapter responds - status endpoint returns 410`() {
        val location =
            mockMvc
                .post(base) {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"systemId":{"identifikatorverdi":"123"}}"""
                }.andExpect { status { isAccepted() } }
                .andReturn()
                .response
                .getHeader("Location")
                ?: error("No Location header in response")

        val corrId = location.substringAfterLast("/")

        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            mockMvc
                .get("$base/status/$corrId")
                .andExpect { status { isEqualTo(410) } }
        }
    }
}
