package no.fintlabs.provider.datasync

import no.fintlabs.adapter.models.sync.FullSyncPage
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.models.sync.SyncPageMetadata
import no.fintlabs.cache.CacheService
import no.fintlabs.provider.TestcontainersConfiguration
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.kafka.test.context.EmbeddedKafka
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@EmbeddedKafka(partitions = 1)
@Import(TestcontainersConfiguration::class)
class SyncEvictionIntegrationTest
    @Autowired
    constructor(
        private val syncPageService: SyncPageService,
        private val cacheService: CacheService,
    ) {
        private val domain = "utdanning"
        private val pkg = "elev"
        private val resource = "elev"
        private val idA = "utdanning.elev.elev/systemid/a"
        private val idB = "utdanning.elev.elev/systemid/b"

        @Test
        fun `full sync evicts resources missing from the new sync`() {
            val cache = cacheService.getCache("utdanning_elev_elev")

            syncPageService.doSync(fullSync(totalSize = 2, idA, idB), domain, pkg, resource)
            awaitUntil { cache.get(idA) != null && cache.get(idB) != null }

            // ensure the second full sync writes with a strictly greater timestamp
            Thread.sleep(5)

            syncPageService.doSync(fullSync(totalSize = 1, idA), domain, pkg, resource)
            awaitUntil { cache.get(idB) == null }

            assertNotNull(cache.get(idA), "resource present in the second full sync must survive")
            assertNull(cache.get(idB), "resource absent from the second full sync must be evicted")
        }

        private fun fullSync(
            totalSize: Long,
            vararg identifiers: String,
        ): FullSyncPage =
            FullSyncPage().apply {
                metadata =
                    SyncPageMetadata
                        .builder()
                        .orgId("test.org.no")
                        .corrId(UUID.randomUUID().toString())
                        .totalSize(totalSize)
                        .page(0)
                        .pageSize(identifiers.size.toLong())
                        .totalPages(1)
                        .uriRef("/$domain/$pkg/$resource")
                        .time(System.currentTimeMillis())
                        .build()
                resources = identifiers.map { SyncPageEntry.of(it, mapOf("name" to it)) }
            }

        private fun awaitUntil(
            timeoutMs: Long = 10_000,
            condition: () -> Boolean,
        ) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (condition()) return
                Thread.sleep(50)
            }
            throw AssertionError("condition not met within ${timeoutMs}ms")
        }
    }
