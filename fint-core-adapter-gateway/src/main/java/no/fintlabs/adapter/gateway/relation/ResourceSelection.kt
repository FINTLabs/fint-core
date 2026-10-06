package no.fintlabs.adapter.gateway.relation

import no.novari.fint.core.model.FintResourceRef

/** What an admin job was asked to run on: one resource, every resource in a component, or every resource the org has stored. */
sealed interface ResourceSelection {
    data class Resource(
        val ref: FintResourceRef,
    ) : ResourceSelection

    data class Component(
        val domainName: String,
        val packageName: String,
    ) : ResourceSelection

    data object All : ResourceSelection
}
