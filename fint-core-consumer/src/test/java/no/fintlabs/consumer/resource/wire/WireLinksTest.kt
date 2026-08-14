package no.fintlabs.consumer.resource.wire

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.util.ISO8601DateFormat
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import no.novari.core.shared.json.FintModelModule
import no.novari.fint.core.model.Link
import no.novari.fint.core.model.felles.kompleksedatatyper.Adresse
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.utdanning.elev.Elev
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WireLinksTest {
    private val baseUrl = "https://api.felleskomponent.no"

    private val mapper =
        Jackson2ObjectMapperBuilder()
            .failOnUnknownProperties(false)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .modules(
                JavaTimeModule(),
                KotlinModule.Builder().build(),
                FintModelModule(),
                WireLinksModule(baseUrl),
            ).dateFormat(ISO8601DateFormat())
            .build<com.fasterxml.jackson.databind.ObjectMapper>()

    private fun wire(resource: Any): JsonNode = mapper.readTree(mapper.writeValueAsString(resource))

    private fun hrefs(
        node: JsonNode,
        relation: String,
    ): List<String> = node.get("_links").get(relation).map { it.get("href").asText() }

    @Test
    fun `self links are generated from every id field that has a value`() {
        val elev =
            Elev(
                systemId = Identifikator(identifikatorverdi = "123"),
                elevnummer = Identifikator(identifikatorverdi = "ELEV-1"),
            )

        assertEquals(
            listOf(
                "$baseUrl/utdanning/elev/elev/elevnummer/ELEV-1",
                "$baseUrl/utdanning/elev/elev/systemid/123",
            ),
            hrefs(wire(elev), "self").sorted(),
        )
    }

    @Test
    fun `relation links are rendered against the target's path`() {
        val elev =
            Elev(systemId = Identifikator(identifikatorverdi = "123")).apply {
                addLink("person", Link("fodselsnummer", "01010112345"))
                addLink("elevforhold", Link("systemid", "EF-1"))
            }

        val wire = wire(elev)

        assertEquals(
            listOf("$baseUrl/utdanning/elev/person/fodselsnummer/01010112345"),
            hrefs(wire, "person"),
        )
        assertEquals(
            listOf("$baseUrl/utdanning/elev/elevforhold/systemid/EF-1"),
            hrefs(wire, "elevforhold"),
        )
    }

    @Test
    fun `unresolved links are emitted as they arrived`() {
        val elev =
            Elev(systemId = Identifikator(identifikatorverdi = "123")).apply {
                addLink("person", Link(unresolved = "https://data.udir.no/whatever"))
            }

        assertEquals(listOf("https://data.udir.no/whatever"), hrefs(wire(elev), "person"))
    }

    @Test
    fun `id values are encoded in the rendered href`() {
        val elev = Elev(systemId = Identifikator(identifikatorverdi = "a b/c"))

        assertEquals(
            listOf("$baseUrl/utdanning/elev/elev/systemid/a%20b%2Fc"),
            hrefs(wire(elev), "self"),
        )
    }

    @Test
    fun `a stored self link never overrides the generated one`() {
        val elev =
            Elev(systemId = Identifikator(identifikatorverdi = "123")).apply {
                addLink("self", Link(unresolved = "https://stale.example.no/old"))
            }

        assertEquals(listOf("$baseUrl/utdanning/elev/elev/systemid/123"), hrefs(wire(elev), "self"))
    }

    @Test
    fun `nested resources without their own path get no self link`() {
        val adresse = Adresse(postnummer = "0150")

        assertNull(wire(adresse).get("_links"))
    }

    @Test
    fun `metadata and the raw link form never reach the wire`() {
        val json =
            mapper.writeValueAsString(
                Elev(systemId = Identifikator(identifikatorverdi = "123")).apply {
                    addLink("person", Link("fodselsnummer", "01010112345"))
                },
            )

        assertTrue("metadata" !in json, "metadata leaked into $json")
        assertTrue("idField" !in json, "raw link form leaked into $json")
        assertTrue("\"links\"" !in json, "unrenamed links property leaked into $json")
    }
}
