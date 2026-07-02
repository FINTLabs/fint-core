package no.fintlabs.provider.heartbeat

import no.fintlabs.adapter.models.AdapterHeartbeat
import no.fintlabs.provider.security.AdapterRequestValidator
import no.novari.resource.server.authentication.CorePrincipal
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class HeartbeatController(
    private val requestValidator: AdapterRequestValidator,
    private val heartbeatService: HeartbeatService,
) {
    @PostMapping("heartbeat")
    fun heartbeat(
        corePrincipal: CorePrincipal,
        @RequestBody adapterHeartbeat: AdapterHeartbeat,
    ): ResponseEntity<String> {
        requestValidator.validateOrgId(corePrincipal, adapterHeartbeat.orgId)
        //        requestValidator.validateAdapterId(corePrincipal, adapterHeartbeat.getAdapterId());
        heartbeatService.beat(adapterHeartbeat)
        return ResponseEntity.ok("💗")
    }
}
