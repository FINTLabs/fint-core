package no.novari.core.shared.json

import com.fasterxml.jackson.databind.ObjectMapper
import no.novari.fint.core.model.Link
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.utdanning.elev.Elev
import no.novari.fint.core.model.utdanning.timeplan.Fag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FintModelModuleTest {
    private val mapper: ObjectMapper = FintJson.storageMapper()

    private fun elev(links: String) = mapper.readValue("""{ "_links": { $links } }""", Elev::class.java)

    @Test
    fun `metadata is never serialized`() {
        val json = mapper.writeValueAsString(Elev(elevnummer = Identifikator(identifikatorverdi = "456")))

        assertTrue("metadata" !in json, "metadata leaked into $json")
        assertTrue("elevnummer" in json)
    }

    @Test
    fun `the last segment is the id value and the one before it the id field`() {
        val elev = elev(""""person": [ { "href": "https://api.felleskomponent.no/felles/person/fodselsnummer/123" } ]""")

        assertEquals(Link("fodselsnummer", "123"), elev.relationLinks("person").single())
    }

    @Test
    fun `an encoded id value is decoded and stored whole`() {
        val elev = elev(""""person": [ { "href": "https://api.felleskomponent.no/felles/person/fodselsnummer/ABC%2FDEF%2F1" } ]""")

        assertEquals(Link("fodselsnummer", "ABC/DEF/1"), elev.relationLinks("person").single())
    }

    @Test
    fun `an encoded space is decoded, not turned into a plus`() {
        val elev = elev(""""person": [ { "href": "https://api.felleskomponent.no/felles/person/fodselsnummer/a%20b" } ]""")

        assertEquals(Link("fodselsnummer", "a b"), elev.relationLinks("person").single())
    }

    @Test
    fun `a raw unencoded value containing a slash no longer resolves`() {
        val href = "https://api.felleskomponent.no/felles/person/fodselsnummer/ABC/DEF/1"
        val elev = elev(""""person": [ { "href": "$href" } ]""")

        assertEquals(Link(unresolved = href), elev.relationLinks("person").single())
    }

    @Test
    fun `a raw value with a stray percent is kept verbatim rather than failing the page`() {
        val elev = elev(""""person": [ { "href": "https://api.felleskomponent.no/felles/person/fodselsnummer/100%" } ]""")

        assertEquals(Link("fodselsnummer", "100%"), elev.relationLinks("person").single())
    }

    @Test
    fun `relation names are matched regardless of case`() {
        val elev = elev(""""PERSON": [ { "href": "https://api.felleskomponent.no/felles/person/fodselsnummer/123" } ]""")

        assertEquals(Link("fodselsnummer", "123"), elev.relationLinks("PERSON").single())
    }

    @Test
    fun `relative hrefs are read the same way`() {
        val elev = elev(""""person": [ { "href": "fodselsnummer/123" } ]""")

        assertEquals(Link("fodselsnummer", "123"), elev.relationLinks("person").single())
    }

    @Test
    fun `referanse targets have no id fields so the href is kept unresolved`() {
        val fag =
            mapper.readValue(
                """{ "_links": { "grepreferanse": [ { "href": "https://data.udir.no/kl06/v201906/fagkoder/FSP01-01" } ] } }""",
                Fag::class.java,
            )

        assertEquals(
            Link(unresolved = "https://data.udir.no/kl06/v201906/fagkoder/FSP01-01"),
            fag.relationLinks("grepreferanse").single(),
        )
    }

    @Test
    fun `unparseable hrefs are stored unresolved rather than discarded`() {
        val elev = elev(""""person": [ { "href": "RandomInvalidLink" } ]""")

        assertEquals(Link(unresolved = "RandomInvalidLink"), elev.relationLinks("person").single())
    }

    @Test
    fun `null link entries are dropped`() {
        val elev = elev(""""person": [ null, { "href": "fodselsnummer/123" } ]""")

        assertEquals(1, elev.relationLinks("person").size)
    }

    @Test
    fun `stored id-based links are read back unchanged`() {
        val elev = elev(""""person": [ { "idField": "fodselsnummer", "idValue": "123" } ]""")

        assertEquals(Link("fodselsnummer", "123"), elev.relationLinks("person").single())
    }

    @Test
    fun `stored unresolved links survive a round trip`() {
        val elev = elev(""""person": [ { "unresolved": "https://data.udir.no/whatever" } ]""")

        assertEquals(Link(unresolved = "https://data.udir.no/whatever"), elev.relationLinks("person").single())
    }

    @Test
    fun `links are written back under _links in id form`() {
        val elev =
            Elev(elevnummer = Identifikator(identifikatorverdi = "456")).apply {
                addLink("person", Link("fodselsnummer", "123"))
            }

        val tree = mapper.readTree(mapper.writeValueAsString(elev))
        val person = tree.get("_links").get("person").get(0)

        assertEquals("fodselsnummer", person.get("idField").asText())
        assertEquals("123", person.get("idValue").asText())
        assertNull(tree.get("links"))
    }

    @Test
    fun `nested resources are read against their own relations`() {
        val payload =
            """
            {
              "hybeladresse": {
                "postnummer": "0150",
                "_links": { "land": [ { "href": "https://api.felleskomponent.no/felles/kodeverk/iso/landkode/systemid/NO" } ] }
              }
            }
            """.trimIndent()

        val elev = mapper.readValue(payload, Elev::class.java)

        assertEquals(Link("systemid", "NO"), elev.hybeladresse?.relationLinks("land")?.single())
    }
}
