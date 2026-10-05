package no.novari.core.shared.json

import no.novari.fint.core.model.FintResource
import no.novari.fint.core.model.Link
import no.novari.fint.core.model.felles.kompleksedatatyper.Adresse
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.utdanning.elev.Elev
import no.novari.fint.core.model.utdanning.elev.Elevforhold
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals

class SortedLinksSerializerTest {
    private val mapper: JsonMapper = FintJson.storageMapper()

    @Test
    fun `relation names are written in alphabetical order`() {
        val elevforhold =
            elevforhold().apply {
                addLink("skole", Link("skolenummer", "S-1"))
                addLink("kategori", Link("systemid", "K-1"))
                addLink("elev", Link("elevnummer", "E-1"))
            }

        assertEquals(listOf("elev", "kategori", "skole"), storedLinks(elevforhold).names())
    }

    @Test
    fun `links under one relation are written by id field and then by id value`() {
        val elevforhold =
            elevforhold().apply {
                addLink("elev", Link("systemid", "2"))
                addLink("elev", Link("elevnummer", "9"))
                addLink("elev", Link("systemid", "1"))
            }

        assertEquals(
            listOf("elevnummer/9", "systemid/1", "systemid/2"),
            storedLinks(elevforhold).get("elev").values().map { "${it.get("idField").asString()}/${it.get("idValue").asString()}" },
        )
    }

    @Test
    fun `links without an id field are written first, ordered by their href`() {
        val elevforhold =
            elevforhold().apply {
                addLink("elev", Link("systemid", "1"))
                addLink("elev", Link(unresolved = "https://example.com/b"))
                addLink("elev", Link(unresolved = "https://example.com/a"))
            }

        assertEquals(
            listOf("https://example.com/a", "https://example.com/b", "1"),
            storedLinks(elevforhold).get("elev").values().map { (it.get("unresolved") ?: it.get("idValue")).asString() },
        )
    }

    @Test
    fun `the same links added in another order are written as the same JSON`() {
        val first =
            elevforhold().apply {
                addLink("skole", Link("skolenummer", "S-1"))
                addLink("elev", Link("elevnummer", "E-2"))
                addLink("elev", Link("elevnummer", "E-1"))
            }
        val second =
            elevforhold().apply {
                addLink("elev", Link("elevnummer", "E-1"))
                addLink("elev", Link("elevnummer", "E-2"))
                addLink("skole", Link("skolenummer", "S-1"))
            }

        assertEquals(mapper.writeValueAsString(first), mapper.writeValueAsString(second))
    }

    @Test
    fun `links on a nested resource are written sorted too`() {
        val hybeladresse =
            Adresse(postnummer = "0150").apply {
                addLink("land", Link("systemid", "SE"))
                addLink("land", Link("systemid", "NO"))
            }
        val elev = Elev(systemId = Identifikator(identifikatorverdi = "E-1"), hybeladresse = hybeladresse)

        val land = tree(elev).get("hybeladresse").get("_links").get("land")

        assertEquals(listOf("NO", "SE"), land.values().map { it.get("idValue").asString() })
    }

    @Test
    fun `writing leaves the links on the resource in the order they were added`() {
        val elevforhold =
            elevforhold().apply {
                addLink("skole", Link("skolenummer", "S-1"))
                addLink("elev", Link("elevnummer", "E-2"))
                addLink("elev", Link("elevnummer", "E-1"))
            }

        mapper.writeValueAsString(elevforhold)

        assertEquals(listOf("skole", "elev"), elevforhold.links.keys.toList())
        assertEquals(listOf("E-2", "E-1"), elevforhold.relationLinks("elev").map { it.idValue })
    }

    @Test
    fun `the response form still writes self first`() {
        val elevforhold =
            elevforhold().apply {
                addLink("skole", Link("skolenummer", "S-1"))
                addLink("elev", Link("elevnummer", "E-1"))
            }
        val responseMapper = FintJson.responseMapper("https://api.felleskomponent.no")

        val links = responseMapper.readTree(responseMapper.writeValueAsString(elevforhold)).get("_links")

        assertEquals("self", links.names().first())
    }

    private fun elevforhold() = Elevforhold(systemId = Identifikator(identifikatorverdi = "EF-1"))

    private fun tree(resource: FintResource): JsonNode = mapper.readTree(mapper.writeValueAsString(resource))

    private fun storedLinks(resource: FintResource): JsonNode = tree(resource).get("_links")

    private fun JsonNode.names(): List<String> = properties().map { it.key }
}
