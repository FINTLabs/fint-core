package no.novari.fint.core.consumer.resource

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

@SpringBootTest(
    properties = [
        "fint.security.enabled=false",
    ],
)
@AutoConfigureMockMvc
@EmbeddedKafka
@ActiveProfiles("utdanning-elev")
class ResourceControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private val base = "/utdanning/elev/elev"

    @Test
    fun `GET resource returns 200`() {
        mockMvc.get(base).andExpect { status { isOk() } }
    }

    @Test
    fun `GET unknown resource returns 404`() {
        mockMvc.get("/utdanning/elev/unknownresource").andExpect { status { isNotFound() } }
    }

    @Test
    fun `GET resource by valid id field returns 404 when value not found`() {
        mockMvc.get("$base/systemid/nonexistent").andExpect { status { isNotFound() } }
    }

    @Test
    fun `GET resource by invalid id field returns 404`() {
        mockMvc.get("$base/invalidfield/123").andExpect { status { isNotFound() } }
    }

    @Test
    fun `GET last-updated returns 200 with timestamp`() {
        mockMvc.get("$base/last-updated").andExpect {
            status { isOk() }
            jsonPath("$.lastUpdated") { exists() }
        }
    }

    @Test
    fun `GET cache-size returns 200`() {
        mockMvc.get("$base/cache/size").andExpect {
            status { isOk() }
            jsonPath("$.size") { exists() }
        }
    }

    @Test
    fun `GET status returns 410 for unknown corrId`() {
        mockMvc.get("$base/status/abc-123").andExpect { status { isEqualTo(410) } }
    }

    @Test
    fun `POST resource returns 202 with location header`() {
        mockMvc
            .post(base) {
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Test Elev"}"""
            }.andExpect {
                status { isAccepted() }
                header { exists("Location") }
            }
    }

    @Test
    fun `PUT resource returns 202 with location header`() {
        mockMvc
            .put("$base/systemid/123") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Updated Elev"}"""
            }.andExpect {
                status { isAccepted() }
                header { exists("Location") }
            }
    }

    @Test
    fun `PUT resource with invalid id field returns 404`() {
        mockMvc
            .put("$base/invalidfield/123") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Updated Elev"}"""
            }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `POST query returns 200`() {
        mockMvc
            .post("$base/\$query") {
                contentType = MediaType.TEXT_PLAIN
                content = "systemId/identifikatorverdi eq '123'"
            }.andExpect { status { isOk() } }
    }
}
