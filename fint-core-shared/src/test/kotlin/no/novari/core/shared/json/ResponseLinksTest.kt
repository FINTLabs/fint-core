package no.novari.core.shared.json

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.novari.fint.core.model.Link
import no.novari.fint.core.model.felles.Person
import no.novari.fint.core.model.felles.kompleksedatatyper.Adresse
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.utdanning.elev.Elev
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResponseLinksTest {
    private val baseUrl = "https://api.felleskomponent.no"

    private val mapper = FintJson.responseMapper(baseUrl)

    private val servedMapper = FintJson.responseMapper(baseUrl) { "utdanning/elev" }

    private fun wire(
        resource: Any,
        with: ObjectMapper = mapper,
    ): JsonNode = with.readTree(with.writeValueAsString(resource))

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
    fun `nested resources render their own links as hrefs`() {
        val elev =
            Elev(
                systemId = Identifikator(identifikatorverdi = "123"),
                hybeladresse =
                    Adresse(postnummer = "0150").apply {
                        addLink("land", Link("systemid", "NO"))
                    },
            )

        val nested = wire(elev).get("hybeladresse")

        assertEquals(listOf("$baseUrl/felles/kodeverk/iso/landkode/systemid/NO"), hrefs(nested, "land"))
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

    @Test
    fun `a served common resource gets self links under the component it was reached through`() {
        val person = Person(fodselsnummer = Identifikator(identifikatorverdi = "01010112345"))

        assertEquals(
            listOf("$baseUrl/utdanning/elev/person/fodselsnummer/01010112345"),
            hrefs(wire(person, servedMapper), "self"),
        )
        assertEquals(
            listOf("$baseUrl/administrasjon/personal/person/fodselsnummer/01010112345"),
            hrefs(wire(person, FintJson.responseMapper(baseUrl) { "administrasjon/personal" }), "self"),
        )
    }

    @Test
    fun `a common resource with no component in scope gets no self link`() {
        val person = Person(fodselsnummer = Identifikator(identifikatorverdi = "01010112345"))

        assertNull(wire(person).get("_links"))
    }

    @Test
    fun `a served common resource's links to common targets resolve against the same component`() {
        val person =
            Person(fodselsnummer = Identifikator(identifikatorverdi = "01010112345")).apply {
                addLink("foreldreansvar", Link("fodselsnummer", "02020254321"))
            }

        assertEquals(
            listOf("$baseUrl/utdanning/elev/person/fodselsnummer/02020254321"),
            hrefs(wire(person, servedMapper), "foreldreansvar"),
        )
    }

    @Test
    fun `a served common resource's links to fixed-path targets ignore the component`() {
        val person =
            Person(fodselsnummer = Identifikator(identifikatorverdi = "01010112345")).apply {
                addLink("kommune", Link("systemid", "3201"))
            }

        assertEquals(
            listOf("$baseUrl/felles/kodeverk/kommune/systemid/3201"),
            hrefs(wire(person, servedMapper), "kommune"),
        )
    }

    @Test
    fun `a common resource's links to common targets are dropped when no component is in scope`() {
        val person =
            Person(fodselsnummer = Identifikator(identifikatorverdi = "01010112345")).apply {
                addLink("foreldreansvar", Link("fodselsnummer", "02020254321"))
            }

        assertNull(wire(person).get("_links"))
    }

    @Test
    fun `nested resources stay linkless even when a component is in scope`() {
        val adresse = Adresse(postnummer = "0150")

        assertNull(wire(adresse, servedMapper).get("_links"))
    }
}
