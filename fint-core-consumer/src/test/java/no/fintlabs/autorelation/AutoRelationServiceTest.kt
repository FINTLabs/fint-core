package no.fintlabs.autorelation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.Called
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.autorelation.buffer.UnresolvedRelationCache
import no.fintlabs.autorelation.cache.RelationRuleRegistry
import no.fintlabs.autorelation.model.EntityDescriptor
import no.fintlabs.autorelation.model.InvalidLinkException
import no.fintlabs.autorelation.model.MetricReason
import no.fintlabs.autorelation.model.RelationBinding
import no.fintlabs.autorelation.model.RelationOperation
import no.fintlabs.autorelation.model.RelationSyncRule
import no.fintlabs.autorelation.model.RelationUpdate
import no.fintlabs.cache.CacheService
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.links.LinkService
import no.fintlabs.consumer.resource.ResourceLockService
import no.fintlabs.consumer.resource.context.ResourceContext
import no.novari.fint.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.Link
import no.novari.fint.model.resource.utdanning.vurdering.ElevfravarResource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AutoRelationServiceTest {
    private var linkService: LinkService = mockk(relaxed = true)
    private var cacheService: CacheService = mockk(relaxed = true)
    private var unresolvedRelationCache: UnresolvedRelationCache = mockk(relaxed = true)
    private var relationRuleRegistry: RelationRuleRegistry = mockk(relaxed = true)
    private var consumerConfig: ConsumerConfiguration = mockk(relaxed = true)
    private var relationEventService: RelationEventService = mockk(relaxed = true)
    private var objectMapper: ObjectMapper = jacksonObjectMapper()
    private var resourceContext: ResourceContext = mockk(relaxed = true)
    private var resourceLockService: ResourceLockService =
        mockk {
            every { withLock(any(), any(), any()) } answers {
                val block = thirdArg<() -> Unit>()
                block()
            }
        }
    private val metricService: MetricService = mockk(relaxed = true)

    private var service: AutoRelationService =
        AutoRelationService(
            linkService,
            cacheService,
            consumerConfig,
            relationRuleRegistry,
            relationEventService,
            unresolvedRelationCache,
            resourceContext,
            objectMapper,
            resourceLockService,
            metricService,
        )

    private val relationUpdate: RelationUpdate = createRelationUpdate()

    @BeforeEach
    fun setUpClassMock() {
        // resourceContext bridges resource names to their concrete classes (not interfaces)
        every {
            resourceContext.getResource(any())!!.clazz
        } returns ElevfravarResource::class.java as Class<out FintResource>
    }

    @AfterEach
    fun tearDown() = clearAllMocks()

    @Nested
    inner class ProcessRelationUpdateScenarios {
        @Test
        fun `should apply update immediately when resource exists in cache`() {
            val resource = createElevFravar()
            val targetId = relationUpdate.targetIds.first()

            every {
                cacheService.getCache(relationUpdate.targetEntity.resourceName).get(targetId)
            } returns resource
            every {
                cacheService.getCache(relationUpdate.targetEntity.resourceName).put(any(), any(), any())
            } returns true

            service.process(relationUpdate)

            verify(exactly = 1) { linkService.mapLinks(relationUpdate.targetEntity.resourceName, any()) }
            verify(exactly = 1) {
                metricService.incrementUpdateApplied(
                    relationUpdate.targetEntity.resourceName,
                    relationUpdate.operation,
                )
            }
            verify(exactly = 0) { metricService.incrementCachePutRejectedOlderTimestamp(any()) }
            verify { unresolvedRelationCache wasNot Called }
        }

        @Test
        fun `should record cache put rejected metric when put returns false`() {
            val resource = createElevFravar()
            val targetId = relationUpdate.targetIds.first()

            every {
                cacheService.getCache(relationUpdate.targetEntity.resourceName).get(targetId)
            } returns resource
            every {
                cacheService.getCache(relationUpdate.targetEntity.resourceName).put(any(), any(), any())
            } returns false

            service.process(relationUpdate)

            verify(exactly = 1) {
                metricService.incrementCachePutRejectedOlderTimestamp(relationUpdate.targetEntity.resourceName)
            }
        }

        @Test
        fun `should buffer ADD operation if resource does not exist`() {
            val addUpdate = createRelationUpdate(operation = RelationOperation.ADD)
            val targetId = addUpdate.targetIds.first()

            every {
                cacheService.getCache(addUpdate.targetEntity.resourceName).get(targetId)
            } returns null

            service.process(addUpdate)

            verify(exactly = 1) {
                unresolvedRelationCache.registerRelation(
                    resourceName = addUpdate.targetEntity.resourceName,
                    resourceId = targetId,
                    relationName = addUpdate.binding.relationName,
                    relationLink = addUpdate.binding.link,
                    createdAt = addUpdate.timestamp,
                )
            }
            verify(exactly = 1) {
                metricService.incrementUpdateBuffered(
                    addUpdate.targetEntity.resourceName,
                    RelationOperation.ADD,
                )
            }
        }

        @Test
        fun `should record failed metric tagged UNEXPECTED_ERROR when an unexpected exception is thrown`() {
            val resource = createElevFravar()
            val targetId = relationUpdate.targetIds.first()

            every {
                cacheService.getCache(relationUpdate.targetEntity.resourceName).get(targetId)
            } returns resource
            every {
                linkService.mapLinks(relationUpdate.targetEntity.resourceName, any())
            } throws RuntimeException("boom")

            service.process(relationUpdate)

            verify(exactly = 1) {
                metricService.incrementUpdateFailed(
                    relationUpdate.targetEntity.resourceName,
                    relationUpdate.operation,
                    MetricReason.UNEXPECTED_ERROR,
                )
            }
            verify(exactly = 0) { metricService.incrementUpdateApplied(any(), any()) }
        }

        @Test
        fun `should record failed metric tagged with metricReason when AutoRelationException is thrown`() {
            val resource = createElevFravar()
            val targetId = relationUpdate.targetIds.first()

            every {
                cacheService.getCache(relationUpdate.targetEntity.resourceName).get(targetId)
            } returns resource
            every {
                linkService.mapLinks(relationUpdate.targetEntity.resourceName, any())
            } throws InvalidLinkException("rel")

            service.process(relationUpdate)

            verify(exactly = 1) {
                metricService.incrementUpdateFailed(
                    relationUpdate.targetEntity.resourceName,
                    relationUpdate.operation,
                    MetricReason.INVALID_LINK,
                )
            }
            verify(exactly = 0) { metricService.incrementUpdateApplied(any(), any()) }
        }

        @Test
        fun `should buffer DELETE operation if resource does not exist`() {
            val deleteUpdate = createRelationUpdate(operation = RelationOperation.DELETE)
            val targetId = deleteUpdate.targetIds.first()

            every {
                cacheService.getCache(deleteUpdate.targetEntity.resourceName).get(targetId)
            } returns null

            service.process(deleteUpdate)

            verify(exactly = 1) {
                unresolvedRelationCache.removeRelation(
                    resourceName = deleteUpdate.targetEntity.resourceName,
                    resourceId = targetId,
                    relationName = deleteUpdate.binding.relationName,
                    relationLink = deleteUpdate.binding.link,
                )
            }
            verify(exactly = 1) {
                metricService.incrementUpdateBuffered(
                    deleteUpdate.targetEntity.resourceName,
                    RelationOperation.DELETE,
                )
            }
        }
    }

    @Nested
    inner class ReconcileLinksScenarios {
        @Test
        fun `should handle new resource (no old resource) without pruning`() {
            val resourceName = "elevfravar"
            val resourceId = "123"
            val newResource = createElevFravar(resourceId)

            every { consumerConfig.domain } returns "test-domain"
            every { consumerConfig.packageName } returns "test-pkg"
            every { cacheService.getCache(resourceName).get(resourceId) } returns null

            service.reconcileLinks(resourceName, resourceId, newResource)

            verify(exactly = 0) { relationEventService.removeObsoleteRelations(any(), any(), any(), any(), any()) }
            verify(exactly = 0) { relationEventService.removeRelations(any(), any(), any()) }

            verify(exactly = 1) { relationRuleRegistry.getInverseRelations(any(), any(), any()) }
        }

        @Test
        fun `should remove obsolete relations when links are removed (Pruning)`() {
            val resourceName = "elevfravar"
            val resourceId = "123"
            val relationName = "rel_test"

            val oldResource =
                createElevFravar(resourceId).apply {
                    addLink(relationName, Link.with("http://old-link"))
                }

            val newResource = createElevFravar(resourceId)

            every { consumerConfig.domain } returns "test-domain"
            every { consumerConfig.packageName } returns "test-pkg"

            val mockRule = mockk<RelationSyncRule>(relaxed = true)
            every { mockRule.targetRelation } returns relationName
            every { mockRule.shouldPruneLinks() } returns true

            every {
                relationRuleRegistry.getRules("test-domain", "test-pkg", resourceName)
            } returns listOf(mockRule)

            every { cacheService.getCache(resourceName).get(resourceId) } returns oldResource

            service.reconcileLinks(resourceName, resourceId, newResource)

            verify(exactly = 1) {
                relationEventService.removeObsoleteRelations(
                    resourceName = resourceName,
                    resourceId = resourceId,
                    currentResource = newResource,
                    obsoleteLinks = any(),
                    rules = any(),
                )
            }
        }

        @Test
        fun `should preserve links from old resource if configured (Inverse Relations)`() {
            val resourceName = "elevfravar"
            val resourceId = "123"
            val relationName = "managed_relation"
            val oldLink = Link.with("http://should-be-kept")

            val oldResource =
                createElevFravar(resourceId).apply {
                    addLink(relationName, oldLink)
                }
            val newResource = createElevFravar(resourceId)

            every { consumerConfig.domain } returns "test-domain"
            every { consumerConfig.packageName } returns "test-pkg"
            every { cacheService.getCache(resourceName).get(resourceId) } returns oldResource

            every { relationRuleRegistry.getRules("test-domain", "test-pkg", resourceName) } returns emptyList()

            every {
                relationRuleRegistry.getInverseRelations("test-domain", "test-pkg", resourceName)
            } returns setOf(relationName)

            service.reconcileLinks(resourceName, resourceId, newResource)

            assert(newResource.links[relationName]?.contains(oldLink) == true)
            verify(exactly = 1) {
                metricService.incrementPreservedLinks(resourceName, relationName, 1)
            }
        }

        @Test
        fun `should apply pending links from buffer`() {
            val resourceName = "elevfravar"
            val resourceId = "123"
            val relationName = "managed_relation"
            val pendingLink = Link.with("http://pending-link")

            val newResource = createElevFravar(resourceId)

            every { consumerConfig.domain } returns "test-domain"
            every { consumerConfig.packageName } returns "test-pkg"
            every { cacheService.getCache(resourceName).get(resourceId) } returns null

            every { relationRuleRegistry.getRules("test-domain", "test-pkg", resourceName) } returns emptyList()

            every {
                relationRuleRegistry.getInverseRelations("test-domain", "test-pkg", resourceName)
            } returns setOf(relationName)
            every {
                unresolvedRelationCache.takeRelations(resourceName, resourceId, relationName)
            } returns listOf(pendingLink)

            service.reconcileLinks(resourceName, resourceId, newResource)

            assert(newResource.links[relationName]?.contains(pendingLink) == true)
            verify(exactly = 1) {
                metricService.incrementHydratedLinks(resourceName, relationName, 1)
            }
        }
    }

    private fun createElevFravar(id: String = "123"): ElevfravarResource =
        ElevfravarResource().apply {
            systemId = Identifikator().apply { identifikatorverdi = id }
        }

    private fun createRelationUpdate(
        orgId: String = "fintlabs.no",
        domain: String = "utdanning",
        pkg: String = "vurdering",
        resource: String = "elevfravar",
        resourceId: String = "123",
        relation: String = "fravarsregistrering",
        relationId: String = "321",
        operation: RelationOperation = RelationOperation.ADD,
        timestamp: Long = System.currentTimeMillis(),
    ) = RelationUpdate(
        binding =
            RelationBinding(
                relationName = relation,
                link = Link.with("systemid/$relationId"),
            ),
        operation = operation,
        targetEntity = EntityDescriptor(domain, pkg, resource),
        targetIds = listOf(resourceId),
        timestamp = timestamp,
    )
}
