package no.novari.fint.core.consumer.resource

import no.novari.fint.core.consumer.config.ConsumerConfiguration
import no.novari.fint.core.consumer.config.JacksonConfiguration
import no.novari.fint.core.shared.link.LinkGenerator
import no.novari.fint.core.shared.link.LinkPaginator
import no.novari.fint.core.shared.link.LinkParser
import no.novari.fint.core.shared.link.LinkService
import no.novari.fint.core.shared.link.nested.NestedLinkMapper
import no.novari.fint.core.shared.link.nested.NestedLinkService
import no.novari.fint.core.shared.reflection.ReflectionCache
import no.novari.fint.core.shared.reflection.ReflectionInitializer
import no.novari.fint.core.shared.resource.ResourceConverter
import no.novari.fint.core.shared.resource.context.ResourceContext
import no.novari.fint.core.shared.resource.context.ResourceContextCache
import no.novari.fint.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.model.resource.Link
import no.novari.fint.model.resource.utdanning.elev.ElevResource
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
import kotlin.test.assertEquals

@SpringJUnitConfig(classes = [ResourceConverterTest.Config::class])
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
class ResourceConverterTest {
    @EnableConfigurationProperties(ConsumerConfiguration::class)
    @Import(
        ReflectionCache::class,
        ReflectionInitializer::class,
        ResourceContextCache::class,
        ResourceContext::class,
        JacksonConfiguration::class,
        LinkGenerator::class,
        LinkPaginator::class,
        LinkParser::class,
        NestedLinkMapper::class,
        NestedLinkService::class,
        LinkService::class,
        ResourceConverter::class,
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
    private lateinit var resourceConverter: ResourceConverter

    @Test
    fun convertSuccess() {
        val elevResource = ElevResource()
        elevResource.systemId = Identifikator().apply { identifikatorverdi = "123321" }
        elevResource.addElevforhold(Link.with("test/link"))

        val fintResource = resourceConverter.convert("utdanning_elev_elev", elevResource)

        assertEquals("test/link", fintResource.links["elevforhold"]!!.first().href)
    }
}
