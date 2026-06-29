package no.fintlabs.provider

import lombok.RequiredArgsConstructor
import no.fintlabs.adapter.models.AdapterContract
import no.fintlabs.adapter.models.AdapterHeartbeat
import no.fintlabs.adapter.models.sync.DeleteSyncPage
import no.fintlabs.adapter.models.sync.DeltaSyncPage
import no.fintlabs.adapter.models.sync.FullSyncPage
import no.fintlabs.adapter.models.sync.SyncPage
import no.fintlabs.provider.buffer.SyncPageService
import no.fintlabs.provider.heartbeat.HeartbeatService
import no.fintlabs.provider.register.RegistrationService
import no.fintlabs.provider.security.AdapterRequestValidator
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.model.ResourceRef
import no.novari.resource.server.authentication.CorePrincipal
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequiredArgsConstructor
@RestController
@RequestMapping
class ProviderController(
    private val requestValidator: AdapterRequestValidator,
    private val registrationService: RegistrationService,
    private val heartbeatService: HeartbeatService,
    private val syncPageService: SyncPageService,
) {
    private val logger = LoggerFactory.getLogger(ProviderController::class.java)

    @GetMapping("status")
    fun status(corePrincipal: CorePrincipal): ResponseEntity<MutableMap<String, Any>> =
        ResponseEntity.ok(
            mutableMapOf(
                "status" to "Greetings form FINTLabs 👋",
                "corePrincipal" to corePrincipal,
            ),
        )

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

    @PostMapping("{domainName}/{packageName}/{resourceName}")
    fun fullSync(
        corePrincipal: CorePrincipal, // JWT and authentication
        @RequestBody syncPage: FullSyncPage, // Page of data
        @PathVariable domainName: String,
        @PathVariable packageName: String,
        @PathVariable resourceName: String,
    ): ResponseEntity<Void> = handleSync(corePrincipal, syncPage, domainName, packageName, resourceName, HttpStatus.CREATED)

    @PatchMapping("{domainName}/{packageName}/{resourceName}")
    fun deltaSync(
        corePrincipal: CorePrincipal,
        @RequestBody syncPage: DeltaSyncPage,
        @PathVariable domainName: String,
        @PathVariable packageName: String,
        @PathVariable resourceName: String,
    ): ResponseEntity<Void> = handleSync(corePrincipal, syncPage, domainName, packageName, resourceName, HttpStatus.CREATED)

    @DeleteMapping("{domainName}/{packageName}/{resourceName}")
    fun deleteSync(
        corePrincipal: CorePrincipal,
        @RequestBody syncPage: DeleteSyncPage,
        @PathVariable domainName: String,
        @PathVariable packageName: String,
        @PathVariable resourceName: String,
    ): ResponseEntity<Void> = handleSync(corePrincipal, syncPage, domainName, packageName, resourceName, HttpStatus.OK)

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

    private fun handleSync(
        corePrincipal: CorePrincipal,
        syncPage: SyncPage,
        domainName: String,
        packageName: String,
        resourceName: String,
        status: HttpStatus,
    ): ResponseEntity<Void> {
        requestValidator.validateOrgId(corePrincipal, syncPage.metadata.orgId)
        // TODO: Enable validationg of AdapterId once we persist AdapterContracts
        //        requestValidator.validateAdapterId(corePrincipal, syncPage.getMetadata().getAdapterId());

        // TODO: Disabled until contracts are in database
//        requestValidator.validateAdapterCapabilityPermission(
//            syncPage.metadata.adapterId,
//            domainName,
//            packageName,
//            entity,
//        )

        val coords = ResourceCoordinate(
            corePrincipal.orgId,
            domainName,
            packageName,
            resourceName
        )
        syncPageService.doSync(syncPage, coords)
        return ResponseEntity.status(status).build()
    }
}
