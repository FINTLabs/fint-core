package no.fintlabs.consumer.resource

import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.resource.context.ResourceContext
import no.fintlabs.consumer.resource.context.ResourceContextCache
import no.fintlabs.reflection.ReflectionCache
import no.fintlabs.reflection.ReflectionInitializer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringJUnitConfig(classes = [ResourceContextTest.Config::class])
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
class ResourceContextTest {
    @EnableConfigurationProperties(ConsumerConfiguration::class)
    @Import(
        ReflectionCache::class,
        ReflectionInitializer::class,
        ResourceContextCache::class,
        ResourceContext::class,
    )
    class Config

    @Autowired
    private lateinit var resourceContext: ResourceContext

    @Test
    fun commonResource_ShouldBePresent() {
        assertTrue(resourceContext.resourceNames.contains("person"))
    }

    @Test
    fun commonResource_ShouldNotBePresent_WhenNotRelatedToThisComponent() {
        assertFalse(resourceContext.resourceNames.contains("virksomhet"))
    }

    @Test
    fun resourceBelongingToComponent_ShouldBePresent() {
        assertTrue(resourceContext.resourceNames.contains("elev"))
    }

    @Test
    fun resourceWithTheSameDomain_ShouldNotPresent() {
        assertFalse(resourceContext.resourceNames.contains("elevfravar"))
    }

    @Test
    fun resourceBelongingToOtherComponent_ShouldNotBePresent() {
        assertFalse(resourceContext.resourceNames.contains("otstatus"))
    }
}
