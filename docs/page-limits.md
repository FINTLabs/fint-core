# Page limits

This page proposes a default and a maximum page size for each resource in the client-api.

The client-api does not enforce these limits today. A read without `size` returns the full collection, and `size`
has no maximum. The team has not agreed on the values.

The byte values on this page are estimates. We did not measure a response body.
The time values are estimates too. They come from the time of real requests in production.
See [How we calculated the values](#how-we-calculated-the-values).

## Global limit

| Limit | Entries |
|---|---|
| Default `size` | 1,000 |
| Maximum `size` | 5,000 |

The global limit applies to each resource that has no own limit. That is 67 of 75 resources.
A client that needs more entries follows the `next` link.

The table shows a full page for the resources that have that many entries in the largest collection.

| Entries in the page | Resources | Bytes, average | Bytes, largest | Time, average | Time, largest |
|---|---|---|---|---|---|
| 1,000 | 33 | 937 KB | 3.0 MB | 220 ms | 2.8 s |
| 5,000 | 24 | 4.1 MB | 9.7 MB | 830 ms | 4.8 s |

The average time is for the median organisation. The largest time is for the slowest organisation.
A page is smaller and faster when the collection has fewer entries than the page size.

## Resources with their own limit

These resources have large entries, and the full collection does not fit in one page.

| Resource | Default | Maximum | One entry | Default page | Default time | Maximum page | Maximum time | Back-links for each entry | Largest collection |
|---|---|---|---|---|---|---|---|---|---|
| `utdanning/utdanningsprogram/skole` | 1 | 5 | 1.1 to 1.2 MB | 1.2 MB | 230 to 690 ms | 6.1 MB | 1.1 to 3.4 s | 12,492 to 13,590 | 54 entries, 66.1 MB |
| `administrasjon/organisasjon/arbeidslokasjon` | 20 | 100 | 47 to 67 KB | 1.3 MB | 59 to 74 ms | 6.7 MB | 240 to 310 ms | 221 to 315 | 159 entries, 10.6 MB |
| `utdanning/larling/virksomhet` | 50 | 200 | 2.4 to 30 KB | 1.5 MB | 33 to 180 ms | 6.1 MB | 87 to 660 ms | 19 to 330 | 366 entries, 11.1 MB |
| `utdanning/timeplan/fag` | 200 | 1,000 | 2.8 to 5.1 KB | 1.0 MB | 62 to 86 ms | 5.1 MB | 250 to 370 ms | 1.2 to 2.7 | 10,880 entries, 55.0 MB |
| `utdanning/vurdering/elevfravar` | 500 | 2,000 | 2.7 to 3.5 KB | 1.8 MB | 900 ms to 1.4 s | 7.0 MB | 3.5 to 5.4 s | 26 to 35 | 36,057 entries, 126.8 MB |
| `utdanning/timeplan/undervisningsgruppe` | 500 | 2,000 | 2.4 to 2.9 KB | 1.4 MB | 360 ms to 1.1 s | 5.7 MB | 1.4 to 4.4 s | 16 to 21 | 12,924 entries, 37.0 MB |
| `utdanning/timeplan/faggruppe` | 500 | 2,000 | 2.1 to 2.5 KB | 1.3 MB | 250 ms to 1.2 s | 5.0 MB | 940 ms to 4.7 s | 17 to 22 | 13,985 entries, 35.0 MB |
| `utdanning/elev/elevforhold` | 500 | 2,000 | 2.4 to 2.5 KB | 1.3 MB | 350 to 910 ms | 5.0 MB | 1.4 to 3.6 s | 15 to 16 | 41,754 entries, 104.5 MB |

- **Default page** and **Maximum page** are the estimated bytes of a page with that number of entries.
- **Default time** and **Maximum time** are the estimated request time of that page.
- **Largest collection** is the full collection, in entries and in estimated bytes.
- Each range goes from the median organisation to the organisation with the largest value.

One `skole` is about 1.1 MB because about 13,000 entries of other resources link to it.
Paging cannot make one entry smaller.

The time follows the number of back-links more than the bytes.
A page of `elevfravar` takes much longer than a page of `fag` with the same bytes.

The value for `virksomhet` is the least sure. Its entity size is assumed, and the back-links differ much between
organisations.

## Resources with large entries and a small collection

One entry of these resources is larger than 2 KB, but the full collection fits in one page.
They use the global limit. A maximum page holds the full collection, so the two byte values are equal.

| Resource | One entry | Maximum page | Maximum time | Back-links for each entry | Largest collection |
|---|---|---|---|---|---|
| `personvern/samtykke/behandling` | 651 KB | 651 KB | 81 ms | 7,230 | 1 entry, 651 KB |
| `administrasjon/fullmakt/rolle` | 119 to 264 KB | 528 KB | 85 to 240 ms | 702 to 2,263 | 2 entries, 528 KB |
| `utdanning/utdanningsprogram/arstrinn` | 15 to 31 KB | 217 KB | 140 to 170 ms | 164 to 341 | 7 entries, 217 KB |
| `utdanning/utdanningsprogram/utdanningsprogram` | 24 to 30 KB | 679 KB | 540 to 560 ms | 220 to 221 | 23 entries, 679 KB |
| `administrasjon/organisasjon/organisasjonselement` | 7.3 to 9.4 KB | 6.9 MB | 630 to 850 ms | 31 to 40 | 737 entries, 6.9 MB |
| `utdanning/kodeverk/karakterskala` | 2.6 to 4.1 KB | 232 KB | 160 ms | 12 to 20 | 57 entries, 232 KB |
| `utdanning/elev/klasse` | 2.4 to 3.0 KB | 6.1 MB | 2.0 to 5.6 s | 17 to 23 | 2,048 entries, 6.1 MB |
| `utdanning/elev/kontaktlarergruppe` | 2.3 to 2.5 KB | 5.5 MB | 990 ms to 3.3 s | 15 to 18 | 2,181 entries, 5.5 MB |

## All resources

The list holds each resource that had data in production in November 2026.
A resource that is not in the list uses the global limit.

- **One entry** is the estimate for the organisation with the largest entries.
- **Default page** and **Maximum page** are the estimated bytes of a page with that number of entries.
  A page cannot hold more entries than the largest collection.
- **Default time** and **Maximum time** are the estimated request time of that page.
  The range goes from the median organisation to the slowest organisation.
- **Entity size** tells how we got the size of the entity without back-links.

### administrasjon

| Resource | Default | Maximum | One entry | Default page | Default time | Maximum page | Maximum time | Largest collection | Entity size |
|---|---|---|---|---|---|---|---|---|---|
| `administrasjon/fullmakt/fullmakt` | 1,000 | 5,000 | 0.6 KB | 612 KB | 130 ms | 1.9 MB | 360 ms | 3,083 | measured |
| `administrasjon/fullmakt/rolle` | 1,000 | 5,000 | 264 KB | 528 KB | 85 to 240 ms | 528 KB | 85 to 240 ms | 2 | few samples |
| `administrasjon/kodeverk/ansvar` | 1,000 | 5,000 | 1.0 KB | 443 KB | 110 to 120 ms | 443 KB | 110 to 120 ms | 450 | assumed |
| `administrasjon/kodeverk/arbeidsforholdstype` | 1,000 | 5,000 | 0.7 KB | 19 KB | 21 to 23 ms | 19 KB | 21 to 23 ms | 27 | assumed |
| `administrasjon/kodeverk/art` | 1,000 | 5,000 | 0.7 KB | 197 KB | 49 to 52 ms | 197 KB | 49 to 52 ms | 282 | assumed |
| `administrasjon/kodeverk/fravarstype` | 1,000 | 5,000 | 0.3 KB | 19 KB | 26 to 27 ms | 19 KB | 26 to 27 ms | 73 | few samples |
| `administrasjon/kodeverk/funksjon` | 1,000 | 5,000 | 0.7 KB | 372 KB | 76 ms | 372 KB | 76 ms | 531 | assumed |
| `administrasjon/kodeverk/lonnsart` | 1,000 | 5,000 | 0.3 KB | 251 KB | 100 ms | 251 KB | 100 ms | 743 | few samples |
| `administrasjon/kodeverk/organisasjonstype` | 1,000 | 5,000 | 0.7 KB | 9.8 KB | 22 ms | 9.8 KB | 22 ms | 14 | assumed |
| `administrasjon/kodeverk/personalressurskategori` | 1,000 | 5,000 | 0.7 KB | 13 KB | 22 to 26 ms | 13 KB | 22 to 26 ms | 18 | assumed |
| `administrasjon/kodeverk/prosjekt` | 1,000 | 5,000 | 0.3 KB | 294 KB | 130 ms | 1.2 MB | 490 to 500 ms | 4,233 | measured |
| `administrasjon/kodeverk/stillingskode` | 1,000 | 5,000 | 0.7 KB | 146 KB | 41 to 42 ms | 146 KB | 41 to 42 ms | 209 | assumed |
| `administrasjon/kodeverk/uketimetall` | 1,000 | 5,000 | 0.7 KB | 4.2 KB | 19 to 20 ms | 4.2 KB | 19 to 20 ms | 6 | assumed |
| `administrasjon/organisasjon/arbeidslokasjon` | 20 | 100 | 67 KB | 1.3 MB | 59 to 74 ms | 6.7 MB | 240 to 310 ms | 159 | measured |
| `administrasjon/organisasjon/organisasjonselement` | 1,000 | 5,000 | 9.4 KB | 6.9 MB | 630 to 850 ms | 6.9 MB | 630 to 850 ms | 737 | measured |
| `administrasjon/personal/arbeidsforhold` | 1,000 | 5,000 | 1.6 KB | 1.6 MB | 150 to 180 ms | 7.9 MB | 680 to 840 ms | 23,430 | measured |
| `administrasjon/personal/fastlonn` | 1,000 | 5,000 | 0.7 KB | 192 KB | 46 ms | 192 KB | 46 ms | 275 | assumed |
| `administrasjon/personal/fasttillegg` | 1,000 | 5,000 | 0.7 KB | 20 KB | 18 ms | 20 KB | 18 ms | 29 | assumed |
| `administrasjon/personal/fravar` | 1,000 | 5,000 | 0.7 KB | 700 KB | 130 ms | 926 KB | 160 ms | 1,323 | assumed |
| `administrasjon/personal/person` | 1,000 | 5,000 | 0.6 KB | 602 KB | 130 ms | 3.0 MB | 590 to 610 ms | 9,515 | measured |
| `administrasjon/personal/personalressurs` | 1,000 | 5,000 | 1.9 KB | 1.9 MB | 360 to 370 ms | 9.7 MB | 1.8 s | 9,515 | measured |
| `administrasjon/personal/variabellonn` | 1,000 | 5,000 | 0.7 KB | 700 KB | 130 ms | 3.0 MB | 490 ms | 4,267 | assumed |

### personvern

| Resource | Default | Maximum | One entry | Default page | Default time | Maximum page | Maximum time | Largest collection | Entity size |
|---|---|---|---|---|---|---|---|---|---|
| `personvern/kodeverk/behandlingsgrunnlag` | 1,000 | 5,000 | 0.7 KB | 4.2 KB | 16 ms | 4.2 KB | 16 ms | 6 | assumed |
| `personvern/kodeverk/personopplysning` | 1,000 | 5,000 | 0.7 KB | 0.7 KB | 15 ms | 0.7 KB | 15 ms | 1 | assumed |
| `personvern/samtykke/behandling` | 1,000 | 5,000 | 651 KB | 651 KB | 81 ms | 651 KB | 81 ms | 1 | assumed |
| `personvern/samtykke/samtykke` | 1,000 | 5,000 | 0.7 KB | 687 KB | 130 ms | 3.4 MB | 610 ms | 7,230 | measured |
| `personvern/samtykke/tjeneste` | 1,000 | 5,000 | 0.8 KB | 0.8 KB | 18 ms | 0.8 KB | 18 ms | 1 | assumed |

### ressurs

| Resource | Default | Maximum | One entry | Default page | Default time | Maximum page | Maximum time | Largest collection | Entity size |
|---|---|---|---|---|---|---|---|---|---|
| `ressurs/datautstyr/digitalenhet` | 1,000 | 5,000 | 0.7 KB | 32 KB | 150 ms | 32 KB | 150 ms | 43 | measured |
| `ressurs/datautstyr/enhetsgruppe` | 1,000 | 5,000 | 1.9 KB | 5.7 KB | 18 ms | 5.7 KB | 18 ms | 3 | measured |
| `ressurs/datautstyr/enhetsgruppemedlemskap` | 1,000 | 5,000 | 0.3 KB | 16 KB | 24 ms | 16 KB | 24 ms | 48 | measured |
| `ressurs/kodeverk/enhetstype` | 1,000 | 5,000 | 0.2 KB | 0.9 KB | 16 ms | 0.9 KB | 16 ms | 5 | measured |
| `ressurs/kodeverk/plattform` | 1,000 | 5,000 | 0.2 KB | 0.8 KB | 15 ms | 0.8 KB | 15 ms | 4 | measured |
| `ressurs/kodeverk/status` | 1,000 | 5,000 | 0.2 KB | 2.7 KB | 17 ms | 2.7 KB | 17 ms | 14 | measured |

### utdanning

| Resource | Default | Maximum | One entry | Default page | Default time | Maximum page | Maximum time | Largest collection | Entity size |
|---|---|---|---|---|---|---|---|---|---|
| `utdanning/elev/elev` | 1,000 | 5,000 | 0.9 KB | 867 KB | 370 to 470 ms | 4.3 MB | 1.8 to 2.3 s | 38,347 | measured |
| `utdanning/elev/elevforhold` | 500 | 2,000 | 2.5 KB | 1.3 MB | 350 to 910 ms | 5.0 MB | 1.4 to 3.6 s | 41,754 | measured |
| `utdanning/elev/klasse` | 1,000 | 5,000 | 3.0 KB | 3.0 MB | 990 ms to 2.8 s | 6.1 MB | 2.0 to 5.6 s | 2,048 | measured |
| `utdanning/elev/klassemedlemskap` | 1,000 | 5,000 | 0.5 KB | 476 KB | 140 to 210 ms | 2.4 MB | 620 to 980 ms | 35,893 | measured |
| `utdanning/elev/kontaktlarergruppe` | 1,000 | 5,000 | 2.5 KB | 2.5 MB | 460 ms to 1.5 s | 5.5 MB | 990 ms to 3.3 s | 2,181 | measured |
| `utdanning/elev/kontaktlarergruppemedlemskap` | 1,000 | 5,000 | 0.6 KB | 564 KB | 130 to 200 ms | 2.8 MB | 580 to 950 ms | 33,311 | measured |
| `utdanning/elev/kontaktperson` | 1,000 | 5,000 | 0.6 KB | 642 KB | 130 to 150 ms | 3.2 MB | 580 to 680 ms | 32,571 | measured |
| `utdanning/elev/person` | 1,000 | 5,000 | 0.9 KB | 880 KB | 220 to 260 ms | 4.4 MB | 1.0 to 1.2 s | 77,070 | measured |
| `utdanning/elev/skoleressurs` | 1,000 | 5,000 | 1.0 KB | 1.0 MB | 310 to 390 ms | 5.2 MB | 1.5 to 1.9 s | 7,206 | measured |
| `utdanning/elev/undervisningsforhold` | 1,000 | 5,000 | 1.0 KB | 1.0 MB | 270 to 490 ms | 5.2 MB | 1.3 to 2.4 s | 8,324 | measured |
| `utdanning/kodeverk/eksamensform` | 1,000 | 5,000 | 0.3 KB | 2.4 KB | 19 to 20 ms | 2.4 KB | 19 to 20 ms | 7 | measured |
| `utdanning/kodeverk/elevkategori` | 1,000 | 5,000 | 0.3 KB | 0.9 KB | 19 to 20 ms | 0.9 KB | 19 to 20 ms | 3 | few samples |
| `utdanning/kodeverk/fravarstype` | 1,000 | 5,000 | 0.4 KB | 2.2 KB | 19 ms | 2.2 KB | 19 ms | 6 | measured |
| `utdanning/kodeverk/karakterskala` | 1,000 | 5,000 | 4.1 KB | 232 KB | 160 ms | 232 KB | 160 ms | 57 | measured |
| `utdanning/kodeverk/otenhet` | 1,000 | 5,000 | 0.3 KB | 8.9 KB | 29 to 48 ms | 8.9 KB | 29 to 48 ms | 31 | measured |
| `utdanning/kodeverk/otstatus` | 1,000 | 5,000 | 0.4 KB | 9.5 KB | 20 to 21 ms | 9.5 KB | 20 to 21 ms | 26 | measured |
| `utdanning/kodeverk/skolear` | 1,000 | 5,000 | 0.4 KB | 21 KB | 24 to 25 ms | 21 KB | 24 to 25 ms | 54 | measured |
| `utdanning/kodeverk/termin` | 1,000 | 5,000 | 0.4 KB | 41 KB | 41 to 53 ms | 41 KB | 41 to 53 ms | 108 | measured |
| `utdanning/larling/larling` | 1,000 | 5,000 | 0.6 KB | 596 KB | 120 to 160 ms | 3.0 MB | 560 to 750 ms | 7,326 | measured |
| `utdanning/larling/person` | 1,000 | 5,000 | 0.7 KB | 682 KB | 260 to 310 ms | 3.4 MB | 1.2 to 1.5 s | 7,326 | measured |
| `utdanning/larling/virksomhet` | 50 | 200 | 30 KB | 1.5 MB | 33 to 180 ms | 6.1 MB | 87 to 660 ms | 366 | assumed |
| `utdanning/ot/otungdom` | 1,000 | 5,000 | 0.5 KB | 457 KB | 130 to 160 ms | 2.3 MB | 570 to 760 ms | 5,763 | measured |
| `utdanning/ot/person` | 1,000 | 5,000 | 0.6 KB | 583 KB | 120 to 160 ms | 2.9 MB | 550 to 720 ms | 5,763 | fitted |
| `utdanning/timeplan/fag` | 200 | 1,000 | 5.1 KB | 1.0 MB | 62 to 86 ms | 5.1 MB | 250 to 370 ms | 10,880 | measured |
| `utdanning/timeplan/faggruppe` | 500 | 2,000 | 2.5 KB | 1.3 MB | 250 ms to 1.2 s | 5.0 MB | 940 ms to 4.7 s | 13,985 | measured |
| `utdanning/timeplan/faggruppemedlemskap` | 1,000 | 5,000 | 0.6 KB | 585 KB | 120 to 140 ms | 2.9 MB | 550 to 630 ms | 261,293 | measured |
| `utdanning/timeplan/undervisningsgruppe` | 500 | 2,000 | 2.9 KB | 1.4 MB | 360 ms to 1.1 s | 5.7 MB | 1.4 to 4.4 s | 12,924 | measured |
| `utdanning/timeplan/undervisningsgruppemedlemskap` | 1,000 | 5,000 | 0.6 KB | 643 KB | 120 to 190 ms | 3.2 MB | 560 to 880 ms | 235,297 | measured |
| `utdanning/utdanningsprogram/arstrinn` | 1,000 | 5,000 | 31 KB | 217 KB | 140 to 170 ms | 217 KB | 140 to 170 ms | 7 | measured |
| `utdanning/utdanningsprogram/programomrade` | 1,000 | 5,000 | 1.2 KB | 1.2 MB | 740 to 810 ms | 5.9 MB | 3.5 to 3.8 s | 4,823 | measured |
| `utdanning/utdanningsprogram/programomrademedlemskap` | 1,000 | 5,000 | 0.6 KB | 647 KB | 120 to 140 ms | 3.2 MB | 550 to 630 ms | 37,569 | measured |
| `utdanning/utdanningsprogram/skole` | 1 | 5 | 1.2 MB | 1.2 MB | 230 to 690 ms | 6.1 MB | 1.1 to 3.4 s | 54 | measured |
| `utdanning/utdanningsprogram/utdanningsprogram` | 1,000 | 5,000 | 30 KB | 679 KB | 540 to 560 ms | 679 KB | 540 to 560 ms | 23 | measured |
| `utdanning/vurdering/eksamensgruppe` | 1,000 | 5,000 | 2.0 KB | 2.0 MB | 260 to 630 ms | 6.3 MB | 810 ms to 2.0 s | 3,225 | measured |
| `utdanning/vurdering/eksamensgruppemedlemskap` | 1,000 | 5,000 | 0.6 KB | 569 KB | 120 to 140 ms | 2.8 MB | 550 to 640 ms | 16,358 | measured |
| `utdanning/vurdering/elevfravar` | 500 | 2,000 | 3.5 KB | 1.8 MB | 900 ms to 1.4 s | 7.0 MB | 3.5 to 5.4 s | 36,057 | measured |
| `utdanning/vurdering/elevvurdering` | 1,000 | 5,000 | 1.4 KB | 1.4 MB | 330 to 960 ms | 6.8 MB | 1.6 to 4.8 s | 41,753 | measured |
| `utdanning/vurdering/fravarsregistrering` | 1,000 | 5,000 | 0.9 KB | 868 KB | 120 to 140 ms | 4.3 MB | 550 to 630 ms | 1,040,587 | measured |
| `utdanning/vurdering/halvarsfagvurdering` | 1,000 | 5,000 | 0.7 KB | 706 KB | 120 to 130 ms | 3.5 MB | 540 to 610 ms | 11,760 | measured |
| `utdanning/vurdering/karakterverdi` | 1,000 | 5,000 | 0.5 KB | 481 KB | 130 to 190 ms | 543 KB | 150 to 210 ms | 1,130 | measured |
| `utdanning/vurdering/sluttfagvurdering` | 1,000 | 5,000 | 0.7 KB | 700 KB | 120 to 130 ms | 3.5 MB | 540 to 600 ms | 8,575 | measured |
| `utdanning/vurdering/underveisfagvurdering` | 1,000 | 5,000 | 0.7 KB | 730 KB | 120 to 140 ms | 3.6 MB | 540 to 630 ms | 153,748 | measured |

## How we calculated the values

The byte values come from production metrics for the 14 days before 2026-11-19.

- **Targets.** A default page is about 2 MB and a maximum page is about 10 MB. These targets are an assumption.
- **Largest collection.** The largest value of `core_resource_count` for one organisation.
- **Entity size.** The bytes that the adapter-gateway read from the Kafka buffer, divided by the number of records.
  We used only the 30-second steps where one resource had 99 % of the writes.
  We subtracted about 280 bytes for the record key and headers.
- **Back-links for each entry.** `core_relation_edges_lookup_edges` divided by `core_relation_edges_lookup_resources`.
- **One entry.** The entity size plus 90 bytes for each back-link. The 90 bytes are an assumption.

The time values come from production for the 7 days before 2026-11-19.

- **Request time.** The time that Traefik logs for a request, from the request to the last byte of the response.
  The access logs had about 202,000 paged reads.
- **Edge lookup time.** `core_relation_edges_lookup_seconds` divided by `core_relation_edges_lookup_resources`.
  This is the time to find the back-links of one entry.
- **Time for one entry.** The edge lookup time plus 57 microseconds.
  The added value is the median difference between the request time and the edge lookup time.
- **Time of a page.** 15 ms plus the number of entries multiplied by the time for one entry.
  The 15 ms are the request time of a page with one entry.
- **Check.** For 15 resources we compared the estimate with the request time of real pages. The estimate was within
  30 % for 31 of 33 pairs of organisation and resource.
- **Page size.** The request time for one entry of `fravarsregistrering` was about equal for pages of 500 and of
  200,000 entries. We assumed the same for the other resources.

## What the values do not show

- The entity size is what the adapter sent. The entry in a response can be larger, because each link becomes a full URL.
- 17 resources have no measured entity size. We assumed 700 bytes for them.
- `rolle` and `arbeidslokasjon` can be up to two times too high. This occurs if the adapter sends the same links
  that the back-links add.
- The size of the largest collection is the largest count multiplied by the largest entry.
  The two values can come from different organisations, so the real collection can be smaller.
- The back-links change with the data. Measure again before you set a limit.
- A page limit does not make one entry smaller. One `skole` stays at about 1.1 MB.
- The time is from one week with the load and the database size of that week. A busy database gives a longer time.
- The request time includes the transfer to the client. A slow client gets a longer time.
- 12 resources have no edge lookup time. We assumed 55 microseconds plus 50 microseconds for each back-link.
- 7 resources have an edge lookup time from small lookups only. Their time can be too high.
