package no.novari.core.shared.relation

import no.novari.core.shared.model.ResourceCoordinate
import no.novari.fint.core.model.Link
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.utdanning.elev.Elevforhold
import no.novari.fint.core.model.utdanning.elev.Klasse
import no.novari.fint.core.model.utdanning.elev.Skoleressurs
import no.novari.fint.core.model.utdanning.elev.Undervisningsforhold
import no.novari.fint.core.model.utdanning.vurdering.Fravarsoversikt
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelationEdgeFactoryTest {
    private val elevforholdCoordinate = ResourceCoordinate("fintlabs.no", "utdanning", "elev", "elevforhold")

    @Test
    fun `a one-to-many relation produces an edge with the inverse name and target as declared`() {
        val resource =
            elevforhold().apply {
                addLink("elev", Link("elevnummer", "E-456"))
            }

        val edges = RelationEdgeFactory.createRelationEdges(elevforholdCoordinate, "EF-123", resource)

        val edge = edges.single()
        assertEquals("elevforhold", edge.inverseName)
        assertEquals("utdanning/elev/elev", edge.targetType)
        assertEquals("elevnummer", edge.targetIdField)
        assertEquals("E-456", edge.targetIdValue)
        assertEquals("systemid", edge.sourceIdField)
        assertEquals("EF-123", edge.sourceIdValue)
        assertEquals(
            listOf("utdanning/elev/elevforhold", "EF-123", "elev", "utdanning/elev/elev", "elevnummer", "E-456")
                .joinToString(RELATION_EDGE_ID_DELIMITER),
            edge.id,
        )
    }

    @Test
    fun `the inverse name comes from the model, not the source resource name`() {
        val coordinate = ResourceCoordinate("fintlabs.no", "utdanning", "vurdering", "fravarsoversikt")
        val resource =
            Fravarsoversikt(systemId = Identifikator(identifikatorverdi = "FO-1")).apply {
                addLink("elevforhold", Link("systemid", "EF-123"))
            }

        val edges = RelationEdgeFactory.createRelationEdges(coordinate, "FO-1", resource)

        val edge = edges.single()
        assertEquals("elevfravar", edge.inverseName)
        assertEquals("utdanning/elev/elevforhold", edge.targetType)
    }

    @Test
    fun `relations that do not qualify produce no edges`() {
        val resource =
            elevforhold().apply {
                addLink("fravarsregistreringer", Link("systemid", "FR-1"))
                addLink("faggruppemedlemskap", Link("systemid", "FGM-1"))
                addLink("kategori", Link("systemid", "K-1"))
            }

        val edges = RelationEdgeFactory.createRelationEdges(elevforholdCoordinate, "EF-123", resource)

        assertTrue(edges.isEmpty(), "expected no edges for 1:1, many-to-one and inverse-less relations, got $edges")
    }

    @Test
    fun `a many-to-many relation produces edges only from its owning side`() {
        val klasse =
            Klasse(systemId = Identifikator(identifikatorverdi = "K-1")).apply {
                addLink("undervisningsforhold", Link("systemid", "UF-1"))
                addLink("undervisningsforhold", Link("systemid", "UF-2"))
            }
        val undervisningsforhold =
            Undervisningsforhold(systemId = Identifikator(identifikatorverdi = "UF-1")).apply {
                addLink("klasse", Link("systemid", "K-1"))
            }

        val owningSide =
            RelationEdgeFactory.createRelationEdges(
                ResourceCoordinate("fintlabs.no", "utdanning", "elev", "klasse"),
                "K-1",
                klasse,
            )
        val nonOwningSide =
            RelationEdgeFactory.createRelationEdges(
                ResourceCoordinate("fintlabs.no", "utdanning", "elev", "undervisningsforhold"),
                "UF-1",
                undervisningsforhold,
            )

        assertEquals(2, owningSide.size, "the owning side iterates every declared link")
        assertTrue(owningSide.all { it.inverseName == "klasse" && it.targetType == "utdanning/elev/undervisningsforhold" })
        assertTrue(nonOwningSide.isEmpty(), "the non-owning side of a many-to-many is not authoritative")
    }

    @Test
    fun `a cross-domain relation produces no edges`() {
        val coordinate = ResourceCoordinate("fintlabs.no", "utdanning", "elev", "skoleressurs")
        val resource =
            Skoleressurs(systemId = Identifikator(identifikatorverdi = "SR-1")).apply {
                addLink("personalressurs", Link("ansattnummer", "A-1"))
            }

        val edges = RelationEdgeFactory.createRelationEdges(coordinate, "SR-1", resource)

        assertTrue(edges.isEmpty(), "a target in another domain must never receive edges, got $edges")
    }

    @Test
    fun `a single-valued relation takes the first link only`() {
        val resource =
            elevforhold().apply {
                addLink("elev", Link("elevnummer", "E-1"))
                addLink("elev", Link("elevnummer", "E-2"))
            }

        val edges = RelationEdgeFactory.createRelationEdges(elevforholdCoordinate, "EF-123", resource)

        assertEquals("E-1", edges.single().targetIdValue)
    }

    @Test
    fun `an unresolved link produces no edge`() {
        val resource =
            elevforhold().apply {
                addLink("elev", Link(unresolved = "https://elsewhere.example/elev/1"))
            }

        val edges = RelationEdgeFactory.createRelationEdges(elevforholdCoordinate, "EF-123", resource)

        assertTrue(edges.isEmpty())
    }

    @Test
    fun `a sync id matching no identifier value breaks the contract and derives nothing`() {
        val resource =
            elevforhold(id = "EF-123").apply {
                addLink("elev", Link("elevnummer", "E-456"))
            }

        val edges = RelationEdgeFactory.createRelationEdges(elevforholdCoordinate, "opaque-sync-key", resource)

        assertTrue(edges.isEmpty())
    }

    @Test
    fun `a resource without any identifier value derives nothing`() {
        val resource =
            Elevforhold().apply {
                addLink("elev", Link("elevnummer", "E-456"))
            }

        val edges = RelationEdgeFactory.createRelationEdges(elevforholdCoordinate, "EF-123", resource)

        assertTrue(edges.isEmpty())
    }

    private fun elevforhold(id: String = "EF-123") = Elevforhold(systemId = Identifikator(identifikatorverdi = id))
}
