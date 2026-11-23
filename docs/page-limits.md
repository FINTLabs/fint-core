# Page limits

This page proposes a page size for each resource in the client-api.

The client-api does not enforce these page sizes today. A read without `size` returns the full collection, and
`size` has no maximum. The team has not agreed on the values.

The page has two lists.

- [With the network gateway](#with-the-network-gateway) is the list for today. Each page is at most 0.5 MB.
- [Without the gateway problem](#without-the-gateway-problem) is the list we recommend when the gateway problem
  is gone.

## Why the gateway needs small pages

A client request goes through a network gateway before it reaches the client-api.
We have seen that a large response body can arrive damaged when it goes through the gateway.
The client then gets a body that is cut off or that it cannot parse.

Our tests show that the largest safe size is about 0.5 MB.
A page of 1,000 entries of `utdanning/timeplan/undervisningsgruppemedlemskap` is 0.50 MB, and it went through the
gateway with a correct body.

## With the network gateway

A page is at most 0.5 MB and at most 1,000 entries.

| Resource | Page size | Page |
|---|---|---|
| `administrasjon/fullmakt/fullmakt` | 500 | 0.27 MB |
| `administrasjon/fullmakt/rolle` | 1 | 0.26 MB \* |
| `administrasjon/kodeverk/ansvar` | 1,000 | 0.37 MB |
| `administrasjon/kodeverk/arbeidsforholdstype` | 1,000 | 0.23 MB |
| `administrasjon/kodeverk/art` | 1,000 | 0.22 MB |
| `administrasjon/kodeverk/fravarstype` | 1,000 | 0.24 MB |
| `administrasjon/kodeverk/funksjon` | 1,000 | 0.23 MB |
| `administrasjon/kodeverk/lonnsart` | 1,000 | 0.31 MB |
| `administrasjon/kodeverk/organisasjonstype` | 1,000 | 0.23 MB |
| `administrasjon/kodeverk/personalressurskategori` | 1,000 | 0.22 MB |
| `administrasjon/kodeverk/prosjekt` | 1,000 | 0.31 MB |
| `administrasjon/kodeverk/stillingskode` | 1,000 | 0.29 MB |
| `administrasjon/kodeverk/uketimetall` | 1,000 | 0.21 MB |
| `administrasjon/organisasjon/arbeidslokasjon` | 5 | 0.34 MB \* |
| `administrasjon/organisasjon/organisasjonselement` | 50 | 0.47 MB \* |
| `administrasjon/personal/arbeidsforhold` | 250 | 0.37 MB |
| `administrasjon/personal/fastlonn` | 250 | 0.32 MB |
| `administrasjon/personal/fasttillegg` | 250 | 0.32 MB |
| `administrasjon/personal/fravar` | 500 | 0.38 MB |
| `administrasjon/personal/person` | 500 | 0.29 MB |
| `administrasjon/personal/personalressurs` | 250 | 0.33 MB |
| `administrasjon/personal/variabellonn` | 250 | 0.32 MB |
| `personvern/kodeverk/behandlingsgrunnlag` | 500 | 0.35 MB \* |
| `personvern/kodeverk/personopplysning` | 500 | 0.35 MB \* |
| `personvern/samtykke/behandling` | 1 | 0.65 MB \* |
| `personvern/samtykke/samtykke` | 500 | 0.35 MB \* |
| `personvern/samtykke/tjeneste` | 500 | 0.40 MB \* |
| `ressurs/datautstyr/digitalenhet` | 500 | 0.35 MB \* |
| `ressurs/datautstyr/enhetsgruppe` | 250 | 0.48 MB \* |
| `ressurs/datautstyr/enhetsgruppemedlemskap` | 1,000 | 0.30 MB \* |
| `ressurs/kodeverk/enhetstype` | 1,000 | 0.20 MB \* |
| `ressurs/kodeverk/plattform` | 1,000 | 0.20 MB \* |
| `ressurs/kodeverk/status` | 1,000 | 0.20 MB \* |
| `utdanning/elev/elev` | 500 | 0.42 MB |
| `utdanning/elev/elevforhold` | 100 | 0.26 MB |
| `utdanning/elev/klasse` | 100 | 0.30 MB \* |
| `utdanning/elev/klassemedlemskap` | 1,000 | 0.37 MB |
| `utdanning/elev/kontaktlarergruppe` | 100 | 0.26 MB |
| `utdanning/elev/kontaktlarergruppemedlemskap` | 1,000 | 0.43 MB |
| `utdanning/elev/kontaktperson` | 1,000 | 0.49 MB |
| `utdanning/elev/person` | 500 | 0.37 MB |
| `utdanning/elev/skoleressurs` | 500 | 0.43 MB |
| `utdanning/elev/undervisningsforhold` | 500 | 0.44 MB |
| `utdanning/kodeverk/eksamensform` | 1,000 | 0.23 MB |
| `utdanning/kodeverk/elevkategori` | 1,000 | 0.19 MB |
| `utdanning/kodeverk/fravarstype` | 1,000 | 0.26 MB |
| `utdanning/kodeverk/karakterskala` | 100 | 0.41 MB \* |
| `utdanning/kodeverk/otenhet` | 1,000 | 0.18 MB |
| `utdanning/kodeverk/otstatus` | 1,000 | 0.28 MB |
| `utdanning/kodeverk/skolear` | 1,000 | 0.28 MB |
| `utdanning/kodeverk/termin` | 1,000 | 0.26 MB |
| `utdanning/larling/larling` | 500 | 0.29 MB |
| `utdanning/larling/person` | 1,000 | 0.46 MB |
| `utdanning/larling/virksomhet` | 10 | 0.30 MB \* |
| `utdanning/ot/otungdom` | 1,000 | 0.44 MB |
| `utdanning/ot/person` | 1,000 | 0.45 MB |
| `utdanning/timeplan/fag` | 50 | 0.26 MB \* |
| `utdanning/timeplan/faggruppe` | 100 | 0.25 MB \* |
| `utdanning/timeplan/faggruppemedlemskap` | 1,000 | 0.47 MB |
| `utdanning/timeplan/undervisningsgruppe` | 100 | 0.29 MB \* |
| `utdanning/timeplan/undervisningsgruppemedlemskap` | 1,000 | 0.50 MB |
| `utdanning/utdanningsprogram/arstrinn` | 10 | 0.31 MB \* |
| `utdanning/utdanningsprogram/programomrade` | 500 | 0.30 MB |
| `utdanning/utdanningsprogram/programomrademedlemskap` | 500 | 0.26 MB |
| `utdanning/utdanningsprogram/skole` | 1 | 1.20 MB \* |
| `utdanning/utdanningsprogram/utdanningsprogram` | 10 | 0.30 MB \* |
| `utdanning/vurdering/eksamensgruppe` | 250 | 0.30 MB |
| `utdanning/vurdering/eksamensgruppemedlemskap` | 1,000 | 0.44 MB |
| `utdanning/vurdering/elevfravar` | 100 | 0.35 MB \* |
| `utdanning/vurdering/elevvurdering` | 500 | 0.36 MB |
| `utdanning/vurdering/fravarsregistrering` | 500 | 0.38 MB |
| `utdanning/vurdering/halvarsfagvurdering` | 500 | 0.32 MB |
| `utdanning/vurdering/karakterverdi` | 1,000 | 0.36 MB |
| `utdanning/vurdering/sluttfagvurdering` | 500 | 0.31 MB |
| `utdanning/vurdering/underveisfagvurdering` | 500 | 0.32 MB |

Two resources cannot follow the rule. One entry of `utdanning/utdanningsprogram/skole` is about 1.2 MB, and one
entry of `personvern/samtykke/behandling` is about 0.65 MB. Paging cannot make one entry smaller.

## Without the gateway problem

A page is at most 2 MB and at most 1,000 entries.

| Resource | Page size | Page |
|---|---|---|
| `administrasjon/fullmakt/fullmakt` | 1,000 | 0.54 MB |
| `administrasjon/fullmakt/rolle` | 5 | 1.32 MB \* |
| `administrasjon/kodeverk/ansvar` | 1,000 | 0.37 MB |
| `administrasjon/kodeverk/arbeidsforholdstype` | 1,000 | 0.23 MB |
| `administrasjon/kodeverk/art` | 1,000 | 0.22 MB |
| `administrasjon/kodeverk/fravarstype` | 1,000 | 0.24 MB |
| `administrasjon/kodeverk/funksjon` | 1,000 | 0.23 MB |
| `administrasjon/kodeverk/lonnsart` | 1,000 | 0.31 MB |
| `administrasjon/kodeverk/organisasjonstype` | 1,000 | 0.23 MB |
| `administrasjon/kodeverk/personalressurskategori` | 1,000 | 0.22 MB |
| `administrasjon/kodeverk/prosjekt` | 1,000 | 0.31 MB |
| `administrasjon/kodeverk/stillingskode` | 1,000 | 0.29 MB |
| `administrasjon/kodeverk/uketimetall` | 1,000 | 0.21 MB |
| `administrasjon/organisasjon/arbeidslokasjon` | 25 | 1.68 MB \* |
| `administrasjon/organisasjon/organisasjonselement` | 100 | 0.94 MB \* |
| `administrasjon/personal/arbeidsforhold` | 1,000 | 1.50 MB |
| `administrasjon/personal/fastlonn` | 1,000 | 1.29 MB |
| `administrasjon/personal/fasttillegg` | 1,000 | 1.28 MB |
| `administrasjon/personal/fravar` | 1,000 | 0.76 MB |
| `administrasjon/personal/person` | 1,000 | 0.57 MB |
| `administrasjon/personal/personalressurs` | 1,000 | 1.30 MB |
| `administrasjon/personal/variabellonn` | 1,000 | 1.27 MB |
| `personvern/kodeverk/behandlingsgrunnlag` | 1,000 | 0.70 MB \* |
| `personvern/kodeverk/personopplysning` | 1,000 | 0.70 MB \* |
| `personvern/samtykke/behandling` | 2 | 1.30 MB \* |
| `personvern/samtykke/samtykke` | 1,000 | 0.70 MB \* |
| `personvern/samtykke/tjeneste` | 1,000 | 0.80 MB \* |
| `ressurs/datautstyr/digitalenhet` | 1,000 | 0.70 MB \* |
| `ressurs/datautstyr/enhetsgruppe` | 1,000 | 1.90 MB \* |
| `ressurs/datautstyr/enhetsgruppemedlemskap` | 1,000 | 0.30 MB \* |
| `ressurs/kodeverk/enhetstype` | 1,000 | 0.20 MB \* |
| `ressurs/kodeverk/plattform` | 1,000 | 0.20 MB \* |
| `ressurs/kodeverk/status` | 1,000 | 0.20 MB \* |
| `utdanning/elev/elev` | 1,000 | 0.83 MB |
| `utdanning/elev/elevforhold` | 500 | 1.28 MB |
| `utdanning/elev/klasse` | 500 | 1.50 MB \* |
| `utdanning/elev/klassemedlemskap` | 1,000 | 0.37 MB |
| `utdanning/elev/kontaktlarergruppe` | 500 | 1.30 MB |
| `utdanning/elev/kontaktlarergruppemedlemskap` | 1,000 | 0.43 MB |
| `utdanning/elev/kontaktperson` | 1,000 | 0.49 MB |
| `utdanning/elev/person` | 1,000 | 0.74 MB |
| `utdanning/elev/skoleressurs` | 1,000 | 0.86 MB |
| `utdanning/elev/undervisningsforhold` | 1,000 | 0.87 MB |
| `utdanning/kodeverk/eksamensform` | 1,000 | 0.23 MB |
| `utdanning/kodeverk/elevkategori` | 1,000 | 0.19 MB |
| `utdanning/kodeverk/fravarstype` | 1,000 | 0.26 MB |
| `utdanning/kodeverk/karakterskala` | 250 | 1.03 MB \* |
| `utdanning/kodeverk/otenhet` | 1,000 | 0.18 MB |
| `utdanning/kodeverk/otstatus` | 1,000 | 0.28 MB |
| `utdanning/kodeverk/skolear` | 1,000 | 0.28 MB |
| `utdanning/kodeverk/termin` | 1,000 | 0.26 MB |
| `utdanning/larling/larling` | 1,000 | 0.59 MB |
| `utdanning/larling/person` | 1,000 | 0.46 MB |
| `utdanning/larling/virksomhet` | 50 | 1.50 MB \* |
| `utdanning/ot/otungdom` | 1,000 | 0.44 MB |
| `utdanning/ot/person` | 1,000 | 0.45 MB |
| `utdanning/timeplan/fag` | 250 | 1.28 MB \* |
| `utdanning/timeplan/faggruppe` | 500 | 1.25 MB \* |
| `utdanning/timeplan/faggruppemedlemskap` | 1,000 | 0.47 MB |
| `utdanning/timeplan/undervisningsgruppe` | 500 | 1.45 MB \* |
| `utdanning/timeplan/undervisningsgruppemedlemskap` | 1,000 | 0.50 MB |
| `utdanning/utdanningsprogram/arstrinn` | 50 | 1.55 MB \* |
| `utdanning/utdanningsprogram/programomrade` | 1,000 | 0.60 MB |
| `utdanning/utdanningsprogram/programomrademedlemskap` | 1,000 | 0.51 MB |
| `utdanning/utdanningsprogram/skole` | 1 | 1.20 MB \* |
| `utdanning/utdanningsprogram/utdanningsprogram` | 50 | 1.50 MB \* |
| `utdanning/vurdering/eksamensgruppe` | 1,000 | 1.19 MB |
| `utdanning/vurdering/eksamensgruppemedlemskap` | 1,000 | 0.44 MB |
| `utdanning/vurdering/elevfravar` | 500 | 1.75 MB \* |
| `utdanning/vurdering/elevvurdering` | 1,000 | 0.71 MB |
| `utdanning/vurdering/fravarsregistrering` | 1,000 | 0.76 MB |
| `utdanning/vurdering/halvarsfagvurdering` | 1,000 | 0.63 MB |
| `utdanning/vurdering/karakterverdi` | 1,000 | 0.36 MB |
| `utdanning/vurdering/sluttfagvurdering` | 1,000 | 0.63 MB |
| `utdanning/vurdering/underveisfagvurdering` | 1,000 | 0.65 MB |

## How we got the numbers

- **Page** is the body of a full page. It is the page size multiplied by the bytes of one entry.
  A page holds fewer entries when the collection is small.
- **Page size** is rounded down to 1, 2, 5, 10, 25, 50, 100, 250, 500 or 1,000.
- **Measured values.** On 23 November 2026 we read sample pages of 64 resources from the client-api in production,
  for one county. The samples came from the start, the middle and the end of each collection.
- **Values with `*`** are estimates. An estimate is the bytes of the entity as the adapter sent it, plus 90 bytes
  for each link that the client-api adds.
- **Entries that differ between counties.** For 16 resources, other entries link to each entry, and the number of
  these links differs much between counties. For 13 of them the estimate for the county with the largest entries
  is larger than the measured value, and the table uses that estimate.
- **Resources that we did not measure.** 11 resources had no data in that county, or our client had no access to
  them. The table uses the estimate for them.
- A page can be larger than the table shows when it holds an entry that is much larger than the other entries.
