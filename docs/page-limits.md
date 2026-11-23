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
The table starts with the smallest page size.

**Requests** and **Time** show a read of the full collection, in the county that has the most entries of that
resource. The sum for all 75 resources is 5,282 requests and about 8 minutes.

| Resource | Page size | Page | Requests | Time |
|---|---|---|---|---|
| `administrasjon/fullmakt/rolle` | 1 | 0.26 MB \* | 2 | 47 ms |
| `personvern/samtykke/behandling` | 1 | 0.65 MB \* | 1 | 82 ms |
| `utdanning/utdanningsprogram/skole` | 1 | 1.20 MB \* | 54 | 39 s |
| `administrasjon/organisasjon/arbeidslokasjon` | 5 | 0.34 MB \* | 32 | 820 ms |
| `utdanning/larling/virksomhet` | 10 | 0.30 MB \* | 37 | 730 ms |
| `utdanning/utdanningsprogram/arstrinn` | 10 | 0.31 MB \* | 1 | 25 ms |
| `utdanning/utdanningsprogram/utdanningsprogram` | 10 | 0.30 MB \* | 3 | 150 ms |
| `administrasjon/organisasjon/organisasjonselement` | 50 | 0.47 MB \* | 15 | 900 ms |
| `utdanning/timeplan/fag` | 50 | 0.26 MB \* | 218 | 5.0 s |
| `utdanning/elev/elevforhold` | 100 | 0.26 MB | 418 | 32 s |
| `utdanning/elev/klasse` | 100 | 0.30 MB \* | 21 | 1.9 s |
| `utdanning/elev/kontaktlarergruppe` | 100 | 0.26 MB | 22 | 1.8 s |
| `utdanning/kodeverk/karakterskala` | 100 | 0.41 MB \* | 1 | 64 ms |
| `utdanning/timeplan/faggruppe` | 100 | 0.25 MB \* | 141 | 9.0 s |
| `utdanning/timeplan/undervisningsgruppe` | 100 | 0.29 MB \* | 130 | 8.3 s |
| `utdanning/vurdering/elevfravar` | 100 | 0.35 MB \* | 361 | 47 s |
| `administrasjon/personal/arbeidsforhold` | 250 | 0.37 MB | 95 | 4.5 s |
| `administrasjon/personal/fastlonn` | 250 | 0.32 MB | 1 | 180 ms |
| `administrasjon/personal/fasttillegg` | 250 | 0.32 MB | 1 | 25 ms |
| `administrasjon/personal/personalressurs` | 250 | 0.33 MB | 39 | 4.0 s |
| `administrasjon/personal/variabellonn` | 250 | 0.32 MB | 3 | 160 ms |
| `ressurs/datautstyr/enhetsgruppe` | 250 | 0.48 MB \* | 1 | 25 ms |
| `utdanning/vurdering/eksamensgruppe` | 250 | 0.30 MB | 13 | 2.4 s |
| `administrasjon/fullmakt/fullmakt` | 500 | 0.27 MB | 7 | 440 ms |
| `administrasjon/personal/fravar` | 500 | 0.38 MB | 1 | 33 ms |
| `administrasjon/personal/person` | 500 | 0.29 MB | 20 | 1.4 s |
| `personvern/kodeverk/behandlingsgrunnlag` | 500 | 0.35 MB \* | 1 | 16 ms |
| `personvern/kodeverk/personopplysning` | 500 | 0.35 MB \* | 1 | 15 ms |
| `personvern/samtykke/samtykke` | 500 | 0.35 MB \* | 15 | 1.1 s |
| `personvern/samtykke/tjeneste` | 500 | 0.40 MB \* | 1 | 18 ms |
| `ressurs/datautstyr/digitalenhet` | 500 | 0.35 MB \* | 1 | 160 ms |
| `utdanning/elev/elev` | 500 | 0.42 MB | 77 | 13 s |
| `utdanning/elev/person` | 500 | 0.37 MB | 154 | 16 s |
| `utdanning/elev/skoleressurs` | 500 | 0.43 MB | 15 | 2.8 s |
| `utdanning/elev/undervisningsforhold` | 500 | 0.44 MB | 17 | 4.3 s |
| `utdanning/larling/larling` | 500 | 0.29 MB | 15 | 990 ms |
| `utdanning/utdanningsprogram/programomrade` | 500 | 0.30 MB | 10 | 3.6 s |
| `utdanning/utdanningsprogram/programomrademedlemskap` | 500 | 0.26 MB | 76 | 5.2 s |
| `utdanning/vurdering/elevvurdering` | 500 | 0.36 MB | 84 | 15 s |
| `utdanning/vurdering/fravarsregistrering` | 500 | 0.38 MB | 2,147 | 2.5 min |
| `utdanning/vurdering/halvarsfagvurdering` | 500 | 0.32 MB | 24 | 1.6 s |
| `utdanning/vurdering/sluttfagvurdering` | 500 | 0.31 MB | 18 | 1.2 s |
| `utdanning/vurdering/underveisfagvurdering` | 500 | 0.32 MB | 322 | 22 s |
| `administrasjon/kodeverk/ansvar` | 1,000 | 0.37 MB | 1 | 100 ms |
| `administrasjon/kodeverk/arbeidsforholdstype` | 1,000 | 0.23 MB | 1 | 21 ms |
| `administrasjon/kodeverk/art` | 1,000 | 0.22 MB | 1 | 51 ms |
| `administrasjon/kodeverk/fravarstype` | 1,000 | 0.24 MB | 1 | 26 ms |
| `administrasjon/kodeverk/funksjon` | 1,000 | 0.23 MB | 1 | 76 ms |
| `administrasjon/kodeverk/lonnsart` | 1,000 | 0.31 MB | 1 | 100 ms |
| `administrasjon/kodeverk/organisasjonstype` | 1,000 | 0.23 MB | 1 | 21 ms |
| `administrasjon/kodeverk/personalressurskategori` | 1,000 | 0.22 MB | 1 | 20 ms |
| `administrasjon/kodeverk/prosjekt` | 1,000 | 0.31 MB | 5 | 540 ms |
| `administrasjon/kodeverk/stillingskode` | 1,000 | 0.29 MB | 1 | 40 ms |
| `administrasjon/kodeverk/uketimetall` | 1,000 | 0.21 MB | 1 | 20 ms |
| `ressurs/datautstyr/enhetsgruppemedlemskap` | 1,000 | 0.30 MB \* | 1 | 28 ms |
| `ressurs/kodeverk/enhetstype` | 1,000 | 0.20 MB \* | 1 | 21 ms |
| `ressurs/kodeverk/plattform` | 1,000 | 0.20 MB \* | 1 | 22 ms |
| `ressurs/kodeverk/status` | 1,000 | 0.20 MB \* | 1 | 23 ms |
| `utdanning/elev/klassemedlemskap` | 1,000 | 0.37 MB | 36 | 4.9 s |
| `utdanning/elev/kontaktlarergruppemedlemskap` | 1,000 | 0.43 MB | 34 | 4.2 s |
| `utdanning/elev/kontaktperson` | 1,000 | 0.49 MB | 33 | 4.1 s |
| `utdanning/kodeverk/eksamensform` | 1,000 | 0.23 MB | 1 | 19 ms |
| `utdanning/kodeverk/elevkategori` | 1,000 | 0.19 MB | 1 | 19 ms |
| `utdanning/kodeverk/fravarstype` | 1,000 | 0.26 MB | 1 | 19 ms |
| `utdanning/kodeverk/otenhet` | 1,000 | 0.18 MB | 1 | 21 ms |
| `utdanning/kodeverk/otstatus` | 1,000 | 0.28 MB | 1 | 22 ms |
| `utdanning/kodeverk/skolear` | 1,000 | 0.28 MB | 1 | 24 ms |
| `utdanning/kodeverk/termin` | 1,000 | 0.26 MB | 1 | 28 ms |
| `utdanning/larling/person` | 1,000 | 0.46 MB | 8 | 2.2 s |
| `utdanning/ot/otungdom` | 1,000 | 0.44 MB | 6 | 750 ms |
| `utdanning/ot/person` | 1,000 | 0.45 MB | 6 | 710 ms |
| `utdanning/timeplan/faggruppemedlemskap` | 1,000 | 0.47 MB | 262 | 32 s |
| `utdanning/timeplan/undervisningsgruppemedlemskap` | 1,000 | 0.50 MB | 236 | 29 s |
| `utdanning/vurdering/eksamensgruppemedlemskap` | 1,000 | 0.44 MB | 17 | 2.0 s |
| `utdanning/vurdering/karakterverdi` | 1,000 | 0.36 MB | 2 | 150 ms |

