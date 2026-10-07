package no.novari.core.shared.event

import com.mongodb.client.MongoClients
import no.fintlabs.adapter.operation.OperationType
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.resourceRefOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import java.time.Instant

@Testcontainers
class EventCapabilityStoreIT {
    private val store = EventCapabilityStore(template)
    private val orgId = OrgId.from("fintlabs.no")
    private val elevfravar = resourceRefOf("utdanning", "vurdering", "elevfravar")

    companion object {
        @Container
        val mongo = MongoDBContainer("mongo:8.0.4")

        private val template by lazy { MongoTemplate(MongoClients.create(mongo.connectionString), "test") }
    }

    @BeforeEach
    fun dropCollection() {
        template.dropCollection(EventCapabilityStore.COLLECTION_NAME)
    }

    @Test
    fun `an org without a document cannot read anything live`() {
        assertThat(store.find(orgId)).isNull()
    }

    @Test
    fun `the document comes back as it was saved`() {
        val saved =
            OrgEventCapabilities(
                orgId = orgId.value,
                resources = listOf(ResourceOperations.of(elevfravar, setOf(OperationType.READ, OperationType.CREATE))),
                updatedAt = Instant.parse("2026-10-07T08:12:00Z"),
            )

        store.save(saved)

        val found = store.find(orgId)!!
        assertThat(found).isEqualTo(saved)
        assertThat(found.canRead(elevfravar)).isTrue()
        assertThat(found.canRead(resourceRefOf("utdanning", "elev", "elev"))).isFalse()
    }

    @Test
    fun `saving again replaces the org's document`() {
        store.save(OrgEventCapabilities(orgId.value, listOf(ResourceOperations.of(elevfravar, setOf(OperationType.READ))), Instant.now()))
        store.save(OrgEventCapabilities(orgId.value, emptyList(), Instant.now()))

        assertThat(store.find(orgId)!!.resources).isEmpty()
        assertThat(template.count(Query(), EventCapabilityStore.COLLECTION_NAME)).isEqualTo(1)
    }
}
