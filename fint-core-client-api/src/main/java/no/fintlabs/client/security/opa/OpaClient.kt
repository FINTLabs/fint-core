package no.fintlabs.client.security.opa

import org.slf4j.LoggerFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.http.HttpClient

@Component
class OpaClient(
    opaProperties: OpaProperties,
) {
    private val restClient =
        RestClient
            .builder()
            .baseUrl(opaProperties.url)
            .requestFactory(
                JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(opaProperties.timeout).build())
                    .apply { setReadTimeout(opaProperties.timeout) },
            ).build()

    fun getDecision(opaRequest: OpaRequest): OpaDecision =
        try {
            val result =
                restClient
                    .post()
                    .uri("/v1/data/core")
                    .body(opaRequest)
                    .retrieve()
                    .body(OpaResponse::class.java)
                    ?.result
            when {
                result == null -> OpaDecision.Unavailable.also { logger.error("Empty decision from OPA") }
                result.allow -> OpaDecision.Allowed(result.fields, result.relations)
                else -> OpaDecision.Denied
            }
        } catch (e: Exception) {
            logger.error("Failed to get decision from OPA: {}", e.message)
            OpaDecision.Unavailable
        }

    companion object {
        private val logger = LoggerFactory.getLogger(OpaClient::class.java)
    }
}
