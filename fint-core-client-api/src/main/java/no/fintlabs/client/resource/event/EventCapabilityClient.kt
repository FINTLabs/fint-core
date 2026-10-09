package no.fintlabs.client.resource.event

import no.fintlabs.client.config.AdapterGatewayProperties
import no.fintlabs.client.config.ConsumerConfiguration
import no.novari.core.shared.event.OrgEventCapabilities
import no.novari.core.shared.model.OrgId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.http.HttpClient

/**
 * Asks the org's adapter gateway what its adapters answer events for. Only a request that asks
 * for a live read makes the call. When the gateway cannot be reached, or answers with anything
 * but the list, the answer is null and the read is answered from the cache, the same as when no
 * adapter reads the resource live.
 */
@Component
class EventCapabilityClient(
    private val restClient: RestClient,
) {
    @Autowired
    constructor(configuration: ConsumerConfiguration) : this(restClientFor(configuration.adapterGateway))

    fun find(orgId: OrgId): OrgEventCapabilities? =
        try {
            restClient
                .get()
                .uri("${OrgEventCapabilities.PATH}?orgId={orgId}", orgId.value)
                .retrieve()
                .body(OrgEventCapabilities::class.java)
        } catch (e: RestClientException) {
            logger.warn("Could not ask the adapter gateway what {} can read live: {}", orgId.value, e.message)
            null
        }

    companion object {
        private val logger = LoggerFactory.getLogger(EventCapabilityClient::class.java)

        private fun restClientFor(properties: AdapterGatewayProperties): RestClient =
            RestClient
                .builder()
                .baseUrl(properties.url)
                .requestFactory(
                    JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(properties.timeout).build())
                        .apply { setReadTimeout(properties.timeout) },
                ).build()
    }
}
