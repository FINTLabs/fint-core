package no.novari.core.shared.relation

import no.novari.core.shared.store.IdentifierRef
import no.novari.core.shared.store.ResourceEntry
import no.novari.fint.core.model.FintResource
import no.novari.fint.core.model.Link
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.utdanning.elev.Elev
import org.bson.Document
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelationEdgeMergerTest {
    @Test
    fun `edges land under their inverse name on the resource they point at`() {
        val elevA = elev("E-1")
        val elevB = elev("E-2")
        val page =
            listOf(
                entry("E-1", "elevnummer" to "E-1", "systemid" to "S-1") to (elevA as FintResource),
                entry("E-2", "elevnummer" to "E-2", "systemid" to "S-2") to (elevB as FintResource),
            )

        listOf(
            edge("EF-1", "elevnummer", "E-1"),
            edge("EF-2", "systemid", "S-1"),
            edge("EF-3", "elevnummer", "E-2"),
        ).mergeInto(page)

        assertEquals(listOf("systemid" to "EF-1", "systemid" to "EF-2"), elevA.linkPairs("elevforhold"))
        assertEquals(listOf("systemid" to "EF-3"), elevB.linkPairs("elevforhold"))
    }

    @Test
    fun `a pair the adapter already supplied is not duplicated`() {
        val elev =
            elev("E-1").apply {
                addLink("elevforhold", Link("SystemId", "EF-1"))
            }
        val page = listOf(entry("E-1", "elevnummer" to "E-1") to (elev as FintResource))

        listOf(edge("EF-1", "elevnummer", "E-1")).mergeInto(page)

        assertEquals(1, elev.links["elevforhold"]!!.size)
    }

    @Test
    fun `an edge pointing at a resource outside the page is ignored`() {
        val elev = elev("E-1")
        val page = listOf(entry("E-1", "elevnummer" to "E-1") to (elev as FintResource))

        listOf(edge("EF-1", "elevnummer", "E-999")).mergeInto(page)

        assertNull(elev.links["elevforhold"])
    }

    @Test
    fun `an empty edge list leaves the page untouched`() {
        val elev = elev("E-1")
        val page = listOf(entry("E-1", "elevnummer" to "E-1") to (elev as FintResource))

        emptyList<RelationEdge>().mergeInto(page)

        assertTrue(elev.links.isEmpty())
    }

    private fun entry(
        id: String,
        vararg identifiers: Pair<String, String>,
    ) = ResourceEntry(
        id = id,
        data = Document(),
        identifiers = identifiers.map { IdentifierRef(it.first, it.second) },
        createdAt = Instant.EPOCH,
        lastModified = Instant.EPOCH,
    )

    private fun elev(id: String) = Elev(elevnummer = Identifikator(identifikatorverdi = id))

    private fun edge(
        sourceId: String,
        targetIdField: String,
        targetIdValue: String,
        inverseName: String = "elevforhold",
    ) = RelationEdge(
        id =
            relationEdgeId(
                sourceType = "utdanning/elev/elevforhold",
                sourceId = sourceId,
                relationName = "elev",
                targetType = "utdanning/elev/elev",
                targetIdField = targetIdField,
                targetIdValue = targetIdValue,
            ),
        sourceIdField = "systemid",
        sourceIdValue = sourceId,
        inverseName = inverseName,
        targetType = "utdanning/elev/elev",
        targetIdField = targetIdField,
        targetIdValue = targetIdValue,
    )

    private fun FintResource.linkPairs(relationName: String) = links[relationName]?.map { it.idField to it.idValue }
}
