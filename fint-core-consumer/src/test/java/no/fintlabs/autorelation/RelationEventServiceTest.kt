package no.fintlabs.autorelation

import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.autorelation.cache.RelationRuleRegistry
import no.fintlabs.autorelation.kafka.RelationUpdateProducer
import no.fintlabs.autorelation.model.EntityDescriptor
import no.fintlabs.autorelation.model.MetricReason
import no.fintlabs.autorelation.model.RelationOperation
import no.fintlabs.autorelation.model.RelationSyncRule
import no.fintlabs.autorelation.model.RelationUpdate
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.resource.ResourceConverter
import no.novari.fint.model.FintMultiplicity
import no.novari.fint.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.model.resource.Link
import no.novari.fint.model.resource.utdanning.vurdering.ElevfravarResource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RelationEventServiceTest {
    private val resourceConverter: ResourceConverter = mockk(relaxed = true)
    private val relationRuleRegistry: RelationRuleRegistry = mockk(relaxed = true)
    private val consumerConfiguration: ConsumerConfiguration = mockk(relaxed = true)
    private val relationUpdateProducer: RelationUpdateProducer = mockk(relaxed = true)
    private val metricService: MetricService = mockk(relaxed = true)

    private val service =
        RelationEventService(
            resourceConverter,
            relationRuleRegistry,
            consumerConfiguration,
            relationUpdateProducer,
            metricService,
        )

    private val resourceName = "elevfravar"
    private val resourceId = "123"
    private val targetType = EntityDescriptor("utdanning", "vurdering", "fravarsregistrering")

    @BeforeEach
    fun setUp() {
        every { consumerConfiguration.domain } returns "utdanning"
        every { consumerConfiguration.packageName } returns "vurdering"
    }

    @AfterEach
    fun tearDown() = clearAllMocks()

    @Test
    fun `addRelations returns silently when no rules are registered`() {
        every { relationRuleRegistry.getRules(any(), any(), resourceName) } returns emptyList()

        service.addRelations(resourceName, resourceId, mockk<Any>())

        verify { relationUpdateProducer wasNot io.mockk.Called }
        verify(exactly = 0) { metricService.incrementRuleSkipped(any(), any()) }
    }

    @Test
    fun `addRelations records CONVERSION_FAILED when conversion throws`() {
        every { relationRuleRegistry.getRules(any(), any(), resourceName) } returns
            listOf(buildRule(targetRelation = "fravarsregistrering", isToOne = false))
        every { resourceConverter.convert(resourceName, any()) } throws RuntimeException("bad payload")

        service.addRelations(resourceName, resourceId, mockk<Any>())

        verify(exactly = 1) {
            metricService.incrementRuleSkipped(resourceName, MetricReason.CONVERSION_FAILED)
        }
        verify { relationUpdateProducer wasNot io.mockk.Called }
    }

    @Test
    fun `publishAll records NO_TARGETS when toRelationUpdate yields null`() {
        // non-mandatory rule + resource with no link for that relation -> getTargetIds returns null
        val rule = buildRule(targetRelation = "fravarsregistrering", isToOne = false)
        val resource = createElevFravar(resourceId)

        every { relationRuleRegistry.getRules(any(), any(), resourceName) } returns listOf(rule)

        service.removeRelations(resourceName, resourceId, resource)

        verify(exactly = 1) {
            metricService.incrementRuleSkipped(resourceName, MetricReason.NO_TARGETS)
        }
        verify { relationUpdateProducer wasNot io.mockk.Called }
    }

    @Test
    fun `publishAll records MISSING_MANDATORY_LINK when mandatory rule has no link`() {
        // mandatory rule + resource with no link for that relation -> getTargetIds throws
        val rule = buildRule(targetRelation = "fravarsregistrering", isToOne = true)
        val resource = createElevFravar(resourceId)

        every { relationRuleRegistry.getRules(any(), any(), resourceName) } returns listOf(rule)

        service.removeRelations(resourceName, resourceId, resource)

        verify(exactly = 1) {
            metricService.incrementRuleSkipped(resourceName, MetricReason.MISSING_MANDATORY_LINK)
        }
    }

    @Test
    fun `publishAll records INVALID_LINK when target link href is malformed`() {
        val rule = buildRule(targetRelation = "fravarsregistrering", isToOne = false)
        val resource =
            createElevFravar(resourceId).apply {
                addLink("fravarsregistrering", Link.with("badhrefnoslash"))
            }

        every { relationRuleRegistry.getRules(any(), any(), resourceName) } returns listOf(rule)

        service.removeRelations(resourceName, resourceId, resource)

        verify(exactly = 1) {
            metricService.incrementRuleSkipped(resourceName, MetricReason.INVALID_LINK)
        }
    }

    @Test
    fun `publishAll records VALIDATION_ID_MISMATCH when resourceId is not in identifikators`() {
        val rule = buildRule(targetRelation = "fravarsregistrering", isToOne = false)
        // resource has id "999" but caller passes resourceId "123" -> toLink throws
        val resource =
            createElevFravar("999").apply {
                addLink("fravarsregistrering", Link.with("systemid/abc"))
            }

        every { relationRuleRegistry.getRules(any(), any(), resourceName) } returns listOf(rule)

        service.removeRelations(resourceName, resourceId, resource)

        verify(exactly = 1) {
            metricService.incrementRuleSkipped(resourceName, MetricReason.VALIDATION_ID_MISMATCH)
        }
    }

    @Test
    fun `publishAll records UNEXPECTED_ERROR when producer throws unknown exception`() {
        val rule = buildRule(targetRelation = "fravarsregistrering", isToOne = false)
        val resource =
            createElevFravar(resourceId).apply {
                addLink("fravarsregistrering", Link.with("systemid/abc"))
            }

        every { relationRuleRegistry.getRules(any(), any(), resourceName) } returns listOf(rule)
        every {
            relationUpdateProducer.publishRelationUpdate(
                any(),
                any(),
                any(),
            )
        } throws RuntimeException("kafka down")

        service.removeRelations(resourceName, resourceId, resource)

        verify(exactly = 1) {
            metricService.incrementRuleSkipped(resourceName, MetricReason.UNEXPECTED_ERROR)
        }
    }

    @Test
    fun `publishAll publishes update on happy path and does not increment skip metric`() {
        val rule = buildRule(targetRelation = "fravarsregistrering", isToOne = false)
        val resource =
            createElevFravar(resourceId).apply {
                addLink("fravarsregistrering", Link.with("systemid/abc"))
            }

        every { relationRuleRegistry.getRules(any(), any(), resourceName) } returns listOf(rule)

        service.removeRelations(resourceName, resourceId, resource)

        verify(exactly = 1) {
            relationUpdateProducer.publishRelationUpdate(
                match<RelationUpdate> { it.operation == RelationOperation.DELETE },
                resourceName,
                resourceId,
            )
        }
        verify(exactly = 0) { metricService.incrementRuleSkipped(any(), any()) }
    }

    private fun createElevFravar(id: String): ElevfravarResource =
        ElevfravarResource().apply {
            systemId = Identifikator().apply { identifikatorverdi = id }
        }

    /**
     * `isToOne = true` -> targetMultiplicity ONE_TO_ONE, isMandatory = true.
     * `isToOne = false` -> NONE_TO_MANY/NONE_TO_MANY, isMandatory = false.
     */
    private fun buildRule(
        targetRelation: String,
        isToOne: Boolean,
    ): RelationSyncRule {
        val targetMult = if (isToOne) FintMultiplicity.ONE_TO_ONE else FintMultiplicity.NONE_TO_MANY
        return RelationSyncRule(
            targetRelation = targetRelation,
            inverseRelation = "elevfravar",
            targetType = targetType,
            targetMultiplicity = targetMult,
            inverseMultiplicity = FintMultiplicity.NONE_TO_MANY,
            isSource = true,
        )
    }
}
