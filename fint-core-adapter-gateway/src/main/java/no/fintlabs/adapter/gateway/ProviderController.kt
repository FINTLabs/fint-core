package no.fintlabs.adapter.gateway

import no.novari.resource.server.authentication.CorePrincipal
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(AdapterApiPaths.V1, AdapterApiPaths.V2)
class ProviderController {
    @GetMapping("/status")
    fun status(corePrincipal: CorePrincipal): ResponseEntity<MutableMap<String, Any>> =
        ResponseEntity.ok(
            mutableMapOf(
                "status" to "Greetings form FINTLabs 👋",
                "corePrincipal" to corePrincipal,
            ),
        )
}
