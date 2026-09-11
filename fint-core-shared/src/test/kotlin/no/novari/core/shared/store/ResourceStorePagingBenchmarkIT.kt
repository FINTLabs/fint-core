package no.novari.core.shared.store

import com.mongodb.ExplainVerbosity
import com.mongodb.client.MongoClients
import no.novari.core.shared.mongoTestContainer
import org.bson.Document
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.Date
import kotlin.system.measureNanoTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Shows, on a collection the size of a real resource cache, that list reads walk the
 * `created_at_id` index instead of sorting the whole collection. Each test runs the same read
 * twice. The first run has only the `last_modified` index, which is what every collection had
 * before `created_at_id` existed. The second run happens after `prepareCollection` has added the
 * new index.
 *
 * The collection holds 200 000 documents by default. To try the size of the largest production
 * collection, run:
 *
 * `./gradlew :fint-core-shared:test -Pbenchmark.documents=2000000 --tests '*PagingBenchmarkIT'`
 *
 * The timings are logged. The assertions are on the query plan and on how many documents Mongo
 * had to look at, so they hold on a slow CI machine too.
 */
@Testcontainers
class ResourceStorePagingBenchmarkIT {
    private lateinit var resourceStore: ResourceStore

    @BeforeEach
    fun startWithoutTheNewIndex() {
        resourceStore = ResourceStore(template, FintResourceBsonConverter())
        if (template.indexOps(COLLECTION).indexInfo.any { it.name == CREATED_AT_INDEX }) {
            template.indexOps(COLLECTION).dropIndex(CREATED_AT_INDEX)
        }
    }

    @Test
    fun `the first page of one document only looks at one document once the index exists`() {
        val query = resourceStore.pageQuery(null, 1, 0)

        val before = measure("size=1 offset=0, before index", query) { resourceStore.findPage(null, 1, 0, COLLECTION).size }
        resourceStore.prepareCollection(COLLECTION)
        val after = measure("size=1 offset=0, after index", query) { resourceStore.findPage(null, 1, 0, COLLECTION).size }

        assertEquals(DOCUMENT_COUNT.toLong(), before.plan.docsExamined)
        assertTrue(before.plan.stages.contains("SORT"))

        assertEquals(1, after.plan.docsExamined)
        assertFalse(after.plan.stages.contains("SORT"))
        assertTrue(after.plan.indexes.contains(CREATED_AT_INDEX))
        assertTrue(after.nanos < before.nanos)
        assertEquals(listOf(id(0)), resourceStore.findPage(null, 1, 0, COLLECTION).map { it.id })
    }

    @Test
    fun `a page at the end of the collection walks the index instead of sorting everything`() {
        val offset = (DOCUMENT_COUNT - PAGE_SIZE).toLong()
        val query = resourceStore.pageQuery(null, PAGE_SIZE, offset)

        val before =
            measure(
                "size=$PAGE_SIZE offset=$offset, before index",
                query,
            ) { resourceStore.findPage(null, PAGE_SIZE, offset, COLLECTION).size }
        resourceStore.prepareCollection(COLLECTION)
        val after =
            measure(
                "size=$PAGE_SIZE offset=$offset, after index",
                query,
            ) { resourceStore.findPage(null, PAGE_SIZE, offset, COLLECTION).size }

        assertTrue(before.plan.stages.contains("SORT"))
        assertFalse(after.plan.stages.contains("SORT"))
        assertTrue(after.plan.indexes.contains(CREATED_AT_INDEX))

        val expectedIds = (offset.toInt() until DOCUMENT_COUNT).map { id(it) }
        assertEquals(expectedIds, resourceStore.findPage(null, PAGE_SIZE, offset, COLLECTION).map { it.id })
    }

    @Test
    fun `a full dump streams in index order instead of sorting the whole collection`() {
        val query = resourceStore.baseQuery(null)

        val before = explain(query)
        resourceStore.prepareCollection(COLLECTION)
        val after = explain(query)
        log.info("full dump, before index: {}", before)
        log.info("full dump, after index: {}", after)

        assertTrue(before.stages.contains("SORT"))
        assertFalse(after.stages.contains("SORT"))
        assertTrue(after.indexes.contains(CREATED_AT_INDEX))
    }

