# MongoDB-backed FintCache

## Why MongoDB

The in-memory `FintCache` consumed up to 30 GiB of heap per pod on weekly bulk syncs and had to refill from Kafka on every restart. Moving the cache to MongoDB:

- pushes the working set off the JVM heap onto disk,
- persists data across restarts so consumers do not have to replay Kafka from scratch (subject to durable consumer offsets, which is a separate concern),
- keeps the public `FintCache` API unchanged so callers (REST controller, eviction service, sync tracker, auto-relation) are not affected.

## Deployment model

MongoDB runs as a **sidecar container** inside the same pod as the consumer, backed by a **persistent volume**. The consumer connects via `mongodb://localhost:27017/fintcache` by default. The connection string is configurable through `spring.data.mongodb.uri`.

There is exactly one Mongo instance per consumer pod. No replication, no sharding. The volume is the unit of durability.

## Schema

One collection per resource type:

```
cache_{resourceName_lowercase}
```

The prefix is exposed as `CacheService.COLLECTION_PREFIX`. Example: resource `Elev` maps to collection `cache_elev`.

Document layout (produced by `CacheDocumentCodec`):

```
{
  _id:        <resourceId>,                  // string, primary lookup
  timestamp:  <Long>,                        // Kafka record timestamp
  type:       <fully qualified class name>,  // for Jackson deserialization
  data:       "<JSON string>",               // serialized FintResource
  identifiers: [                             // flattened identifikators
    { key: "<lowercase id field>", value: "<idValue>" },
    ...
  ]
}
```

The resource payload is stored as a **JSON string**, not as a nested BSON document. This keeps the schema flat, avoids registering FintResource subtypes with the Mongo POJO codec, and lets `ODataFilterService` run unchanged on deserialized Java objects.

## Indexes

Each collection has two compound indexes, created lazily on first access in `FintCache.ensureIndexes()`:

| Name | Fields | Purpose |
|---|---|---|
| `timestamp_id_idx` | `(timestamp ASC, _id ASC)` | sorted listing, `sinceTimestamp` range queries, eviction by `timestamp < X` |
| `identifiers_idx` | `(identifiers.key ASC, identifiers.value ASC)` | `getByIdField` lookups |

`spring.data.mongodb.auto-index-creation` is set to `false` — index creation is owned by `FintCache`, not by Spring Data annotations.

## Method-by-method behavior

| FintCache method | Mongo operation |
|---|---|
| `put(id, resource, ts)` | Under write lock: read existing `timestamp` for `_id`. If existing > new, reject. Otherwise `replaceOne({_id:id}, doc, upsert=true)`. |
| `get(id)` | `findOne({_id:id})` → deserialize via `type` field. |
| `getByIdField(field, value)` | `findOne({identifiers: {$elemMatch: {key: lowercase(field), value: value.toString()}}})`. |
| `getList(size, offset, since, filter)` | Cursor over `{timestamp: {$gte: since}}` (or empty) sorted by `(timestamp, _id) ASC`. Stream → deserialize → in-app OData filter → skip/limit → `toList()`. Cursor closed on exit. |
| `remove(id, ts)` | `deleteOne({_id:id, timestamp: {$lt: ts}})` — single round-trip with the timestamp guard baked into the filter. |
| `evictExpired(ts)` | Read all docs with `timestamp < ts` into memory, return `(id, resource)` pairs to caller, then `deleteMany({timestamp: {$lt: ts}})`. |
| `lastUpdated` | In-memory `AtomicLong`, primed on `FintCache.init` from `max(timestamp)`. Updated on accepted `put` / `remove`. |
| `size` | `countDocuments({})`. |

A per-`FintCache` `ReentrantReadWriteLock` serialises writes within the JVM so the timestamp-monotonicity guarantee holds without relying on Mongo conditional writes. Reads are concurrent.

## OData filter

OData `$filter` is **applied in-app**, not pushed down to Mongo. The Mongo cursor is streamed in `(timestamp, _id)` order, each document is deserialized to its FintResource subtype, and `ODataFilterService` filters the resulting `Stream<T>` before pagination. This keeps OData semantics identical to the in-memory implementation; the cost is that `getList` with no `size` cap still deserializes every matching document.

## Lifecycle and operational notes

- **Startup:** `CacheService.getCache(resourceName)` is `computeIfAbsent`. On first call per resource, `FintCache.init` ensures indexes and primes `lastUpdated` with one aggregation query.
- **Eviction:** `CacheEvictionService.evictExpired` is `@Async` and runs after a full sync completes. The expired set is materialised in heap; on very large sweeps this can be a memory pressure point. Batch the cursor if it becomes a problem.
- **Write latency:** every Kafka record now triggers a Mongo round-trip. There is no bulk-write batching yet. If ingest throughput becomes the bottleneck, introduce `BulkOperations` in `EntityProcessingService` or in `FintCache.put`.
- **Backup / restore:** the persistent volume holds the entire cache state. A volume snapshot is enough to restore.

## Local development

`application-local.yaml` points at `mongodb://localhost:27017/fintcache-local`. To run locally you need a Mongo on `localhost:27017`. Quick start:

```
docker run -d --name fintcache-mongo -p 27017:27017 mongo:7.0
```

Then `./gradlew bootRun --args='--spring.profiles.active=local'` as usual.

## Tests

Integration tests use a **singleton Testcontainers Mongo** per JVM. The container is started by `MongoTestcontainerInitializer`, a JUnit Jupiter extension registered via the service loader:

- `src/integrationTest/resources/META-INF/services/org.junit.jupiter.api.extension.Extension`
- `src/integrationTest/resources/junit-platform.properties` enables extension autodetection.

The extension:
1. Lazily starts a `MongoDBContainer("mongo:7.0")` on first access.
2. Sets `spring.data.mongodb.uri` system property so Spring autoconfig picks it up.
3. In `beforeAll`, drops every `cache_*` collection in the test database so prior test classes cannot pollute the next one.

Unit tests do not exercise `FintCache` directly any longer. The cache test (`FintCacheTest`) and the eviction service test (`CacheEvictionServiceTest`) live under `src/integrationTest/` because they require a real Mongo instance.

## Configuration reference

| Property | Default | Description |
|---|---|---|
| `spring.data.mongodb.uri` | `mongodb://localhost:27017/fintcache` | Mongo connection string. Override per environment. |
| `spring.data.mongodb.auto-index-creation` | `false` | Disabled. Indexes are created by `FintCache.ensureIndexes()`. |

## Out of scope for this migration

- **Kafka offset strategy.** The consumer still replays from whatever offset its consumer group resumes from. Persisting cache state in Mongo only helps avoid replay if the consumer group also resumes from a committed offset.
- **Bulk writes on the ingest path.** Each Kafka record triggers an individual Mongo round-trip.
- **OData filter pushdown.** Filtering still runs in the JVM after deserialization.
