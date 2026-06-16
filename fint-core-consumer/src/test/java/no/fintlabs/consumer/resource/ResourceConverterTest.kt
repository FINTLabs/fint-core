package no.fintlabs.consumer.resource

import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.config.JacksonConfiguration
import no.fintlabs.consumer.links.LinkGenerator
import no.fintlabs.consumer.links.LinkPaginator
import no.fintlabs.consumer.links.LinkParser
import no.fintlabs.consumer.links.LinkService
import no.fintlabs.consumer.links.nested.NestedLinkMapper
import no.fintlabs.consumer.links.nested.NestedLinkService
import no.fintlabs.consumer.resource.context.ResourceContext
import no.fintlabs.consumer.resource.context.ResourceContextCache
import no.fintlabs.reflection.ReflectionCache
import no.fintlabs.reflection.ReflectionInitializer
import no.novari.fint.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.model.resource.Link
import no.novari.fint.model.resource.utdanning.elev.ElevResource
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
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
    class Config

    @Autowired
    private lateinit var resourceConverter: ResourceConverter

    @Test
    fun convertSuccess() {
        val elevResource = ElevResource()
        elevResource.systemId = Identifikator().apply { identifikatorverdi = "123321" }
        elevResource.addElevforhold(Link.with("test/link"))

        val fintResource = resourceConverter.convert("elev", elevResource)

        assertEquals("test/link", fintResource.links["elevforhold"]!!.first().href)
    }
}
