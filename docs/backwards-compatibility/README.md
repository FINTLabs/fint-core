# Backwards compatibility with provider-gateway

adapter-gateway takes over from the shared provider-gateway one county at a time. An adapter that works against
provider-gateway today has to keep working when its traffic moves, without a code change. This page lists every place
where adapter-gateway treats an adapter differently. Each area gets its own PRD.

| Area         | PRD                                | Status      |
|--------------|------------------------------------|-------------|
| Registration | [registration.md](registration.md) | Implemented |
| Sync         | –                                  | Not started |
| Events       | –                                  | Not started |

## What was compared

|                     | provider-gateway                                                  | adapter-gateway                                  |
|---------------------|-------------------------------------------------------------------|--------------------------------------------------|
| Version             | Prod `1.11.8-hotfix.1-4.0.10`, `main` at `12f15c7`                | `develop` at `d55f77a`                           |
| infra-models        | 2.1.0-rc-5, no validation annotations                             | 3.4.2, with `@NotBlank`, `@Min`, `@Max`          |
| Spring Boot         | 3.5 with Jackson 2                                                | 4.1.1 with Jackson 3                             |
| FINT model          | resource-model-java 4.0.10, read through fint-core-metamodel      | fint-core-information-model 0.7.0                |
| fint-core-principal | 4.1.0                                                             | 4.1.0                                            |
| Contracts           | Postgres, one per username, dropped on every restart (`create`)   | Postgres, one per username and org, kept         |

Comparison made on 2026-10-19. Re-check this page when either side changes.

## What is the same

- The token is read the same way, since both use fint-core-principal 4.1.0. Every endpoint needs an adapter token with
  the `fint-adapter` scope.
- Sync paths (`POST`, `PATCH`, `DELETE /provider/{domain}/{package}/{resource}`) need the component role for the
  package, checked before the body is read.
- The org check compares the body's `orgId` with the JWT assets, with `-` and `_` read as `.`. adapter-gateway also
  ignores case and normalizes the assets, which only makes it more lenient.
- Paths, the `/provider` prefix, trailing slashes and the status codes on success: `200` on register, `201` on full
  and delta sync, `200` on delete sync and on an event answer.
- Unknown JSON fields are ignored.

## Register

| Rule                                    | provider-gateway        | adapter-gateway                                  |
|-----------------------------------------|-------------------------|--------------------------------------------------|
| `orgId` in the JWT assets               | 403                     | 403                                              |
| `orgId` belongs to this gateway's county | –                      | **403** `ORG_NOT_SERVED`                         |
| `username` equals the token's           | 403                     | 403                                              |
| `adapterId` blank or missing            | accepted                | accepted, the username is used                   |
| `heartbeatIntervalInMinutes` outside 1–5 | accepted               | accepted                                         |
| `capabilities` missing                  | 500                     | 400                                              |
| Capability names a resource not in the model | 400                | 400. Also accepts any domain and package with a common resource name |
| `fullSyncIntervalInDays` outside 1–7    | 400                     | 400                                              |
| `deltaSyncInterval` missing             | accepted                | accepted                                         |
| Same resource listed twice              | accepted, both stored   | accepted, kept once with the shortest full sync interval |
| `eventCapabilities`                     | not in the model        | new field. Leaving it out or sending `null` is fine |
| Order of checks                         | org and username first  | body first, so a request that fails both gets 400, not 403 |
| Error body                              | plain text              | `application/problem+json`, every failing field listed |

Both FINT models know the same concrete resources. The only resources in 4.0.10 that are missing from the information
model are abstract or complex types, such as `lonn`, `fagvurdering` and `noark/*`, which no adapter registers.

## Sync

| Rule                                     | provider-gateway          | adapter-gateway                                  |
|------------------------------------------|---------------------------|--------------------------------------------------|
| Component role for the path              | 403                       | 403                                              |
| `metadata.orgId` in the JWT assets       | 403                       | 403                                              |
| `orgId` belongs to this gateway's county | –                         | **403** `ORG_NOT_SERVED`                         |
| Contract for the username and org        | not checked               | **403** `NO_CONTRACT`                            |
| Resource listed in the contract's `capabilities` | not checked       | **403** `RESOURCE_NOT_IN_CONTRACT`               |
| `metadata` missing                       | 500                       | 403                                              |
| `metadata.uriRef`                        | overwritten from the path | kept as the adapter sent it, and passed on to status-service |
| Full sync with `totalSize` 0             | only the metadata is sent on | the resource is emptied in the cache          |
| A resource that does not fit the model   | sent on to Kafka as is    | accepted with `201`, then fails later in the buffer reader |

