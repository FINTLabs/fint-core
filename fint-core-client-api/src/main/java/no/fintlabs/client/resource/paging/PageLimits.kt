package no.fintlabs.client.resource.paging

import no.novari.core.shared.model.ResourceCoordinate

/** The largest page a client can get of each resource. */
object PageLimits {
    /** The largest page of a resource that has no value of its own. */
    const val DEFAULT = 1000

    // The network gateway can damage a response body above 0.5 MB. A page of DEFAULT entries of
    // these resources is above that. The values are the first list in docs/page-limits.md.
    internal val maxSizes =
        mapOf(
            "administrasjon/fullmakt/rolle" to 1,
            "personvern/samtykke/behandling" to 1,
            "utdanning/utdanningsprogram/skole" to 1,
            "administrasjon/organisasjon/arbeidslokasjon" to 5,
            "utdanning/larling/virksomhet" to 10,
            "utdanning/utdanningsprogram/arstrinn" to 10,
            "utdanning/utdanningsprogram/utdanningsprogram" to 10,
            "administrasjon/organisasjon/organisasjonselement" to 50,
            "utdanning/timeplan/fag" to 50,
            "utdanning/elev/elevforhold" to 100,
            "utdanning/elev/klasse" to 100,
            "utdanning/elev/kontaktlarergruppe" to 100,
            "utdanning/kodeverk/karakterskala" to 100,
            "utdanning/timeplan/faggruppe" to 100,
            "utdanning/timeplan/undervisningsgruppe" to 100,
            "utdanning/vurdering/elevfravar" to 100,
            "administrasjon/personal/arbeidsforhold" to 250,
            "administrasjon/personal/fastlonn" to 250,
            "administrasjon/personal/fasttillegg" to 250,
            "administrasjon/personal/personalressurs" to 250,
            "administrasjon/personal/variabellonn" to 250,
            "ressurs/datautstyr/enhetsgruppe" to 250,
            "utdanning/vurdering/eksamensgruppe" to 250,
            "administrasjon/fullmakt/fullmakt" to 500,
            "administrasjon/personal/fravar" to 500,
            "administrasjon/personal/person" to 500,
            "personvern/kodeverk/behandlingsgrunnlag" to 500,
            "personvern/kodeverk/personopplysning" to 500,
            "personvern/samtykke/samtykke" to 500,
            "personvern/samtykke/tjeneste" to 500,
            "ressurs/datautstyr/digitalenhet" to 500,
            "utdanning/elev/elev" to 500,
            "utdanning/elev/person" to 500,
            "utdanning/elev/skoleressurs" to 500,
            "utdanning/elev/undervisningsforhold" to 500,
            "utdanning/larling/larling" to 500,
            "utdanning/utdanningsprogram/programomrade" to 500,
            "utdanning/utdanningsprogram/programomrademedlemskap" to 500,
            "utdanning/vurdering/elevvurdering" to 500,
            "utdanning/vurdering/fravarsregistrering" to 500,
            "utdanning/vurdering/halvarsfagvurdering" to 500,
            "utdanning/vurdering/sluttfagvurdering" to 500,
            "utdanning/vurdering/underveisfagvurdering" to 500,
        )

    fun maxSizeOf(coordinate: ResourceCoordinate): Int = maxSizes[coordinate.toResourceUri()] ?: DEFAULT
}
