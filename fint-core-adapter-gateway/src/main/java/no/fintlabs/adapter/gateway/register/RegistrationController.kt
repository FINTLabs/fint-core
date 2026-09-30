package no.fintlabs.adapter.gateway.register

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

    @PostMapping("register")
    @PreAuthorize(OWNS_CONTRACT)
    fun register(
        @RequestBody adapterContract: AdapterContract,
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
