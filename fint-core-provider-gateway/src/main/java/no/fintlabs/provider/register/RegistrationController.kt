package no.fintlabs.provider.register

import no.fintlabs.adapter.models.AdapterContract
import no.fintlabs.provider.security.AdapterRequestValidator
import no.novari.resource.server.authentication.CorePrincipal
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class RegistrationController(
    private val requestValidator: AdapterRequestValidator,
    private val registrationService: RegistrationService,
) {
    private val logger = LoggerFactory.getLogger(RegistrationController::class.java)

    @PostMapping("register")
    fun register(
        corePrincipal: CorePrincipal,
        @RequestBody adapterContract: AdapterContract,
    ): ResponseEntity<Void?> {
        logger.debug("Received contract: {}", adapterContract.adapterId)
        requestValidator.validateOrgId(corePrincipal, adapterContract.orgId)
        requestValidator.validateUsername(corePrincipal, adapterContract.username)
        logger.debug("Contract validated: {}", adapterContract.adapterId)
        registrationService.register(adapterContract)
        return ResponseEntity.ok().build<Void?>()
    }
}
