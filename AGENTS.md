# Repository Guidelines

## Project Structure & Module Organization
This Gradle monorepo contains three main modules: `fint-core-client-api` for client-facing read APIs, `fint-core-adapter-gateway` for adapter-facing write and sync flows (an adapter sends data to the provider), and `fint-core-shared` for shared MongoDB, Kafka, and resource utilities. Source code is under each module's `src/main`, tests under `src/test`, and Spring configuration under `src/main/resources`. Shared Gradle conventions live in `buildSrc`, local infrastructure in `docker-compose.yaml`, and Kubernetes/Kustomize manifests in `kustomize/`.

## Domain Information
This system is for keeping data as an interface between the end users (the counties) and the data providers.
The counties interact with the Consumer. The Consumer contains the public facing API for requesting data.
The Provider receives data from the data providers, then puts the unprocessed data into a kafka buffer.
It then reads from that buffer again to process the data and persist it in a database. Our biggest data providers
requires us to acknowledge that the data is received very fast. That is why we have the kafka buffer.
When a data provider wants to deliver data they implement what we call an Adapter.
We keep the data as FINT Informasjonsmodell objects. This is to keep a standard across all data providers.

## Working status
We gathered the consumer and provider to this repo to have them as a monorepo. We are going away from reactive spring,
and are rewriting to have a database instead of in-memory cache. There is mostly likely still traces of the old
architecture here and there.

## Build, Test, and Development Commands
Use the repository Gradle wrapper.

- `./gradlew :fint-core-client-api:check` runs consumer tests and ktlint.
- `./gradlew :fint-core-adapter-gateway:check` runs provider tests and ktlint.
- `./gradlew :fint-core-shared:check` verifies shared code.
- `./gradlew check` verifies for all packages.
- `docker compose up -d` starts local Kafka, MongoDB, Postgres, Kafka UI, and toxiproxy.
- `./gradlew :fint-core-adapter-gateway:bootRun` starts the provider locally.
- `./gradlew :fint-core-client-api:bootRun` starts the consumer locally.

## Coding Style & Naming Conventions
Java 25 is configured through the Gradle toolchain. Kotlin and Java coexist in the services; follow existing package names such as `no.fintlabs...` and `no.novari.core.shared...`. Use 4-space indentation and keep classes focused by feature package. Kotlin formatting is enforced by ktlint via `check`.

## Testing Guidelines
Tests use JUnit Jupiter, Spring Boot Test, MockK/Mockito, Awaitility, and Testcontainers. Name unit tests `*Test` and integration tests `*IT`, matching examples such as `RequestCacheTest.kt` and `ProviderControllerIT.kt`. Run the affected module's `check` task before opening a PR.

## Commit & Pull Request Guidelines
Recent commits use short, lowercase type prefixes such as `feat:`, `fix:`, `refactor:`, `docs:`, `style:`, `test:`, `chore:`, and scoped forms like `test(provider):`. PRs should describe the change, list affected modules, link relevant issues, and include verification commands. Include manifest or configuration notes when changing `kustomize/`, Docker, or Spring YAML files.

## Linking
We provide HATEOAS linking in the responses from our Consumer. However we only receive links one way when recieving the data. We have to set the backlinks when the end-user queries the API.
For example, and we can receive an Elev and an Elevforhold. The Elevforhold comes with a link to the Elev, but we have to create the link back to Elevforhold if an Elevforhold is queried.

## Destructive changes
Note that we have not pushed this to production, so we do not need to worry about destructive changes yet.

## Release
Changes to develop automatically releases to beta. Changes to main automatically release to production (Which we call API).
