package no.fintlabs.consumer.kafka.event

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.adapter.operation.OperationType
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.config.EventCacheProperties
import no.fintlabs.consumer.config.EventCacheProperties.LifeCycle
import no.fintlabs.consumer.config.OrgId
import no.fintlabs.consumer.resource.ResourceConverter
import no.fintlabs.consumer.resource.event.EventStatusStore
import no.novari.fint.model.resource.FintResource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RequestFintEventServiceTest {
    private val objectMapper = mockk<ObjectMapper>()
    private val props = mockk<EventCacheProperties>()
    private val config = mockk<ConsumerConfiguration>()
    private val clock = Clock.fixed(Instant.parse("2020-05-24T14:00:00Z"), ZoneOffset.UTC)
    private val resourceConverter = mockk<ResourceConverter>()
    private val producer = mockk<RequestFintEventProducer>(relaxed = true)
    private val eventStatusStore = mockk<EventStatusStore>(relaxed = true)

    private lateinit var service: RequestFintEventService

    private val resourceKey = "utdanning_vurdering_elevfravar"
    private val resourceName = "elevfravar"

    @BeforeEach
    fun setUp() {
        service =
            RequestFintEventService(
                objectMapper = objectMapper,
                props = props,
                config = config,
                clock = clock,
                resourceConverter = resourceConverter,
                requestFintEventProducer = producer,
                eventStatusStore = eventStatusStore,
            )

        every { config.orgId } returns OrgId.from("fintlabs.no")
        every { objectMapper.writeValueAsString(any()) } returns "{}"
        every { producer.publish(any(), any(), any()) } returns CompletableFuture.completedFuture(mockk(relaxed = true))
        every { props.getLifeCycleConfig(resourceName) } returns LifeCycle(ttl = Duration.ofMinutes(2))
        every { resourceConverter.convertAndMapLinks(resourceKey, any()) } returns mockk()
    }

    @Test
    fun `createAndPublish derives org, domain, package and name from the key`() {
        val event = service.createAndPublish(resourceKey, mockk<FintResource>(), OperationType.CREATE)

        assertEquals("fintlabs.no", event.orgId)
        assertEquals("utdanning", event.domainName)
        assertEquals("vurdering", event.packageName)
        assertEquals(resourceName, event.resourceName)
        assertEquals(OperationType.CREATE, event.operationType)
    }

    @Test
    fun `createAndPublish sets correct TTL from clock and props`() {
        val event = service.createAndPublish(resourceKey, mockk<FintResource>(), OperationType.CREATE)

        val expectedCreated = clock.millis()
        assertEquals(expectedCreated, event.created)
        assertEquals(expectedCreated + Duration.ofMinutes(2).toMillis(), event.timeToLive)
    }

    @Test
    fun `createAndPublish generates a corrId`() {
        val event = service.createAndPublish(resourceKey, mockk<FintResource>(), OperationType.CREATE)

        assertNotNull(event.corrId)
        assertTrue(event.corrId.isNotBlank())
    }

    @Test
    fun `createAndPublish routes to the component request topic`() {
        val event = service.createAndPublish(resourceKey, mockk<FintResource>(), OperationType.CREATE)

        verify(exactly = 1) { producer.publish(event, "utdanning", "vurdering") }
    }

    @Test
    fun `createAndPublish with validateOnly true maps to VALIDATE operation type`() {
        val event = service.createAndPublish(resourceKey, mockk<FintResource>(), validateOnly = true)

        assertEquals(OperationType.VALIDATE, event.operationType)
    }

    @Test
    fun `createAndPublish with validateOnly false maps to CREATE operation type`() {
        val event = service.createAndPublish(resourceKey, mockk<FintResource>(), validateOnly = false)

        assertEquals(OperationType.CREATE, event.operationType)
    }

    @Test
    fun `createAndPublish serializes resource through objectMapper`() {
        val fintResource = mockk<FintResource>()
        val serialized = """{"systemId":"123"}"""
        every { resourceConverter.convertAndMapLinks(resourceKey, any()) } returns fintResource
        every { objectMapper.writeValueAsString(fintResource) } returns serialized

        val event = service.createAndPublish(resourceKey, fintResource, OperationType.CREATE)

        assertEquals(serialized, event.value)
    }

    @Test
    fun `createAndPublish with null resourceData bypasses converter and serializes null`() {
        every { objectMapper.writeValueAsString(null) } returns "null"

        val event = service.createAndPublish(resourceKey, null, OperationType.CREATE)

        assertEquals("null", event.value)
        verify { resourceConverter wasNot called }
    }

    @Test
    fun `two separate publishes produce different corrIds`() {
        val first = service.createAndPublish(resourceKey, mockk<FintResource>(), OperationType.CREATE)
        val second = service.createAndPublish(resourceKey, mockk<FintResource>(), OperationType.CREATE)

        assertTrue(first.corrId != second.corrId)
    }
}
