package no.fintlabs.adapter.gateway.storage

import com.mongodb.client.MongoClients
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.fintlabs.adapter.gateway.mongoTestContainer
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.relation.BackLinkStore
import no.novari.core.shared.relation.RelationEdgeStore
import no.novari.core.shared.store.FintResourceBsonConverter
import no.novari.core.shared.store.ResourceStore
import no.novari.fint.core.model.Link
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.utdanning.elev.Elev
import no.novari.fint.core.model.utdanning.elev.Elevforhold
import org.assertj.core.api.Assertions.assertThat
import org.bson.Document
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
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Sends resources through the write pipeline with the back-link writer in place, against a real
 * Mongo replica set, and reads what ended up on the targets.
 */
@Testcontainers
class BackLinkWriteIT {
    companion object {
        @Container
        @JvmStatic
        val MONGO = mongoTestContainer()

        private const val DATABASE = "back-link-write-it"
        private val elevforhold = ResourceCoordinate("fintlabs.no", "utdanning", "elev", "elevforhold")
        private val elev = ResourceCoordinate("fintlabs.no", "utdanning", "elev", "elev")
        private val base = Instant.parse("2026-09-15T10:00:00Z")
        private val later = base.plusSeconds(60)
    }

    private val factory by lazy { SimpleMongoClientDatabaseFactory(MongoClients.create(MONGO.connectionString), DATABASE) }
    private val mongoTemplate by lazy { MongoTemplate(factory) }
    private val transactions by lazy { MongoTransactions(TransactionTemplate(MongoTransactionManager(factory)), factory) }
    private val resourceStore by lazy { ResourceStore(mongoTemplate, FintResourceBsonConverter()) }
    private val relationEdgeStore by lazy { RelationEdgeStore(mongoTemplate) }
    private val backLinkWriter by lazy {
        BackLinkWriter(BackLinkStore(mongoTemplate, relationEdgeStore), RelationWriteLock(mongoTemplate, transactions))
    }
    private val pipeline by lazy {
        ResourceWritePipeline(resourceStore, relationEdgeStore, transactions, SimpleMeterRegistry(), backLinkWriter)
    }

    @BeforeEach
    fun clean() {
        listOf(elev.toCollectionName(), elevforhold.toCollectionName(), elev.toEdgeCollectionName(), RelationWriteLock.COLLECTION)
            .forEach { mongoTemplate.remove(Query(), it) }
    }

    @Test
    fun `a source stored after its target gives the target a back-link`() {
        pipeline.apply(saveElev("1", base))

        pipeline.apply(saveElevforhold("EF-1", elevnummer = "E-1", at = later))

        assertThat(backLinksOf("1")).containsExactly("systemid/EF-1")
        assertThat(lastModifiedOf("1")).isEqualTo(later)
    }

    @Test
    fun `a target stored after its source gets the back-link when it arrives`() {
        pipeline.apply(saveElevforhold("EF-1", elevnummer = "E-1", at = base))

        pipeline.apply(saveElev("1", later))

        assertThat(backLinksOf("1")).containsExactly("systemid/EF-1")
    }

    @Test
    fun `a source and its target in one batch end up linked`() {
        pipeline.applyAll(listOf(saveElevforhold("EF-1", elevnummer = "E-1", at = base), saveElev("1", base)))

        assertThat(backLinksOf("1")).containsExactly("systemid/EF-1")
    }

    @Test
    fun `a source that moves to another target takes its back-link along`() {
        pipeline.applyAll(listOf(saveElev("1", base), saveElev("2", base), saveElevforhold("EF-1", elevnummer = "E-1", at = base)))

        pipeline.apply(saveElevforhold("EF-1", elevnummer = "E-2", at = later))

        assertThat(backLinksOf("1")).isEmpty()
        assertThat(backLinksOf("2")).containsExactly("systemid/EF-1")
        assertThat(lastModifiedOf("1")).isEqualTo(later)
        assertThat(lastModifiedOf("2")).isEqualTo(later)
    }

    @Test
    fun `a deleted source takes its back-link away`() {
        pipeline.applyAll(listOf(saveElev("1", base), saveElevforhold("EF-1", elevnummer = "E-1", at = base)))

        pipeline.apply(ResourceIngest.Delete(elevforhold, "EF-1", later))

        assertThat(backLinksOf("1")).isEmpty()
        assertThat(lastModifiedOf("1")).isEqualTo(later)
    }

