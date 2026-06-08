package no.novari.fint.core.provider.datasync

import no.fintlabs.adapter.models.sync.DeleteSyncPage
import no.fintlabs.adapter.models.sync.FullSyncPage
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.models.sync.SyncPageMetadata
import no.novari.fint.core.provider.TestcontainersConfiguration
import no.novari.fint.core.shared.cache.CacheService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.kafka.test.context.EmbeddedKafka
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@EmbeddedKafka(partitions = 1)
@Import(TestcontainersConfiguration::class)
class SyncAutoRelationIntegrationTest
    @Autowired
    constructor(
        private val syncPageService: SyncPageService,
        private val cacheService: CacheService,
    ) {
        private val elevKey = "utdanning_elev_elev"
        private val elevforholdKey = "utdanning_elev_elevforhold"
        private val backRelation = "elevforhold"

        @Test
        fun `full sync wires a back-link from elevforhold onto its elev`() {
            val elevCache = cacheService.getCache(elevKey)

            syncElev("e1")
            syncElevforhold(totalSize = 1, Elevforhold("ef1", "e1"))

            awaitUntil { elevCache.findIdsByBackLink(backRelation, "systemid/ef1").isNotEmpty() }

            assertEquals(setOf("e1"), elevCache.findIdsByBackLink(backRelation, "systemid/ef1"))
            val elev = elevCache.get("e1")
            assertTrue(
                elev != null && elev.links[backRelation]?.isNotEmpty() == true,
                "elev must expose the elevforhold back-link in its _links",
            )
        }

        @Test
        fun `eviction retracts the back-link of an elevforhold dropped from a later full sync`() {
            val elevCache = cacheService.getCache(elevKey)

            syncElev("e2")
            syncElevforhold(totalSize = 1, Elevforhold("ef2", "e2"))
            awaitUntil { elevCache.findIdsByBackLink(backRelation, "systemid/ef2").isNotEmpty() }

            Thread.sleep(5)
            syncElevforhold(totalSize = 1, Elevforhold("ef2b", "e2"))

            awaitUntil { elevCache.findIdsByBackLink(backRelation, "systemid/ef2").isEmpty() }

            assertTrue(
                elevCache.findIdsByBackLink(backRelation, "systemid/ef2").isEmpty(),
                "evicted elevforhold must not leave a dangling back-link",
            )
            assertEquals(
                setOf("e2"),
                elevCache.findIdsByBackLink(backRelation, "systemid/ef2b"),
                "the elevforhold present in the latest full sync keeps its back-link",
            )
        }

        @Test
        fun `delete sync retracts the back-link`() {
            val elevCache = cacheService.getCache(elevKey)

            syncElev("e3")
            syncElevforhold(totalSize = 1, Elevforhold("ef3", "e3"))
            awaitUntil { elevCache.findIdsByBackLink(backRelation, "systemid/ef3").isNotEmpty() }

            deleteElevforhold("ef3")

            assertTrue(
                elevCache.findIdsByBackLink(backRelation, "systemid/ef3").isEmpty(),
                "deleted elevforhold must not leave a dangling back-link",
            )
        }

        @Test
        fun `evicting the target removes back-link rows that pointed at it`() {
            val elevCache = cacheService.getCache(elevKey)

            syncElev("e4")
            syncElevforhold(totalSize = 1, Elevforhold("ef4", "e4"))
            awaitUntil { elevCache.findIdsByBackLink(backRelation, "systemid/ef4").isNotEmpty() }

            Thread.sleep(5)
            syncElev("e4keep")

            awaitUntil { elevCache.get("e4") == null }

            assertNull(elevCache.get("e4"), "the target must be evicted")
            assertTrue(
                elevCache.findIdsByBackLink(backRelation, "systemid/ef4").isEmpty(),
                "no back-link row may point at an evicted target",
            )
        }

        private fun syncElev(vararg ids: String) =
            syncPageService.doSync(
                fullSync(
                    ids.size.toLong(),
                    ids.map { SyncPageEntry.of(it, mapOf("systemId" to mapOf("identifikatorverdi" to it))) },
                ),
                "utdanning",
                "elev",
                "elev",
            )

        private fun syncElevforhold(
            totalSize: Long,
            vararg elevforhold: Elevforhold,
        ) = syncPageService.doSync(
            fullSync(totalSize, elevforhold.map { it.toEntry() }),
            "utdanning",
            "elev",
            "elevforhold",
        )

        private fun deleteElevforhold(id: String) =
            syncPageService.doSync(
                DeleteSyncPage().apply {
                    metadata = metadata(1)
                    resources = listOf(SyncPageEntry.of(id, mapOf("systemId" to mapOf("identifikatorverdi" to id))))
                },
                "utdanning",
                "elev",
                "elevforhold",
            )

        private fun fullSync(
            totalSize: Long,
            entries: List<SyncPageEntry>,
        ): FullSyncPage =
            FullSyncPage().apply {
                metadata = metadata(totalSize, entries.size.toLong())
                resources = entries
            }

        private fun metadata(
            totalSize: Long,
            pageSize: Long = totalSize,
        ): SyncPageMetadata =
            SyncPageMetadata
                .builder()
                .orgId("test.org.no")
                .corrId(UUID.randomUUID().toString())
                .totalSize(totalSize)
                .page(0)
                .pageSize(pageSize)
                .totalPages(1)
                .uriRef("/utdanning/elev")
                .time(System.currentTimeMillis())
                .build()

        private data class Elevforhold(
            val id: String,
            val elevId: String,
        ) {
            fun toEntry(): SyncPageEntry =
                SyncPageEntry.of(
                    id,
                    mapOf(
                        "systemId" to mapOf("identifikatorverdi" to id),
                        "_links" to mapOf("elev" to listOf(mapOf("href" to "systemid/$elevId"))),
                    ),
                )
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
