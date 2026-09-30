package no.fintlabs.adapter.gateway.relation

import no.novari.fint.core.model.FintResourceRef

/** Which resource types a job runs on: a fixed list, or every type the org has stored. */
sealed interface ResourceSelection {
    data class Types(
        val resources: List<FintResourceRef>,
    ) : ResourceSelection

    data object AllStored : ResourceSelection
}
