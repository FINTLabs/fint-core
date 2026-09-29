package no.fintlabs.adapter.gateway.admin

import no.novari.fint.core.model.FintResourceRef

/** One component of the model, such as `utdanning/elev`, with every resource type it serves. */
data class FintComponent(
    val domainName: String,
    val packageName: String,
    val resources: List<FintResourceRef>,
)
