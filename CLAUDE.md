# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Gradle monorepo for the FINT core platform that brokers FINT resources between adapters (data
sources) and county clients. Two deployable Spring Boot services plus a shared engine library:

- **`fint-core-provider-gateway`** — adapter-facing. The **sole writer** of FINT resources: ingests
  adapter sync pages, runs autorelation and eviction, writes into MongoDB. Web MVC + JPA (Postgres,
  for adapter contracts) + OAuth2 resource server. Servlet context-path `/provider`.
- **`fint-core-consumer`** — client-facing. **Read-only over the Mongo resource store.** Serves a
  HATEOAS REST API and forwards client mutations to adapters over Kafka. Spring MVC on virtual threads.
- **`fint-core-resource-store`** — shared `java-library` (the "resource engine"): the Mongo cache,
  autorelation, link mapping, metamodel reflection. Depended on by both services.

The platform runs **per org**: one provider + one consumer + one MongoDB per org, isolated and scaled
per org. There is **no shared cross-org state** at runtime.

`README.md` (repo root) is the authoritative reference for **versioning, CI, CD, and kustomize
deployment** — consult it rather than re-deriving those. This file covers code architecture and the
build/test workflow.

## Build / test / run

JDK 25 toolchain, Kotlin 2.3.21, Spring Boot 3.5.12, Gradle 9.5.1 (via wrapper).

```bash
./gradlew build                              # build everything
./gradlew check                              # all module checks (unit + integration + ktlint)
./gradlew :fint-core-consumer:check          # one module's full gate
./gradlew :fint-core-consumer:test           # consumer UNIT tests only
./gradlew :fint-core-consumer:integrationTest # consumer integration tests (separate source set)
./gradlew :fint-core-provider-gateway:test   # provider tests (unit + integration live together here)

# single test class / method
./gradlew :fint-core-consumer:test --tests "no.novari.fint.core.consumer.resource.ResourceServiceTest"
./gradlew :fint-core-consumer:integrationTest --tests "*KontaktpersonLinkIT*"

./gradlew ktlintCheck                         # lint (consumer + resource-store only; NOT provider)
./gradlew ktlintFormat                        # auto-fix

# run a service locally (start docker-compose first; use the 'local' profile)
docker compose up -d                          # Kafka, Kafka UI, Mongo, Postgres, toxiproxy
./gradlew :fint-core-provider-gateway:bootRun
./gradlew :fint-core-consumer:bootRun

# images build from the REPO ROOT (the Dockerfile copies the whole monorepo)
docker build -f fint-core-consumer/Dockerfile .
```

Integration tests require Docker (Testcontainers: Mongo, Kafka, Postgres). CI runs
`./gradlew :<module>:check` per module, path-filtered; a change to `fint-core-resource-store/**` or
root Gradle files rebuilds both services. The `ci-ok` job is the single required gate.

## Architecture

Both services are thin shells around the shared engine in `fint-core-resource-store`. All first-party
code lives under `no.novari.fint.core.*` (Gradle group `no.novari`): the engine under
`no.novari.fint.core.shared.{cache, autorelation, reflection, resource, link, sync}` (shared by both
services), the apps under `no.novari.fint.core.{consumer,provider}.*`. **External FINT libraries keep
their `no.fintlabs.adapter` / `no.fintlabs.status` / `no.fint.antlr` names — never rename those.** Both
`Application` classes component-scan `no.novari` with `@ConfigurationPropertiesScan`.

**Two distinct data paths — keep them separate in your head:**

1. **Resource ingestion (adapter → store).** Adapter `POST/PATCH/DELETE`s sync pages to the provider
   `ProviderController` (`{domain}/{package}/{entity}`, FULL/DELTA/DELETE). `SyncPageService.doSync`
   flattens the page onto a per-org Kafka buffer topic (`<org>.<context>.entity.adapter-sync`, one record
   per resource entry, plain spring-kafka) and acks the adapter once the broker confirms — **no Mongo
   I/O on the request thread**, so the ack no longer means "persisted". The provider's own
   `SyncIngestListener` batch-consumes (`max.poll.records`/`idleBetweenPolls` bound Mongo throughput)
   → `ResourceCacheWriter.writeBatch` converts + link-maps each resource, bulk-upserts into the Mongo
   cache, applies autorelation back-links, and (on FULL sync) feeds `SyncCompletionTracker` so
   `ProviderEvictionService` can evict stale entries; unconvertible records go to the `.DLT` topic.
   The **consumer reads this same Mongo** via
   `ResourceService` → `CacheService` and serves it. The consumer no longer consumes resource/entity
   Kafka topics.

2. **Client write-back (client → adapter).** Client `POST/PUT`s to the consumer `ResourceController`
   → `RequestFintEventService` publishes a `RequestFintEvent` to Kafka and returns `202` with a
   `…/status/{corrId}` location. The provider's `RequestFintEventConsumer` relays to the adapter; the
   adapter's `ResponseFintEvent` is consumed back (`EventResponseConsumer`) into an `EventStatusStore`
   the client polls. **No client write touches the Mongo cache directly.**

**The shared engine (`fint-core-resource-store`):**

