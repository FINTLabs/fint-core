package no.fintlabs.adapter.gateway.admin

import io.swagger.v3.oas.annotations.Parameter
import no.fintlabs.adapter.gateway.AdapterApiPaths
import no.fintlabs.adapter.gateway.relation.RelationEdgeJob
import no.fintlabs.adapter.gateway.relation.RelationEdgeJobRunningException
import no.fintlabs.adapter.gateway.relation.RelationEdgeJobs
import no.fintlabs.adapter.gateway.relation.ResourceSelection
import no.novari.core.shared.model.OrgId
import no.novari.resource.server.authentication.CorePrincipal
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.net.URI
import java.util.UUID

/**
 * Operator actions on this adapter gateway. Every endpoint here has its own access rule in
 * `SecurityConfiguration`, and any other path under `/admin` is refused, so a new endpoint stays
 * closed until it is given a rule of its own.
 *
 * A rebuild or drift check runs in the background, because it can take longer than the gateway in
 * front keeps a request open. Starting one answers 202 with the job and a `Location` to follow
 * until the job is no longer `RUNNING`.
 */
@RestController
@RequestMapping(AdapterApiPaths.ROOT + "/admin")
class AdminController(
    private val jobs: RelationEdgeJobs,
) {
    /**
     * Starts a rebuild of the relation edges of one org. The scope is one resource, for example
     * `?orgId=ude.oslo.kommune.no&scope=utdanning/elev/person`, every resource in one component,
     * `scope=utdanning/elev`, or every resource the org has stored, `scope=all`.
     */
    @PostMapping("/relation-edges/rebuild")
    fun rebuild(
        @RequestParam orgId: OrgId,
        @Parameter(description = SCOPE_DESCRIPTION, example = "utdanning/elev/person")
        @RequestParam scope: ResourceSelection,
        principal: CorePrincipal,
    ): ResponseEntity<RelationEdgeJob> = accepted(jobs.startRebuild(orgId, scope, principal.username))

    /**
     * Starts a check of what [rebuild] would change, without writing anything: edges missing,
     * edges stale, and edges whose source is gone, with a few examples of each. Takes the same
     * query parameters as [rebuild].
     */
    @PostMapping("/relation-edges/drift")
    fun drift(
        @RequestParam orgId: OrgId,
        @Parameter(description = SCOPE_DESCRIPTION, example = "utdanning/elev/person")
        @RequestParam scope: ResourceSelection,
        principal: CorePrincipal,
    ): ResponseEntity<RelationEdgeJob> = accepted(jobs.startDrift(orgId, scope, principal.username))

    /** A rebuild or drift check started earlier, with the result of each resource that is done. */
    @GetMapping("/relation-edges/jobs/{id}")
    fun job(
        @PathVariable id: UUID,
    ): RelationEdgeJob = jobs.find(id) ?: throw RelationEdgeJobNotFoundException(id)

    @ExceptionHandler(RelationEdgeJobRunningException::class)
    fun alreadyRunning(exception: RelationEdgeJobRunningException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.message).apply {
            setProperty("runningJob", exception.runningJobId)
        }

    @ExceptionHandler(RelationEdgeJobNotFoundException::class)
    fun jobNotFound(exception: RelationEdgeJobNotFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.message)

    /**
     * Answers a query parameter that cannot be read with what was wrong with it. Spring's own answer
     * is a 400 without the reason.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun unreadableParameter(exception: MethodArgumentTypeMismatchException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "Bad ${exception.name}: ${exception.rootCause?.message ?: exception.value}",
        )

    /** Answers a missing query parameter with its name, for the same reason as [unreadableParameter]. */
    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missingParameter(exception: MissingServletRequestParameterException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Missing ${exception.parameterName}")

    private fun accepted(job: RelationEdgeJob): ResponseEntity<RelationEdgeJob> =
        ResponseEntity
            .accepted()
            .location(URI.create("${AdapterApiPaths.ROOT}/admin/relation-edges/jobs/${job.id}"))
            .body(job)

    companion object {
        private const val SCOPE_DESCRIPTION =
            "all for every resource the org has stored, a component such as utdanning/elev, or one resource such as utdanning/elev/person"
    }
}

class RelationEdgeJobNotFoundException(
    id: UUID,
) : RuntimeException("No relation edge job $id. Jobs are kept in memory, so they are gone after a restart.")
