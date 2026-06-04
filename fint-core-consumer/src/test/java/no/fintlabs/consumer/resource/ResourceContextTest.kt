package no.fintlabs.consumer.resource

import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.resource.context.ResourceContext
import no.fintlabs.consumer.resource.context.ResourceContextCache
import no.fintlabs.reflection.ReflectionCache
import no.fintlabs.reflection.ReflectionInitializer
import no.novari.metamodel.ComponentBuilder
import no.novari.metamodel.MetamodelService
import no.novari.metamodel.ReflectionService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringJUnitConfig(classes = [ResourceContextTest.Config::class])
@TestPropertySource(
    properties = [
        "fint.consumer.base-url=https://test.felleskomponent.no",
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
    class Config {
        @Bean
        fun reflectionService() = ReflectionService()

        @Bean
        fun componentBuilder(reflectionService: ReflectionService) = ComponentBuilder(reflectionService)

        @Bean
        fun metamodelService(componentBuilder: ComponentBuilder) = MetamodelService(componentBuilder)
    }

    @Autowired
    private lateinit var resourceContext: ResourceContext

    @Test
    fun `resources are keyed by the qualified domain_package_resource key`() {
        assertTrue(resourceContext.resourceNames.contains("utdanning_elev_elev"))
        assertFalse(resourceContext.resourceNames.contains("elev"))
    }

    @Test
    fun `every component is loaded, not just one`() {
        assertTrue(resourceContext.resourceNames.contains("utdanning_vurdering_elevfravar"))
    }

    @Test
    fun `common resources are present under the consuming component`() {
        assertTrue(resourceContext.resourceNames.any { it.endsWith("_person") })
    }
}
