package no.novari.core.shared.relation

import com.mongodb.client.MongoClients
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

        store.applyAll(listOf(replace("CHILD", "PARENT-A")))

        assertThat(edges().map { it.targetIdValue }).containsExactly("PARENT-A")
        assertThat(edges().single().createdAt).isEqualTo(createdAt)
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

    private fun replace(
        sourceId: String,
        vararg parents: String,
    ) = RelationEdgeWrite.Replace(COLLECTION, PERSON, sourceId, parents.map { edge(sourceId, it) })

    private fun edge(
        sourceId: String,
        parent: String,
    ) = RelationEdge(
        id = relationEdgeId(PERSON, sourceId, "foreldre", PERSON, "fodselsnummer", parent),
        sourceType = PERSON,
        sourceId = sourceId,
        sourceIdField = "fodselsnummer",
        sourceIdValue = sourceId,
        inverseName = "foreldreansvar",
        targetType = PERSON,
        targetIdField = "fodselsnummer",
        targetIdValue = parent,
    )

    private fun edges(): List<RelationEdge> = template.find(Query(), RelationEdge::class.java, COLLECTION)
}
