package no.novari.core.shared.store

import no.novari.fint.core.model.Link
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.utdanning.elev.Elev
import no.novari.fint.core.model.utdanning.elev.Elevforhold
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FintResourceBsonConverterTest {
    private val converter = FintResourceBsonConverter()

    @Test
    fun `a link to another resource gives another hash`() {
        val one = elevforhold().apply { addLink("elev", Link("elevnummer", "E-1")) }
        val other = elevforhold().apply { addLink("elev", Link("elevnummer", "E-2")) }

        assertNotEquals(converter.toStorageForm(one).contentHash, converter.toStorageForm(other).contentHash)
    }

    @Test
    fun `a changed field gives another hash`() {
        val one = Elev(systemId = Identifikator(identifikatorverdi = "1"))
        val other = Elev(systemId = Identifikator(identifikatorverdi = "1"), elevnummer = Identifikator(identifikatorverdi = "E-1"))

        assertNotEquals(converter.toStorageForm(one).contentHash, converter.toStorageForm(other).contentHash)
    }

    @Test
    fun `the hash is 32 bytes`() {
        assertEquals(
            32,
            converter
                .toStorageForm(elevforhold())
                .contentHash.data.size,
        )
    }

    private fun elevforhold() = Elevforhold(systemId = Identifikator(identifikatorverdi = "EF-1"))
}
