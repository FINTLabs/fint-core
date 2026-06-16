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
import no.novari.fint.model.resource.FintResource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
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

    private lateinit var service: RequestFintEventService

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
            )

        every { config.orgId } returns OrgId.from("fintlabs.no")
        every { config.domain } returns "utdanning"
        every { config.packageName } returns "vurdering"
        every { objectMapper.writeValueAsString(any()) } returns "{}"
    }

    @Test
    fun `createAndPublish with operationType maps config fields onto event`() {
        val resourceName = "elevfravar"
        val fintResource = mockk<FintResource>()
        val ttl = Duration.ofMinutes(2)

        every { resourceConverter.convertAndMapLinks(resourceName, any()) } returns fintResource
        every { props.getLifeCycleConfig(resourceName) } returns LifeCycle(ttl = ttl)

        val event = service.createAndPublish(resourceName, fintResource, OperationType.CREATE)

        assertEquals("fintlabs.no", event.orgId)
        assertEquals("utdanning", event.domainName)
        assertEquals("vurdering", event.packageName)
        assertEquals(resourceName, event.resourceName)
        assertEquals(OperationType.CREATE, event.operationType)
    }

    @Test
    fun `createAndPublish with operationType sets correct TTL from clock and props`() {
        val resourceName = "elevfravar"
        val fintResource = mockk<FintResource>()
        val ttl = Duration.ofMinutes(2)

        every { resourceConverter.convertAndMapLinks(resourceName, any()) } returns fintResource
        every { props.getLifeCycleConfig(resourceName) } returns LifeCycle(ttl = ttl)

        val event = service.createAndPublish(resourceName, fintResource, OperationType.CREATE)

        val expectedCreated = clock.millis()
        val expectedTtl = expectedCreated + ttl.toMillis()

        assertEquals(expectedCreated, event.created)
        assertEquals(expectedTtl, event.timeToLive)
    }

    @Test
    fun `createAndPublish with operationType generates a corrId`() {
        val resourceName = "elevfravar"
        val fintResource = mockk<FintResource>()

        every { resourceConverter.convertAndMapLinks(resourceName, any()) } returns fintResource
        every { props.getLifeCycleConfig(resourceName) } returns
            LifeCycle(
                ttl =
                    Duration.ofMinutes(
                        2,
                    ),
            )

        val event = service.createAndPublish(resourceName, fintResource, OperationType.CREATE)

        assertNotNull(event.corrId)
        assertTrue(event.corrId.isNotBlank())
    }

    @Test
    fun `createAndPublish with operationType delegates to producer`() {
        val resourceName = "elevfravar"
        val fintResource = mockk<FintResource>()

        every { resourceConverter.convertAndMapLinks(resourceName, any()) } returns fintResource
        every { props.getLifeCycleConfig(resourceName) } returns LifeCycle(ttl = Duration.ofMinutes(2))

        val event = service.createAndPublish(resourceName, fintResource, OperationType.CREATE)

        verify(exactly = 1) { producer.publish(event) }
    }

    @Test
    fun `createAndPublish with validateOnly true maps to VALIDATE operation type`() {
        val resourceName = "elevfravar"
        val fintResource = mockk<FintResource>()

        every { resourceConverter.convertAndMapLinks(resourceName, any()) } returns fintResource
        every { props.getLifeCycleConfig(resourceName) } returns LifeCycle(ttl = Duration.ofMinutes(2))

        val event = service.createAndPublish(resourceName, fintResource, validateOnly = true)

        assertEquals(OperationType.VALIDATE, event.operationType)
    }

    @Test
    fun `createAndPublish with validateOnly false maps to CREATE operation type`() {
        val resourceName = "elevfravar"
        val fintResource = mockk<FintResource>()

        every { resourceConverter.convertAndMapLinks(resourceName, any()) } returns fintResource
        every { props.getLifeCycleConfig(resourceName) } returns LifeCycle(ttl = Duration.ofMinutes(2))

        val event = service.createAndPublish(resourceName, fintResource, validateOnly = false)

        assertEquals(OperationType.CREATE, event.operationType)
    }

    @Test
    fun `createAndPublish serializes resource through objectMapper`() {
        val resourceName = "elevfravar"
        val fintResource = mockk<FintResource>()
        val serialized = """{"systemId":"123"}"""

        every { resourceConverter.convertAndMapLinks(resourceName, any()) } returns fintResource
        every { props.getLifeCycleConfig(resourceName) } returns LifeCycle(ttl = Duration.ofMinutes(2))
        every { objectMapper.writeValueAsString(fintResource) } returns serialized

        val event = service.createAndPublish(resourceName, fintResource, OperationType.CREATE)

        assertEquals(serialized, event.value)
    }

    @Test
    fun `createAndPublish with null resourceData bypasses converter and serializes null`() {
        val resourceName = "elevfravar"

        every { props.getLifeCycleConfig(resourceName) } returns LifeCycle(ttl = Duration.ofMinutes(2))
        every { objectMapper.writeValueAsString(null) } returns "null"

        val event = service.createAndPublish(resourceName, null, OperationType.CREATE)

        assertEquals("null", event.value)
        verify { resourceConverter wasNot called }
    }

    @Test
    fun `two separate publishes produce different corrIds`() {
        val resourceName = "elevfravar"
        val fintResource = mockk<FintResource>()

        every { resourceConverter.convertAndMapLinks(resourceName, any()) } returns fintResource
        every { props.getLifeCycleConfig(resourceName) } returns LifeCycle(ttl = Duration.ofMinutes(2))

        val first = service.createAndPublish(resourceName, fintResource, OperationType.CREATE)
        val second = service.createAndPublish(resourceName, fintResource, OperationType.CREATE)

        assertTrue(first.corrId != second.corrId)
    }
}
