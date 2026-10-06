package no.fintlabs.adapter.gateway.relation

import com.mongodb.client.MongoClients
import no.fintlabs.adapter.gateway.mongoTestContainer
import no.fintlabs.adapter.gateway.storage.MongoTransactions
import no.fintlabs.adapter.gateway.storage.ResourceWritePipeline
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.relation.RelationEdge
import no.novari.core.shared.relation.RelationEdgeStore
import no.novari.core.shared.relation.RelationEdgeWrite
import no.novari.core.shared.relation.relationEdgeId
import no.novari.core.shared.store.FintResourceBsonConverter
import no.novari.core.shared.store.ResourceStore
import no.novari.core.shared.store.Save
import no.novari.fint.core.model.Link
import no.novari.fint.core.model.felles.Person
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory
import org.springframework.data.mongodb.core.query.Query
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import kotlin.test.assertEquals

/**
 * Rebuilds against a real Mongo. Resources are stored straight through [ResourceStore], so no
 * edges are written with them, and edges are planted straight through [RelationEdgeStore]. That
 * sets up the states a rebuild has to repair: edges missing, edges left behind by an earlier
 * version, and edges whose source is gone.
 */
@Testcontainers
class RelationEdgeRebuilderIT {
    companion object {
        @Container
        @JvmStatic
        val MONGO = mongoTestContainer()

        private const val PERSON_TYPE = "utdanning/elev/person"
        private val WRITTEN_AT: Instant = Instant.parse("2026-09-25T10:00:00Z")
    }

    private val factory by lazy { SimpleMongoClientDatabaseFactory(MongoClients.create(MONGO.connectionString), "rebuild-it") }
    private val mongoTemplate by lazy { MongoTemplate(factory) }
    private val transactions by lazy { MongoTransactions(TransactionTemplate(MongoTransactionManager(factory)), factory) }
    private val resourceStore by lazy { ResourceStore(mongoTemplate, FintResourceBsonConverter()) }
    private val relationEdgeStore by lazy { RelationEdgeStore(mongoTemplate) }
    private val pipeline by lazy { ResourceWritePipeline(resourceStore, relationEdgeStore, transactions) }
    private val rebuilder by lazy { RelationEdgeRebuilder(resourceStore, relationEdgeStore, pipeline, transactions, batchSize = 2) }

    private val personCoordinate = ResourceCoordinate("fintlabs.no", "utdanning", "elev", "person")
    private val edgeCollection = personCoordinate.toEdgeCollectionName()
    private val personCollection = personCoordinate.toCollectionName()

    @BeforeEach
    fun clean() {
        mongoTemplate.remove(Query(), edgeCollection)
        mongoTemplate.dropCollection(personCollection)
    }

    @Test
    fun `a rebuild adds the edges a stored resource is missing and removes the ones it no longer produces`() {
        storePersons(child("FNR-CHILD", "FNR-PARENT"), person("FNR-FORMER-CHILD"))
        plantEdge(sourceId = "FNR-FORMER-CHILD", parent = "FNR-PARENT")

        val rebuild = rebuilder.rebuild(personCoordinate)

        assertEquals(listOf("FNR-CHILD" to "FNR-PARENT"), edges().map { it.sourceId to it.targetIdValue })
        assertEquals(RelationEdgeRebuild(resourcesRead = 2, edgesWritten = 1, edgesRemoved = 1), rebuild)
    }

    @Test
    fun `a rebuild removes the edges of sources that are no longer stored`() {
        storePersons(person("FNR-STAYS"))
        plantEdge(sourceId = "FNR-GONE", parent = "FNR-PARENT")

        val rebuild = rebuilder.rebuild(personCoordinate)

        assertEquals(emptyList(), edges())
        assertEquals(1, rebuild.edgesRemoved)
    }

    @Test
    fun `a rebuild leaves the edges of other resource types alone`() {
        val elevforholdEdge = edge(sourceType = "utdanning/elev/elevforhold", sourceId = "EF-GONE", parent = "FNR-PARENT")
        relationEdgeStore.applyAll(
            listOf(RelationEdgeWrite.Replace(edgeCollection, "utdanning/elev/elevforhold", "EF-GONE", listOf(elevforholdEdge))),
        )

        rebuilder.rebuild(personCoordinate)

        assertEquals(listOf(elevforholdEdge.id), edges().map { it.id })
    }

    @Test
    fun `a rebuild reads every batch of stored resources`() {
        storePersons(*(1..5).map { child("FNR-CHILD-$it", "FNR-PARENT") }.toTypedArray())

        val rebuild = rebuilder.rebuild(personCoordinate)

        assertEquals(5, rebuild.resourcesRead)
        assertEquals((1..5).map { "FNR-CHILD-$it" }, edges().map { it.sourceId }.sorted())
    }