- **`CacheService` / `MongoDBFintCache`** — one Mongo collection per resource type, named
  `cache_<key>`. Writes are timestamp-monotonic conditional upserts (a single `updateOne`/`bulkWrite`,
  no JVM lock) so concurrent replicas sharing one Mongo can't regress an entry. Bidirectional
  relations (back-links) live as individual rows in a shared **`backlinks`** collection — one row per
  `(coll, target, relation, ref)`, indexed for O(matches) add/remove/lookup — **not** as an array on
  the target doc (that array was unbounded and pegged Mongo under load). A target's back-links are
  merged into its `_links` on read via one batched query; there is no stub-document model. A shared
  `cache_meta` collection tracks `lastUpdated` + a monotonic `version` (the latter invalidates the
  memoised `size`) per collection. OData `$filter` is applied in-app over the streamed cursor.
- **`AutoRelationService`** — reconciles relations by diffing the source's *desired* target set
  against the targets that *already* hold the back-link (looked up per sync page with one batched
  `findIdsByBackLinks` per `(target collection, relation)`), then applying the delta as one bulk write
  per target collection. Rules come from `RelationRuleRegistry` (built from the metamodel). The full
  rule table is generated into `fint-core-consumer/RELATION_RULES.md` by `RelationRuleDocGeneratorIT`.
- **`ResourceRef`** — the canonical identity. Resource names are **not** globally unique (`person`
  exists in many components), so everything keys off the qualified `domain_package_resource` (e.g.
  `utdanning_vurdering_sluttvurdering`), never the bare name. Use `ResourceRef.keyOf(...)` /
  `fromKey(...)`.
- **`ReflectionInitializer` / `ReflectionCache`** — at startup, scans `no.novari.fint.model.*`
  subtypes (via the `reflections` lib) to map package → resource/meta/abstract/reference classes.
  This is why each service pins the `no.novari:fint-*-resource-model-java` dependencies — they supply
  the concrete model classes the reflection scan discovers.
- **`LinkService` / `ResourceConverter` / `ResourceContext`** — convert raw payloads to typed
  `FintResource`s and rewrite HATEOAS `_links`.

## Versioning & the information-model coupling

Release tags carry **two** versions: the service SemVer and the information-model ("imodel") version,
e.g. `v1.0.0-rc.1-4.0.30` (full details in `README.md`). The imodel is pinned **per module** as
`def fintVersion` in each `build.gradle` and the two modules can legitimately differ (currently
consumer `4.0.30`, provider `4.0.10`). Bumping the information model = change `fintVersion` in the
module(s) **and** retag with the matching imodel suffix. The provider also reads its version from
`RELEASE_VERSION` env at build time.

## Conventions & gotchas

- **Mixed Java + Kotlin in one tree.** New code is mostly Kotlin; Lombok is active (the Kotlin Lombok
  plugin is applied) so Java classes use `@RequiredArgsConstructor` / `@Slf4j`. Most files carry no
  comments — match that.
- **`kotlin.version=2.3.21` is pinned in `gradle.properties`** and overrides Boot's managed Kotlin.
  If you change the Kotlin plugin version, change it here too or the BOM-managed `kotlin-*` artifacts
  will drift from the compiler.
- **ktlint is applied to consumer + resource-store only**, not provider. `:check` includes
  `ktlintCheck` for those two.
- **Consumer has a dedicated `integrationTest` source set** (`src/integrationTest`, a `JvmTestSuite`);
  `*IT.kt` classes live there and run after unit tests. The **provider keeps integration tests in
  `src/test`** using Spring Boot `@ServiceConnection` Testcontainers (`TestcontainersConfiguration`).
- **Consumer tests run serially** (`maxParallelForks = 1`, JUnit parallelism disabled). Consumer
  integration tests share a singleton Mongo started by a globally-registered JUnit extension
  (`MongoTestcontainerInitializer`, via `META-INF/services`) that wipes `cache_*`/`sync_*` between
  classes — don't assume an empty DB inside a class.
- **`jar { enabled = false }`** in both services — they produce `bootJar`s.
- **Per-org config flows from `fint.org-id`** into `novari.kafka.topic.org-id` and the Mongo URI. The
  Mongo connection string is injected by the FLAIS operator from 1Password, not held in config.
- **Kafka** goes through `no.novari:kafka` (`ParameterizedListenerContainerFactoryService`,
  topic-name patterns), not raw `@KafkaListener`. The consumer staggers listener startup
  (`KafkaListenerStartupJitter`) to avoid a thundering herd when many consumers restart at once.
- **Auth:** both services are **servlet** OAuth2 resource servers with their own `SecurityConfiguration`
  built on `no.novari:fint-core-principal` (JWT → `CorePrincipal`). Provider requires the adapter scope
  + per-component authorization on sync paths. Consumer (own OPA layer in
  `no.novari.fint.core.consumer.security`) authorizes per component then calls OPA, and prunes response
  fields/relations via `OpaFilter` + a servlet `ResponseBodyAdvice`; gated by `fint.security.enabled` /
  `fint.security.opa.enabled`. The reactive `core-resource-server` was dropped when the consumer moved
  to MVC.