## Reading events: `GET /provider/event/...`

| Rule                                         | provider-gateway             | adapter-gateway                          |
|----------------------------------------------|------------------------------|------------------------------------------|
| Component role for a named package           | not checked                  | **403**                                  |
| Domain-only path, no role in the domain      | not checked                  | **403**                                  |
| No JWT asset belongs to this county          | –                            | **403** `ORG_NOT_SERVED`                 |
| No contract for any of the adapter's orgs    | not checked                  | **403** `NO_REGISTERED_CONTRACT`         |
| One org without a contract                   | its events are served        | its events are left out                  |
| Which operations are served                  | all                          | as listed in `eventCapabilities`. A resource that is not listed gets every operation except `READ`, which matches the old behaviour |
| Nothing to serve                             | `[]`                         | `[]`                                     |
| Order                                        | cache order                  | oldest first                             |
| Response body                                | infra-models 2.1             | adds `"filter": null` and `"id": null`, which a strict client outside Spring could refuse |

## Answering events: `POST /provider/event`

| Rule                                                  | provider-gateway       | adapter-gateway              |
|-------------------------------------------------------|------------------------|------------------------------|
| `orgId` in the JWT assets                             | 403                    | 403                          |
| `orgId` belongs to this gateway's county              | –                      | **403** `ORG_NOT_SERVED`     |
| Contract for the org                                  | not checked            | **403** `NO_CONTRACT`        |
| Component role for the request's package              | not checked            | **403**                      |
| Contract lists the resource without this operation    | –                      | **403** `EVENT_NOT_IN_CONTRACT` |
| Unknown, answered or expired `corrId`                 | 404                    | 404                          |
| `orgId` differs from the request's                    | 409                    | **404**                      |
| `operationType` missing                               | 400                    | 400                          |
| `operationType` differs from the request's            | accepted               | **400**                      |
| `value` missing on a write                            | 400                    | 400                          |
| `value.resource` does not fit the model               | accepted, sent to Kafka | **error**, most likely 500, and the event stays pending until it expires |
| `handledAt`                                           | the adapter's value is kept when above 0 | always set to the time FINT received it |

## Heartbeat

Both check only that `orgId` is in the JWT assets. adapter-gateway also requires the org to belong to its county.

## JSON parsing on every endpoint

adapter-gateway runs Jackson 3. Spring Boot 4 turns off `FAIL_ON_UNKNOWN_PROPERTIES` and otherwise keeps Jackson 3's
defaults for reading JSON. Two of them differ from provider-gateway:

| Setting                       | provider-gateway                   | adapter-gateway                       |
|-------------------------------|------------------------------------|---------------------------------------|
| `FAIL_ON_NULL_FOR_PRIMITIVES` | off: an explicit `null` reads as `0` or `false` | **on: an explicit `null` on any `int`, `long` or `boolean` field is 400** |
| `FAIL_ON_TRAILING_TOKENS`     | off                                | on, so text after the JSON is 400     |

Adapters built on infra-models never send `null` for these fields. An adapter written in another language might.
On register, `heartbeatIntervalInMinutes` and `time` are wrapper types from infra-models 3.4.2, so they accept `null`,
see [C4](registration.md#c4-accept-heartbeatintervalinminutes-null-and-keep-jackson-3s-defaults).

## Deployment

- Traefik sends a request to a county's gateway when the token's `x-allowed-asset-ids` holds that county. A token
  with assets in two counties reaches only one of the two gateways, and gets `ORG_NOT_SERVED` for the other county's
  org. provider-gateway served every org in one place.
- `GET /provider/adapter`, the list of adapter ids, does not exist in adapter-gateway. Adapters do not call it.

## Adapters register only once

fint-core-adapter-common (0.1.7) registers when the adapter starts. After one successful registration it never
registers again, and heartbeats only retry registration while the adapter is still unregistered. An adapter that is
running when its county moves to adapter-gateway therefore has no contract there until it restarts. Every check above
that needs a contract fails for it until then.
