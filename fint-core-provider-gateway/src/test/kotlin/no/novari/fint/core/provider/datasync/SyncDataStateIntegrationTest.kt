package no.novari.fint.core.provider.datasync

import no.fintlabs.adapter.models.sync.DeleteSyncPage
import no.fintlabs.adapter.models.sync.DeltaSyncPage
import no.fintlabs.adapter.models.sync.FullSyncPage
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.models.sync.SyncPageMetadata
import no.novari.fint.core.provider.TestcontainersConfiguration
import no.novari.fint.core.shared.cache.CacheService
import no.novari.fint.model.resource.utdanning.elev.ElevResource
import org.junit.jupiter.api.Assertions.assertEquals
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
class SyncDataStateIntegrationTest
    @Autowired
    constructor(
        private val syncPageService: SyncPageService,
        private val cacheService: CacheService,
    ) {
        private val key = "utdanning_elev_elev"

        @Test
        fun `delta sync updates the targeted resource, adds new ones and leaves the rest untouched`() {
            val cache = cacheService.getCache(key)

            doFull(entry("da", gjest = false), entry("db", gjest = false), entry("dc", gjest = false))
            awaitUntil { cache.get("da") != null && cache.get("db") != null && cache.get("dc") != null }

            doDelta(entry("da", gjest = true), entry("dd", gjest = false))

            assertEquals(true, (cache.get("da") as ElevResource).gjest, "delta must update the targeted resource")
            assertNotNull(cache.get("dd"), "delta must add the new resource")
            assertNotNull(cache.get("db"), "resource absent from the delta must survive")
            assertNotNull(cache.get("dc"), "resource absent from the delta must survive")
            assertEquals(false, (cache.get("db") as ElevResource).gjest, "an untouched resource keeps its value")
        }

        @Test
        fun `delete sync removes only the targeted resource`() {
            val cache = cacheService.getCache(key)

            doFull(entry("xa"), entry("xb"), entry("xc"))
            awaitUntil { cache.get("xa") != null && cache.get("xb") != null && cache.get("xc") != null }

            doDelete("xb")

            assertNull(cache.get("xb"), "delete must remove the targeted resource")
            assertNotNull(cache.get("xa"), "delete must not touch other resources")
            assertNotNull(cache.get("xc"), "delete must not touch other resources")
        }

        private fun entry(
            id: String,
            gjest: Boolean = false,
        ): SyncPageEntry =
            SyncPageEntry.of(
                id,
                mapOf("systemId" to mapOf("identifikatorverdi" to id), "gjest" to gjest),
            )

        private fun doFull(vararg entries: SyncPageEntry) =
            syncPageService.doSync(page(FullSyncPage(), entries.toList()), "utdanning", "elev", "elev")

        private fun doDelta(vararg entries: SyncPageEntry) =
            syncPageService.doSync(page(DeltaSyncPage(), entries.toList()), "utdanning", "elev", "elev")

        private fun doDelete(vararg ids: String) =
            syncPageService.doSync(
                page(DeleteSyncPage(), ids.map { SyncPageEntry.of(it, mapOf("systemId" to mapOf("identifikatorverdi" to it))) }),
                "utdanning",
                "elev",
                "elev",
            )

        private fun <T : no.fintlabs.adapter.models.sync.SyncPage> page(
            page: T,
            entries: List<SyncPageEntry>,
        ): T =
            page.apply {
                metadata = meta(entries.size.toLong())
                resources = entries
            }

        private fun meta(totalSize: Long): SyncPageMetadata =
            SyncPageMetadata
                .builder()
                .orgId("test.org.no")
                .corrId(UUID.randomUUID().toString())
                .totalSize(totalSize)
                .page(0)
                .pageSize(totalSize)
                .totalPages(1)
                .uriRef("/utdanning/elev")
                .time(System.currentTimeMillis())
                .build()

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
