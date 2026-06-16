package no.fintlabs.autorelation

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.fintlabs.autorelation.model.EntityDescriptor
import no.fintlabs.autorelation.model.RelationBinding
import no.fintlabs.autorelation.model.RelationOperation
import no.fintlabs.autorelation.model.RelationUpdate
import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.Link
import no.novari.fint.model.resource.utdanning.vurdering.ElevfravarResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull

class FintResourceExtensionsTest {
    private lateinit var resource: FintResource

    @BeforeEach
    fun setUp() {
        resource = ElevfravarResource()
    }

    @Nested
    inner class FindObsoleteLinksScenarios {
        @Test
        fun `should identify links present in oldResource but missing in new resource`() {
            val relation = "rel_teacher"
            val linkToKeep = Link.with("https://api.fint.no/teacher/1")
            val linkToDelete = Link.with("https://api.fint.no/teacher/2")

            val oldResource =
                ElevfravarResource().apply {
                    addUniqueLinks(relation, listOf(linkToKeep, linkToDelete))
                }

            resource.addUniqueLinks(relation, listOf(linkToKeep))

            val result = resource.findObsoleteLinks(oldResource, listOf(relation))

            assertTrue(result.containsKey(relation))
            assertEquals(1, result[relation]?.size)
            assertEquals(linkToDelete.href, result[relation]?.first()?.href)
        }

        @Test
        fun `should mark all links obsolete if new resource has no links for relation`() {
            val relation = "rel_teacher"
            val oldLinks = listOf(Link.with("https://api.fint.no/teacher/1"))

            val oldResource =
                ElevfravarResource().apply {
                    addUniqueLinks(relation, oldLinks)
                }

            val result = resource.findObsoleteLinks(oldResource, listOf(relation))

            assertEquals(oldLinks, result[relation])
        }

        @Test
        fun `should return empty map if old resource has no links`() {
            val relation = "rel_teacher"
            val oldResource = ElevfravarResource()

            resource.addUniqueLinks(relation, listOf(Link.with("teacher/1")))

            val result = resource.findObsoleteLinks(oldResource, listOf(relation))

            assertTrue(result.isEmpty())
        }

        @Test
        fun `should match links with different base URLs correctly`() {
            val relation = "rel_student"
            val oldResource =
                ElevfravarResource().apply {
                    addUniqueLinks(relation, listOf(Link.with("https://api.fint.no/model/elev/123")))
                }

            resource.addUniqueLinks(relation, listOf(Link.with("elev/123")))

            val result = resource.findObsoleteLinks(oldResource, listOf(relation))

            assertTrue(result.isEmpty(), "Should match based on ID suffix and not mark as obsolete")
        }
    }

    @Nested
    inner class IsSameResourceScenarios {
        @Test
        fun `should match based on last two segments`() {
            val link1 = Link.with("https://beta.fintlabs.no/utdanning/vurdering/elevfravar/systemid/123")
            val link2 = Link.with("systemid/123")

            assertTrue(link1.isSameResource(link2))
        }

        @Test
        fun `should return false if suffix differs`() {
            val link1 = Link.with("systemid/123")
            val link2 = Link.with("systemid/456")

            assertFalse(link1.isSameResource(link2))
        }

        @Test
        fun `should handle null hrefs safely`() {
            val link1 = Link()
            val link2 = Link.with("systemid/123")

            assertFalse(link1.isSameResource(link2))
        }

        @Test
        fun `should not match different idFields that share a suffix`() {
            val link1 = Link.with("https://api.fint.no/model/otherid/123")
            val link2 = Link.with("https://api.fint.no/model/id/123")

            assertFalse(link1.isSameResource(link2))
        }
    }

