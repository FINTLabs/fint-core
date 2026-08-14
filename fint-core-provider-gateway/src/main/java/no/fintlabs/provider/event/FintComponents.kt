package no.fintlabs.provider.event

import no.novari.fint.core.model.FintModel

/**
 * Component ids come straight from the model reference: `utdanning-elev:Elev` belongs to
 * `utdanning-elev`. Refs without a dash are the shared `felles` bucket, which has no component
 * topics of its own.
 */
object FintComponents {
    val ids: List<String> =
        FintModel.resources
            .map { it.ref.substringBefore(':') }
            .filter { it.contains('-') }
            .distinct()
            .sorted()

    fun eventNames(eventType: String): Array<String> = ids.map { "$it-$eventType" }.toTypedArray()
}