    @Test
    fun `a rebuild of edges that are already right changes nothing`() {
        storePersons(child("FNR-CHILD", "FNR-PARENT"))
        rebuilder.rebuild(personCoordinate)
        val createdAt = edges().single().createdAt

        val rebuild = rebuilder.rebuild(personCoordinate)

        assertEquals(RelationEdgeRebuild(resourcesRead = 1, edgesWritten = 0, edgesRemoved = 0), rebuild)
        assertEquals(createdAt, edges().single().createdAt)
    }

    @Test
    fun `a drift check reports missing, stale and orphaned edges and changes nothing`() {
        storePersons(child("FNR-CHILD", "FNR-PARENT"), person("FNR-FORMER-CHILD"))
        plantEdge(sourceId = "FNR-FORMER-CHILD", parent = "FNR-PARENT")
        plantEdge(sourceId = "FNR-GONE", parent = "FNR-PARENT")
        val before = edges().map { it.id }.sorted()

        val drift = rebuilder.drift(personCoordinate)

        assertEquals(before, edges().map { it.id }.sorted())
        assertEquals(2, drift.resourcesRead)
        assertEquals(1, drift.edgesMissing)
        assertEquals(1, drift.edgesStale)
        assertEquals(1, drift.edgesOfSourcesGone)
        assertEquals(
            setOf(
                RelationEdgeDriftExample.Kind.MISSING to "FNR-CHILD",
                RelationEdgeDriftExample.Kind.STALE to "FNR-FORMER-CHILD",
                RelationEdgeDriftExample.Kind.SOURCE_GONE to "FNR-GONE",
            ),
            drift.examples.map { it.kind to it.sourceId }.toSet(),
        )
    }

    @Test
    fun `a drift check agrees with the rebuild after it and finds nothing once the rebuild is done`() {
        storePersons(child("FNR-CHILD", "FNR-PARENT"), child("FNR-SIBLING", "FNR-PARENT"), person("FNR-FORMER-CHILD"))
        plantEdge(sourceId = "FNR-FORMER-CHILD", parent = "FNR-PARENT")
        plantEdge(sourceId = "FNR-GONE", parent = "FNR-PARENT")

        val drift = rebuilder.drift(personCoordinate)
        val rebuild = rebuilder.rebuild(personCoordinate)
        val after = rebuilder.drift(personCoordinate)

        assertEquals(drift.edgesMissing, rebuild.edgesWritten)
        assertEquals(drift.edgesStale + drift.edgesOfSourcesGone, rebuild.edgesRemoved)
        assertEquals(
            RelationEdgeDrift(resourcesRead = 3, edgesMissing = 0, edgesStale = 0, edgesOfSourcesGone = 0, examples = emptyList()),
            after,
        )
    }

    @Test
    fun `a drift check of a type with nothing stored reports only the edges whose source is gone`() {
        plantEdge(sourceId = "FNR-GONE", parent = "FNR-PARENT")

        val drift = rebuilder.drift(personCoordinate)

        assertEquals(0, drift.resourcesRead)
        assertEquals(1, drift.edgesOfSourcesGone)
    }

    private fun person(fodselsnummer: String) = Person(fodselsnummer = Identifikator(identifikatorverdi = fodselsnummer))

    private fun child(
        fodselsnummer: String,
        parent: String,
    ) = person(fodselsnummer).apply { addLink("foreldre", Link("fodselsnummer", parent)) }

    private fun storePersons(vararg persons: Person) {
        resourceStore.saveAll(
            persons.map {
                Save(it.fodselsnummer!!.identifikatorverdi!!, personCollection, it, WRITTEN_AT)
            },
        )
    }

    private fun plantEdge(
        sourceId: String,
        parent: String,
    ) {
        relationEdgeStore.applyAll(
            listOf(RelationEdgeWrite.Replace(edgeCollection, PERSON_TYPE, sourceId, listOf(edge(PERSON_TYPE, sourceId, parent)))),
        )
    }

    private fun edge(
        sourceType: String,
        sourceId: String,
        parent: String,
    ) = RelationEdge(
        id = relationEdgeId(sourceType, sourceId, "foreldre", PERSON_TYPE, "fodselsnummer", parent),
        sourceType = sourceType,
        sourceId = sourceId,
        sourceIdField = "fodselsnummer",
        sourceIdValue = sourceId,
        inverseName = "foreldreansvar",
        targetType = PERSON_TYPE,
        targetIdField = "fodselsnummer",
        targetIdValue = parent,
    )

    private fun edges(): List<RelationEdge> = mongoTemplate.find(Query(), RelationEdge::class.java, edgeCollection)
}
