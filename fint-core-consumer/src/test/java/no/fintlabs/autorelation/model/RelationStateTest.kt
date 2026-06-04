package no.fintlabs.autorelation.model

import io.mockk.every
import io.mockk.mockk
import no.novari.fint.model.FintIdentifikator
import no.novari.fint.model.FintMultiplicity
import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.Link
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RelationStateTest {
    private val targetEntity = EntityDescriptor("utdanning", "vurdering", "elevfravar")

    private fun createRule(
        targetRelation: String = "elevfravar",
        inverseRelation: String = "fravaersregistrering",
        targetMultiplicity: FintMultiplicity = FintMultiplicity.ONE_TO_ONE,
        inverseMultiplicity: FintMultiplicity = FintMultiplicity.ONE_TO_MANY,
        isSource: Boolean = true,
    ) = RelationSyncRule(
        targetRelation = targetRelation,
        inverseRelation = inverseRelation,
        targetType = targetEntity,
        targetMultiplicity = targetMultiplicity,
        inverseMultiplicity = inverseMultiplicity,
        isSource = isSource,
    )

    private fun createIdentifikator(value: String) =
        mockk<FintIdentifikator> {
            every { identifikatorverdi } returns value
        }

    private fun createResource(
        links: Map<String, List<Link>>,
        identifikators: Map<String, FintIdentifikator> = mapOf("systemId" to createIdentifikator("test-id")),
    ): FintResource =
        mockk {
            every { this@mockk.links } returns links
            every { this@mockk.identifikators } returns identifikators
        }

    @Nested
    inner class MissingLinkTests {
        @Test
        fun `yields empty state when a mandatory relation has no link`() {
            val rule = createRule(targetMultiplicity = FintMultiplicity.ONE_TO_ONE)
            val resource = createResource(mapOf("elevfravar" to emptyList()))

            assertTrue(rule.toRelationState(resource, "test-id").targetIds.isEmpty())
        }

        @Test
        fun `yields empty state when the only link has a blank href`() {
            val rule = createRule(targetMultiplicity = FintMultiplicity.ONE_TO_ONE)
            val resource = createResource(mapOf("elevfravar" to listOf(Link.with(""))))

            assertTrue(rule.toRelationState(resource, "test-id").targetIds.isEmpty())
        }

        @Test
        fun `yields empty state when the target relation is absent`() {
            val rule = createRule(targetMultiplicity = FintMultiplicity.ONE_TO_MANY)
            val resource = createResource(emptyMap())

            assertTrue(rule.toRelationState(resource, "test-id").targetIds.isEmpty())
        }
    }

    @Nested
    inner class ValidLinkTests {
        @Test
        fun `extracts targetId from a standard href`() {
            val rule = createRule()
            val resource =
                createResource(mapOf("elevfravar" to listOf(Link.with("https://example.com/utdanning/elevfravar/123"))))

            val result = rule.toRelationState(resource, "test-id")

            assertEquals(listOf("123"), result.targetIds)
            assertEquals(targetEntity, result.targetEntity)
        }

        @Test
        fun `uses the first link when multiplicity is to-one`() {
            val rule = createRule()
            val resource =
                createResource(
                    mapOf(
                        "elevfravar" to
                            listOf(Link.with("domain/resource/first"), Link.with("domain/resource/second")),
                    ),
                )

            assertEquals(listOf("first"), rule.toRelationState(resource, "test-id").targetIds)
        }

        @Test
        fun `keeps all links when multiplicity is many-to-many`() {
            val rule =
                createRule(
                    targetMultiplicity = FintMultiplicity.NONE_TO_MANY,
                    inverseMultiplicity = FintMultiplicity.NONE_TO_MANY,
                )
            val resource =
                createResource(
                    mapOf(
                        "elevfravar" to
                            listOf(Link.with("domain/resource/a"), Link.with("domain/resource/b")),
                    ),
                )

            assertEquals(listOf("a", "b"), rule.toRelationState(resource, "test-id").targetIds)
        }

        @Test
        fun `toEmptyRelationState yields empty target ids with the inverse binding`() {
            val rule = createRule(inverseRelation = "fravaersregistrering")
            val resource = createResource(mapOf("elevfravar" to listOf(Link.with("domain/resource/456"))))

            val result = rule.toEmptyRelationState(resource, "test-id")

            assertTrue(result.targetIds.isEmpty())
            assertEquals("fravaersregistrering", result.binding.relationName)
        }
    }

    @Nested
    inner class InvalidLinkFormatTests {
        @Test
        fun `throws when link has only one segment`() {
            val rule = createRule()
            val resource = createResource(mapOf("elevfravar" to listOf(Link.with("single-segment"))))

            assertThrows<InvalidLinkException> { rule.toRelationState(resource, "test-id") }
        }

        @Test
        fun `throws when last segment is blank`() {
            val rule = createRule()
            val resource = createResource(mapOf("elevfravar" to listOf(Link.with("domain/"))))

            assertThrows<InvalidLinkException> { rule.toRelationState(resource, "test-id") }
        }
    }

    @Nested
    inner class RelationBindingTests {
        @Test
        fun `includes the inverse relation in the binding`() {
            val rule = createRule(targetRelation = "elevfravar", inverseRelation = "fravaersregistrering")
            val resource = createResource(mapOf("elevfravar" to listOf(Link.with("domain/resource/123"))))

            assertEquals("fravaersregistrering", rule.toRelationState(resource, "test-id").binding.relationName)
        }
    }
}
