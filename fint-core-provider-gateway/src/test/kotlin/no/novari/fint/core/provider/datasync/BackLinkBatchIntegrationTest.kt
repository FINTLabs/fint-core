package no.novari.fint.core.provider.datasync

import no.novari.fint.core.shared.cache.BackLinkOp
import no.novari.fint.core.shared.cache.CacheService
import no.novari.fint.core.provider.TestcontainersConfiguration
import no.novari.fint.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.model.resource.Link
import no.novari.fint.model.resource.utdanning.elev.ElevResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.kafka.test.context.EmbeddedKafka

/**
 * Exercises the bulk back-link pipeline ([no.novari.fint.core.shared.cache.FintCache.applyBackLinkOps]) against a
 * real Mongo: a batch of adds creates stubs, a later [put] fills a stub without dropping its
 * back-link, and a batch remove retracts it. This is the integration coverage the autorelation
 * write path lost when the consumer's FintCacheIT was removed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@EmbeddedKafka(partitions = 1)
@Import(TestcontainersConfiguration::class)
class BackLinkBatchIntegrationTest
    @Autowired
    constructor(
        private val cacheService: CacheService,
    ) {
        private val targetKey = "utdanning_elev_elev"
        private val relation = "elevfravar"
        private val sourceRef = "systemid/src-1"

        @Test
        fun `batch adds create stubs, put fills one keeping the back-link, batch remove drops it`() {
            val cache = cacheService.getCache(targetKey)
            val link = Link.with(sourceRef)

            cache.applyBackLinkOps(
                listOf(BackLinkOp.Add("t1", relation, link), BackLinkOp.Add("t2", relation, link)),
                1000L,
            )

            assertEquals(setOf("t1", "t2"), cache.findIdsByBackLink(relation, sourceRef))
            assertNull(cache.get("t1"), "a back-link-only target is a data-less stub, invisible to reads")

            cache.put("t1", ElevResource().apply { systemId = Identifikator().apply { identifikatorverdi = "t1" } }, 2000L)

            val filled = cache.get("t1")
            assertTrue(filled != null && filled.links.containsKey(relation), "put must fill the stub without dropping the back-link")
            assertEquals(sourceRef, filled!!.links[relation]!!.first().href)

            cache.applyBackLinkOps(listOf(BackLinkOp.Remove("t1", relation, sourceRef)), 3000L)

            assertEquals(setOf("t2"), cache.findIdsByBackLink(relation, sourceRef))
            assertTrue(cache.get("t1")!!.links[relation].isNullOrEmpty(), "batch remove must drop the back-link")
        }
    }
