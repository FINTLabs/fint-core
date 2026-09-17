package no.fintlabs.adapter.gateway.storage

import com.mongodb.client.MongoClients
import no.fintlabs.adapter.gateway.mongoTestContainer
import no.novari.core.shared.org.OrgStore
import no.novari.core.shared.relation.RelationEdgeStore
import no.novari.core.shared.store.FintResourceBsonConverter
import no.novari.core.shared.store.ResourceStore
import org.assertj.core.api.Assertions.assertThat
import org.bson.Document
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * Starts from collections that hold documents but no indexes, which is how a collection looks
 * when it was filled before an index was added to the code. Checks that the ensurer creates the
 * indexes on the org's resource and relation-edge collections and leaves every other collection
 * alone.
 */
@Testcontainers
class ResourceIndexEnsurerIT {
    companion object {
        @Container
        @JvmStatic
        val MONGO = mongoTestContainer()
    }

    private val template = MongoTemplate(MongoClients.create(MONGO.connectionString), "ensurer-it-${UUID.randomUUID()}")
    private val orgStore = OrgStore(template)
    private val ensurer =
        ResourceIndexEnsurer(
            template,
            orgStore,
            ResourceStore(template, FintResourceBsonConverter()),
            RelationEdgeStore(template),
        )

    @Test
    fun `creates the missing indexes on the existing collections of a registered org`() {
        orgStore.upsert("test.org.no")
        template.insert(Document("_id", "E-1"), "test_org_no_utdanning_elev_elev")
        template.insert(Document("_id", "edge-1"), "test_org_no_relation_edges")

        ensurer.ensureIndexes()

        assertThat(indexNames("test_org_no_utdanning_elev_elev"))
            .containsExactlyInAnyOrder("_id_", "last_modified", "created_at_id")
        assertThat(indexNames("test_org_no_relation_edges"))
            .containsExactlyInAnyOrder("_id_", "target_lookup", "source_lookup")
    }

    @Test
    fun `leaves collections that are not resource or edge collections alone`() {
        orgStore.upsert("test.org.no")
        template.insert(Document("_id", "x"), "test_org_no_events")
        template.insert(Document("_id", "x"), "cache_utdanning_elev_elev")
        template.insert(Document("_id", "x"), "other_org_no_utdanning_elev_elev")

        ensurer.ensureIndexes()

        assertThat(indexNames("test_org_no_events")).containsExactly("_id_")
        assertThat(indexNames("cache_utdanning_elev_elev")).containsExactly("_id_")
        assertThat(indexNames("other_org_no_utdanning_elev_elev")).containsExactly("_id_")
    }

    @Test
    fun `does not create collections that do not exist yet`() {
        orgStore.upsert("test.org.no")

        ensurer.ensureIndexes()

        assertThat(template.collectionNames).containsExactly(OrgStore.COLLECTION_NAME)
    }

    private fun indexNames(collectionName: String) = template.indexOps(collectionName).indexInfo.map { it.name }
}
