package no.fintlabs.adapter.gateway.relation

import com.fasterxml.jackson.annotation.JsonInclude
import no.novari.core.shared.model.OrgId
import no.novari.fint.core.model.FintResourceRef
import java.time.Instant
import java.util.UUID

/**
 * A rebuild or drift check of one or more resource types for one org, as it stands right now. A
 * job is never changed in place, every step makes a new copy, so a reader always sees a whole state.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class RelationEdgeJob(
    val id: UUID,
    val kind: Kind,
    val orgId: String,
    val state: State,
    val startedBy: String,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val resources: List<RelationEdgeJobResource>,
) {
    enum class Kind { REBUILD, DRIFT }

    /** A job is [FAILED] when it is finished and at least one of its resource types failed. */
    enum class State { RUNNING, DONE, FAILED }

    fun update(
        resource: String,
        change: (RelationEdgeJobResource) -> RelationEdgeJobResource,
    ): RelationEdgeJob = copy(resources = resources.map { if (it.resource == resource) change(it) else it })

    fun finish(at: Instant): RelationEdgeJob =
        copy(
            state = if (resources.any { it.state == RelationEdgeJobResource.State.FAILED }) State.FAILED else State.DONE,
            finishedAt = at,
        )

    companion object {
        fun start(
            kind: Kind,
            orgId: OrgId,
            resources: List<FintResourceRef>,
            startedBy: String,
            startedAt: Instant,
        ): RelationEdgeJob =
            RelationEdgeJob(
                id = UUID.randomUUID(),
                kind = kind,
                orgId = orgId.value,
                state = State.RUNNING,
                startedBy = startedBy,
                startedAt = startedAt,
                finishedAt = null,
                resources = resources.map { RelationEdgeJobResource.waiting(it) },
            )
    }
}

/** One resource type of a [RelationEdgeJob], such as `utdanning/elev/person`, and how far it got. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class RelationEdgeJobResource(
    val resource: String,
    val state: State,
    val result: RelationEdgeJobResult?,
    val error: String?,
) {
    enum class State { WAITING, RUNNING, DONE, FAILED }

    fun running(): RelationEdgeJobResource = copy(state = State.RUNNING)

    fun done(result: RelationEdgeJobResult): RelationEdgeJobResource = copy(state = State.DONE, result = result)

    fun failed(error: String): RelationEdgeJobResource = copy(state = State.FAILED, error = error)

    companion object {
        fun waiting(resource: FintResourceRef): RelationEdgeJobResource =
            RelationEdgeJobResource(
                resource = "${resource.domainName}/${resource.packageName}/${resource.resourceName}",
                state = State.WAITING,
                result = null,
                error = null,
            )
    }
}

/** What one resource type of a job gave: a [RelationEdgeRebuild] or a [RelationEdgeDrift]. */
sealed interface RelationEdgeJobResult
