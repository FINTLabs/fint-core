package no.novari.fint.core.consumer.filter

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.novari.fint.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.model.resource.Link
import no.novari.fint.model.resource.utdanning.elev.ElevResource
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import no.novari.fint.core.consumer.filter.interfaces.OpaFilter as OpaFilterMixin

/**
 * Exercises the actual field/relation pruning the OPA layer exists for: a resource is serialized
 * through the [OpaFilter] with a given allowed-fields/relations set (what OPA returns), and the
 * output must contain only those.
 */
class OpaFilterTest {
    private fun prune(
        fields: Set<String>,
        relations: Set<String>,
    ): com.fasterxml.jackson.databind.JsonNode {
        val mapper =
            jacksonObjectMapper().apply {
                addMixIn(ElevResource::class.java, OpaFilterMixin::class.java)
                setFilterProvider(
                    SimpleFilterProvider()
                        .addFilter("opaFilter", OpaFilter(fields, relations))
                        .setFailOnUnknownId(false),
                )
            }
        return plainMapper.readTree(mapper.writeValueAsString(elev()))
    }

    @Test
    fun `keeps the allowed fields and relations and drops the rest`() {
        val tree = prune(fields = setOf("systemid"), relations = setOf("elevforhold"))

        assertTrue(tree.has("systemId"))
        assertFalse(tree.has("brukernavn"))
        assertFalse(tree.has("feidenavn"))

        val links = tree.get("_links")
        assertTrue(links.has("elevforhold"))
        assertFalse(links.has("basisgruppemedlemskap"))
    }

    @Test
    fun `empty allowed sets prune every data field and relation, keeping only _links`() {
        val tree = prune(fields = emptySet(), relations = emptySet())

        assertFalse(tree.has("systemId"))
        assertFalse(tree.has("brukernavn"))
        assertTrue(tree.has("_links"))
        assertFalse(tree.get("_links").has("elevforhold"))
    }

    private fun elev(): ElevResource =
        ElevResource().apply {
            systemId = identifikator("123")
            brukernavn = identifikator("user")
            feidenavn = identifikator("feide")
            addLink("elevforhold", Link.with("link/elevforhold/1"))
            addLink("basisgruppemedlemskap", Link.with("link/basisgruppemedlemskap/1"))
        }

    private fun identifikator(value: String): Identifikator =
        object : Identifikator() {
            init {
                identifikatorverdi = value
            }
        }

    companion object {
        private val plainMapper: ObjectMapper = jacksonObjectMapper()
    }
}
