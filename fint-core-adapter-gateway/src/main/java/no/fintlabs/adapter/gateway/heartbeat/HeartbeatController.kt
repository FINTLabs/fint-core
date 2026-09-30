package no.fintlabs.adapter.gateway.heartbeat

import no.fintlabs.adapter.models.AdapterHeartbeat
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class HeartbeatController(
    private val heartbeatService: HeartbeatService,
) {
    @PostMapping("heartbeat")
    @PreAuthorize("@adapterAuth.hasOrg(authentication, #adapterHeartbeat.orgId)")
    fun heartbeat(
        @RequestBody adapterHeartbeat: AdapterHeartbeat,
    ): ResponseEntity<String> {
        heartbeatService.beat(adapterHeartbeat)
        return ResponseEntity.ok("💗")
    }
}
