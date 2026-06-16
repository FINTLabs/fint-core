package no.fintlabs.autorelation

import no.fintlabs.consumer.resource.ResourceConverter
import no.novari.fint.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.model.resource.Link
import no.novari.fint.model.resource.utdanning.vurdering.FravarsregistreringResource
import org.junit.jupiter.api.Test
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean

@SpringBootTest
@EmbeddedKafka
@ActiveProfiles("utdanning-vurdering")
class RelationEventServiceTest {
    @Autowired
    private lateinit var relationEventService: RelationEventService

    @MockitoSpyBean
    private lateinit var resourceConverter: ResourceConverter

    @Test // TODO: Move this test to AutoRelationRegistry Integration test
    fun `fravarsregistrering is a managed resource (one to many)`() {
        val resourceId = "123"
        val resourceName = "fravarsregistrering"
        val resource = createFravarsregistrering(resourceId)

        relationEventService.addRelations(resourceName, resourceId, resource)

        verify(resourceConverter, times(1)).convert(resourceName, resource)
    }

    private fun createFravarsregistrering(resourceId: String) =
        FravarsregistreringResource().apply {
            systemId =
                Identifikator().apply {
                    identifikatorverdi = "123"
                }
            addElevfravar(Link.with("valid/123"))
        }
}
