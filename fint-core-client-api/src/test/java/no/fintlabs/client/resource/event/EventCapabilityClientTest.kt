package no.fintlabs.client.resource.event

import no.fintlabs.adapter.operation.OperationType
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.resourceRefOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class EventCapabilityClientTest {
    private val builder = RestClient.builder().baseUrl("http://fint-core-adapter-gateway:8080")
    private val gateway = MockRestServiceServer.bindTo(builder).build()
    private val client = EventCapabilityClient(builder.build())

    private val orgId = OrgId.from("fintlabs.no")
    private val request = "http://fint-core-adapter-gateway:8080/internal/event-capabilities?orgId=fintlabs.no"

    @Test
    fun `asks the gateway for the org and reads what its adapters answer`() {
        gateway
            .expect(requestTo(request))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """
                    {
                      "orgId": "fintlabs.no",
                      "resources": [
                        {
                          "domainName": "utdanning",
                          "packageName": "vurdering",
                          "resourceName": "elevfravar",
                          "operations": ["READ", "CREATE"]
                        }
                      ]
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val capabilities = client.find(orgId)!!

        assertThat(capabilities.operationsFor(resourceRefOf("utdanning", "vurdering", "elevfravar")))
            .containsExactlyInAnyOrder(OperationType.READ, OperationType.CREATE)
        assertThat(capabilities.canRead(resourceRefOf("utdanning", "elev", "elev"))).isFalse()
    }

    @Test
    fun `answers nothing when the gateway fails`() {
        gateway.expect(requestTo(request)).andRespond(withServerError())

        assertThat(client.find(orgId)).isNull()
    }

    @Test
    fun `answers nothing when the gateway does not have the endpoint yet`() {
        gateway.expect(requestTo(request)).andRespond(withResourceNotFound())

        assertThat(client.find(orgId)).isNull()
    }
}
