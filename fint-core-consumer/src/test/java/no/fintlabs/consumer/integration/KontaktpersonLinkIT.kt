package no.fintlabs.consumer.integration

import no.fintlabs.Application
import no.fintlabs.consumer.resource.context.ResourceContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Regression test for the common (felles) resource link bug in utdanning-elev.
 *
 * `Kontaktperson` is a felles resource reachable from utdanning-elev. It has a relation named
 * `kontaktperson` that points to `Person` (also felles, packageName = no.novari.fint.model.felles.Person).
 *
 * Bug: `ResourceContextCache.createRelationUri` used the *relation name* ("kontaktperson")
 * as the last path segment when building the URI for a felles target, producing:
 *   utdanning/elev/kontaktperson
 * instead of the correct URI derived from the target *resource name*:
 *   utdanning/elev/person
 *
 * This caused links like `/utdanning/elev/person/fodselsnummer/123456789` to be generated
 * as `/utdanning/elev/kontaktperson/fodselsnummer/123456789` instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = [Application::class])
@EmbeddedKafka(partitions = 1)
@TestPropertySource(
    properties = [
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.group-id=kontaktperson-link-it",

        "novari.kafka.default-replicas=1",
        "fint.relation.base-url=https://test.felleskomponent.no",
        "fint.org-id=foo.org",
        "fint.consumer.org-id=foo.org",
        "fint.consumer.domain=utdanning",
        "fint.consumer.package=elev",
        "fint.consumer.autorelation.enabled=true",
        "fint.security.enabled=false",
    ],
)
@DirtiesContext
class KontaktpersonLinkIT {
    @Autowired
    lateinit var resourceContext: ResourceContext

    @Test
    fun `kontaktperson is registered as a resource in the utdanning-elev context`() {
        assertNotNull(
            resourceContext.getResource("kontaktperson"),
            "Kontaktperson (felles) must be discoverable as a resource in the utdanning-elev context",
        )
    }

    @Test
    fun `kontaktperson relation uri points to person, not to the relation name`() {
        assertNotNull(
            resourceContext.getResource("kontaktperson"),
            "Kontaktperson must be registered before its relation URI can be resolved",
        )

        val uri = resourceContext.getRelationUri("kontaktperson", "kontaktperson")

        assertEquals(
            "utdanning/elev/person",
            uri,
            "The 'kontaktperson' relation on Kontaktperson targets Person (felles). " +
                "The URI must use the target resource name ('person'), not the relation name ('kontaktperson'). " +
                "Got: $uri",
        )
    }

    @Test
    fun `kontaktperson relation link is built with person path, not relation name path`() {
        assertNotNull(
            resourceContext.getResource("kontaktperson"),
            "Kontaktperson must be registered before its relation URI can be resolved",
        )

        val uri = resourceContext.getRelationUri("kontaktperson", "kontaktperson")
        val fullLink = "https://test.felleskomponent.no/$uri/fodselsnummer/123456789"

        assertEquals(
            "https://test.felleskomponent.no/utdanning/elev/person/fodselsnummer/123456789",
            fullLink,
            "Full link for a Person via Kontaktperson.kontaktperson must resolve to utdanning/elev/person/..., got: $fullLink",
        )
    }
}
