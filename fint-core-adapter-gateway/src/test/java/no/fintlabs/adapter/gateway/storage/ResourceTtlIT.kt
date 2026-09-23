package no.fintlabs.adapter.gateway.storage

import com.mongodb.client.MongoClients
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.fintlabs.adapter.gateway.config.ProviderProperties
import no.fintlabs.adapter.gateway.config.ResourceTtlProperties
import no.fintlabs.adapter.gateway.mongoTestContainer
import no.fintlabs.adapter.gateway.sync.FullSyncStatus
import no.fintlabs.adapter.gateway.sync.FullSyncStatusStore
import no.fintlabs.adapter.gateway.sync.SyncProgressStore
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.org.OrgStore
import no.novari.core.shared.relation.RelationEdge
import no.novari.core.shared.relation.RelationEdgeStore
import no.novari.core.shared.store.FintResourceBsonConverter
import no.novari.core.shared.store.IdentifierRef
import no.novari.core.shared.store.ResourceStore
import no.novari.fint.core.model.Link
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.utdanning.elev.Elev
import no.novari.fint.core.model.utdanning.elev.Elevforhold
import org.bson.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class ResourceTtlIT {
    companion object {
        @Container
        @JvmStatic
        val MONGO = mongoTestContainer()

        private const val GATEWAY_ORG = "fintlabs.no"
        private const val SUB_ORG = "test.fintlabs.no"
        private const val OTHER_ORG = "other.no"

        private val NOW = Instant.parse("2026-09-23T12:00:00Z")
        private val MAX_AGE = Duration.ofDays(21)

        /** A full sync that completed one millisecond more than 21 days ago, so the resource type is wiped. */
        private val TOO_LONG_AGO = NOW.minus(MAX_AGE).minusMillis(1)

        /** A full sync that completed exactly 21 days ago, which is still recent enough to keep. */
        private val AT_THE_LIMIT = NOW.minus(MAX_AGE)

        /** A full sync that completed yesterday, like an adapter that is running normally. */
        private val YESTERDAY = NOW.minus(Duration.ofDays(1))

        /** The first sweep after the 21 days have passed. */
        private val AFTER_MAX_AGE = NOW.plus(MAX_AGE).plusMillis(1)
    }

    private val factory by lazy { SimpleMongoClientDatabaseFactory(MongoClients.create(MONGO.connectionString), "resource-ttl-it") }
    private val mongoTemplate by lazy { MongoTemplate(factory) }
    private val transactions by lazy { MongoTransactions(TransactionTemplate(MongoTransactionManager(factory)), factory) }
    private val resourceStore by lazy { ResourceStore(mongoTemplate, FintResourceBsonConverter()) }
    private val relationEdgeStore by lazy { RelationEdgeStore(mongoTemplate) }
    private val orgStore by lazy { OrgStore(mongoTemplate) }
    private val fullSyncStatusStore by lazy { FullSyncStatusStore(mongoTemplate) }
    private val syncProgressStore by lazy { SyncProgressStore(mongoTemplate) }
    private val pipeline by lazy { ResourceWritePipeline(resourceStore, relationEdgeStore, transactions) }
    private val meterRegistry = SimpleMeterRegistry()

    @BeforeEach
    fun clean() {
        mongoTemplate.db.drop()
    }

    @Test
    fun `a resource type with no completed full sync for 21 days is wiped, with everything delivered since and the edges they own`() {
        orgStore.upsert(GATEWAY_ORG)
        fullSyncCompleted(GATEWAY_ORG, "elevforhold", at = TOO_LONG_AGO)
        deliverElevforhold(GATEWAY_ORG, "EF-SYNCED", elevnummer = "E-1", at = TOO_LONG_AGO)
        deliverElevforhold(GATEWAY_ORG, "EF-EVENT", elevnummer = "E-1", at = YESTERDAY)

        ttlService().expireOldResources()

        assertTrue(storedIds(elevforholdCollection(GATEWAY_ORG)).isEmpty())
        assertTrue(
            edgesTargeting(GATEWAY_ORG, "E-1").isEmpty(),
            "the Elev they pointed at no longer gets back-links to them",
        )
        assertNull(status(GATEWAY_ORG, "elevforhold"))
        assertEquals(2.0, wiped("fint.core.wipe.resources"))
        assertEquals(2.0, wiped("fint.core.wipe.edges"))
    }

    @Test
    fun `a resource type whose full sync completed within 21 days is left alone, also one that completed exactly 21 days ago`() {
        orgStore.upsert(GATEWAY_ORG)
        fullSyncCompleted(GATEWAY_ORG, "elevforhold", at = AT_THE_LIMIT)
        fullSyncCompleted(GATEWAY_ORG, "elev", at = YESTERDAY)
        deliverElevforhold(GATEWAY_ORG, "EF-1", elevnummer = "E-1", at = AT_THE_LIMIT)
        deliverElev(GATEWAY_ORG, "E-1", at = YESTERDAY)

        ttlService().expireOldResources()

        assertEquals(listOf("EF-1"), storedIds(elevforholdCollection(GATEWAY_ORG)))
        assertEquals(listOf("E-1"), storedIds(elevCollection(GATEWAY_ORG)))
        assertEquals(listOf("EF-1"), edgesTargeting(GATEWAY_ORG, "E-1").map { it.sourceId })
        assertNotNull(status(GATEWAY_ORG, "elevforhold"))
        assertNotNull(status(GATEWAY_ORG, "elev"))
    }

    @Test
    fun `a resource type seen for the first time gets 21 days before it can be wiped`() {
        orgStore.upsert(GATEWAY_ORG)
        deliverElevforhold(GATEWAY_ORG, "EF-1", elevnummer = "E-1", at = YESTERDAY)

        ttlService().expireOldResources()

        assertEquals(listOf("EF-1"), storedIds(elevforholdCollection(GATEWAY_ORG)))
        val status = assertNotNull(status(GATEWAY_ORG, "elevforhold"))
        assertEquals(NOW, status.createdAt, "the clock starts when the sweep first sees the collection")
        assertNull(status.lastCompletedAt)

        ttlService(clock = Clock.fixed(AFTER_MAX_AGE, ZoneOffset.UTC)).expireOldResources()

        assertTrue(storedIds(elevforholdCollection(GATEWAY_ORG)).isEmpty())
    }

    @Test
    fun `a resource type with a full sync in progress is not wiped`() {
        orgStore.upsert(GATEWAY_ORG)
        fullSyncCompleted(GATEWAY_ORG, "elevforhold", at = TOO_LONG_AGO)
        deliverElevforhold(GATEWAY_ORG, "EF-1", elevnummer = "E-1", at = YESTERDAY)
        syncProgressStore.fold(
            corrId = "S-1",
            coordinate = coordinate(GATEWAY_ORG, "elevforhold"),
            totalSize = 2,
            partition = 0,
            expectedOffset = null,
            highestOffset = 0,
            freshCount = 1,
            startedAt = NOW,
        )

        ttlService().expireOldResources()

        assertEquals(listOf("EF-1"), storedIds(elevforholdCollection(GATEWAY_ORG)))
        assertEquals(listOf("EF-1"), edgesTargeting(GATEWAY_ORG, "E-1").map { it.sourceId })
        assertNotNull(status(GATEWAY_ORG, "elevforhold"))
    }

    @Test
    fun `only this gateway's org and its sub-orgs are wiped`() {
        listOf(GATEWAY_ORG, SUB_ORG, OTHER_ORG).forEach { org ->
            orgStore.upsert(org)
            fullSyncCompleted(org, "elevforhold", at = TOO_LONG_AGO)
            deliverElevforhold(org, "EF-1", elevnummer = "E-1", at = YESTERDAY)
        }

        ttlService().expireOldResources()

        assertTrue(storedIds(elevforholdCollection(GATEWAY_ORG)).isEmpty())
        assertTrue(storedIds(elevforholdCollection(SUB_ORG)).isEmpty())
        assertEquals(listOf("EF-1"), storedIds(elevforholdCollection(OTHER_ORG)))
        assertEquals(listOf("EF-1"), edgesTargeting(OTHER_ORG, "E-1").map { it.sourceId })
        assertNotNull(status(OTHER_ORG, "elevforhold"))
    }

    @Test
    fun `a wipe that fails after the edges are removed is finished by the next sweep`() {
        val failingStore =
            object : ResourceStore(mongoTemplate, FintResourceBsonConverter()) {
                override fun dropCollection(collectionName: String): Unit = throw IllegalStateException("drop failed")
            }
        orgStore.upsert(GATEWAY_ORG)
        fullSyncCompleted(GATEWAY_ORG, "elevforhold", at = TOO_LONG_AGO)
        deliverElevforhold(GATEWAY_ORG, "EF-1", elevnummer = "E-1", at = YESTERDAY)

        ttlService(wipingWith = failingStore).expireOldResources()

        assertTrue(edgesTargeting(GATEWAY_ORG, "E-1").isEmpty(), "the edges went before the drop failed")
        assertEquals(listOf("EF-1"), storedIds(elevforholdCollection(GATEWAY_ORG)))
        assertNotNull(status(GATEWAY_ORG, "elevforhold"), "the status stays, so the next sweep tries again")

        ttlService().expireOldResources()

        assertTrue(storedIds(elevforholdCollection(GATEWAY_ORG)).isEmpty())
        assertNull(status(GATEWAY_ORG, "elevforhold"))
    }

    @Test
    fun `a resource type written again after a wipe gets its indexes back and a fresh 21 days`() {
        orgStore.upsert(GATEWAY_ORG)
        fullSyncCompleted(GATEWAY_ORG, "elevforhold", at = TOO_LONG_AGO)
        deliverElevforhold(GATEWAY_ORG, "EF-1", elevnummer = "E-1", at = YESTERDAY)
        ttlService().expireOldResources()
        assertTrue(storedIds(elevforholdCollection(GATEWAY_ORG)).isEmpty())

        deliverElevforhold(GATEWAY_ORG, "EF-2", elevnummer = "E-1", at = NOW)
        ttlService().expireOldResources()

        assertEquals(listOf("EF-2"), storedIds(elevforholdCollection(GATEWAY_ORG)))
        assertTrue(
            indexNames(elevforholdCollection(GATEWAY_ORG)).containsAll(listOf("last_modified", "created_at_id")),
            "the store forgot the dropped collection and indexed it again on the next write",
        )
        val status = assertNotNull(status(GATEWAY_ORG, "elevforhold"))
        assertEquals(NOW, status.createdAt)
        assertNull(status.lastCompletedAt)
    }

    @Test
    fun `the sweep is handed to the eviction runner, and no second sweep is queued while the first one waits`() {
        orgStore.upsert(GATEWAY_ORG)
        fullSyncCompleted(GATEWAY_ORG, "elevforhold", at = TOO_LONG_AGO)
        deliverElevforhold(GATEWAY_ORG, "EF-1", elevnummer = "E-1", at = YESTERDAY)
        val runner = HoldingEvictionRunner()
        val service = ttlService(runner = runner)

        service.expireOldResources()
        service.expireOldResources()

        assertEquals(1, runner.waiting.size, "the second call finds the first sweep still waiting and skips")
        assertEquals(listOf("EF-1"), storedIds(elevforholdCollection(GATEWAY_ORG)), "nothing is wiped before the runner runs the sweep")

        runner.runWaiting()
        assertTrue(storedIds(elevforholdCollection(GATEWAY_ORG)).isEmpty())

        service.expireOldResources()
        assertEquals(1, runner.waiting.size, "once the first sweep has run, the next call queues a new one")
    }

    @Test
    fun `a sweep that is turned off wipes nothing`() {
        orgStore.upsert(GATEWAY_ORG)
        fullSyncCompleted(GATEWAY_ORG, "elevforhold", at = TOO_LONG_AGO)
        deliverElevforhold(GATEWAY_ORG, "EF-1", elevnummer = "E-1", at = YESTERDAY)

        ttlService(ResourceTtlProperties(enabled = false, maxAge = MAX_AGE)).expireOldResources()

        assertEquals(listOf("EF-1"), storedIds(elevforholdCollection(GATEWAY_ORG)))
    }

    /**
     * Keeps the submitted sweeps instead of running them, so a test can check what happened
     * before the runner got to the work.
     */
    private class HoldingEvictionRunner : EvictionRunner() {
        val waiting = mutableListOf<() -> Unit>()

        override fun submit(eviction: () -> Unit) {
            waiting += eviction
        }

        fun runWaiting() {
            val sweeps = waiting.toList()
            waiting.clear()
            sweeps.forEach { it() }
        }
    }

    private fun ttlService(
        properties: ResourceTtlProperties = ResourceTtlProperties(maxAge = MAX_AGE),
        runner: EvictionRunner = InlineEvictionRunner(),
        clock: Clock = Clock.fixed(NOW, ZoneOffset.UTC),
        wipingWith: ResourceStore = resourceStore,
    ) = ResourceTtlService(
        mongoTemplate,
        orgStore,
        fullSyncStatusStore,
        syncProgressStore,
        ResourceWipeService(wipingWith, relationEdgeStore, fullSyncStatusStore, meterRegistry),
        runner,
        ProviderProperties(GATEWAY_ORG),
        properties,
        clock,
    )

    private fun fullSyncCompleted(
        org: String,
        resourceName: String,
        at: Instant,
    ) = fullSyncStatusStore.recordCompleted(coordinate(org, resourceName), at)

    private fun deliverElevforhold(
        org: String,
        resourceId: String,
        elevnummer: String,
        at: Instant,
    ) {
        val elevforhold =
            Elevforhold(systemId = Identifikator(identifikatorverdi = resourceId)).apply {
                addLink("elev", Link("elevnummer", elevnummer))
            }
        pipeline.apply(ResourceIngest.Save(elevforhold, coordinate(org, "elevforhold"), resourceId, at))
    }

    private fun deliverElev(
        org: String,
        resourceId: String,
        at: Instant,
    ) {
        val elev = Elev(elevnummer = Identifikator(identifikatorverdi = resourceId))
        pipeline.apply(ResourceIngest.Save(elev, coordinate(org, "elev"), resourceId, at))
    }

    private fun coordinate(
        org: String,
        resourceName: String,
    ) = ResourceCoordinate(org, "utdanning", "elev", resourceName)

    private fun elevforholdCollection(org: String) = coordinate(org, "elevforhold").toCollectionName()

    private fun elevCollection(org: String) = coordinate(org, "elev").toCollectionName()

    private fun status(
        org: String,
        resourceName: String,
    ): FullSyncStatus? =
        mongoTemplate.findById(
            coordinate(org, resourceName).toCollectionName(),
            FullSyncStatus::class.java,
            FullSyncStatusStore.COLLECTION_NAME,
        )

    private fun indexNames(collectionName: String): List<String> = mongoTemplate.indexOps(collectionName).indexInfo.map { it.name }

    private fun storedIds(collectionName: String): List<String> =
        mongoTemplate
            .findAll(Document::class.java, collectionName)
            .map { it.getString("_id") }

    private fun edgesTargeting(
        org: String,
        elevnummer: String,
    ): List<RelationEdge> =
        relationEdgeStore.findByTargets(
            coordinate(org, "elev").toEdgeCollectionName(),
            "utdanning/elev/elev",
            listOf(IdentifierRef("elevnummer", elevnummer)),
        )

    private fun wiped(counter: String): Double =
        meterRegistry
            .get(counter)
            .tag("resource", "utdanning/elev/elevforhold")
            .counter()
            .count()
}
