package no.novari.core.shared.relation

import com.mongodb.client.MongoClients
import no.novari.core.shared.store.IdentifierRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer

@Testcontainers
class RelationEdgeStoreIT {
    private val store = RelationEdgeStore(template)

    companion object {
        @Container
        val mongo = MongoDBContainer("mongo:8.0.4")

        private const val COLLECTION = "test_org_no_relation_edges"
        private const val PERSON = "utdanning/elev/person"
        private val template by lazy { MongoTemplate(MongoClients.create(mongo.connectionString), "test") }
    }

    @BeforeEach
    fun dropCollection() {
        template.dropCollection(COLLECTION)
    }

    @Test
    fun `a replace keeps the edges still produced, with their createdAt, and removes the rest`() {
        store.applyAll(listOf(replace("CHILD", "PARENT-A", "PARENT-B")))
        val createdAt = edges().first { it.targetIdValue == "PARENT-A" }.createdAt

        val result = store.applyAll(listOf(replace("CHILD", "PARENT-A")))

        assertThat(edges().map { it.targetIdValue }).containsExactly("PARENT-A")
        assertThat(edges().single().createdAt).isEqualTo(createdAt)
        assertThat(result).isEqualTo(RelationEdgeWriteResult(written = 0, removed = 1))
    }

    @Test
    fun `a replace with no edges removes every edge the source owned`() {
        store.applyAll(listOf(replace("CHILD", "PARENT-A", "PARENT-B")))

        store.applyAll(listOf(replace("CHILD")))

        assertThat(edges()).isEmpty()
    }

    @Test
    fun `a replace leaves the edges of other sources alone`() {
        store.applyAll(listOf(replace("CHILD", "PARENT-A"), replace("SIBLING", "PARENT-A")))

        store.applyAll(listOf(replace("CHILD")))

        assertThat(edges().map { it.sourceId }).containsExactly("SIBLING")
    }

    @Test
    fun `the sources that own edges of a type are listed once each`() {
        store.applyAll(listOf(replace("CHILD", "PARENT-A", "PARENT-B"), replace("SIBLING", "PARENT-A")))

        assertThat(store.findSourceIds(COLLECTION, PERSON)).containsExactlyInAnyOrder("CHILD", "SIBLING")
    }

    @Test
    fun `edges are found by the sources that own them`() {
        store.applyAll(listOf(replace("CHILD", "PARENT-A", "PARENT-B"), replace("SIBLING", "PARENT-A"), replace("OTHER", "PARENT-C")))

        val found = store.findBySources(COLLECTION, PERSON, listOf("CHILD", "SIBLING"))

        assertThat(found.map { it.sourceId to it.targetIdValue })
            .containsExactlyInAnyOrder("CHILD" to "PARENT-A", "CHILD" to "PARENT-B", "SIBLING" to "PARENT-A")
    }

    @Test
    fun `edges are found by the targets they point at, across identifier fields`() {
        store.applyAll(listOf(replace("CHILD", "PARENT-A"), replace("OTHER", "PARENT-C")))
        store.applyAll(listOf(RelationEdgeWrite.Replace(COLLECTION, PERSON, "PUPIL", listOf(edge("PUPIL", "E-1", "elevnummer")))))

        val found =
            store.findByTargets(
                COLLECTION,
                PERSON,
                listOf(
                    IdentifierRef("fodselsnummer", "PARENT-A"),
                    IdentifierRef("fodselsnummer", "PARENT-A"),
                    IdentifierRef("elevnummer", "E-1"),
                    IdentifierRef("elevnummer", "MISSING"),
                ),
            )

        assertThat(found.map { it.targetIdField to it.sourceIdValue })
            .containsExactlyInAnyOrder("fodselsnummer" to "CHILD", "elevnummer" to "PUPIL")
    }

    @Test
    fun `edges of another target type are not found`() {
        store.applyAll(listOf(replace("CHILD", "PARENT-A")))

        val found = store.findByTargets(COLLECTION, "utdanning/elev/elev", listOf(IdentifierRef("fodselsnummer", "PARENT-A")))

        assertThat(found).isEmpty()
    }

    @Test
    fun `edges are found when the targets span more than one query`() {
        val parents = (1..RelationEdgeStore.TARGET_VALUES_PER_QUERY + 1).map { "PARENT-$it" }
        store.applyAll(listOf(replace("CHILD", *parents.toTypedArray())))

        val found = store.findByTargets(COLLECTION, PERSON, parents.map { IdentifierRef("fodselsnummer", it) })

        assertThat(found.map { it.targetIdValue }).containsExactlyInAnyOrderElementsOf(parents)
    }

    @Test
    fun `a found edge carries what the back-link needs`() {
        store.applyAll(listOf(replace("CHILD", "PARENT-A")))

        val found = store.findByTargets(COLLECTION, PERSON, listOf(IdentifierRef("fodselsnummer", "PARENT-A"))).single()

        assertThat(found.inverseName).isEqualTo("foreldreansvar")
        assertThat(found.sourceIdField to found.sourceIdValue).isEqualTo("fodselsnummer" to "CHILD")
        assertThat(found.targetIdField to found.targetIdValue).isEqualTo("fodselsnummer" to "PARENT-A")
    }

    @Test
    fun `no targets finds no edges`() {
        store.applyAll(listOf(replace("CHILD", "PARENT-A")))

        assertThat(store.findByTargets(COLLECTION, PERSON, emptyList())).isEmpty()
    }

    private fun replace(
        sourceId: String,
        vararg parents: String,
    ) = RelationEdgeWrite.Replace(COLLECTION, PERSON, sourceId, parents.map { edge(sourceId, it) })

    private fun edge(
        sourceId: String,
        parent: String,
        targetIdField: String = "fodselsnummer",
    ) = RelationEdge(
        id = relationEdgeId(PERSON, sourceId, "foreldre", PERSON, targetIdField, parent),
        sourceType = PERSON,
        sourceId = sourceId,
        sourceIdField = "fodselsnummer",
        sourceIdValue = sourceId,
        inverseName = "foreldreansvar",
        targetType = PERSON,
        targetIdField = targetIdField,
        targetIdValue = parent,
    )

    private fun edges(): List<RelationEdge> = template.find(Query(), RelationEdge::class.java, COLLECTION)
}
