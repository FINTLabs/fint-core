package no.fintlabs.adapter.gateway.relation

import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.store.ResourceStore
import no.novari.fint.core.model.FintModel
import no.novari.fint.core.model.FintResourceRef
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Runs relation edge rebuilds and drift checks in the background, so a caller gets an answer at
 * once and asks for the result later.
 *
 * Only one job runs at a time on this gateway, and the resource types of a job run one after
 * another, so Mongo sees one batch at a time. A resource type that fails is marked as failed and
 * the job goes on with the next one. The last [KEPT_JOBS] jobs are kept in memory only, so they are
 * gone after a restart. Running a rebuild again is safe.
 */
@Service
class RelationEdgeJobs(
    private val rebuilder: RelationEdgeRebuilder,
    private val resourceStore: ResourceStore,
    private val runner: RelationEdgeJobRunner,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jobs = ConcurrentHashMap<UUID, RelationEdgeJob>()
    private val lock = ReentrantLock()
    private var runningJobId: UUID? = null

    /** @throws RelationEdgeJobRunningException when another job is running */
    fun startRebuild(
        orgId: OrgId,
        selection: ResourceSelection,
        startedBy: String,
    ): RelationEdgeJob = start(RelationEdgeJob.Kind.REBUILD, orgId, resourcesOf(orgId, selection), startedBy, rebuilder::rebuild)

    /** @throws RelationEdgeJobRunningException when another job is running */
    fun startDrift(
        orgId: OrgId,
        selection: ResourceSelection,
        startedBy: String,
    ): RelationEdgeJob = start(RelationEdgeJob.Kind.DRIFT, orgId, resourcesOf(orgId, selection), startedBy, rebuilder::drift)

    fun find(id: UUID): RelationEdgeJob? = jobs[id]

    private fun start(
        kind: RelationEdgeJob.Kind,
        orgId: OrgId,
        resources: List<FintResourceRef>,
        startedBy: String,
        work: (ResourceCoordinate) -> RelationEdgeJobResult,
    ): RelationEdgeJob {
        val job = RelationEdgeJob.start(kind, orgId, resources, startedBy, clock.instant())
        lock.withLock {
            runningJobId?.let { throw RelationEdgeJobRunningException(it) }
            runningJobId = job.id
            forgetOldJobs()
            jobs[job.id] = job
        }
        log.info(
            "Relation edge {} job {} for {} started by {}: {}",
            kind,
            job.id,
            orgId,
            startedBy,
            job.resources.map { it.resource },
        )
        runner.submit { run(job.id, resources.map { ResourceCoordinate.of(orgId, it) }, work) }
        return jobs.getValue(job.id)
    }

    private fun resourcesOf(
        orgId: OrgId,
        selection: ResourceSelection,
    ): List<FintResourceRef> =
        when (selection) {
            is ResourceSelection.Resource -> listOf(selection.ref)
            is ResourceSelection.Component -> FintModel.refsIn(selection.domainName, selection.packageName).sortedBy { it.resourceName }
            ResourceSelection.All -> resourceStore.storedCoordinates(orgId).map { it.toResourceRef() }
        }

    private fun run(
        jobId: UUID,
        coordinates: List<ResourceCoordinate>,
        work: (ResourceCoordinate) -> RelationEdgeJobResult,
    ) {
        try {
            coordinates.forEach { runOne(jobId, it, work) }
        } finally {
            val job =
                lock.withLock {
                    runningJobId = null
                    update(jobId) { it.finish(clock.instant()) }
                }
            log.info(
                "Relation edge {} job {} for {} finished {} in {} s",
                job.kind,
                job.id,
                job.orgId,
                job.state,
                Duration.between(job.startedAt, job.finishedAt).toSeconds(),
            )
        }
    }

    private fun runOne(
        jobId: UUID,
        coordinate: ResourceCoordinate,
        work: (ResourceCoordinate) -> RelationEdgeJobResult,
    ) {
        val resource = coordinate.toResourceUri()
        update(jobId) { it.update(resource, RelationEdgeJobResource::running) }
        try {
            val result = work(coordinate)
            update(jobId) { it.update(resource) { step -> step.done(result) } }
            log.info("Relation edge job {}: {} for {} done: {}", jobId, resource, coordinate.orgId, result.counts())
        } catch (exception: Exception) {
            update(jobId) { it.update(resource) { step -> step.failed(exception.message ?: exception.javaClass.name) } }
            log.error("Relation edge job {}: {} for {} failed", jobId, resource, coordinate.orgId, exception)
        }
    }

    private fun update(
        jobId: UUID,
        change: (RelationEdgeJob) -> RelationEdgeJob,
    ): RelationEdgeJob = checkNotNull(jobs.computeIfPresent(jobId) { _, job -> change(job) }) { "Relation edge job $jobId is gone" }

    private fun forgetOldJobs() {
        jobs.values
            .sortedByDescending { it.startedAt }
            .drop(KEPT_JOBS - 1)
            .forEach { jobs.remove(it.id) }
    }

    private fun RelationEdgeJobResult.counts(): String =
        when (this) {
            is RelationEdgeRebuild -> {
                "resourcesRead=$resourcesRead written=$edgesWritten removed=$edgesRemoved"
            }

            is RelationEdgeDrift -> {
                "resourcesRead=$resourcesRead missing=$edgesMissing stale=$edgesStale sourcesGone=$edgesOfSourcesGone"
            }
        }

    companion object {
        const val KEPT_JOBS = 20
    }
}

class RelationEdgeJobRunningException(
    val runningJobId: UUID,
) : RuntimeException("A relation edge job is already running: $runningJobId")
