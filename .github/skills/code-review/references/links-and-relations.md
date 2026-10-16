# Links and relations

Read this reference for forward-link, back-link, JSON serialization,
relation-edge, common-resource, or URI codec changes in any module.

## Ownership and ingestion

Adapters are responsible for forward-link content. FINT Core is responsible for
derived back-links.

- Store a resource even when a link is malformed, unresolved, or points to a
  missing target.
- Link quality is monitored and analyzed after ingestion; it is not an ingestion
  gate.
- Preserve provider data and expose it as received rather than silently fixing,
  dropping, or hiding bad forward links.
- Do not trust adapter-supplied back-links as authoritative.
- Generated back-link correctness is a FINT Core responsibility and an important
  review concern.

## Relation edges and back-links

Back-links are derived from stored relation edges and merged into targets at
read time. They are not ordinary provider fields persisted into target
resources.

- The linked target does not need to exist when the source is ingested.
- Saving changed source content replaces the complete set of edges owned by that
  source so removed forward links cannot leave stale back-links.
- Deleting or evicting a source removes the relation edges it owns.
- Stale or unchanged deliveries must not accidentally destroy valid edges.
- Resource and owned-edge mutations must remain transactionally consistent.
- Merge generated back-links before filtering when filters can inspect
  relations.
- Changes to supported autorelations must keep `/RELATION_RULES.md` synchronized
  with its generating test.

## JSON link forms

Storage and county-facing responses intentionally use different link forms:

- MongoDB and Kafka storage form use `idField` and `idValue`, omit `self`, and use
  `FintJson.storageMapper()`.
- HTTP response form renders absolute `href` values, regenerates `self`, and uses
  `FintJson.responseMapper(...)` at the HTTP boundary.
- Convert inbound adapter hrefs to identifier links once during ingestion.
- Preserve unresolved inbound hrefs instead of discarding them.
- Use `LinkCodec` for identifier encoding and decoding; do not add ad hoc URI
  encoding.

## Common resources

Person, Kontaktperson, and Virksomhet have no single component path. Their hrefs
depend on the component through which the current request serves them.

- Resolve their component from the current request.
- Do not persist environment- or component-specific hrefs.
- Do not invent a default component outside a request.
- Ensure the response-form serializer also handles nested resources.
