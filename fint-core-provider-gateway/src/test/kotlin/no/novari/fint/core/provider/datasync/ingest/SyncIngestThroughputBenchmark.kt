package no.novari.fint.core.provider.datasync.ingest

import io.micrometer.core.instrument.MeterRegistry
import no.fintlabs.adapter.models.sync.FullSyncPage
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.models.sync.SyncPageMetadata
import no.novari.fint.core.provider.datasync.SyncPageService
import no.novari.fint.core.shared.cache.CacheService
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID
import java.util.concurrent.TimeUnit

@Tag("benchmark")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@EmbeddedKafka(partitions = 1)
@Import(BenchmarkContainersConfiguration::class)
class SyncIngestThroughputBenchmark
    @Autowired
    constructor(
        private val syncPageService: SyncPageService,
        private val cacheService: CacheService,
        private val meterRegistry: MeterRegistry,
    ) {
        @Test
        fun `measure ingest throughput into a cpu-limited mongo`() {
            val elevCount = TOTAL_RECORDS / 2
            val elevforholdCount = TOTAL_RECORDS - elevCount
            val elevCache = cacheService.getCache("utdanning_elev_elev")

            val started = System.currentTimeMillis()
            syncFull("elev", elevCount) { i -> elevEntry("bench-e$i", i) }
            syncFull("elevforhold", elevforholdCount) { i -> elevforholdEntry("bench-ef$i", "bench-e${i % elevCount}") }
            awaitUntil(timeoutMs = 600_000) { ingestedRecords() >= TOTAL_RECORDS }
            val elapsedMs = System.currentTimeMillis() - started

            awaitUntil { elevCache.findIdsByBackLink("elevforhold", "systemid/bench-ef0").isNotEmpty() }
            assertTrue(elevCache.get("bench-e0") != null)

            report(elapsedMs)
        }

        private fun ingestedRecords(): Long = meterRegistry.counter("fint.provider.sync.ingest.records").count().toLong()

        private fun report(endToEndMs: Long) {
            val timer = meterRegistry.timer("fint.provider.sync.ingest.batch")
            val writeMs = timer.totalTime(TimeUnit.MILLISECONDS)
            println("=== sync ingest throughput (mongo limited to ${BenchmarkContainersConfiguration.MONGO_CPUS} cpu) ===")
            println("records:            $TOTAL_RECORDS (half elev with varied sizes, half elevforhold with autorelation)")
            println("page size:          $PAGE_SIZE, max-poll-records: ${System.getProperty("benchmark.maxPollRecords", "500")}")
            println("end-to-end:         ${endToEndMs}ms -> ${TOTAL_RECORDS * 1000 / endToEndMs} records/s (produce + consume + write)")
            println("mongo write time:   ${writeMs.toLong()}ms -> ${(TOTAL_RECORDS * 1000 / writeMs).toLong()} records/s (pure writeBatch + autorelation + tracking)")
            println("batches:            ${timer.count()}, mean ${timer.mean(TimeUnit.MILLISECONDS).toLong()}ms, max ${timer.max(TimeUnit.MILLISECONDS).toLong()}ms")
        }

        private fun syncFull(
            entity: String,
            total: Int,
            entry: (Int) -> SyncPageEntry,
        ) {
            val corrId = UUID.randomUUID().toString()
            val pages = (0 until total).chunked(PAGE_SIZE)
            pages.forEachIndexed { pageNumber, indexes ->
                val page =
                    FullSyncPage().apply {
                        metadata =
                            SyncPageMetadata
                                .builder()
                                .orgId("test.org.no")
                                .corrId(corrId)
                                .totalSize(total.toLong())
                                .page(pageNumber.toLong())
                                .pageSize(indexes.size.toLong())
                                .totalPages(pages.size.toLong())
                                .uriRef("/utdanning/elev/$entity")
                                .time(System.currentTimeMillis())
                                .build()
                        resources = indexes.map(entry)
                    }
                syncPageService.doSync(page, "utdanning", "elev", entity)
            }
        }

        private fun elevEntry(
            id: String,
            index: Int,
        ): SyncPageEntry {
            val base = mutableMapOf<String, Any>("systemId" to mapOf("identifikatorverdi" to id))
            when (index % 3) {
                1 -> {
                    base["elevnummer"] = mapOf("identifikatorverdi" to "nr-$index")
                    base["feidenavn"] = mapOf("identifikatorverdi" to "feide-$index@fintlabs.no")
                    base["kontaktinformasjon"] = mapOf("epostadresse" to "elev-$index@fintlabs.no")
                }
                2 -> {
                    base["elevnummer"] = mapOf("identifikatorverdi" to "nr-$index")
                    base["brukernavn"] = mapOf("identifikatorverdi" to PADDING_1KB)
                    base["feidenavn"] = mapOf("identifikatorverdi" to PADDING_1KB)
                    base["kontaktinformasjon"] = mapOf("epostadresse" to PADDING_1KB + PADDING_1KB)
                }
            }
            return SyncPageEntry.of(id, base)
        }

        private fun elevforholdEntry(
            id: String,
            elevId: String,
        ): SyncPageEntry =
            SyncPageEntry.of(
                id,
                mapOf(
                    "systemId" to mapOf("identifikatorverdi" to id),
                    "_links" to mapOf("elev" to listOf(mapOf("href" to "systemid/$elevId"))),
                ),
            )

        private fun awaitUntil(
            timeoutMs: Long = 30_000,
            condition: () -> Boolean,
        ) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (condition()) return
                Thread.sleep(100)
            }
            throw AssertionError("condition not met within ${timeoutMs}ms")
        }

        companion object {
            private val TOTAL_RECORDS = Integer.getInteger("benchmark.records", 20_000)
            private val PAGE_SIZE = Integer.getInteger("benchmark.pageSize", 1_000)
            private val PADDING_1KB = "x".repeat(1024)

            @JvmStatic
            @DynamicPropertySource
            fun ingestProperties(registry: DynamicPropertyRegistry) {
                registry.add("fint.provider.sync-ingest.max-poll-records") { System.getProperty("benchmark.maxPollRecords", "500") }
                registry.add("fint.provider.sync-ingest.idle-between-polls") { System.getProperty("benchmark.idleBetweenPolls", "0ms") }
            }
        }
    }
