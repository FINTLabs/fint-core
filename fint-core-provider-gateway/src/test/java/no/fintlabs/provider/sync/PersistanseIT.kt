package no.fintlabs.provider.sync

import no.fintlabs.adapter.models.sync.SyncPage
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.models.sync.SyncPageMetadata
import no.fintlabs.adapter.models.sync.SyncType
import no.fintlabs.provider.Application
import no.fintlabs.provider.KafkaContainerBaseIT
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.store.ResourceStore
import org.awaitility.kotlin.await
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Duration

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = [Application::class])
class PersistanseIT(
    @Autowired private val writer: BufferWriter,
    @Autowired private val resourceStore: ResourceStore,
    @Autowired private val mongoTemplate: MongoTemplate,
) : KafkaContainerBaseIT() {
    @MockitoSpyBean
    private lateinit var reader: BufferReader

    @Test
    fun `When BufferReader receives data, it saves to database`() {
        // Write to Buffer
        // verify that data is saved, after read
        val resource = SyncPageEntry.of("123", createElev())

        val resourceCoordinate =
            ResourceCoordinate(
                "fintlabs.no",
                "utdanning",
                "elev",
                "elev",
            )
        val sync =
            SyncPage(
                SyncPageMetadata(
                    "test",
                    "corr-id-random",
                    "fintlabs-no",
                    1L,
                    1L,
                    1L,
                    1L,
                    "beta.felleskomponent.no/utdanning/elev",
                    1782300748715L,
                ),
                listOf(resource),
                SyncType.FULL,
            )
        writer.sendSyncEntity(sync, resource, resourceCoordinate).get() // .get() because it is async

        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            val entry = resourceStore.findByResourceId("123", resourceCoordinate.toCollectionName())

            assertNotNull(entry)
            assertTrue(entry.identifiers.any { it.value == "123" })
        }
    }

    fun createElev(): Map<String, Any> =
        mapOf(
            "systemId" to identifikator("123"),
            "elevnummer" to identifikator("ELEV-123"),
            "brukernavn" to identifikator("ola.nordmann"),
            "feidenavn" to identifikator("ola.nordmann@fintlabs.no"),
            "gjest" to false,
            "hybeladresse" to
                mapOf(
                    "adresselinje" to listOf("Skoleveien 1", "Hybel 204"),
                    "postnummer" to "0123",
                    "poststed" to "Oslo",
                ),
            "kontaktinformasjon" to
                mapOf(
                    "epostadresse" to "ola.nordmann@example.no",
                    "mobiltelefonnummer" to "40000000",
                    "telefonnummer" to "22000000",
                    "nettsted" to "https://example.no/elev/123",
                ),
        )

    private fun identifikator(value: String) = mapOf("identifikatorverdi" to value)
}
