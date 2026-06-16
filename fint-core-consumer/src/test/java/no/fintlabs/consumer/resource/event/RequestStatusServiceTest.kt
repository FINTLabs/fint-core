package no.fintlabs.consumer.resource.event

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.adapter.models.event.EventBodyResponse
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.operation.OperationType
import no.fintlabs.cache.CacheService
import no.fintlabs.cache.FintCache
import no.fintlabs.consumer.links.LinkService
import no.fintlabs.consumer.resource.ResourceConverter
import no.fintlabs.consumer.resource.event.RequestFailed.FailureType
import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.Link
import no.novari.fint.model.resource.utdanning.vurdering.ElevfravarResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

class RequestStatusServiceTest {
    private val eventStatusCache: EventStatusCache = mockk()
    private val cacheService: CacheService = mockk()
    private val resourceConverter: ResourceConverter = mockk()
    private val linkService: LinkService = mockk()
    private val resourceCache: FintCache<FintResource> = mockk()

    private val service = RequestStatusService(eventStatusCache, cacheService, resourceConverter, linkService)

    private val resourceName = "student"
    private val resourceIdentifier = "my-id"
    private val corrId = "abc-123"

    @BeforeEach
    fun setup() {
        every { cacheService.getCache(resourceName) } returns resourceCache
    }

    @Test
    fun `should return VALIDATED with EventBodyResponse when operation is VALIDATE`() {
        val event = createResponse(OperationType.VALIDATE)
        every { eventStatusCache.getResponse(corrId) } returns event
        every { eventStatusCache.requestExists(corrId) } returns true

        val result = service.getStatusResponse(resourceName, corrId)

        assertInstanceOf(RequestValidated::class.java, result)
        val validatedResult = result as RequestValidated

        assertInstanceOf(EventBodyResponse::class.java, validatedResult.body)
    }

    @Test
    fun `should return FAILED with EventBodyResponse when event is failed`() {
        val event = createResponse(OperationType.CREATE, failed = true)
        every { eventStatusCache.getResponse(corrId) } returns event
        every { eventStatusCache.requestExists(corrId) } returns true

        val result = service.getStatusResponse(resourceName, corrId)

        assertInstanceOf(RequestFailed::class.java, result)
        val failedResult = result as RequestFailed
        assertEquals(FailureType.ERROR, failedResult.failureType)
        assertInstanceOf(EventBodyResponse::class.java, failedResult.body)
    }

    @Test
    fun `should return REJECTED with EventBodyResponse when event is rejected`() {
        val event = createResponse(OperationType.CREATE, rejected = true)
        every { eventStatusCache.requestExists(corrId) } returns true
        every { eventStatusCache.getResponse(corrId) } returns event

        val result = service.getStatusResponse(resourceName, corrId)

        assertInstanceOf(RequestFailed::class.java, result)
        val failedResult = result as RequestFailed
        assertEquals(FailureType.REJECTED, failedResult.failureType)
        assertInstanceOf(EventBodyResponse::class.java, failedResult.body)
    }

    @Test
    fun `should return CONFLICT with Resource converted from Event and MapLinks called`() {
        val realResource = ElevfravarResource()
        val event = createResponse(OperationType.CREATE, conflicted = true, resource = realResource)

        every { linkService.mapLinks(resourceName, realResource) } just Runs
        every { eventStatusCache.getResponse(corrId) } returns event
        every { eventStatusCache.requestExists(corrId) } returns true
        every { resourceConverter.convert(resourceName, event.value.resource) } returns realResource

        val result = service.getStatusResponse(resourceName, corrId)

        assertInstanceOf(RequestFailed::class.java, result)
        val failedResult = result as RequestFailed
        assertEquals(FailureType.CONFLICT, failedResult.failureType)
        assertEquals(realResource, failedResult.body)

        verify { resourceConverter.convert(resourceName, event.value.resource) }
        verify { linkService.mapLinks(resourceName, realResource) }
    }

