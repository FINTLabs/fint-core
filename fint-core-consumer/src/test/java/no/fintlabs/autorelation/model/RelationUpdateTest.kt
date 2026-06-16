package no.fintlabs.autorelation.model

import io.mockk.every
import io.mockk.mockk
import no.novari.fint.model.FintIdentifikator
import no.novari.fint.model.FintMultiplicity
import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.Link
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RelationUpdateTest {
    private val targetEntity =
        EntityDescriptor(
            "utdanning",
            "vurdering",
            "elevfravar",
        )

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
    inner class MandatoryLinkTests {
        @Test
        fun `should throw MissingMandatoryLinkException when href is null and relation is mandatory`() {
            val rule = createRule(targetMultiplicity = FintMultiplicity.ONE_TO_ONE)
            val resource = createResource(mapOf("elevfravar" to emptyList()))

            val exception =
                assertThrows<MissingMandatoryLinkException> {
                    rule.toRelationUpdate(resource, "test-id", RelationOperation.ADD)
                }

            assertEquals("Missing mandatory link for 'elevfravar'", exception.message)
        }

        @Test
        fun `should throw MissingMandatoryLinkException when href is blank and relation is mandatory`() {
            val rule = createRule(targetMultiplicity = FintMultiplicity.ONE_TO_ONE)
            val resource =
                createResource(
                    mapOf("elevfravar" to listOf(Link.with(""))),
                )

            val exception =
                assertThrows<MissingMandatoryLinkException> {
                    rule.toRelationUpdate(resource, "test-id", RelationOperation.ADD)
                }

            assertEquals("Missing mandatory link for 'elevfravar'", exception.message)
        }

        @Test
        fun `should throw MissingMandatoryLinkException when mandatory target relation is missing`() {
            val rule = createRule(targetMultiplicity = FintMultiplicity.ONE_TO_ONE)
            val resource = createResource(emptyMap())

            val exception =
                assertThrows<MissingMandatoryLinkException> {
                    rule.toRelationUpdate(resource, "test-id", RelationOperation.ADD)
                }

            assertEquals("Missing mandatory link for 'elevfravar'", exception.message)
        }
    }

    @Nested
    inner class OptionalLinkTests {
        @Test
        fun `should return null when href is null and relation is optional`() {
            val rule = createRule(targetMultiplicity = FintMultiplicity.NONE_TO_ONE)
            val resource = createResource(mapOf("elevfravar" to emptyList()))

            val result = rule.toRelationUpdate(resource, "test-id", RelationOperation.ADD)

            assertNull(result)
        }

        @Test
        fun `should return null when href is blank and relation is optional`() {
            val rule = createRule(targetMultiplicity = FintMultiplicity.NONE_TO_ONE)
            val resource =
                createResource(
                    mapOf("elevfravar" to listOf(Link.with(""))),
                )

            val result = rule.toRelationUpdate(resource, "test-id", RelationOperation.ADD)

            assertNull(result)
        }

        @Test
        fun `should return null when links map does not contain target relation and is optional`() {
            val rule = createRule(targetMultiplicity = FintMultiplicity.NONE_TO_ONE)
            val resource = createResource(emptyMap())

            val result = rule.toRelationUpdate(resource, "test-id", RelationOperation.ADD)

            assertNull(result)
        }
    }

    @Nested
    inner class ValidLinkTests {
        @Test
        fun `should extract targetId from valid link with standard format`() {
            val rule = createRule()
            val resource =
                createResource(
                    mapOf("elevfravar" to listOf(Link.with("https://example.com/utdanning/elevfravar/123"))),
                )

            val result = rule.toRelationUpdate(resource, "test-id", RelationOperation.ADD)

            assertNotNull(result)
            assertEquals(listOf("123"), result!!.targetIds)
            assertEquals(targetEntity, result.targetEntity)
            assertEquals(RelationOperation.ADD, result.operation)
        }

        @Test
        fun `should extract targetId from link with domain and id`() {
            val rule = createRule()
            val resource =
                createResource(
                    mapOf("elevfravar" to listOf(Link.with("domain/abc-123"))),
                )

            val result = rule.toRelationUpdate(resource, "test-id", RelationOperation.ADD)

            assertNotNull(result)
            assertEquals(listOf("abc-123"), result!!.targetIds)
        }

        @Test
        fun `should create RelationUpdate with DELETE operation`() {
            val rule = createRule()
            val resource =
                createResource(
                    mapOf("elevfravar" to listOf(Link.with("domain/resource/456"))),
                )

            val result = rule.toRelationUpdate(resource, "test-id", RelationOperation.DELETE)

            assertNotNull(result)
            assertEquals(listOf("456"), result!!.targetIds)
            assertEquals(RelationOperation.DELETE, result.operation)
        }

        @Test
        fun `should use first link when multiple links are present and multiplicity is ONE`() {
            val rule = createRule()
            val resource =
                createResource(
                    mapOf(
                        "elevfravar" to
                            listOf(
                                Link.with("domain/resource/first"),
                                Link.with("domain/resource/second"),
                            ),
                    ),
                )

            val result = rule.toRelationUpdate(resource, "test-id", RelationOperation.ADD)

            assertNotNull(result)
            assertEquals(listOf("first"), result!!.targetIds)
        }
    }

    @Nested
    inner class InvalidLinkFormatTests {
        @Test
        fun `should throw InvalidLinkException when link has only one segment`() {
            val rule = createRule()
            val invalidLink = "single-segment"
            val resource =
                createResource(
                    mapOf("elevfravar" to listOf(Link.with(invalidLink))),
                )

            val exception =
                assertThrows<InvalidLinkException> {
                    rule.toRelationUpdate(resource, "test-id", RelationOperation.ADD)
                }

            assertTrue(
                exception.message.contains("Invalid link format for relation: '$invalidLink'"),
                "Expected message to contain link href, got: ${exception.message}",
            )
        }

        @Test
        fun `should throw InvalidLinkException when last segment is blank`() {
            val rule = createRule()
            val invalidLink = "domain/"
            val resource =
                createResource(
                    mapOf("elevfravar" to listOf(Link.with(invalidLink))),
                )

            val exception =
                assertThrows<InvalidLinkException> {
                    rule.toRelationUpdate(resource, "test-id", RelationOperation.ADD)
                }

            assertTrue(
                exception.message.contains("Invalid link format for relation: '$invalidLink'"),
                "Expected message to contain link href, got: ${exception.message}",
            )
        }

        @Test
        fun `should throw InvalidLinkException when penultimate segment is blank`() {
            val rule = createRule()
            val invalidLink = "/123"
            val resource =
                createResource(
                    mapOf("elevfravar" to listOf(Link.with(invalidLink))),
                )

            val exception =
                assertThrows<InvalidLinkException> {
                    rule.toRelationUpdate(resource, "test-id", RelationOperation.ADD)
                }

            assertTrue(
                exception.message.contains("Invalid link format for relation: '$invalidLink'"),
                "Expected message to contain link href, got: ${exception.message}",
            )
        }
    }

    @Nested
    inner class RelationBindingTests {
        @Test
        fun `should include relation binding in RelationUpdate`() {
            val rule =
                createRule(
                    targetRelation = "elevfravar",
                    inverseRelation = "fravaersregistrering",
                )
            val resource =
                createResource(
                    mapOf("elevfravar" to listOf(Link.with("domain/resource/123"))),
                )

            val result = rule.toRelationUpdate(resource, "test-id", RelationOperation.ADD)

            assertNotNull(result)
            assertEquals("fravaersregistrering", result!!.binding.relationName)
        }

        @Test
        fun `should handle different multiplicity combinations`() {
            val rule = createRule(targetMultiplicity = FintMultiplicity.NONE_TO_MANY)
            val resource =
                createResource(
                    mapOf("elevfravar" to listOf(Link.with("domain/resource/789"))),
                )

            val result = rule.toRelationUpdate(resource, "test-id", RelationOperation.ADD)

            assertNotNull(result)
            assertEquals(listOf("789"), result!!.targetIds)
        }

        @Test
        fun `should throw MissingMandatoryLinkException for ONE_TO_MANY (mandatory)`() {
            val rule = createRule(targetMultiplicity = FintMultiplicity.ONE_TO_MANY)
            val resourceWithNoLink = createResource(emptyMap())

            val exception =
                assertThrows<MissingMandatoryLinkException> {
                    rule.toRelationUpdate(resourceWithNoLink, "test-id", RelationOperation.ADD)
                }

            assertEquals("Missing mandatory link for 'elevfravar'", exception.message)
        }
    }
}
