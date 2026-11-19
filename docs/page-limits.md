# Page limits

This page proposes a default and a maximum page size for each resource in the client-api.

The client-api does not enforce these limits today. A read without `size` returns the full collection, and `size`
has no maximum. The team has not agreed on the values.

The byte values on this page are estimates. We did not measure a response body.
See [How we calculated the values](#how-we-calculated-the-values).

## Global limit

| Limit | Entries |
|---|---|
| Default `size` | 1,000 |
| Maximum `size` | 5,000 |

The global limit applies to each resource that has no own limit. That is 67 of 75 resources.
The largest page is then about 10 MB.
A client that needs more entries follows the `next` link.

## Resources with their own limit

These resources have large entries, and the full collection does not fit in one page.

| Resource | Default | Maximum | One entry | Back-links for each entry | Largest collection |
|---|---|---|---|---|---|
| `utdanning/utdanningsprogram/skole` | 1 | 5 | 1.1 to 1.2 MB | 12,492 to 13,590 | 54 entries, 66.1 MB |
| `administrasjon/organisasjon/arbeidslokasjon` | 20 | 100 | 47 to 67 KB | 221 to 315 | 159 entries, 10.6 MB |
| `utdanning/larling/virksomhet` | 50 | 200 | 2.4 to 30 KB | 19 to 330 | 366 entries, 11.1 MB |
| `utdanning/timeplan/fag` | 200 | 1,000 | 2.8 to 5.1 KB | 1.2 to 2.7 | 10,880 entries, 55.0 MB |
| `utdanning/vurdering/elevfravar` | 500 | 2,000 | 2.7 to 3.5 KB | 26 to 35 | 36,057 entries, 126.8 MB |
| `utdanning/timeplan/undervisningsgruppe` | 500 | 2,000 | 2.4 to 2.9 KB | 16 to 21 | 12,924 entries, 37.0 MB |
| `utdanning/timeplan/faggruppe` | 500 | 2,000 | 2.1 to 2.5 KB | 17 to 22 | 13,985 entries, 35.0 MB |
| `utdanning/elev/elevforhold` | 500 | 2,000 | 2.4 to 2.5 KB | 15 to 16 | 41,754 entries, 104.5 MB |

Each range goes from the median organisation to the organisation with the largest value.

One `skole` is about 1.1 MB because about 13,000 entries of other resources link to it.
Paging cannot make one entry smaller.

The value for `virksomhet` is the least sure. Its entity size is assumed, and the back-links differ much between
organisations.

## Resources with large entries and a small collection

One entry of these resources is larger than 2 KB, but the full collection fits in one page.
They use the global limit.

| Resource | One entry | Back-links for each entry | Largest collection |
|---|---|---|---|
| `personvern/samtykke/behandling` | 651 KB | 7,230 | 1 entry, 0.7 MB |
| `administrasjon/fullmakt/rolle` | 119 to 264 KB | 702 to 2,263 | 2 entries, 0.5 MB |
| `utdanning/utdanningsprogram/arstrinn` | 15 to 31 KB | 164 to 341 | 7 entries, 0.2 MB |
| `utdanning/utdanningsprogram/utdanningsprogram` | 24 to 30 KB | 220 to 221 | 23 entries, 0.7 MB |
| `administrasjon/organisasjon/organisasjonselement` | 7.3 to 9.4 KB | 31 to 40 | 737 entries, 6.9 MB |
| `utdanning/kodeverk/karakterskala` | 2.6 to 4.1 KB | 12 to 20 | 57 entries, 0.2 MB |
| `utdanning/elev/klasse` | 2.4 to 3.0 KB | 17 to 23 | 2,048 entries, 6.1 MB |
| `utdanning/elev/kontaktlarergruppe` | 2.3 to 2.5 KB | 15 to 18 | 2,181 entries, 5.5 MB |

## All resources

The list holds each resource that had data in production in November 2026.
A resource that is not in the list uses the global limit.

- **One entry** is the estimate for the organisation with the largest entries.
- **Default page** and **Maximum page** are the estimated bytes of a page with that number of entries.
  A page cannot hold more entries than the largest collection.
- **Entity size** tells how we got the size of the entity without back-links.

### administrasjon

| Resource | Default | Maximum | One entry | Default page | Maximum page | Largest collection | Entity size |
|---|---|---|---|---|---|---|---|
| `administrasjon/fullmakt/fullmakt` | 1,000 | 5,000 | 0.6 KB | 0.6 MB | 1.9 MB | 3,083 | measured |
| `administrasjon/fullmakt/rolle` | 1,000 | 5,000 | 264 KB | 0.5 MB | 0.5 MB | 2 | few samples |
| `administrasjon/kodeverk/ansvar` | 1,000 | 5,000 | 1.0 KB | 0.4 MB | 0.4 MB | 450 | assumed |
| `administrasjon/kodeverk/arbeidsforholdstype` | 1,000 | 5,000 | 0.7 KB | under 0.1 MB | under 0.1 MB | 27 | assumed |
| `administrasjon/kodeverk/art` | 1,000 | 5,000 | 0.7 KB | 0.2 MB | 0.2 MB | 282 | assumed |
| `administrasjon/kodeverk/fravarstype` | 1,000 | 5,000 | 0.3 KB | under 0.1 MB | under 0.1 MB | 73 | few samples |
| `administrasjon/kodeverk/funksjon` | 1,000 | 5,000 | 0.7 KB | 0.4 MB | 0.4 MB | 531 | assumed |
| `administrasjon/kodeverk/lonnsart` | 1,000 | 5,000 | 0.3 KB | 0.3 MB | 0.3 MB | 743 | few samples |
| `administrasjon/kodeverk/organisasjonstype` | 1,000 | 5,000 | 0.7 KB | under 0.1 MB | under 0.1 MB | 14 | assumed |
| `administrasjon/kodeverk/personalressurskategori` | 1,000 | 5,000 | 0.7 KB | under 0.1 MB | under 0.1 MB | 18 | assumed |
| `administrasjon/kodeverk/prosjekt` | 1,000 | 5,000 | 0.3 KB | 0.3 MB | 1.2 MB | 4,233 | measured |
| `administrasjon/kodeverk/stillingskode` | 1,000 | 5,000 | 0.7 KB | 0.1 MB | 0.1 MB | 209 | assumed |
| `administrasjon/kodeverk/uketimetall` | 1,000 | 5,000 | 0.7 KB | under 0.1 MB | under 0.1 MB | 6 | assumed |
| `administrasjon/organisasjon/arbeidslokasjon` | 20 | 100 | 67 KB | 1.3 MB | 6.7 MB | 159 | measured |
| `administrasjon/organisasjon/organisasjonselement` | 1,000 | 5,000 | 9.4 KB | 6.9 MB | 6.9 MB | 737 | measured |
| `administrasjon/personal/arbeidsforhold` | 1,000 | 5,000 | 1.6 KB | 1.6 MB | 7.9 MB | 23,430 | measured |
| `administrasjon/personal/fastlonn` | 1,000 | 5,000 | 0.7 KB | 0.2 MB | 0.2 MB | 275 | assumed |
| `administrasjon/personal/fasttillegg` | 1,000 | 5,000 | 0.7 KB | under 0.1 MB | under 0.1 MB | 29 | assumed |
| `administrasjon/personal/fravar` | 1,000 | 5,000 | 0.7 KB | 0.7 MB | 0.9 MB | 1,323 | assumed |
| `administrasjon/personal/person` | 1,000 | 5,000 | 0.6 KB | 0.6 MB | 3.0 MB | 9,515 | measured |
| `administrasjon/personal/personalressurs` | 1,000 | 5,000 | 1.9 KB | 1.9 MB | 9.7 MB | 9,515 | measured |
| `administrasjon/personal/variabellonn` | 1,000 | 5,000 | 0.7 KB | 0.7 MB | 3.0 MB | 4,267 | assumed |

### personvern

| Resource | Default | Maximum | One entry | Default page | Maximum page | Largest collection | Entity size |
|---|---|---|---|---|---|---|---|
| `personvern/kodeverk/behandlingsgrunnlag` | 1,000 | 5,000 | 0.7 KB | under 0.1 MB | under 0.1 MB | 6 | assumed |
| `personvern/kodeverk/personopplysning` | 1,000 | 5,000 | 0.7 KB | under 0.1 MB | under 0.1 MB | 1 | assumed |
| `personvern/samtykke/behandling` | 1,000 | 5,000 | 651 KB | 0.7 MB | 0.7 MB | 1 | assumed |
| `personvern/samtykke/samtykke` | 1,000 | 5,000 | 0.7 KB | 0.7 MB | 3.4 MB | 7,230 | measured |
| `personvern/samtykke/tjeneste` | 1,000 | 5,000 | 0.8 KB | under 0.1 MB | under 0.1 MB | 1 | assumed |

### ressurs

| Resource | Default | Maximum | One entry | Default page | Maximum page | Largest collection | Entity size |
|---|---|---|---|---|---|---|---|
| `ressurs/datautstyr/digitalenhet` | 1,000 | 5,000 | 0.7 KB | under 0.1 MB | under 0.1 MB | 43 | measured |
| `ressurs/datautstyr/enhetsgruppe` | 1,000 | 5,000 | 1.9 KB | under 0.1 MB | under 0.1 MB | 3 | measured |
| `ressurs/datautstyr/enhetsgruppemedlemskap` | 1,000 | 5,000 | 0.3 KB | under 0.1 MB | under 0.1 MB | 48 | measured |
| `ressurs/kodeverk/enhetstype` | 1,000 | 5,000 | 0.2 KB | under 0.1 MB | under 0.1 MB | 5 | measured |
| `ressurs/kodeverk/plattform` | 1,000 | 5,000 | 0.2 KB | under 0.1 MB | under 0.1 MB | 4 | measured |
| `ressurs/kodeverk/status` | 1,000 | 5,000 | 0.2 KB | under 0.1 MB | under 0.1 MB | 14 | measured |

### utdanning

| Resource | Default | Maximum | One entry | Default page | Maximum page | Largest collection | Entity size |
|---|---|---|---|---|---|---|---|
| `utdanning/elev/elev` | 1,000 | 5,000 | 0.9 KB | 0.9 MB | 4.3 MB | 38,347 | measured |
| `utdanning/elev/elevforhold` | 500 | 2,000 | 2.5 KB | 1.3 MB | 5.0 MB | 41,754 | measured |
| `utdanning/elev/klasse` | 1,000 | 5,000 | 3.0 KB | 3.0 MB | 6.1 MB | 2,048 | measured |
| `utdanning/elev/klassemedlemskap` | 1,000 | 5,000 | 0.5 KB | 0.5 MB | 2.4 MB | 35,893 | measured |
| `utdanning/elev/kontaktlarergruppe` | 1,000 | 5,000 | 2.5 KB | 2.5 MB | 5.5 MB | 2,181 | measured |
| `utdanning/elev/kontaktlarergruppemedlemskap` | 1,000 | 5,000 | 0.6 KB | 0.6 MB | 2.8 MB | 33,311 | measured |
| `utdanning/elev/kontaktperson` | 1,000 | 5,000 | 0.6 KB | 0.6 MB | 3.2 MB | 32,571 | measured |
| `utdanning/elev/person` | 1,000 | 5,000 | 0.9 KB | 0.9 MB | 4.4 MB | 77,070 | measured |
| `utdanning/elev/skoleressurs` | 1,000 | 5,000 | 1.0 KB | 1.0 MB | 5.2 MB | 7,206 | measured |
| `utdanning/elev/undervisningsforhold` | 1,000 | 5,000 | 1.0 KB | 1.0 MB | 5.2 MB | 8,324 | measured |
| `utdanning/kodeverk/eksamensform` | 1,000 | 5,000 | 0.3 KB | under 0.1 MB | under 0.1 MB | 7 | measured |
| `utdanning/kodeverk/elevkategori` | 1,000 | 5,000 | 0.3 KB | under 0.1 MB | under 0.1 MB | 3 | few samples |
| `utdanning/kodeverk/fravarstype` | 1,000 | 5,000 | 0.4 KB | under 0.1 MB | under 0.1 MB | 6 | measured |
| `utdanning/kodeverk/karakterskala` | 1,000 | 5,000 | 4.1 KB | 0.2 MB | 0.2 MB | 57 | measured |
| `utdanning/kodeverk/otenhet` | 1,000 | 5,000 | 0.3 KB | under 0.1 MB | under 0.1 MB | 31 | measured |
| `utdanning/kodeverk/otstatus` | 1,000 | 5,000 | 0.4 KB | under 0.1 MB | under 0.1 MB | 26 | measured |
| `utdanning/kodeverk/skolear` | 1,000 | 5,000 | 0.4 KB | under 0.1 MB | under 0.1 MB | 54 | measured |
| `utdanning/kodeverk/termin` | 1,000 | 5,000 | 0.4 KB | under 0.1 MB | under 0.1 MB | 108 | measured |
| `utdanning/larling/larling` | 1,000 | 5,000 | 0.6 KB | 0.6 MB | 3.0 MB | 7,326 | measured |
| `utdanning/larling/person` | 1,000 | 5,000 | 0.7 KB | 0.7 MB | 3.4 MB | 7,326 | measured |
| `utdanning/larling/virksomhet` | 50 | 200 | 30 KB | 1.5 MB | 6.1 MB | 366 | assumed |
| `utdanning/ot/otungdom` | 1,000 | 5,000 | 0.5 KB | 0.5 MB | 2.3 MB | 5,763 | measured |
| `utdanning/ot/person` | 1,000 | 5,000 | 0.6 KB | 0.6 MB | 2.9 MB | 5,763 | fitted |
| `utdanning/timeplan/fag` | 200 | 1,000 | 5.1 KB | 1.0 MB | 5.1 MB | 10,880 | measured |
| `utdanning/timeplan/faggruppe` | 500 | 2,000 | 2.5 KB | 1.3 MB | 5.0 MB | 13,985 | measured |
| `utdanning/timeplan/faggruppemedlemskap` | 1,000 | 5,000 | 0.6 KB | 0.6 MB | 2.9 MB | 261,293 | measured |
| `utdanning/timeplan/undervisningsgruppe` | 500 | 2,000 | 2.9 KB | 1.4 MB | 5.7 MB | 12,924 | measured |
| `utdanning/timeplan/undervisningsgruppemedlemskap` | 1,000 | 5,000 | 0.6 KB | 0.6 MB | 3.2 MB | 235,297 | measured |
| `utdanning/utdanningsprogram/arstrinn` | 1,000 | 5,000 | 31 KB | 0.2 MB | 0.2 MB | 7 | measured |
| `utdanning/utdanningsprogram/programomrade` | 1,000 | 5,000 | 1.2 KB | 1.2 MB | 5.9 MB | 4,823 | measured |
| `utdanning/utdanningsprogram/programomrademedlemskap` | 1,000 | 5,000 | 0.6 KB | 0.6 MB | 3.2 MB | 37,569 | measured |
| `utdanning/utdanningsprogram/skole` | 1 | 5 | 1.2 MB | 1.2 MB | 6.1 MB | 54 | measured |
| `utdanning/utdanningsprogram/utdanningsprogram` | 1,000 | 5,000 | 30 KB | 0.7 MB | 0.7 MB | 23 | measured |
| `utdanning/vurdering/eksamensgruppe` | 1,000 | 5,000 | 2.0 KB | 2.0 MB | 6.3 MB | 3,225 | measured |
| `utdanning/vurdering/eksamensgruppemedlemskap` | 1,000 | 5,000 | 0.6 KB | 0.6 MB | 2.8 MB | 16,358 | measured |
| `utdanning/vurdering/elevfravar` | 500 | 2,000 | 3.5 KB | 1.8 MB | 7.0 MB | 36,057 | measured |
| `utdanning/vurdering/elevvurdering` | 1,000 | 5,000 | 1.4 KB | 1.4 MB | 6.8 MB | 41,753 | measured |
| `utdanning/vurdering/fravarsregistrering` | 1,000 | 5,000 | 0.9 KB | 0.9 MB | 4.3 MB | 1,040,587 | measured |
| `utdanning/vurdering/halvarsfagvurdering` | 1,000 | 5,000 | 0.7 KB | 0.7 MB | 3.5 MB | 11,760 | measured |
| `utdanning/vurdering/karakterverdi` | 1,000 | 5,000 | 0.5 KB | 0.5 MB | 0.5 MB | 1,130 | measured |
| `utdanning/vurdering/sluttfagvurdering` | 1,000 | 5,000 | 0.7 KB | 0.7 MB | 3.5 MB | 8,575 | measured |
| `utdanning/vurdering/underveisfagvurdering` | 1,000 | 5,000 | 0.7 KB | 0.7 MB | 3.6 MB | 153,748 | measured |

## How we calculated the values

The values come from production metrics for the 14 days before 2026-11-19.

- **Targets.** A default page is about 2 MB and a maximum page is about 10 MB. These targets are an assumption.
- **Largest collection.** The largest value of `core_resource_count` for one organisation.
- **Entity size.** The bytes that the adapter-gateway read from the Kafka buffer, divided by the number of records.
  We used only the 30-second steps where one resource had 99 % of the writes.
  We subtracted about 280 bytes for the record key and headers.
- **Back-links for each entry.** `core_relation_edges_lookup_edges` divided by `core_relation_edges_lookup_resources`.
- **One entry.** The entity size plus 90 bytes for each back-link. The 90 bytes are an assumption.

## What the values do not show

- The entity size is what the adapter sent. The entry in a response can be larger, because each link becomes a full URL.
- 17 resources have no measured entity size. We assumed 700 bytes for them.
- `rolle` and `arbeidslokasjon` can be up to two times too high. This occurs if the adapter sends the same links
  that the back-links add.
- The size of the largest collection is the largest count multiplied by the largest entry.
  The two values can come from different organisations, so the real collection can be smaller.
- The back-links change with the data. Measure again before you set a limit.
- A page limit does not make one entry smaller. One `skole` stays at about 1.1 MB.
