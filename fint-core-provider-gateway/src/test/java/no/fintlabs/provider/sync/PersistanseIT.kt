package no.fintlabs.provider.sync

import no.fintlabs.adapter.models.sync.SyncPage
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.models.sync.SyncPageMetadata
import no.fintlabs.adapter.models.sync.SyncType
import no.fintlabs.provider.Application
import no.fintlabs.provider.KafkaContainerBaseIT
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.store.ResourceEntry
import no.novari.core.shared.store.ResourceStore
import no.novari.fint.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.model.felles.kompleksedatatyper.Kontaktinformasjon
import no.novari.fint.model.resource.felles.kompleksedatatyper.AdresseResource
import no.novari.fint.model.resource.utdanning.elev.ElevResource
import org.awaitility.kotlin.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.mockito.kotlin.any
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.findAll
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
            val entry = resourceStore.read("123", resourceCoordinate)

            assertNotNull(entry)
            assertTrue(entry.identifiers.any { it.value == "123" })
        }
    }

    fun createElev() =
        ElevResource().apply {
            systemId = identifikator("123")
            elevnummer = identifikator("ELEV-123")
            brukernavn = identifikator("ola.nordmann")
            feidenavn = identifikator("ola.nordmann@fintlabs.no")
            gjest = false
            hybeladresse =
                AdresseResource().apply {
                    adresselinje = listOf("Skoleveien 1", "Hybel 204")
                    postnummer = "0123"
                    poststed = "Oslo"
                }
            kontaktinformasjon =
                Kontaktinformasjon().apply {
                    epostadresse = "ola.nordmann@example.no"
                    mobiltelefonnummer = "40000000"
                    telefonnummer = "22000000"
                    nettsted = "https://example.no/elev/123"
                }
        }

    private fun identifikator(value: String) =
        Identifikator().apply {
            identifikatorverdi = value
        }
}
