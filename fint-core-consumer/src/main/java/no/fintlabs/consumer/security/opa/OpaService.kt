package no.fintlabs.consumer.security.opa

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service

@Service
class OpaService(
    private val opaProperties: OpaProperties,
    private val opaMapper: OpaMapper,
    private val opaClient: OpaClient,
) {
    fun requestOpa(
        jwt: Jwt,
        request: HttpServletRequest,
    ): OpaResponse =
        if (opaProperties.enabled) {
            opaClient.getDecision(opaMapper.createOpaRequest(jwt, request))
        } else {
            OpaResponse(OpaResult(allow = true))
        }
}
