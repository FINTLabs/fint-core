package no.fintlabs.adapter.gateway.relation

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.store.ResourceStore
import no.novari.fint.core.model.FintModel
import no.novari.fint.core.model.FintResourceRef
import org.awaitility.kotlin.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelationEdgeJobsTest {
    private val rebuilder = mockk<RelationEdgeRebuilder>()
    private val resourceStore = mockk<ResourceStore>()
    private val runner = RelationEdgeJobRunner()
    private val jobs = RelationEdgeJobs(rebuilder, resourceStore, runner, TickingClock())

    private val orgId = OrgId.from("fintlabs.no")
    private val elev = FintResourceRef("utdanning", "elev", "elev")
    private val person = FintResourceRef("utdanning", "elev", "person")
    private val elevRebuild = RelationEdgeRebuild(resourcesRead = 3, edgesWritten = 2, edgesRemoved = 1)
    private val personRebuild = RelationEdgeRebuild(resourcesRead = 5, edgesWritten = 0, edgesRemoved = 4)

    init {
        every { resourceStore.storedCoordinates(orgId) } returns listOf(coordinateOf(elev), coordinateOf(person))
    }

    @AfterEach
    fun stopRunner() {
        runner.shutdown()
    }

    @Test
    fun `a job for everything stored rebuilds every resource type the org has stored and keeps each result`() {
        every { rebuilder.rebuild(coordinateOf(elev)) } returns elevRebuild
        every { rebuilder.rebuild(coordinateOf(person)) } returns personRebuild

        val job = finished(jobs.startRebuild(orgId, ResourceSelection.All, STARTED_BY))

        assertEquals(RelationEdgeJob.State.DONE, job.state)
        assertEquals(
            listOf(
                RelationEdgeJobResource("utdanning/elev/elev", RelationEdgeJobResource.State.DONE, elevRebuild, null),
                RelationEdgeJobResource("utdanning/elev/person", RelationEdgeJobResource.State.DONE, personRebuild, null),
            ),
            job.resources,
        )
        assertNotNull(job.finishedAt)
    }

    @Test
    fun `a job for a component rebuilds every resource type in the component, sorted by name`() {
        every { rebuilder.rebuild(any()) } returns personRebuild

        val job = finished(jobs.startRebuild(orgId, ResourceSelection.Component("utdanning", "elev"), STARTED_BY))

        val inComponent = FintModel.refsIn("utdanning", "elev").sortedBy { it.resourceName }
        assertEquals(inComponent.map { "utdanning/elev/${it.resourceName}" }, job.resources.map { it.resource })
        assertTrue(job.resources.size > 2)
        inComponent.forEach { verify { rebuilder.rebuild(coordinateOf(it)) } }
    }

    @Test
    fun `a job for one resource rebuilds only that resource type`() {
        every { rebuilder.rebuild(coordinateOf(person)) } returns personRebuild

        val job = finished(jobs.startRebuild(orgId, ResourceSelection.Resource(person), STARTED_BY))

        assertEquals(listOf("utdanning/elev/person"), job.resources.map { it.resource })
        verify(exactly = 1) { rebuilder.rebuild(any()) }
    }

    @Test
    fun `a job for everything stored of an org with nothing stored finishes with no resources`() {
        every { resourceStore.storedCoordinates(orgId) } returns emptyList()

        val job = finished(jobs.startRebuild(orgId, ResourceSelection.All, STARTED_BY))

        assertEquals(RelationEdgeJob.State.DONE, job.state)
        assertEquals(emptyList(), job.resources)
    }

    @Test
    fun `a drift check job keeps the drift of each resource type`() {
        val drift = RelationEdgeDrift(resourcesRead = 5, edgesMissing = 1, edgesStale = 2, edgesOfSourcesGone = 0, examples = emptyList())
        every { rebuilder.drift(coordinateOf(person)) } returns drift

        val job = finished(jobs.startDrift(orgId, ResourceSelection.Resource(person), STARTED_BY))

        assertEquals(RelationEdgeJob.Kind.DRIFT, job.kind)
        assertEquals(drift, job.resources.single().result)
    }

    @Test
    fun `a resource type that fails is marked failed and the job goes on with the next one`() {
        every { rebuilder.rebuild(coordinateOf(elev)) } throws IllegalStateException("the store is down")
        every { rebuilder.rebuild(coordinateOf(person)) } returns personRebuild

        val job = finished(jobs.startRebuild(orgId, ResourceSelection.All, STARTED_BY))

        assertEquals(RelationEdgeJob.State.FAILED, job.state)
        assertEquals(
            listOf(
                RelationEdgeJobResource("utdanning/elev/elev", RelationEdgeJobResource.State.FAILED, null, "the store is down"),
                RelationEdgeJobResource("utdanning/elev/person", RelationEdgeJobResource.State.DONE, personRebuild, null),
            ),
            job.resources,
        )
    }

    @Test
    fun `a running job shows which resource type it is on and which are still waiting`() {
        val release = blockRebuildOf(elev)
        every { rebuilder.rebuild(coordinateOf(person)) } returns personRebuild

        val started = jobs.startRebuild(orgId, ResourceSelection.All, STARTED_BY)
        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            assertEquals(
                listOf(RelationEdgeJobResource.State.RUNNING, RelationEdgeJobResource.State.WAITING),
                jobs.find(started.id)?.resources?.map { it.state },
            )
        }
        assertEquals(RelationEdgeJob.State.RUNNING, jobs.find(started.id)?.state)

        release.countDown()
        finished(started)
    }

    @Test
    fun `a second job is refused while the first is running and is told which job that is`() {
        val release = blockRebuildOf(elev)

        val first = jobs.startRebuild(orgId, ResourceSelection.Resource(elev), STARTED_BY)
        val refused =
            assertThrows<RelationEdgeJobRunningException> { jobs.startDrift(orgId, ResourceSelection.Resource(person), STARTED_BY) }

        assertEquals(first.id, refused.runningJobId)
        release.countDown()
        finished(first)
    }

    @Test
    fun `a new job can start once the last one is done`() {
        every { rebuilder.rebuild(any()) } returns personRebuild
        finished(jobs.startRebuild(orgId, ResourceSelection.Resource(person), STARTED_BY))

        val second = finished(jobs.startRebuild(orgId, ResourceSelection.Resource(person), STARTED_BY))

        assertEquals(RelationEdgeJob.State.DONE, second.state)
    }

    @Test
    fun `only the last jobs are kept`() {
        every { rebuilder.rebuild(any()) } returns personRebuild
        val started =
            (0..RelationEdgeJobs.KEPT_JOBS).map {
                finished(
                    jobs.startRebuild(orgId, ResourceSelection.Resource(person), STARTED_BY),
                )
            }

        assertNull(jobs.find(started.first().id))
        started.drop(1).forEach { assertNotNull(jobs.find(it.id)) }
    }

    @Test
    fun `a job that was never started is not found`() {
        assertNull(jobs.find(UUID.randomUUID()))
    }

    private fun finished(job: RelationEdgeJob): RelationEdgeJob {
        await.atMost(Duration.ofSeconds(5)).until { jobs.find(job.id)?.finishedAt != null }
        return jobs.find(job.id)!!
    }

    private fun blockRebuildOf(resource: FintResourceRef): CountDownLatch {
        val release = CountDownLatch(1)
        every { rebuilder.rebuild(coordinateOf(resource)) } answers {
            release.await(10, TimeUnit.SECONDS)
            elevRebuild
        }
        return release
    }

    private fun coordinateOf(resource: FintResourceRef): ResourceCoordinate = ResourceCoordinate.of(orgId, resource)

    private class TickingClock : Clock() {
        private val ticks = AtomicLong()

        override fun instant(): Instant = START.plusSeconds(ticks.getAndIncrement())

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    companion object {
        private const val STARTED_BY = "test@adapter.novari.no"
        private val START: Instant = Instant.parse("2026-09-29T10:00:00Z")
    }
}
