package no.novari.core.shared.relation

import com.mongodb.client.MongoClients
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.store.FintResourceBsonConverter
import no.novari.core.shared.store.IdentifierRef
import no.novari.core.shared.store.ResourceStore
import no.novari.core.shared.store.Save
import no.novari.fint.core.model.Link
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.utdanning.elev.Elev
import org.assertj.core.api.Assertions.assertThat
import org.bson.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import java.time.Instant

@Testcontainers
class BackLinkStoreIT {
    private val edgeStore = RelationEdgeStore(template)
    private val resourceStore = ResourceStore(template, FintResourceBsonConverter())
    private val backLinks = BackLinkStore(template, edgeStore)

    companion object {
        @Container
        val mongo = MongoDBContainer("mongo:8.0.4")

        private val orgId = OrgId.from("test.org.no")
        private val elev = ResourceCoordinate("test.org.no", "utdanning", "elev", "elev")
        private val elever = elev.toCollectionName()
        private val edges = elev.toEdgeCollectionName()
        private const val ELEV = "utdanning/elev/elev"
        private const val ELEVFORHOLD = "utdanning/elev/elevforhold"
        private val stored = Instant.parse("2026-09-11T10:00:00Z")
        private val later = stored.plusSeconds(60)
        private val template by lazy { MongoTemplate(MongoClients.create(mongo.connectionString), "test") }
    }

    @BeforeEach
    fun dropCollections() {
        template.dropCollection(elever)
        template.dropCollection(edges)
    }

    @Test
    fun `an added edge gives its target a back-link and moves lastModified`() {
        storeElev("1")

        backLinks.patch(orgId, pointAt("EF-1", "systemid" to "1"), later)

        assertThat(backLinksOf("1")).containsExactly("systemid/EF-1")
        assertThat(lastModifiedOf("1")).isEqualTo(later)
    }

    @Test
    fun `a target is found through any of its identifiers`() {
        storeElev("1")

        backLinks.patch(orgId, pointAt("EF-1", "elevnummer" to "N-1"), later)

        assertThat(backLinksOf("1")).containsExactly("systemid/EF-1")
    }

    @Test
    fun `an identifier value held under another field does not make a resource the target`() {
        storeElev("1")

        backLinks.patch(orgId, pointAt("EF-1", "elevnummer" to "1"), later)

        assertThat(backLinksOf("1")).isEmpty()
        assertThat(lastModifiedOf("1")).isEqualTo(stored)
    }

    @Test
    fun `back-links from several sources are all kept`() {
        storeElev("1")

        backLinks.patch(orgId, pointAt("EF-1", "systemid" to "1"), later)
        backLinks.patch(orgId, pointAt("EF-2", "systemid" to "1"), later)

        assertThat(backLinksOf("1")).containsExactlyInAnyOrder("systemid/EF-1", "systemid/EF-2")
    }

    @Test
    fun `a removed edge takes the back-link away and moves lastModified`() {
        storeElev("1")
        backLinks.patch(orgId, pointAt("EF-1", "systemid" to "1"), stored)
        backLinks.patch(orgId, pointAt("EF-2", "systemid" to "1"), stored)

        backLinks.patch(orgId, pointAt("EF-1"), later)

        assertThat(backLinksOf("1")).containsExactly("systemid/EF-2")
        assertThat(lastModifiedOf("1")).isEqualTo(later)
    }

    @Test
    fun `a back-link stays while another edge of the source still gives it`() {
        storeElev("1")
        backLinks.patch(orgId, pointAt("EF-1", "systemid" to "1", "elevnummer" to "N-1"), stored)

        backLinks.patch(orgId, pointAt("EF-1", "elevnummer" to "N-1"), later)

        assertThat(backLinksOf("1")).containsExactly("systemid/EF-1")
        assertThat(lastModifiedOf("1")).isEqualTo(stored)
    }

    @Test
    fun `a back-link stays while another source gives the same link`() {
        storeElev("1")
        backLinks.patch(orgId, pointAt("old-id", "systemid" to "1", sourceIdValue = "EF-1"), stored)
        backLinks.patch(orgId, pointAt("new-id", "systemid" to "1", sourceIdValue = "EF-1"), stored)

        backLinks.patch(orgId, edgeStore.applyAll(listOf(RelationEdgeWrite.Delete(edges, ELEVFORHOLD, "old-id"))), later)

        assertThat(backLinksOf("1")).containsExactly("systemid/EF-1")
        assertThat(lastModifiedOf("1")).isEqualTo(stored)
    }

    @Test
    fun `an edge stored again with another source identifier swaps the back-link`() {
        storeElev("1")
        backLinks.patch(orgId, pointAt("EF-1", "systemid" to "1"), stored)

        backLinks.patch(orgId, pointAt("EF-1", "systemid" to "1", sourceIdValue = "EF-1-renamed"), later)

        assertThat(backLinksOf("1")).containsExactly("systemid/EF-1-renamed")
        assertThat(lastModifiedOf("1")).isEqualTo(later)
    }

    @Test
    fun `a back-link the adapter already delivered is stored without moving lastModified`() {
        storeElev("1") { addLink("elevforhold", Link("systemId", "EF-1")) }

        backLinks.patch(orgId, pointAt("EF-1", "systemid" to "1"), later)

        assertThat(backLinksOf("1")).containsExactly("systemid/EF-1")
        assertThat(lastModifiedOf("1")).isEqualTo(stored)
    }

