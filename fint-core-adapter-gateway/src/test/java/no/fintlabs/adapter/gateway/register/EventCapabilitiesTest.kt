package no.fintlabs.adapter.gateway.register

import no.fintlabs.adapter.operation.OperationType
import no.novari.core.shared.model.resourceRefOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EventCapabilitiesTest {
    private val elevfravar = resourceRefOf("utdanning", "vurdering", "elevfravar")
    private val fravar = resourceRefOf("utdanning", "vurdering", "fravar")

    private val capabilities =
        EventCapabilities(
            mapOf(
                elevfravar to setOf(OperationType.READ),
                fravar to setOf(OperationType.CREATE, OperationType.VALIDATE),
            ),
        )

    @Test
    fun `a listed resource is covered for exactly the operations it lists`() {
        assertThat(capabilities.covers(elevfravar, OperationType.READ)).isTrue()
        assertThat(capabilities.covers(elevfravar, OperationType.CREATE)).isFalse()
        assertThat(capabilities.covers(elevfravar, OperationType.UPDATE)).isFalse()

        assertThat(capabilities.covers(fravar, OperationType.CREATE)).isTrue()
        assertThat(capabilities.covers(fravar, OperationType.VALIDATE)).isTrue()
        assertThat(capabilities.covers(fravar, OperationType.READ)).isFalse()
    }

    @Test
    fun `a resource that is not listed is covered for every operation except READ`() {
        val karakter = resourceRefOf("utdanning", "vurdering", "karakter")

        assertThat(capabilities.operationsFor(karakter))
            .containsExactlyInAnyOrder(OperationType.CREATE, OperationType.UPDATE, OperationType.VALIDATE, OperationType.DELETE)
        assertThat(capabilities.covers(karakter, OperationType.READ)).isFalse()
    }

    @Test
    fun `a contract without event capabilities behaves like before READ existed`() {
        assertThat(EventCapabilities.NONE.covers(elevfravar, OperationType.CREATE)).isTrue()
        assertThat(EventCapabilities.NONE.covers(elevfravar, OperationType.READ)).isFalse()
    }

    @Test
    fun `two capability lists with the same content are equal`() {
        assertThat(EventCapabilities(mapOf(elevfravar to setOf(OperationType.READ))))
            .isEqualTo(EventCapabilities(mapOf(elevfravar to setOf(OperationType.READ))))
    }
}