    @Nested
    inner class ApplyUpdateScenarios {
        @Test
        fun `applyUpdate ADD should add link if relation is missing`() {
            val link = Link.with("systemid/1")
            val update = createUpdate(RelationOperation.ADD, "rel-1", link)

            resource.applyUpdate(update)

            val links = resource.links["rel-1"]
            assertNotNull(links)
            assertEquals(1, links.size)
            assertEquals(link.href, links[0].href)
        }

        @Test
        fun `applyUpdate ADD should ignore duplicate link`() {
            val link = Link.with("systemid/1")
            resource.addUniqueLinks("rel-1", listOf(link))

            val update = createUpdate(RelationOperation.ADD, "rel-1", Link.with("systemid/1"))
            resource.applyUpdate(update)

            val links = resource.links["rel-1"]
            assertEquals(1, links!!.size)
        }

        @Test
        fun `applyUpdate DELETE should remove link`() {
            val link1 = Link.with("systemid/1")
            val link2 = Link.with("systemid/2")
            resource.addUniqueLinks("rel-1", listOf(link1, link2))

            val update = createUpdate(RelationOperation.DELETE, "rel-1", link1)
            resource.applyUpdate(update)

            val links = resource.links["rel-1"]
            assertNotNull(links)
            assertEquals(1, links.size)
            assertEquals(link2.href, links[0].href)
        }

        @Test
        fun `applyUpdate DELETE should remove relation key if list becomes empty`() {
            val link = Link.with("systemid/1")
            resource.addUniqueLinks("rel-1", listOf(link))

            val update = createUpdate(RelationOperation.DELETE, "rel-1", link)
            resource.applyUpdate(update)

            assertFalse(resource.links.containsKey("rel-1"))
        }

        @Test
        fun `applyUpdate DELETE should do nothing if relation does not exist`() {
            val link = Link.with("systemid/1")
            val update = createUpdate(RelationOperation.DELETE, "non-existent-rel", link)

            resource.applyUpdate(update)

            assertFalse(resource.links.containsKey("non-existent-rel"))
        }
    }

    @Nested
    inner class AddUniqueLinksScenarios {
        @Test
        fun `should add multiple unique links`() {
            val link1 = Link.with("systemid/1")
            val link2 = Link.with("systemid/2")

            resource.addUniqueLinks("rel-1", listOf(link1, link2))

            assertEquals(2, resource.links["rel-1"]!!.size)
        }

        @Test
        fun `should ignore empty list`() {
            resource.addUniqueLinks("rel-1", emptyList())
            assertFalse(resource.links.containsKey("rel-1"))
        }

        @Test
        fun `should handle case insensitive duplicates`() {
            val link1 = Link.with("systemid/abc")
            resource.addUniqueLinks("rel-1", listOf(link1))

            val link2 = Link.with("systemid/ABC")
            resource.addUniqueLinks("rel-1", listOf(link2))

            assertEquals(1, resource.links["rel-1"]!!.size)
        }

        @Test
        fun `should not treat different idFields with same idValue as duplicates`() {
            resource.addUniqueLinks("rel-1", listOf(Link.with("systemid/123")))
            resource.addUniqueLinks("rel-1", listOf(Link.with("otherid/123")))

            assertEquals(2, resource.links["rel-1"]!!.size)
        }

        @Test
        fun `should detect duplicate when one is absolute and one is relative`() {
            resource.addUniqueLinks("rel-1", listOf(Link.with("https://api.fint.no/path/systemid/123")))
            resource.addUniqueLinks("rel-1", listOf(Link.with("systemid/123")))

            assertEquals(1, resource.links["rel-1"]!!.size)
        }
    }

    @Nested
    inner class DeepCopyScenarios {
        private val objectMapper = jacksonObjectMapper()

        @Test
        fun `deepCopy should return a completely new instance with identical data`() {
            val relation = "rel_teacher"
            val link = Link.with("https://api.fint.no/teacher/1")
            val original =
                ElevfravarResource().apply {
                    addUniqueLinks(relation, listOf(link))
                }

            val copy = original.deepCopy(objectMapper, ElevfravarResource::class.java)

            assertNotSame(original, copy, "The copy must be a different memory instance")
            assertNotSame(original.links, copy.links, "The nested maps must be different instances")

            assertTrue(copy.links.containsKey(relation))
            assertEquals(1, copy.links[relation]?.size)
            assertEquals(link.href, copy.links[relation]?.first()?.href)
        }

        @Test
        fun `modifying the deep copy should not affect the original resource`() {
            val relation = "rel_teacher"
            val original =
                ElevfravarResource().apply {
                    addUniqueLinks(relation, listOf(Link.with("teacher/1")))
                }

            val copy = original.deepCopy(objectMapper, ElevfravarResource::class.java)

            copy.addUniqueLinks(relation, listOf(Link.with("teacher/2")))
            copy.addUniqueLinks("rel_student", listOf(Link.with("student/1")))

            assertEquals(2, copy.links[relation]?.size, "Copy should have 2 teachers")
            assertTrue(copy.links.containsKey("rel_student"), "Copy should have the student relation")

            assertEquals(1, original.links[relation]?.size, "Original should still only have 1 teacher")
            assertFalse(original.links.containsKey("rel_student"), "Original should not have the student relation")
        }
    }

    private fun createUpdate(
        operation: RelationOperation,
        rel: String,
        link: Link,
    ): RelationUpdate =
        RelationUpdate(
            targetEntity = EntityDescriptor("utdanning", "vurdering", "elevfravar"),
            targetIds = listOf("123"),
            binding = RelationBinding(rel, link),
            operation = operation,
        )
}