    @Test
    fun `a back-link that is already stored does not move lastModified`() {
        storeElev("1")
        val changes = pointAt("EF-1", "systemid" to "1")
        backLinks.patch(orgId, changes, stored)

        backLinks.patch(orgId, changes, later)

        assertThat(backLinksOf("1")).containsExactly("systemid/EF-1")
        assertThat(lastModifiedOf("1")).isEqualTo(stored)
    }

    @Test
    fun `lastModified never moves back`() {
        storeElev("1", at = later)

        backLinks.patch(orgId, pointAt("EF-1", "systemid" to "1"), stored)

        assertThat(backLinksOf("1")).containsExactly("systemid/EF-1")
        assertThat(lastModifiedOf("1")).isEqualTo(later)
    }

    @Test
    fun `a target that is not stored is left alone`() {
        backLinks.patch(orgId, pointAt("EF-1", "systemid" to "1"), later)

        assertThat(template.collectionExists(elever) && template.findById("1", Document::class.java, elever) != null).isFalse()
    }

    @Test
    fun `an identifier that starts with a dollar sign is stored as it is`() {
        storeElev("1")

        backLinks.patch(orgId, pointAt("\$data", "systemid" to "1"), later)

        assertThat(backLinksOf("1")).containsExactly("systemid/\$data")
    }

    @Test
    fun `a new resource gets the back-links of the edges that already point at it`() {
        pointAt("EF-1", "systemid" to "1")
        pointAt("EF-2", "elevnummer" to "N-1")
        pointAt("EF-3", "systemid" to "2")
        storeElev("1")

        backLinks.fill(elev, listOf(fillOf("1", isNew = true)))

        assertThat(backLinksOf("1")).containsExactlyInAnyOrder("systemid/EF-1", "systemid/EF-2")
        assertThat(lastModifiedOf("1")).isEqualTo(stored)
    }

    @Test
    fun `a source that points at a resource through two identifiers gives it one back-link`() {
        pointAt("EF-1", "systemid" to "1", "elevnummer" to "N-1")
        storeElev("1")

        backLinks.fill(elev, listOf(fillOf("1", isNew = true)))

        assertThat(backLinksOf("1")).containsExactly("systemid/EF-1")
    }

    @Test
    fun `a new resource no edge points at gets no back-links`() {
        storeElev("1")

        backLinks.fill(elev, listOf(fillOf("1", isNew = true)))

        assertThat(template.findById("1", Document::class.java, elever)!!.containsKey("backLinks")).isFalse()
    }

    @Test
    fun `a fill replaces the back-links that were stored`() {
        storeElev("1")
        backLinks.patch(orgId, pointAt("EF-1", "systemid" to "1"), stored)
        template.remove(Query(), edges)
        pointAt("EF-2", "systemid" to "1")

        backLinks.fill(elev, listOf(fillOf("1", isNew = false)))

        assertThat(backLinksOf("1")).containsExactly("systemid/EF-2")
    }

    @Test
    fun `a fill of a stored resource no edge points at clears its back-links`() {
        storeElev("1")
        backLinks.patch(orgId, pointAt("EF-1", "systemid" to "1"), stored)
        template.remove(Query(), edges)

        backLinks.fill(elev, listOf(fillOf("1", isNew = false)))

        assertThat(template.findById("1", Document::class.java, elever)!!.containsKey("backLinks")).isFalse()
    }

    private fun storeElev(
        id: String,
        at: Instant = stored,
        configure: Elev.() -> Unit = {},
    ) {
        val resource =
            Elev(
                systemId = Identifikator(identifikatorverdi = id),
                elevnummer = Identifikator(identifikatorverdi = "N-$id"),
            ).apply(configure)
        resourceStore.saveAll(listOf(Save(id, elever, resource, at)))
    }

    private fun fillOf(
        id: String,
        isNew: Boolean,
    ) = BackLinkFill(id, listOf(IdentifierRef("systemid", id), IdentifierRef("elevnummer", "N-$id")), isNew)

    /** Makes the elevforhold [sourceId] point at exactly [targets], and hands back what that changed. */
    private fun pointAt(
        sourceId: String,
        vararg targets: Pair<String, String>,
        sourceIdValue: String = sourceId,
    ): RelationEdgeChanges {
        val wanted =
            targets.map { (targetIdField, targetIdValue) ->
                RelationEdge(
                    id = relationEdgeId(ELEVFORHOLD, sourceId, "elev", ELEV, targetIdField, targetIdValue),
                    sourceType = ELEVFORHOLD,
                    sourceId = sourceId,
                    sourceIdField = "systemid",
                    sourceIdValue = sourceIdValue,
                    inverseName = "elevforhold",
                    targetType = ELEV,
                    targetIdField = targetIdField,
                    targetIdValue = targetIdValue,
                )
            }
        return edgeStore.applyAll(listOf(RelationEdgeWrite.Replace(edges, ELEVFORHOLD, sourceId, wanted)))
    }

    private fun backLinksOf(id: String): List<String> =
        template
            .findById(id, Document::class.java, elever)
            ?.get("backLinks", Document::class.java)
            ?.getList("elevforhold", Document::class.java)
            .orEmpty()
            .map { "${it["idField"]}/${it["idValue"]}" }

    private fun lastModifiedOf(id: String): Instant =
        template.findById(id, Document::class.java, elever)!!.getDate("lastModified").toInstant()
}