    @Test
    fun `should return CREATED with Resource from Cache and MapLinks NOT called`() {
        val handledTime = 1000L
        val selfLink = "https://my-url.com"
        val event = createResponse(OperationType.CREATE, handledAt = handledTime)

        val cachedResource =
            ElevfravarResource().apply {
                addSelf(Link.with(selfLink))
            }

        every { eventStatusCache.requestExists(corrId) } returns true
        every { eventStatusCache.getResponse(corrId) } returns event

        // Cache is synced
        every { resourceCache.lastUpdatedByResourceId(resourceIdentifier) } returns handledTime
        every { resourceCache.get(resourceIdentifier) } returns cachedResource

        val result = service.getStatusResponse(resourceName, corrId)

        assertInstanceOf(ResourceCreated::class.java, result)
        val createdResult = result as ResourceCreated

        assertEquals(cachedResource, createdResult.body)
        assertEquals(URI.create(selfLink), createdResult.location)

        verify { resourceCache.get(resourceIdentifier) }
        verify(exactly = 0) { linkService.mapLinks(any(), any()) }
        verify(exactly = 0) { resourceConverter.convert(any(), any()) }
    }

    @Test
    fun `should return ACCEPTED if cache is lagging (timestamp mismatch)`() {
        val handledTime = 1000L
        val event = createResponse(opType = OperationType.CREATE, handledAt = handledTime)

        every { eventStatusCache.getResponse(corrId) } returns event
        every { eventStatusCache.requestExists(corrId) } returns true

        // CACHE SCENARIO: The cache only has data from time 900 (stale).
        every { resourceCache.lastUpdatedByResourceId(resourceIdentifier) } returns 900L

        val result = service.getStatusResponse(resourceName, corrId)

        assertEquals(RequestAccepted, result)
    }

    @Test
    fun `should return ACCEPTED when event is still running`() {
        every { eventStatusCache.getResponse(corrId) } returns null
        every { eventStatusCache.requestExists(corrId) } returns true

        val result = service.getStatusResponse(resourceName, corrId)

        assertEquals(RequestAccepted, result)
    }

    @Test
    fun `should return GONE when event does not exist`() {
        every { eventStatusCache.getResponse(corrId) } returns null
        every { eventStatusCache.requestExists(corrId) } returns false

        val result = service.getStatusResponse(resourceName, corrId)

        assertEquals(RequestGone, result)
    }

    @Test
    fun `should return DELETED when operation is DELETE`() {
        val event = createResponse(opType = OperationType.DELETE)
        every { eventStatusCache.getResponse(corrId) } returns event
        every { eventStatusCache.requestExists(corrId) } returns true

        val result = service.getStatusResponse(resourceName, corrId)

        assertEquals(ResourceDeleted, result)
    }

    @Test
    fun `should return GONE when response is present but request is not`() {
        val event = createResponse(opType = OperationType.DELETE)
        every { eventStatusCache.getResponse(corrId) } returns event
        every { eventStatusCache.requestExists(corrId) } returns false

        val result = service.getStatusResponse(resourceName, corrId)

        assertEquals(RequestGone, result)
    }

    private fun createResponse(
        opType: OperationType,
        failed: Boolean = false,
        rejected: Boolean = false,
        conflicted: Boolean = false,
        handledAt: Long = 1000L,
        resource: Any? = null,
    ): ResponseFintEvent =
        ResponseFintEvent
            .builder()
            .corrId(corrId)
            .operationType(opType)
            .failed(failed)
            .errorMessage(if (failed) "Specific error" else null)
            .rejected(rejected)
            .rejectReason(if (rejected) "Specific rejection" else null)
            .conflicted(conflicted)
            .conflictReason(if (conflicted) "Specific conflict" else null)
            .handledAt(handledAt)
            .value(SyncPageEntry.of(resourceIdentifier, resource))
            .orgId("mock-org-id")
            .adapterId("mock-adapter-id")
            .build()
}
