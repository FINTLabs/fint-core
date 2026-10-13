# PRD: Stream full-dump list reads

Branch: `feature/stream-full-dump`. Status: agreed and implemented 2026-10-13.

## Problem

A list read without `size` (or `size=0`) is a full dump. `ResourceService.getResources`
loads the whole collection with `findAll`, converts every entry to a `FintResource`, loads
every relation edge of the type, and, when OPA is on, copies the whole response into a
`JsonNode` tree before Jackson writes a byte. Three to four copies of the collection sit in
memory at once. The pod has one replica, a 4 Gi limit and `-XX:+ExitOnOutOfMemoryError`, so
one oversized dump, or a few at the same time, takes the org's API down.

Forcing clients to page would fix this but is not possible today. The HTTP side already
sends large bodies as `Transfer-Encoding: chunked`; the filter that forced `Content-Length`
was dropped in the June 2026 restart. So the response can be written while the collection is
read, and memory becomes a function of the batch size, not the collection size.

## Goal

A full dump of any size completes with bounded memory per request, and the JSON a client
receives is the same as today.

## Scope

In scope: `GET /{domain}/{package}/{resource}` and `POST .../$query` when `size <= 0`, with
or without `sinceTimeStamp` and `$filter`.

Not in scope, unchanged: paged reads (`size > 0`), reads by id, `last-updated`, `cache/size`,
the status endpoint for live reads, and everything in the adapter-gateway.

## Behaviour

### Response shape

The same JSON as today, fields in the same order:

```json
{
  "offset": 0,
  "total_items": 12345,
  "_embedded": { "_entries": [ ... ] },
  "_links": { "self": [ { "href": "..." } ] },
  "size": 12345
}
```

`offset` and `total_items` are known when the dump opens, so they lead. `size` is how many
entries were written, so it closes the body.

- `_entries` holds the resources after `$filter`, in `createdAt, _id` order as today.
- `total_items` is the collection count before `$filter`, from the same `count` the paged
  read uses: cached for five seconds when there is no `sinceTimeStamp`, a live count when
  there is one. It can differ by a few from the entries streamed if a sync lands mid-dump,
  which the paged read already accepts. It is never below the number of entries written.
- `size` is the number of entries written.
- `offset` echoes the request parameter, as today.
- `self` is the same link as today (`sinceTimeStamp` kept when given).
- Every resource renders `_links` through the existing response mapper, so hrefs, `self`
  regeneration and the common-resource component resolution are untouched. Autorelation
  back-links are merged in before rendering, as today.
- When OPA is enabled, fields and relations are pruned per resource with the same rules
  `OpaFieldAdvice` applies today. The pruning logic lives in `OpaFieldPruner`, which the
  advice keeps using for the by-id, paged and status responses.

### Reading

- The entries are read through a Mongo cursor (`MongoTemplate.stream`) with the same query
  and sort as `findAll` today, in batches of `batch-size` entries.
