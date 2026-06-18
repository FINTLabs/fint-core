package no.novari.core.shared

import no.novari.fint.model.FintIdentifikator
import no.novari.fint.model.resource.FintResource

fun FintResource.nonNullIdentifikators(): Map<String, FintIdentifikator> =
    identifikators
        .orEmpty()
        .mapNotNull { (field, identifikator) ->
            identifikator?.let { field to it }
        }.toMap()
