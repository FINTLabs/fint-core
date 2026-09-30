package no.fintlabs.adapter.gateway.config

import no.fintlabs.adapter.gateway.relation.ResourceSelection
import no.novari.fint.core.model.FintModel
import no.novari.fint.core.model.FintResourceRef
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceSelectionConverterTest {
    private val converter = ResourceSelectionConverter()

    @Test
    fun `all means every resource type the org has stored`() {
        assertEquals(ResourceSelection.AllStored, converter.convert("all"))
    }

    @Test
    fun `a resource path means that one resource type`() {
        assertEquals(
            ResourceSelection.Types(listOf(FintResourceRef("utdanning", "elev", "person"))),
            converter.convert("utdanning/elev/person"),
        )
    }

    @Test
    fun `an iso resource path means the type the model gives it`() {
        assertEquals(
            ResourceSelection.Types(listOf(FintResourceRef("felles", "kodeverk", "landkode"))),
            converter.convert("felles/kodeverk/iso/landkode"),
        )
    }

    @Test
    fun `a component path means every resource type in it, sorted by name`() {
        val selection = converter.convert("utdanning/elev") as ResourceSelection.Types

        assertEquals(FintModel.refsIn("utdanning", "elev").sortedBy { it.resourceName }, selection.resources)
        assertTrue(selection.resources.size > 1)
    }

    @Test
    fun `a resource path the model does not serve is refused with the path`() {
        val refused = assertThrows<IllegalArgumentException> { converter.convert("utdanning/elev/nothing") }

        assertEquals("Not a resource the model serves: utdanning/elev/nothing", refused.message)
    }

    @Test
    fun `a component path the model does not serve is refused with the path`() {
        val refused = assertThrows<IllegalArgumentException> { converter.convert("utdanning/nothing") }

        assertEquals("Not a component the model serves: utdanning/nothing", refused.message)
    }
}
