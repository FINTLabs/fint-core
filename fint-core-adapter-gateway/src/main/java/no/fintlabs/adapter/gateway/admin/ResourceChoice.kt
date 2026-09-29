package no.fintlabs.adapter.gateway.admin

import io.swagger.v3.oas.annotations.Parameter
import jakarta.validation.constraints.AssertTrue
import no.novari.fint.core.model.FintResourceRef

/**
 * Which resource types an admin job runs on, read from the `resource` and `component` query
 * parameters. Exactly one of them must be given.
 */
data class ResourceChoice(
    @field:Parameter(description = "One resource type", example = "utdanning/elev/person")
    val resource: FintResourceRef?,
    @field:Parameter(description = "Every resource type in one component", example = "utdanning/elev")
    val component: FintComponent?,
) {
    @get:AssertTrue(message = "Give resource or component, but not both")
    val isOneChosen: Boolean get() = (resource == null) != (component == null)

    fun resources(): List<FintResourceRef> = listOfNotNull(resource) + component?.resources.orEmpty()
}
