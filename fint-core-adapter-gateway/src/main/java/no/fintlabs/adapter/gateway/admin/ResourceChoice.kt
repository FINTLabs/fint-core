package no.fintlabs.adapter.gateway.admin

import io.swagger.v3.oas.annotations.Parameter
import jakarta.validation.constraints.AssertTrue
import no.novari.fint.core.model.FintResourceRef

/**
 * Which resource types an admin job runs on, read from the `resource`, `component` and `all`
 * query parameters. Exactly one of them must be given.
 */
data class ResourceChoice(
    @field:Parameter(description = "One resource type", example = "utdanning/elev/person")
    val resource: FintResourceRef?,
    @field:Parameter(description = "Every resource type in one component", example = "utdanning/elev")
    val component: FintComponent?,
    @field:Parameter(description = "Every resource type the org has stored")
    val all: Boolean = false,
) {
    @get:AssertTrue(message = "Give resource, component or all, but only one of them")
    val isOneChosen: Boolean get() = listOf(resource != null, component != null, all).count { it } == 1

    /** The chosen types, asking [allStored] for them when [all] was chosen. */
    fun resources(allStored: () -> List<FintResourceRef>): List<FintResourceRef> =
        if (all) allStored() else listOfNotNull(resource) + component?.resources.orEmpty()
}
