package no.novari.fint.core.consumer.security.opa

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

/**
 * Blocking OPA client. Runs on a virtual thread per request, so the synchronous [RestClient] call
 * parks cheaply. On any error the decision defaults to a denying [OpaResponse] (allow=false),
 * mirroring the previous reactive client's fail-closed behaviour.
 */
@Service
class OpaClient(
    opaProperties: OpaProperties,
) {
    private val restClient = RestClient.builder().baseUrl(opaProperties.url).build()

    fun getDecision(request: OpaRequest): OpaResponse =
        try {
            restClient
                .post()
                .uri("/v1/data/core")
                .body(request)
                .retrieve()
                .body(OpaResponse::class.java)
                ?: OpaResponse()
        } catch (e: Exception) {
            logger.error("Failed to get decision from OPA: {}", e.message)
            OpaResponse()
        }

    companion object {
        private val logger = LoggerFactory.getLogger(OpaClient::class.java)
    }
}
