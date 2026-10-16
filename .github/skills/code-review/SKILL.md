---
name: code-review
description: Review pull requests in the fint-core repository using its FINT domain, data-flow, persistence, linking, security, and deployment invariants. Use for GitHub Copilot code review and other requests to review changes in this repository.
---

# FINT Core code review

Review for concrete regressions against the repository's domain and architectural
contracts.

## Required context

Before evaluating the change:

1. Read `references/domain-invariants.md` completely.
2. Read the focused references that match the diff:
   - Read `references/sync-storage-events.md` for adapter gateway, Kafka,
     persistence, sync, expiry, or request-event changes.
   - Read `references/client-api-security.md` for client API, authentication,
     OPA, organization isolation, filtering, or pagination changes.
   - Read `references/links-and-relations.md` for forward-link, back-link, JSON
     serialization, relation-edge, common-resource, or URI codec changes.
   - Read `references/operations-upgrades.md` for information-model, recovery,
     observability, deployment, routing, build, or release changes.
   - Read every affected reference for shared or cross-service changes.
3. Read `/AGENTS.md`.
4. Read only the additional repository documentation relevant to the diff:
   - `/README.md` for service boundaries, CI/CD, or deployment changes.
   - `/fint-core-shared/README.md` for JSON, links, MongoDB, or shared contracts.
   - `/RELATION_RULES.md` for autorelation changes.
   - The affected module's README when it contains applicable behavior.
5. Inspect the affected production paths and their tests. Do not infer behavior
   from the changed file alone when it crosses Kafka, MongoDB, security, JSON,
   or service boundaries.

If documentation and executable behavior disagree, use code and tests to
establish the current behavior. Report documentation drift only when the pull
request introduces or materially worsens it.

## Review method

Determine which boundary the change affects:

- Adapter request to Kafka buffer.
- Kafka buffer to MongoDB and relation edges.
- MongoDB to client response.
- Authentication, authorization, or organization isolation.
- Shared serialization, storage, or Kafka contracts.
- Deployment, routing, or release automation.

Trace the changed values across that boundary. Pay particular attention to:

- Organization, resource coordinate, identifier, correlation ID, and timestamp
  propagation.
- Retry, duplicate, stale-delivery, and partial-failure behavior.
- Atomicity between resources and the relation edges they own.
- Storage-form versus response-form JSON.
- Forward links, generated back-links, common resources, and URI encoding.
- Full-sync completion and eviction.
- Pagination ordering and cursor stability.
- Authorization decisions derived from headers, claims, paths, or capabilities.

Use the business consequences in `references/domain-invariants.md` to judge
materiality. In particular, scrutinize cross-organization exposure, sensitive
data in telemetry, duplicate source-system commands, lost deliveries, broken
generated back-links, premature eviction, and slower adapter acknowledgement.
Do not overstate brief staleness during normal eventual consistency.

For changes in `fint-core-shared`, inspect both deployable services because they
share its contracts. For root Gradle, shared configuration, or information-model
changes, treat all modules as affected.

## Evidence threshold

Report a finding only when all of the following are true:

- The pull request introduces or exposes a concrete correctness, security,
  durability, compatibility, or operability problem.
- The problem can occur through a plausible execution path.
- The affected behavior and consequence can be explained from the diff and
  repository context.
- The finding is actionable at a specific changed location.

Do not report:

- Unrelated pre-existing problems.
- Pure style preferences already enforced by ktlint or the build.
- Speculative defensive checks without a realistic failure path.
- Requests for broad refactoring when the changed behavior is correct.
- Missing tests unless an important changed behavior is left unprotected.
- Generated-file changes without checking their source or generator contract.

Before raising a finding, check nearby tests and callers for evidence that the
behavior is intentional.

## Test expectations

Match verification to the affected scope:

- Client API only: `./gradlew :fint-core-client-api:check`
- Adapter gateway only: `./gradlew :fint-core-adapter-gateway:check`
- Shared module only: `./gradlew :fint-core-shared:check`
- Shared contracts, root build logic, or cross-service behavior:
  `./gradlew check`

A change to relation rules must keep `/RELATION_RULES.md` synchronized with the
generated rules test.