- Per batch: convert to `FintResource`, fetch the relation edges for the batch's identifiers
  with the existing `findByTargets` (the paged path's pattern), merge, apply `$filter`, prune
  for OPA, write.
- The read runs on the request thread. With virtual threads enabled there is no need for
  `StreamingResponseBody` or async dispatch, and the request context the response mapper
  reads stays available.

### Always streamed

Every full dump is written as it is read, with no in-memory spool. Memory held by a request
is one batch plus Tomcat's output buffer, whatever the collection size and however many
dumps run at once. The body goes out as `Transfer-Encoding: chunked`, which is what large
bodies get today. There is no `Content-Length`, which large bodies do not get today either.

### Before the first byte

All of this runs before anything is committed, so these failures keep today's status:

| Failure | Status |
|---|---|
| Invalid `$filter` syntax | 400, as today |
| Missing `x-org-id` | 400, as today |
| OPA denial, wrong org, missing role | 403 or 401, as today |
| Mongo unreachable, cursor cannot open, first batch fails | 500, as today |

The first batch, its edges and its `$filter` evaluation complete before the head is written,
so a filter expression that fails on evaluation is caught up front in the common case.

### Failure mid-stream

A Mongo failure on a later batch cannot change the status, which was committed with the
first batch. The rules:

1. The writer stops immediately. Nothing is appended after the last complete entry.
2. The exception propagates as `ResourceDumpAbortedException`. `ResourceExceptionHandler`
   takes it with a handler that writes nothing, so the body never contains an error document
   after the entries.
3. The body is therefore unparseable JSON: the `_entries` array is never closed and `_links`
   and `size` never appear. A client that parses the body cannot mistake it for a complete
   result.
4. The failure is logged at error level with the path, the number of entries written and the
   cause, and counted in a Micrometer counter `fint.client.list.stream.aborted`.
5. On the wire, Tomcat finishes the chunked encoding after the exception, so the client's
   read succeeds with status 200 and the truncated body. This was observed against a real
   Tomcat and is pinned by `ResourceDumpIT`. The body is what gives the failure away.

A client that disconnects mid-stream is treated as a non-event: logged at debug, not counted
as an abort.

### Concurrency

No cap. Memory per request is bounded by the batch, so concurrent dumps add a few megabytes
each, not copies of the collection. Each streaming request holds one Mongo connection and
one server cursor for its duration; the driver's default pool of 100 is far above any
realistic number of simultaneous dumps for one org. A Micrometer gauge
`fint.client.list.stream.active` shows how many are in flight so this can be watched.

### Configuration

Under `fint.consumer.list-read`:

| Key | Default | Meaning |
|---|---|---|
| `batch-size` | 500 | Entries per cursor batch and per edge lookup |

## Implementation

- `ResourceStore.streamAll(since, batchSize, collectionName): Stream<ResourceEntry>` next to
  `findAll`, same query, `cursorBatchSize` set, caller closes the stream. `findAll` and
  `findAllByTargetType` stay in the shared module for the adapter-gateway's tests.
- `ResourceService.openDump` validates the filter and the resource type, runs the count,
  opens the cursor and returns a `ResourceDump`: the count, the offset, the self link and a
  lazy sequence of batches. Each batch is converted, gets its edges through `findByTargets`
  and is filtered when the writer pulls it. Closing the dump closes the cursor.
- `ResourceService.getResources` is the paged read only and refuses a size of zero or less.
- `ResourceDumpWriter` owns the JSON generator, the batch loop, OPA pruning, the counter and
  the gauge. It pulls the first batch before writing the head.
- `OpaFieldAdvice` keeps its `ResponseBodyAdvice` role and delegates to `OpaFieldPruner`,
  which the writer uses per resource.
- `ResourceController.getResource` keeps the live-read check first, then for `size <= 0`
  opens the dump, hands it to the writer with the servlet response and returns null, and for
  `size > 0` returns the `ResponseEntity` as today.
- `ResourceExceptionHandler` has a void handler for `ResourceDumpAbortedException` that takes
  the response and writes nothing. The security handlers run before the controller and need
  no change.

## Tests

- `ResourceDumpWriterTest`: the writer produces the same JSON, fields in the same order, as
  today's `FintResourcesResponse` serialized with the same mapper; `total_items`, `offset`
  and the self link come from the dump; an empty dump; a failure on the first batch leaves
  the response uncommitted; a failure on a later batch leaves an unparseable body, is
  counted and rethrown as aborted; a client that disconnects is not an abort; the gauge
  returns to zero; OPA pruning per entry, and pruning to nothing without a decision.
- `ResourceServiceTest`: `openDump` counts, opens the cursor and batches by the configured
  size; reads nothing until a batch is pulled; closes the cursor; honours `sinceTimeStamp`
  in the count, the read and the self link; filters per batch with `total_items` unfiltered;
  refuses an invalid filter and an unknown type before touching the store.
- `ResourceDumpIT`, real Tomcat: a dump of several batches arrives complete and chunked, also
  through `$query`; a failure on the first batch is a 500; a failure on a later batch gives
  200 with a body that does not parse and no error document appended.
- `ResourceStorePagingIT`: `streamAll` yields every entry in order, with and without a
  timestamp.
- The slice tests that stubbed `getResources` for a read without size now stub `openDump`.

## Decided

- No concurrency cap: a new 503 would break the contract with today's clients.
- No spooling: it only preserved a clean error status for small bodies and added a second
  code path. Every full dump streams.

- `total_items` from `count()`, with its cache, as the paged read does.
- Batch size 500 under `fint.consumer.list-read.batch-size`.
- The PRD stays on the branch; whether it is merged is decided at review.
