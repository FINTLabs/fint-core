package no.fintlabs.adapter.gateway.sync

import no.fintlabs.adapter.gateway.AdapterApiPaths
import no.fintlabs.adapter.models.sync.DeleteSyncPage
import no.fintlabs.adapter.models.sync.DeltaSyncPage
import no.fintlabs.adapter.models.sync.FullSyncPage
import no.fintlabs.adapter.models.sync.SyncPage
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.ResourceCoordinate
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(AdapterApiPaths.V1, AdapterApiPaths.V2 + "/sync")
class SyncController(
    private val syncPageService: SyncPageService,
) {
    @PostMapping("/{domainName}/{packageName}/{resourceName}")
    @PreAuthorize(CAN_SYNC)
    fun fullSync(
        @RequestBody syncPage: FullSyncPage,
        @PathVariable domainName: String,
        @PathVariable packageName: String,
        @PathVariable resourceName: String,
    ): ResponseEntity<Void> = handleSync(syncPage, domainName, packageName, resourceName, HttpStatus.CREATED)

    @PatchMapping("/{domainName}/{packageName}/{resourceName}")
    @PreAuthorize(CAN_SYNC)
    fun deltaSync(
        @RequestBody syncPage: DeltaSyncPage,
        @PathVariable domainName: String,
        @PathVariable packageName: String,
        @PathVariable resourceName: String,
    ): ResponseEntity<Void> = handleSync(syncPage, domainName, packageName, resourceName, HttpStatus.CREATED)

    @DeleteMapping("/{domainName}/{packageName}/{resourceName}")
    @PreAuthorize(CAN_SYNC)
    fun deleteSync(
        @RequestBody syncPage: DeleteSyncPage,
        @PathVariable domainName: String,
        @PathVariable packageName: String,
        @PathVariable resourceName: String,
    ): ResponseEntity<Void> = handleSync(syncPage, domainName, packageName, resourceName, HttpStatus.OK)

    private fun handleSync(
        syncPage: SyncPage,
        domainName: String,
        packageName: String,
        resourceName: String,
        status: HttpStatus,
    ): ResponseEntity<Void> {
        val coords =
            ResourceCoordinate(
                OrgId.from(syncPage.metadata.orgId).value,
                domainName,
                packageName,
                resourceName,
            )
        syncPageService.doSync(syncPage, coords)
        return ResponseEntity.status(status).build()
    }

    companion object {
        private const val CAN_SYNC =
            "@adapterAuth.canSync(authentication, #syncPage.metadata?.orgId, #domainName, #packageName, #resourceName)"
    }
}
