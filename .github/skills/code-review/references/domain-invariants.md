# FINT Core domain invariants - "Rules of the game"

Read this reference for every review. It records business intent that cannot be
derived reliably from implementation details. Use the focused references for
subsystem-specific contracts.

## Purpose and ownership

FINT Core is a temporary interface between Norwegian counties and third-party
data brokers. A broker delivers data from an authoritative source system; the
county owns the data. FINT Core standardizes it as FINT information-model
resources and serves it to county clients.

- FINT Core is not the system of record and must not become the manual editor of
  provider data.
- The data broker is responsible for correcting bad data and redelivering it.
- Operators must never repair resource documents manually in MongoDB.
- Continue serving stored data even when it is known to be wrong. Do not hide,
  rewrite, or fabricate provider data to make it look correct.
- Normal recovery is a delta, delete, or new full sync from the provider.

## Information-model contract

Resources must follow the centrally maintained, versioned
[FINT information model](https://informasjonsmodell.felleskomponent.no/docs).

- The adapter and FINT Core must use the same model version.
- Older model versions are not supported during an upgrade.
- The contract is strict even where runtime validation is currently incomplete;
  accidental acceptance does not make malformed data supported.
- Model changes can require deliberate migration work. Do not assume renamed
  fields, identifiers, relations, or multiplicities are backward-compatible.

## Review priorities

Judge findings by real-world consequence:

1. Cross-organization disclosure is the most serious failure and must be
   prevented.
2. Sensitive data in logs, metrics, errors, or support artifacts is a serious
   privacy failure.
3. Duplicate source-system commands are unacceptable unless the user
   deliberately repeats the action.
4. Lost adapter deliveries, incorrect generated back-links, premature eviction,
   and slower adapter acknowledgement are important operational failures.
5. Briefly stale reads are normally tolerable because most source updates happen
   over the weekend and sync ingestion is eventually consistent.
6. An empty result is acceptable when no data exists. Never substitute data from
   another organization or resource type.

An incomplete full sync must not remove the previous dataset. The provider
restarts it. The separate 21-day expiry rule still applies when no full sync
completes for a prolonged period.

## Privacy

Treat every resource as sensitive data.

- Never log complete resource payloads.
- Never log national identity numbers, including personnummer and
  fødselsnummer.
- Never place individual resource identifiers in metric labels or diagnostic
  telemetry.
- Be equally cautious around deserialization failures, Kafka records, MongoDB
  documents, links, sync entries, and event bodies.

Safe operational dimensions are organization ID, adapter ID, resource type,
correlation ID, page number, and aggregate counts.

## Client compatibility

Any externally observable API change may break county integrations, including:

- JSON fields, resource paths, and identifier URLs.
- Forward links, generated back-links, and `self` links.
- HTTP status codes and error bodies.
- Pagination, ordering, filtering, and timestamp semantics.
- Additive fields that strict downstream clients may not tolerate.

Breaking changes are possible, but they must be identified, coordinated, and
announced well in advance. Compilation against the current model does not prove
client compatibility.