    @Test
    fun `a sinceTimeStamp read still only looks at the changed documents`() {
        val changed = DOCUMENT_COUNT / 100
        val since = createdAt(DOCUMENT_COUNT - changed)
        val filter = Criteria.where("lastModified").gte(since)
        resourceStore.prepareCollection(COLLECTION)

        val measurement =
            measure("since last $changed, after index", resourceStore.pageQuery(filter, PAGE_SIZE, 0)) {
                resourceStore.findPage(filter, PAGE_SIZE, 0, COLLECTION).size
            }

        assertTrue(measurement.plan.docsExamined <= changed)
        val page = resourceStore.findPage(filter, PAGE_SIZE, 0, COLLECTION)
        assertEquals(PAGE_SIZE, page.size)
        assertEquals(id(DOCUMENT_COUNT - changed), page.first().id)
    }

    private fun measure(
        label: String,
        query: Query,
        read: () -> Int,
    ): Measurement {
        var returned = 0
        val nanos = (1..RUNS).minOf { measureNanoTime { returned = read() } }
        val plan = explain(query)
        log.info("{}: {} entries in {} ms, {}", label, returned, nanos / 1_000_000, plan)
        return Measurement(plan, nanos)
    }

    private fun explain(query: Query): Plan {
        val explained =
            template.execute(COLLECTION) { collection ->
                collection
                    .find(query.queryObject)
                    .sort(query.sortObject)
                    .skip(query.skip.toInt())
                    .limit(query.limit)
                    .explain(ExplainVerbosity.EXECUTION_STATS)
            }!!
        val stats = explained.get("executionStats", Document::class.java)
        val winningPlan = explained.get("queryPlanner", Document::class.java).get("winningPlan", Document::class.java)

        return Plan(
            stages = values(winningPlan, "stage"),
            indexes = values(winningPlan, "indexName"),
            keysExamined = (stats["totalKeysExamined"] as Number).toLong(),
            docsExamined = (stats["totalDocsExamined"] as Number).toLong(),
            millis = (stats["executionTimeMillis"] as Number).toLong(),
        )
    }

    private fun values(
        node: Any?,
        key: String,
    ): List<String> =
        when (node) {
            is Document -> {
                node.entries.flatMap { (name, value) ->
                    if (name == key &&
                        value is String
                    ) {
                        listOf(value)
                    } else {
                        values(value, key)
                    }
                }
            }

            is List<*> -> {
                node.flatMap { values(it, key) }
            }

            else -> {
                emptyList()
            }
        }

    private data class Plan(
        val stages: List<String>,
        val indexes: List<String>,
        val keysExamined: Long,
        val docsExamined: Long,
        val millis: Long,
    )

    private data class Measurement(
        val plan: Plan,
        val nanos: Long,
    )

    companion object {
        @Container
        @JvmStatic
        val MONGO = mongoTestContainer()

        private val log = LoggerFactory.getLogger(ResourceStorePagingBenchmarkIT::class.java)

        private const val COLLECTION = "benchmark_no_utdanning_vurdering_fravarsregistrering"
        private const val CREATED_AT_INDEX = "created_at_id"
        private const val PAGE_SIZE = 1_000
        private const val RUNS = 3
        private const val SEED_BATCH = 10_000
        private val DOCUMENT_COUNT = System.getProperty("benchmark.documents")?.toInt() ?: 200_000
        private val BASE = Instant.parse("2024-08-01T00:00:00Z")

        private lateinit var template: MongoTemplate

        @BeforeAll
        @JvmStatic
        fun seedCollection() {
            template = MongoTemplate(MongoClients.create(MONGO.connectionString), "paging-benchmark")
            template.indexOps(COLLECTION).createIndex(
                Index().on("lastModified", Sort.Direction.ASC).named("last_modified"),
            )

            val collection = template.getCollection(COLLECTION)
            val seeding =
                measureNanoTime {
                    (0 until DOCUMENT_COUNT)
                        .asSequence()
                        .chunked(SEED_BATCH)
                        .forEach { batch -> collection.insertMany(batch.map { document(it) }) }
                }
            log.info("seeded {} documents in {} ms", DOCUMENT_COUNT, seeding / 1_000_000)
        }

        private fun document(i: Int): Document {
            val created = Date.from(createdAt(i))
            val data =
                Document("systemId", Document("identifikatorverdi", id(i)))
                    .append("dato", created)
                    .append("kommentar", "Fravær registrert for elev ${i % 5000}")
                    .append("_links", Document("elevforhold", listOf(Document("href", "systemid/EF-${i % 5000}"))))

            return Document("_id", id(i))
                .append("data", data)
                .append("identifiers", listOf(Document("field", "systemid").append("value", id(i))))
                .append("createdAt", created)
                .append("lastModified", created)
        }

        private fun id(i: Int) = "FR-%08d".format(i)

        private fun createdAt(i: Int): Instant = BASE.plusSeconds((i / 10).toLong())
    }
}
