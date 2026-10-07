package no.fintlabs.adapter.gateway.register

import jakarta.validation.Valid
import no.fintlabs.adapter.models.AdapterContract
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class RegistrationController(
    private val registrationService: RegistrationService,
) {
    private val logger = LoggerFactory.getLogger(RegistrationController::class.java)

    /**
     * The body is validated before anything runs: the annotations infra-models carries, plus the
     * rules in [no.fintlabs.adapter.gateway.register.validation.ContractConstraints]. A contract
     * that fails gets a 400 that names every field.
     */
    @PostMapping("register")
    @PreAuthorize(OWNS_CONTRACT)
    fun register(
        @Valid @RequestBody adapterContract: AdapterContract,
    ): ResponseEntity<Void> {
        logger.debug("Received contract: {}", adapterContract.adapterId)
        registrationService.register(adapterContract)
        return ResponseEntity.ok().build()
    }

    companion object {
        private const val OWNS_CONTRACT =
            "@adapterAuth.hasOrg(authentication, #adapterContract.orgId) && " +
                "@adapterAuth.isUsername(authentication, #adapterContract.username)"
    }
}
