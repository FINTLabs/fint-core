package no.fintlabs.consumer.resource

import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.exception.resource.IdentificatorNotFoundException
import no.fintlabs.consumer.exception.resource.ResourceNotFoundException
import no.fintlabs.consumer.exception.resource.ResourceNotWriteableException
import no.fintlabs.consumer.resource.aspect.IdentifierAspect
import no.fintlabs.consumer.resource.aspect.ResourceAspect
import no.fintlabs.consumer.resource.aspect.WriteableAspect
import no.fintlabs.consumer.resource.context.ResourceContext
import no.fintlabs.consumer.resource.context.ResourceContextCache
import no.fintlabs.reflection.ReflectionCache
import no.fintlabs.reflection.ReflectionInitializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig

@SpringJUnitConfig(classes = [ResourceAspectTest.Config::class])
@TestPropertySource(
    properties = [
        "fint.consumer.base-url=https://test.felleskomponent.no",
        "fint.consumer.domain=utdanning",
        "fint.consumer.package-name=elev",
        "fint.consumer.org-id=fintlabs.no",
        "fint.consumer.writeable=klasse",
        "fint.consumer.pod-url=http://test",
    ],
)
class ResourceAspectTest {
    @EnableConfigurationProperties(ConsumerConfiguration::class)
    @Import(
        ReflectionCache::class,
        ReflectionInitializer::class,
        ResourceContextCache::class,
        ResourceContext::class,
        ResourceAspect::class,
        IdentifierAspect::class,
        WriteableAspect::class,
    )
    class Config

    @Autowired
    private lateinit var resourceAspect: ResourceAspect

    @Autowired
    private lateinit var identifierAspect: IdentifierAspect

    @Autowired
    private lateinit var writeableAspect: WriteableAspect

    private val validResource = "elev"
    private val nonWriteableResource = "elevforhold"

    @Test
    fun testResourceSuccess() {
        resourceAspect.checkResource(nonWriteableResource)
    }

    @Test
    fun testResourceFailure_WhenResourceDoesNotExistInComponent() {
        assertThrows<ResourceNotFoundException> { resourceAspect.checkResource("asdfasdfasdf") }
    }

    @Test
    fun testIdentifierSuccess() {
        identifierAspect.checkIdField(nonWriteableResource, "systemid")
    }

    @Test
    fun testIdentifierFailure_WhenIdFieldDoesNotMatchResource() {
        assertThrows<IdentificatorNotFoundException> {
            identifierAspect.checkIdField(
                nonWriteableResource,
                "fodselsnummer",
            )
        }
    }

    @Test
    fun testWriteableAspectSuccess() {
        writeableAspect.checkWriteable(validResource)
    }

    @Test
    fun testWriteableAspectFailure_WhenResourceIsNotWriteable() {
        assertThrows<ResourceNotWriteableException> { writeableAspect.checkWriteable(nonWriteableResource) }
    }

    @Test
    fun testWriteableAspectSuccess_WhenResourceIsInWriteableConfig() {
        assertDoesNotThrow { writeableAspect.checkWriteable("klasse") }
    }

    @Test
    fun testWriteableAspectFails_WhenResourceDoesntExistInContext() {
        assertThrows<ResourceNotWriteableException> { writeableAspect.checkWriteable("asdfasdfasdf") }
    }
}
