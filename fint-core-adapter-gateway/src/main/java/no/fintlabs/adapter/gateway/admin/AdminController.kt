package no.fintlabs.adapter.gateway.admin

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import no.fintlabs.adapter.gateway.relation.RelationEdgeJob
import no.fintlabs.adapter.gateway.relation.RelationEdgeJobRunningException
import no.fintlabs.adapter.gateway.relation.RelationEdgeJobs
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.store.ResourceStore
import no.novari.fint.core.model.FintResourceRef
import no.novari.resource.server.authentication.CorePrincipal
import org.springdoc.core.annotations.ParameterObject
import org.springframework.beans.TypeMismatchException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.validation.FieldError
import org.springframework.validation.ObjectError
import org.springframework.web.bind.MethodArgumentNotValidException
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
@RequestMapping("/admin")
class AdminController(
    private val jobs: RelationEdgeJobs,
    private val resourceStore: ResourceStore,
) {
    /**
     * Starts a rebuild of the relation edges of one resource, for example
     * `?orgId=ude.oslo.kommune.no&resource=utdanning/elev/person`, of every resource in one
     * component, for example `?orgId=ude.oslo.kommune.no&component=utdanning/elev`, or of every
     * resource the org has stored, `?orgId=ude.oslo.kommune.no&all=true`.
     */
    @PostMapping("/relation-edges/rebuild")
    fun rebuild(
        @RequestParam orgId: OrgId,
        @Valid @ParameterObject choice: ResourceChoice,
        principal: CorePrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<RelationEdgeJob> = accepted(jobs.startRebuild(orgId, resourcesOf(orgId, choice), principal.username), request)

    /**
     * Starts a check of what [rebuild] would change, without writing anything: edges missing,
     * edges stale, and edges whose source is gone, with a few examples of each. Takes the same
     * query parameters as [rebuild].
     */
    @PostMapping("/relation-edges/drift")
    fun drift(
        @RequestParam orgId: OrgId,
        @Valid @ParameterObject choice: ResourceChoice,
        principal: CorePrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<RelationEdgeJob> = accepted(jobs.startDrift(orgId, resourcesOf(orgId, choice), principal.username), request)

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
     * Answers a [ResourceChoice] that cannot be read or breaks its rule, with every reason. A
     * value the converter turned down is answered the same way as in [unreadableParameter].
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalidChoice(exception: MethodArgumentNotValidException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            exception.bindingResult.allErrors.joinToString("; ") { it.reason() },
        )

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

    private fun resourcesOf(
        orgId: OrgId,
        choice: ResourceChoice,
    ): List<FintResourceRef> = choice.resources { resourceStore.storedCoordinates(orgId).map { it.toResourceRef() } }

    private fun ObjectError.reason(): String =
        if (this is FieldError && contains(TypeMismatchException::class.java)) {
            "Bad $field: ${unwrap(TypeMismatchException::class.java).mostSpecificCause.message}"
        } else {
            defaultMessage ?: code ?: "Invalid request"
        }

    private fun accepted(
        job: RelationEdgeJob,
        request: HttpServletRequest,
    ): ResponseEntity<RelationEdgeJob> =
        ResponseEntity
            .accepted()
            .location(URI.create("${request.contextPath}/admin/relation-edges/jobs/${job.id}"))
            .body(job)
}

class RelationEdgeJobNotFoundException(
    id: UUID,
) : RuntimeException("No relation edge job $id. Jobs are kept in memory, so they are gone after a restart.")
