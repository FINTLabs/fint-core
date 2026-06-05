package no.novari.fint.core.shared.autorelation

import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import no.novari.fint.core.shared.autorelation.cache.RelationRuleRegistry
import no.novari.fint.core.shared.autorelation.model.EntityDescriptor
import no.novari.fint.core.shared.autorelation.model.MetricReason
import no.novari.fint.core.shared.autorelation.model.RelationSyncRule
import no.novari.fint.core.shared.cache.BackLinkOp
import no.novari.fint.core.shared.cache.CacheService
import no.novari.fint.core.shared.cache.FintCache
import no.novari.fint.core.shared.link.LinkService
import no.novari.fint.model.FintMultiplicity
import no.novari.fint.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.model.resource.Link
import no.novari.fint.model.resource.utdanning.vurdering.ElevfravarResource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AutoRelationServiceTest {
    private val linkService: LinkService = mockk(relaxed = true)
    private val cacheService: CacheService = mockk(relaxed = true)
    private val cache: FintCache = mockk(relaxed = true)
    private val relationRuleRegistry: RelationRuleRegistry = mockk(relaxed = true)
    private val metricService: MetricService = mockk(relaxed = true)

    private val service =
        AutoRelationService(
            linkService,
            cacheService,
            relationRuleRegistry,
            metricService,
        )

    private val sourceKey = "utdanning_vurdering_elevfravar"
    private val sourceDescriptor = EntityDescriptor("utdanning", "vurdering", "elevfravar")
    private val targetKey = "utdanning_vurdering_elev"
    private val inverseRelation = "elevfravar"
    private val sourceRef = "systemid/source-1"

    private val rule =
        RelationSyncRule(
            targetRelation = "elev",
            inverseRelation = inverseRelation,
            targetType = EntityDescriptor("utdanning", "vurdering", "elev"),
            targetMultiplicity = FintMultiplicity.NONE_TO_MANY,
            inverseMultiplicity = FintMultiplicity.NONE_TO_MANY,
            isSource = true,
        )

    @BeforeEach
    fun setUp() {
        every { cacheService.getCache(any()) } returns cache
        every { relationRuleRegistry.getRules(sourceDescriptor) } returns listOf(rule)
        every { cache.findIdsByBackLink(any(), any()) } returns emptySet()
        every { cache.findIdsByBackLinks(any(), any()) } returns emptyMap()
    }

    @AfterEach
    fun tearDown() = clearAllMocks()

    @Nested
    inner class ApplyRelations {
        @Test
        fun `adds a back-link to a target not yet pointing back`() {
            every { cache.findIdsByBackLink(inverseRelation, sourceRef) } returns emptySet()

            service.applyRelations(sourceKey, "source-1", sourceWithTarget("source-1", "t1"))

            verify(exactly = 1) { cache.addBackLink("t1", inverseRelation, any(), any()) }
            verify(exactly = 1) { metricService.incrementUpdateApplied(targetKey, "added") }
        }

        @Test
        fun `leaves an already-resolved, still-desired target untouched`() {
            every { cache.findIdsByBackLink(inverseRelation, sourceRef) } returns setOf("t1")

            service.applyRelations(sourceKey, "source-1", sourceWithTarget("source-1", "t1"))

            verify(exactly = 0) { cache.addBackLink(any(), any(), any(), any()) }
            verify(exactly = 0) { cache.removeBackLink(any(), any(), any(), any()) }
        }

        @Test
        fun `removes a resolved target no longer desired`() {
            every { cache.findIdsByBackLink(inverseRelation, sourceRef) } returns setOf("t1")

            service.applyRelations(sourceKey, "source-1", elevfravar("source-1"))

            verify(exactly = 1) { cache.removeBackLink("t1", inverseRelation, sourceRef, any()) }
            verify(exactly = 1) { metricService.incrementUpdateApplied(targetKey, "removed") }
        }

        @Test
        fun `records UNEXPECTED_ERROR when applying throws`() {
            every { cache.findIdsByBackLink(inverseRelation, sourceRef) } returns emptySet()
            every { cache.addBackLink(any(), any(), any(), any()) } throws RuntimeException("boom")

            service.applyRelations(sourceKey, "source-1", sourceWithTarget("source-1", "t1"))

            verify(exactly = 1) { metricService.incrementUpdateFailed(targetKey, MetricReason.UNEXPECTED_ERROR) }
        }
    }

    @Nested
    inner class ApplyRemoval {
        @Test
        fun `removes a back-link from a resolved target`() {
            every { cache.findIdsByBackLink(inverseRelation, sourceRef) } returns setOf("t1")

            service.applyRemoval(sourceKey, "source-1", elevfravar("source-1"))

            verify(exactly = 1) { cache.removeBackLink("t1", inverseRelation, sourceRef, any()) }
            verify(exactly = 1) { metricService.incrementUpdateApplied(targetKey, "removed") }
        }
    }

    @Nested
    inner class BatchReconcile {
        @Test
        fun `batches adds for the same target collection into one applyBackLinkOps`() {
            service.applyRelations(
                sourceKey,
                listOf(
                    "source-1" to sourceWithTarget("source-1", "t1"),
                    "source-2" to sourceWithTarget("source-2", "t2"),
                ),
                123L,
            )

            val ops = slot<List<BackLinkOp>>()
            verify(exactly = 1) { cache.applyBackLinkOps(capture(ops), 123L) }
            assertEquals(2, ops.captured.size)
            assertTrue(ops.captured.all { it is BackLinkOp.Add })
        }

        @Test
        fun `batch applyRemoval flushes removals via applyBackLinkOps`() {
            every { cache.findIdsByBackLinks(inverseRelation, any()) } returns mapOf(sourceRef to setOf("t1"))

            service.applyRemoval(sourceKey, listOf("source-1" to elevfravar("source-1")), 123L)

            val ops = slot<List<BackLinkOp>>()
            verify(exactly = 1) { cache.applyBackLinkOps(capture(ops), 123L) }
            assertEquals(listOf(BackLinkOp.Remove("t1", inverseRelation, sourceRef)), ops.captured)
        }

        @Test
        fun `empty batch never touches the cache`() {
            service.applyRelations(sourceKey, emptyList(), 123L)

            verify(exactly = 0) { cache.applyBackLinkOps(any(), any()) }
        }
    }

    private fun elevfravar(id: String): ElevfravarResource =
        ElevfravarResource().apply {
            systemId = Identifikator().apply { identifikatorverdi = id }
        }

    private fun sourceWithTarget(
        id: String,
        targetId: String,
    ): ElevfravarResource = elevfravar(id).apply { addLink("elev", Link.with("systemid/$targetId")) }
}
