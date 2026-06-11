package no.novari.fint.core.synthetic.config

/**
 * Built-in datasets with real production counts (per-resource max of the Core 1/Core 2 cache
 * sizes, Grafana snapshot 2026-06-10). [withDefaults] layers YAML overrides on top per resource
 * key, so config only needs to state what differs.
 */
object Datasets {
    val utdanning: Map<String, ResourceSpec> =
        linkedMapOf(
            "utdanning-elev-elev" to ResourceSpec(count = 39511),
            "utdanning-elev-elevforhold" to ResourceSpec(count = 44322),
            "utdanning-elev-klasse" to ResourceSpec(count = 1960),
            "utdanning-elev-klassemedlemskap" to ResourceSpec(count = 35374),
            "utdanning-elev-kontaktlarergruppe" to ResourceSpec(count = 2392),
            "utdanning-elev-kontaktlarergruppemedlemskap" to ResourceSpec(count = 35374),
            "utdanning-elev-kontaktperson" to ResourceSpec(count = 46709),
            "utdanning-elev-person" to ResourceSpec(count = 71038),
            "utdanning-elev-skoleressurs" to ResourceSpec(count = 7709),
            "utdanning-elev-undervisningsforhold" to ResourceSpec(count = 16083),
            "utdanning-kodeverk-eksamensform" to ResourceSpec(count = 7, fixed = true),
            "utdanning-kodeverk-elevkategori" to ResourceSpec(count = 3, fixed = true),
            "utdanning-kodeverk-fravarstype" to ResourceSpec(count = 6, fixed = true),
            "utdanning-kodeverk-karakterskala" to ResourceSpec(count = 17, fixed = true),
            "utdanning-kodeverk-skolear" to ResourceSpec(count = 41, fixed = true),
            "utdanning-kodeverk-termin" to ResourceSpec(count = 82, fixed = true),
            "utdanning-larling-larling" to ResourceSpec(count = 3252),
            "utdanning-larling-person" to ResourceSpec(count = 3252),
            "utdanning-larling-virksomhet" to ResourceSpec(count = 304),
            "utdanning-timeplan-fag" to ResourceSpec(count = 10679),
            "utdanning-timeplan-faggruppe" to ResourceSpec(count = 13610),
            "utdanning-timeplan-faggruppemedlemskap" to ResourceSpec(count = 263748),
            "utdanning-timeplan-undervisningsgruppe" to ResourceSpec(count = 12555),
            "utdanning-timeplan-undervisningsgruppemedlemskap" to ResourceSpec(count = 272891),
            "utdanning-utdanningsprogram-arstrinn" to ResourceSpec(count = 7, fixed = true),
            "utdanning-utdanningsprogram-programomrade" to ResourceSpec(count = 4701),
            "utdanning-utdanningsprogram-programomrademedlemskap" to ResourceSpec(count = 46356),
            "utdanning-utdanningsprogram-skole" to ResourceSpec(count = 41, fixed = true),
            "utdanning-utdanningsprogram-utdanningsprogram" to ResourceSpec(count = 22, fixed = true),
            "utdanning-vurdering-eksamensgruppe" to ResourceSpec(count = 8521),
            "utdanning-vurdering-eksamensgruppemedlemskap" to ResourceSpec(count = 55413),
            "utdanning-vurdering-elevfravar" to ResourceSpec(count = 35576),
            "utdanning-vurdering-elevvurdering" to ResourceSpec(count = 44270),
            "utdanning-vurdering-fravarsoversikt" to ResourceSpec(count = 166448),
            "utdanning-vurdering-fravarsregistrering" to ResourceSpec(count = 2945297),
            "utdanning-vurdering-halvarsfagvurdering" to ResourceSpec(count = 260533),
            "utdanning-vurdering-karakterverdi" to ResourceSpec(count = 213),
            "utdanning-vurdering-sluttfagvurdering" to ResourceSpec(count = 179806),
            "utdanning-vurdering-underveisfagvurdering" to ResourceSpec(count = 762729),
        )

    fun withDefaults(overrides: Map<String, ResourceSpec>): Map<String, ResourceSpec> = LinkedHashMap(utdanning).apply { putAll(overrides) }
}
