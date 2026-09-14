package no.fintlabs.consumer.resource

import no.fintlabs.consumer.config.ConsumerConfiguration
import no.novari.fint.core.model.FintModel
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class EndpointsServiceTest {
    private val endpointsService =
        EndpointsService(
            ConsumerConfiguration(
                baseUrl = "https://beta.felleskomponent.no",
                orgIdValue = "fintlabs.no",
                domain = "utdanning",
                packageName = "vurdering",
                podUrl = "http://localhost",
            ),
        )

    @Test
    fun `package overview contains exactly the resources returned by FintModel`() {
        val domainName = "utdanning"
        val packageName = "vurdering"

        val resourceRefs = FintModel.refsIn(domainName, packageName)
        val expectedResourceNames =
            resourceRefs
                .map { it.resourceName }
                .toSet()

        val result =
            endpointsService.packageOverview(
                domainName = domainName,
                packageName = packageName,
            )

        assertEquals(expectedResourceNames, result.keys)
        assertEquals(resourceRefs.size, result.size)
    }
}
