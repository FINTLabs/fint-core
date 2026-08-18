package no.fintlabs.provider.sync

import no.fintlabs.adapter.models.sync.DeleteSyncPage
import no.fintlabs.adapter.models.sync.DeltaSyncPage
import no.fintlabs.adapter.models.sync.FullSyncPage
import no.fintlabs.adapter.models.sync.SyncPage
import no.fintlabs.provider.security.AdapterRequestValidator
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.resource.server.authentication.CorePrincipal
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class SyncController(
    private val syncPageService: SyncPageService,
    private val requestValidator: AdapterRequestValidator,
) {
    @PostMapping("{domainName}/{packageName}/{resourceName}")
    fun fullSync(
        corePrincipal: CorePrincipal, // JWT and authentication
        @RequestBody syncPage: FullSyncPage, // Page of data
        @PathVariable domainName: String,
        @PathVariable packageName: String,
        @PathVariable resourceName: String,
    ): ResponseEntity<Void> =
        handleSync(corePrincipal, syncPage, domainName, packageName, resourceName, HttpStatus.CREATED)

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

    private fun handleSync(
        corePrincipal: CorePrincipal,
        syncPage: SyncPage,
        domainName: String,
        packageName: String,
        resourceName: String,
        status: HttpStatus,
    ): ResponseEntity<Void> {
        // TODO: Validation shouldn't happen here, it should be within security layer
        requestValidator.validateOrgId(corePrincipal, syncPage.metadata.orgId)

        val coords =
            ResourceCoordinate(
                corePrincipal.orgId,
                domainName,
                packageName,
                resourceName,
            )
        syncPageService.doSync(syncPage, coords)
        return ResponseEntity.status(status).build()
    }
}