Two resources cannot follow the rule. One entry of `utdanning/utdanningsprogram/skole` is about 1.2 MB, and one
entry of `personvern/samtykke/behandling` is about 0.65 MB. Paging cannot make one entry smaller.

## Without the gateway problem

A page is at most 2 MB and at most 1,000 entries.
The table starts with the smallest page size.

**Requests** and **Time** show the same read as in the first list.
The sum for all 75 resources is 2,565 requests and about 7 minutes.

| Resource | Page size | Page | Requests | Time |
|---|---|---|---|---|
| `utdanning/utdanningsprogram/skole` | 1 | 1.20 MB \* | 54 | 39 s |
| `personvern/samtykke/behandling` | 2 | 1.30 MB \* | 1 | 82 ms |
| `administrasjon/fullmakt/rolle` | 5 | 1.32 MB \* | 1 | 32 ms |
| `administrasjon/organisasjon/arbeidslokasjon` | 25 | 1.68 MB \* | 7 | 450 ms |
| `utdanning/larling/virksomhet` | 50 | 1.50 MB \* | 8 | 300 ms |
| `utdanning/utdanningsprogram/arstrinn` | 50 | 1.55 MB \* | 1 | 25 ms |
| `utdanning/utdanningsprogram/utdanningsprogram` | 50 | 1.50 MB \* | 1 | 120 ms |
| `administrasjon/organisasjon/organisasjonselement` | 100 | 0.94 MB \* | 8 | 790 ms |
| `utdanning/kodeverk/karakterskala` | 250 | 1.03 MB \* | 1 | 64 ms |
| `utdanning/timeplan/fag` | 250 | 1.28 MB \* | 44 | 2.4 s |
| `utdanning/elev/elevforhold` | 500 | 1.28 MB | 84 | 27 s |
| `utdanning/elev/klasse` | 500 | 1.50 MB \* | 5 | 1.7 s |
| `utdanning/elev/kontaktlarergruppe` | 500 | 1.30 MB | 5 | 1.5 s |
| `utdanning/timeplan/faggruppe` | 500 | 1.25 MB \* | 29 | 7.4 s |
| `utdanning/timeplan/undervisningsgruppe` | 500 | 1.45 MB \* | 26 | 6.7 s |
| `utdanning/vurdering/elevfravar` | 500 | 1.75 MB \* | 73 | 43 s |
| `administrasjon/fullmakt/fullmakt` | 1,000 | 0.54 MB | 4 | 400 ms |
| `administrasjon/kodeverk/ansvar` | 1,000 | 0.37 MB | 1 | 100 ms |
| `administrasjon/kodeverk/arbeidsforholdstype` | 1,000 | 0.23 MB | 1 | 21 ms |
| `administrasjon/kodeverk/art` | 1,000 | 0.22 MB | 1 | 51 ms |
| `administrasjon/kodeverk/fravarstype` | 1,000 | 0.24 MB | 1 | 26 ms |
| `administrasjon/kodeverk/funksjon` | 1,000 | 0.23 MB | 1 | 76 ms |
| `administrasjon/kodeverk/lonnsart` | 1,000 | 0.31 MB | 1 | 100 ms |
| `administrasjon/kodeverk/organisasjonstype` | 1,000 | 0.23 MB | 1 | 21 ms |
| `administrasjon/kodeverk/personalressurskategori` | 1,000 | 0.22 MB | 1 | 20 ms |
| `administrasjon/kodeverk/prosjekt` | 1,000 | 0.31 MB | 5 | 540 ms |
| `administrasjon/kodeverk/stillingskode` | 1,000 | 0.29 MB | 1 | 40 ms |
| `administrasjon/kodeverk/uketimetall` | 1,000 | 0.21 MB | 1 | 20 ms |
| `administrasjon/personal/arbeidsforhold` | 1,000 | 1.50 MB | 24 | 3.5 s |
| `administrasjon/personal/fastlonn` | 1,000 | 1.29 MB | 1 | 180 ms |
| `administrasjon/personal/fasttillegg` | 1,000 | 1.28 MB | 1 | 25 ms |
| `administrasjon/personal/fravar` | 1,000 | 0.76 MB | 1 | 33 ms |
| `administrasjon/personal/person` | 1,000 | 0.57 MB | 10 | 1.2 s |
| `administrasjon/personal/personalressurs` | 1,000 | 1.30 MB | 10 | 3.5 s |
| `administrasjon/personal/variabellonn` | 1,000 | 1.27 MB | 1 | 130 ms |
| `personvern/kodeverk/behandlingsgrunnlag` | 1,000 | 0.70 MB \* | 1 | 16 ms |
| `personvern/kodeverk/personopplysning` | 1,000 | 0.70 MB \* | 1 | 15 ms |
| `personvern/samtykke/samtykke` | 1,000 | 0.70 MB \* | 8 | 980 ms |
| `personvern/samtykke/tjeneste` | 1,000 | 0.80 MB \* | 1 | 18 ms |
| `ressurs/datautstyr/digitalenhet` | 1,000 | 0.70 MB \* | 1 | 160 ms |
| `ressurs/datautstyr/enhetsgruppe` | 1,000 | 1.90 MB \* | 1 | 25 ms |
| `ressurs/datautstyr/enhetsgruppemedlemskap` | 1,000 | 0.30 MB \* | 1 | 28 ms |
| `ressurs/kodeverk/enhetstype` | 1,000 | 0.20 MB \* | 1 | 21 ms |
| `ressurs/kodeverk/plattform` | 1,000 | 0.20 MB \* | 1 | 22 ms |
| `ressurs/kodeverk/status` | 1,000 | 0.20 MB \* | 1 | 23 ms |
| `utdanning/elev/elev` | 1,000 | 0.83 MB | 39 | 13 s |
| `utdanning/elev/klassemedlemskap` | 1,000 | 0.37 MB | 36 | 4.9 s |
| `utdanning/elev/kontaktlarergruppemedlemskap` | 1,000 | 0.43 MB | 34 | 4.2 s |
| `utdanning/elev/kontaktperson` | 1,000 | 0.49 MB | 33 | 4.1 s |
| `utdanning/elev/person` | 1,000 | 0.74 MB | 77 | 15 s |
| `utdanning/elev/skoleressurs` | 1,000 | 0.86 MB | 8 | 2.7 s |
| `utdanning/elev/undervisningsforhold` | 1,000 | 0.87 MB | 9 | 4.1 s |
| `utdanning/kodeverk/eksamensform` | 1,000 | 0.23 MB | 1 | 19 ms |
| `utdanning/kodeverk/elevkategori` | 1,000 | 0.19 MB | 1 | 19 ms |
| `utdanning/kodeverk/fravarstype` | 1,000 | 0.26 MB | 1 | 19 ms |
| `utdanning/kodeverk/otenhet` | 1,000 | 0.18 MB | 1 | 21 ms |
| `utdanning/kodeverk/otstatus` | 1,000 | 0.28 MB | 1 | 22 ms |
| `utdanning/kodeverk/skolear` | 1,000 | 0.28 MB | 1 | 24 ms |
| `utdanning/kodeverk/termin` | 1,000 | 0.26 MB | 1 | 28 ms |
| `utdanning/larling/larling` | 1,000 | 0.59 MB | 8 | 890 ms |
| `utdanning/larling/person` | 1,000 | 0.46 MB | 8 | 2.2 s |
| `utdanning/ot/otungdom` | 1,000 | 0.44 MB | 6 | 750 ms |
| `utdanning/ot/person` | 1,000 | 0.45 MB | 6 | 710 ms |
| `utdanning/timeplan/faggruppemedlemskap` | 1,000 | 0.47 MB | 262 | 32 s |
| `utdanning/timeplan/undervisningsgruppemedlemskap` | 1,000 | 0.50 MB | 236 | 29 s |
| `utdanning/utdanningsprogram/programomrade` | 1,000 | 0.60 MB | 5 | 3.5 s |
| `utdanning/utdanningsprogram/programomrademedlemskap` | 1,000 | 0.51 MB | 38 | 4.6 s |
| `utdanning/vurdering/eksamensgruppe` | 1,000 | 1.19 MB | 4 | 2.3 s |
| `utdanning/vurdering/eksamensgruppemedlemskap` | 1,000 | 0.44 MB | 17 | 2.0 s |
| `utdanning/vurdering/elevvurdering` | 1,000 | 0.71 MB | 42 | 14 s |
| `utdanning/vurdering/fravarsregistrering` | 1,000 | 0.76 MB | 1,074 | 2.2 min |
| `utdanning/vurdering/halvarsfagvurdering` | 1,000 | 0.63 MB | 12 | 1.4 s |
| `utdanning/vurdering/karakterverdi` | 1,000 | 0.36 MB | 2 | 150 ms |
| `utdanning/vurdering/sluttfagvurdering` | 1,000 | 0.63 MB | 9 | 1.0 s |
| `utdanning/vurdering/underveisfagvurdering` | 1,000 | 0.65 MB | 161 | 19 s |

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
- **Requests** is the number of pages of the largest collection of that resource in production.
  The entry counts are from 23 November 2026.
- **Time** is an estimate of the time that the client-api uses for these requests, one after the other.
  One request is 15 ms plus the time for each entry. The time for one entry is the time to find its back-links
  plus 57 microseconds.
- **Time to find the back-links.** This value comes from production metrics of November 2026, for the county
  with the largest collection. For 9 resources that county had no usable value, and the estimate uses other
  counties or an assumed value.
- **Check of the estimate.** On 19 November 2026 we compared the formula with real page reads in the access log.
  The estimate was within 30 % of the request time for 31 of 33 cases.
- **Network time.** The time does not include the network time between the client and the client-api.
- **Page size and time.** The page size changes the time little. Most of the time is the time for each entry,
  and that time does not change with the page size.
- **The `next` link.** The estimate is for a client that follows the `next` link. That link holds a cursor, and
  the time of such a page does not grow with the offset. A page that a client reads with `offset` and no cursor
  gets slower when the offset grows. In our samples, a page of `utdanning/vurdering/fravarsregistrering` at
  offset 320,000 took about 1 second longer than the first page.
