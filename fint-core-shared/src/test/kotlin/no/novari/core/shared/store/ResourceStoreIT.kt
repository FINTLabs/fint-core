package no.novari.core.shared.store

import com.mongodb.client.MongoClients
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.utdanning.elev.Elev
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import java.time.Instant

@Testcontainers
class ResourceStoreIT {
    private val store = ResourceStore(template, FintResourceBsonConverter())

    companion object {
        @Container
        val mongo = MongoDBContainer("mongo:7.0")

        private val collection = "test_org_no_utdanning_elev_elev"
        private val otherCollection = "other_org_no_utdanning_elev_elev"
        private val base = Instant.parse("2026-09-11T10:00:00Z")
        private val template by lazy { MongoTemplate(MongoClients.create(mongo.connectionString), "test") }
    }

    @BeforeEach
    fun dropCollections() {
        template.dropCollection(collection)
        template.dropCollection(otherCollection)
    }

    @Test
    fun `a delete older than the stored write leaves the document unchanged`() {
        store.saveAll(listOf(save("1", base)))

        store.applyAll(listOf(Delete("1", collection, base.minusSeconds(60))))

        val entry = store.findByResourceId("1", collection)
        assertThat(entry!!.lastModified).isEqualTo(base)
    }

    @Test
    fun `a delete newer than the stored write removes the document`() {
        store.saveAll(listOf(save("1", base)))

        store.applyAll(listOf(Delete("1", collection, base.plusSeconds(60))))

        assertThat(store.findByResourceId("1", collection)).isNull()
    }

    @Test
    fun `a delete with the same timestamp as the stored write removes the document`() {
        store.saveAll(listOf(save("1", base)))

        store.applyAll(listOf(Delete("1", collection, base)))

        assertThat(store.findByResourceId("1", collection)).isNull()
    }

    @Test
    fun `in one batch a newer save wins over an older delete listed after it`() {
        store.applyAll(
            listOf(
                save("1", base.plusSeconds(60)),
                Delete("1", collection, base),
            ),
        )

        val entry = store.findByResourceId("1", collection)
        assertThat(entry!!.lastModified).isEqualTo(base.plusSeconds(60))
    }

    @Test
    fun `in one batch a newer delete wins over an older save listed after it`() {
        store.applyAll(
            listOf(
                Delete("1", collection, base.plusSeconds(60)),
                save("1", base),
            ),
        )

        assertThat(store.findByResourceId("1", collection)).isNull()
    }

    @Test
    fun `in one batch two saves for the same id keep the newer one regardless of order`() {
        val newer =
            Elev(
                systemId = Identifikator(identifikatorverdi = "1"),
                elevnummer = Identifikator(identifikatorverdi = "E-1"),
            )

        store.applyAll(
            listOf(
                Save("1", collection, newer, base.plusSeconds(60)),
                save("1", base),
            ),
        )

        val entry = store.findByResourceId("1", collection)
        assertThat(entry!!.lastModified).isEqualTo(base.plusSeconds(60))
        assertThat(entry.identifiers).hasSize(2)
    }

    @Test
    fun `a delete for an id that was never stored does nothing`() {
        store.applyAll(listOf(Delete("missing", collection, base)))

        assertThat(template.getCollection(collection).countDocuments()).isZero()
    }

    @Test
    fun `a delete in one collection does not touch the same id in another collection`() {
        store.saveAll(listOf(save("1", base), Save("1", otherCollection, elev("1"), base)))

        store.applyAll(listOf(Delete("1", otherCollection, base.plusSeconds(60))))

        assertThat(store.findByResourceId("1", collection)).isNotNull()
        assertThat(store.findByResourceId("1", otherCollection)).isNull()
    }

    private fun save(
        id: String,
        timestamp: Instant,
    ) = Save(id, collection, elev(id), timestamp)

    private fun elev(id: String) = Elev(systemId = Identifikator(identifikatorverdi = id))
}
