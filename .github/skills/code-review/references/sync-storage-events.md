# Sync, storage, and request-event domain rules

Read this reference for changes involving the adapter gateway, Kafka, MongoDB,
sync, expiry, relation-edge persistence, or request events.

## Service and ownership boundaries

- `fint-core-adapter-gateway` is the sole writer of FINT resources.
- `fint-core-client-api` reads the resource store and does not own normal writes.
- `fint-core-shared` owns the common JSON, Kafka, storage, coordinate, and
  relation contracts.
- The platform does not record resource ownership by adapter. The latest valid
  delivery wins regardless of which adapter supplied it.
- The organization knows which broker owns which data, and overlapping providers
  should not occur. One adapter per domain is an operational assumption rather
  than resource-level isolation.
- A full sync can overwrite or evict data another adapter delivered when their
  scopes overlap. Do not claim overlapping adapters are safe without adding an
  explicit ownership model.

## Resource identity

- Identifiers are unique per resource type within the effective organization.
- The adapter chooses a synchronization identifier for each resource. It is the
  Kafka entity key and MongoDB document identity.
- The synchronization identifier must match one identifier value contained in
  the resource; FINT Core correlates the value to its identifier field.
- Clients may retrieve a resource through any of its identifier fields.
- Changing an embedded identifier overwrites the document when the adapter keeps
  the same synchronization key.
- Changing the synchronization key creates a new resource identity. The old
  identity remains until an explicit delete or completed full sync removes it.

## Sync semantics

One full sync is the complete snapshot of one resource type for one effective
organization inside a county deployment, for example every `Elev` for that
organization.

- A delta creates or overwrites only the entries it contains. Omission from a
  delta means no change.
- A delete removes the resource identified by its synchronization key.
- Omission from a completed full sync removes the omitted resource during
  eviction.
- An empty full sync is still authoritative and empties that resource scope.
- Full syncs for the same resource may overlap; they are not globally serialized.
  Delivery timestamps decide which resource version survives.
- A late replay of an older delivery must not overwrite or delete a newer one.

Full-sync pages are written directly into the live collection as they arrive.
Clients may temporarily see a mixture of old and new resources. Atomic snapshot
visibility is not a requirement.

- A partial sync is not complete, but the pages already stored remain readable.
- Never evict for an incomplete sync.
- Recovery from a failed sync is a new full sync. Previously written pages remain
  and are overwritten as the replacement arrives.
- County notification is an operational choice and must not be hard-coded.
- Operators should not manually wipe a failed sync's collection.

## Buffering and consistency

An adapter success response means its page is durably buffered in Kafka, not
that it is already queryable from MongoDB.

- Acknowledge as soon as all required Kafka writes are durable; do not wait for
  MongoDB.
- Clients may see the old value while the buffer is processed. There is no fixed
  maximum propagation delay.
- MongoDB unavailability should not stop ingestion while Kafka remains available.
- Buffered records must be retried after persistence recovers.
- Resource and owned relation-edge updates must remain consistent so a changed,
  deleted, stale, or replayed resource cannot leave incorrect derived edges.

## Expiry

MongoDB is temporary serving storage. The 21-day expiry is a business rule:

- The clock is based on completed full syncs for an organization/resource type.
- Delta activity does not extend the deadline.
- If no full sync completes for 21 days, wipe the entire resource-type
  collection, its owned relation edges, and its full-sync status.
- A recently active full sync temporarily protects the collection from the
  scheduled wipe.
- The next successful full sync repopulates the collection.

Do not confuse this prolonged-expiry rule with full-sync eviction of individual
resources omitted from a successfully completed snapshot.

## Request events

Request events let a county ask an adapter to perform work in the source system,
such as creating an `Elev`. Some integrations use them as a near-direct channel
without a database read between the client and source system.

- Clients receive a correlation ID and poll until answered or expired.
- Multiple adapters may observe an event, but exactly one valid answer may claim
  it. Later or duplicate answers must not replace it.
- An unanswered event expires at its deadline.
- A successful `CREATE` immediately stores the returned resource.
- A successful `VALIDATE` never mutates the resource store.
- A successful `DELETE` immediately deletes the resource.
- Rejected, conflicted, failed, and expired events do not mutate resources. A
  conflict may return the conflicting resource without storing it.
- Trace the adapter contract before changing delete identification; do not invent
  an identifier format where the contract is unclear.
- Duplicate source-system commands are not acceptable unless the user explicitly
  submits the operation again.
- The next full sync converges immediate event results with the authoritative
  source system.

## Scale

The largest counties can reach roughly 3–4 million `fravarsregistrering`
resources by year-end, and a full sync contains the entire set.

- Pages currently arrive mostly sequentially and must finish over the weekend.
- Fast acknowledgement is required because of current provider limitations.
- Do not hold a full sync in memory or add per-resource network/database calls.
- Unbounded `get all` currently loads a collection into memory; this is a known
  limitation being removed, not a pattern to copy. Pagination is intended to
  become mandatory.
- Avoid unnecessary ordering assumptions so future concurrent page delivery is
  possible.
