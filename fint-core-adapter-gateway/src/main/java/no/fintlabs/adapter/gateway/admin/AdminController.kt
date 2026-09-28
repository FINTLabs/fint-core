package no.fintlabs.adapter.gateway.admin

import no.fintlabs.adapter.gateway.relation.RelationEdgeDrift
import no.fintlabs.adapter.gateway.relation.RelationEdgeRebuild
import no.fintlabs.adapter.gateway.relation.RelationEdgeRebuildRunningException
import no.fintlabs.adapter.gateway.relation.RelationEdgeRebuilder
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.fint.core.model.FintModel
import no.novari.resource.server.authentication.CorePrincipal
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Operator actions on this adapter gateway. Every endpoint here has its own access rule in
 * `SecurityConfiguration`, and any other path under `/admin` is refused, so a new endpoint stays
 * closed until it is given a rule of its own.
 */
@RestController
@RequestMapping("/admin")
class AdminController(
    private val rebuilder: RelationEdgeRebuilder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Rebuilds the relation edges of [resource] for [orgId], for example `orgId=ude.oslo.kommune.no`
     * and `resource=utdanning/elev/person`, and answers with what it did once it is done. Use it
     * after a fix to how edges are written, or to clear back-links that stale edges still give.
     * The org id may be written with dots, dashes or underscores. The resource is a path the model
     * serves. Only a FINT client from novari.no may call it.
     */
    @PostMapping("/relation-edges/rebuild")
    fun rebuild(
        @RequestParam orgId: String,
        @RequestParam resource: String,
        principal: CorePrincipal,
    ): RelationEdgeRebuild {
        val coordinate = coordinateOf(orgId, resource)
        log.info(
            "Relation edge rebuild of {} for {} started by {} ({})",
            coordinate.toResourceUri(),
            coordinate.orgId,
            principal.username,
            principal.orgId,
        )
        return rebuilder.rebuild(coordinate).also {
            log.info("Relation edge rebuild of {} for {} done: {}", coordinate.toResourceUri(), coordinate.orgId, it)
        }
    }

    /**
     * Reports what [rebuild] would change for [resource] and [orgId], without writing anything:
     * edges missing, edges stale, and edges whose source is gone, with a few examples of each.
     * Takes the same parameters as [rebuild], and only a FINT client from novari.no may call it.
     */
    @GetMapping("/relation-edges/drift")
    fun drift(
        @RequestParam orgId: String,
        @RequestParam resource: String,
        principal: CorePrincipal,
    ): RelationEdgeDrift {
        val coordinate = coordinateOf(orgId, resource)
        log.info(
            "Relation edge drift check of {} for {} started by {} ({})",
            coordinate.toResourceUri(),
            coordinate.orgId,
            principal.username,
            principal.orgId,
        )
        return rebuilder.drift(coordinate).also {
            log.info(
                "Relation edge drift check of {} for {} done: resourcesRead={} missing={} stale={} sourcesGone={}",
                coordinate.toResourceUri(),
                coordinate.orgId,
                it.resourcesRead,
                it.edgesMissing,
                it.edgesStale,
                it.edgesOfSourcesGone,
            )
        }
    }

    @ExceptionHandler(RelationEdgeRebuildRunningException::class)
    fun alreadyRunning(exception: RelationEdgeRebuildRunningException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.message)

    private fun coordinateOf(
        orgId: String,
        resource: String,
    ): ResourceCoordinate {
        val org =
            runCatching { OrgId.from(orgId) }
                .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Not an org id: $orgId") }
        val ref =
            FintModel.refOf(resource)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a resource the model serves: $resource")
        return ResourceCoordinate(org.value, ref.domainName, ref.packageName, ref.resourceName)
    }
}