    @Test
    fun `a target that gets the identifier a source points at gets the back-link`() {
        pipeline.applyAll(listOf(saveElev("1", base, elevnummer = null), saveElevforhold("EF-1", elevnummer = "E-1", at = base)))
        assertThat(backLinksOf("1")).isEmpty()

        pipeline.apply(saveElev("1", later))

        assertThat(backLinksOf("1")).containsExactly("systemid/EF-1")
    }

    @Test
    fun `a target delivered again with other content keeps its back-links`() {
        pipeline.applyAll(listOf(saveElev("1", base), saveElevforhold("EF-1", elevnummer = "E-1", at = base)))

        pipeline.apply(saveElev("1", later) { addLink("person", Link("fodselsnummer", "P-1")) })

        assertThat(backLinksOf("1")).containsExactly("systemid/EF-1")
    }

    @Test
    fun `a source delivered again unchanged changes nothing on the target`() {
        pipeline.applyAll(listOf(saveElev("1", base), saveElevforhold("EF-1", elevnummer = "E-1", at = base)))

        pipeline.apply(saveElevforhold("EF-1", elevnummer = "E-1", at = later))

        assertThat(backLinksOf("1")).containsExactly("systemid/EF-1")
        assertThat(lastModifiedOf("1")).isEqualTo(base)
    }

    @Test
    fun `every batch writes the lock document of its org`() {
        pipeline.apply(saveElev("1", base))
        pipeline.apply(saveElev("1", later))

        val lock = mongoTemplate.findById("fintlabs.no", Document::class.java, RelationWriteLock.COLLECTION)
        assertThat(lock?.get("version")).isEqualTo(2L)
    }

    @Test
    fun `a source and its target stored at the same moment still end up linked`() {
        val rounds = 25
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(rounds) { round ->
                val start = CyclicBarrier(2)
                val source =
                    executor.submit {
                        start.await()
                        pipeline.apply(saveElevforhold("EF-$round", elevnummer = "E-$round", at = base))
                    }
                val target =
                    executor.submit {
                        start.await()
                        pipeline.apply(saveElev("$round", base))
                    }
                source.get(30, TimeUnit.SECONDS)
                target.get(30, TimeUnit.SECONDS)
            }
        } finally {
            executor.shutdownNow()
        }

        assertThat((0 until rounds).filter { backLinksOf("$it").isEmpty() }).isEmpty()
    }

    @Test
    fun `without the writer no back-links are stored`() {
        val plain = ResourceWritePipeline(resourceStore, relationEdgeStore, transactions, SimpleMeterRegistry())

        plain.applyAll(listOf(saveElev("1", base), saveElevforhold("EF-1", elevnummer = "E-1", at = base)))

        assertThat(storedElev("1")!!.containsKey("backLinks")).isFalse()
    }

    private fun saveElev(
        id: String,
        at: Instant,
        elevnummer: String? = "E-$id",
        configure: Elev.() -> Unit = {},
    ) = ResourceIngest.Save(
        resource =
            Elev(
                systemId = Identifikator(identifikatorverdi = id),
                elevnummer = elevnummer?.let { Identifikator(identifikatorverdi = it) },
            ).apply(configure),
        coordinate = elev,
        resourceId = id,
        timestamp = at,
    )

    private fun saveElevforhold(
        id: String,
        elevnummer: String,
        at: Instant,
    ) = ResourceIngest.Save(
        resource = Elevforhold(systemId = Identifikator(identifikatorverdi = id)).apply { addLink("elev", Link("elevnummer", elevnummer)) },
        coordinate = elevforhold,
        resourceId = id,
        timestamp = at,
    )

    private fun storedElev(id: String): Document? = mongoTemplate.findById(id, Document::class.java, elev.toCollectionName())

    private fun backLinksOf(id: String): List<String> =
        storedElev(id)
            ?.get("backLinks", Document::class.java)
            ?.getList("elevforhold", Document::class.java)
            .orEmpty()
            .map { "${it["idField"]}/${it["idValue"]}" }

    private fun lastModifiedOf(id: String): Instant = storedElev(id)!!.getDate("lastModified").toInstant()
}
