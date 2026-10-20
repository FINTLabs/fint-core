# Operations, recovery, and upgrade rules

Read this reference for information-model, recovery, observability, deployment,
routing, build, or release changes.

## Correction and recovery

- The data broker corrects wrong source data and sends a delta, delete, or new
  full sync.
- FINT operators must never manually edit MongoDB resource documents.
- If FINT Core corrupts resources or derived relations, recovery will generally
  require a new provider sync.
- Database-level recovery for the Azure-hosted DocumentDB is being investigated;
  do not assume an unverified restore capability in an operational design.
- Continue serving stored data while correction or recovery is pending rather
  than silently hiding it.

## Information-model rollout

An information-model dependency update is a coordinated system migration, not
an ordinary library bump.

- Adapters must upgrade when the model changes.
- Coordinate the change with data brokers and counties.
- Validate adapter compatibility in beta before the production/API rollout.
- Wipe existing resource data and repopulate it through full syncs rather than
  migrating documents in place.
- Keep rollback to the previous model possible.
- A coordinated temporary period without data is acceptable during migration.
- Review both services, the shared module, relation rules, generated
  documentation, stored-data handling, deployment sequencing, and rollback.

## Observability without sensitive data

Operators must be able to detect:

- Full syncs that stall or may miss the weekend deadline.
- Kafka backlog or slow MongoDB persistence.
- Providers that miss their weekly full sync.
- Collections approaching the 21-day wipe.
- High malformed or unresolved-link counts.
- Rejected and expired request events.

Organization ID, adapter ID, resource type, correlation ID, page number, and
aggregate counts are safe operational dimensions. Individual resource
identifiers and payloads are not.

## Deployment and routing

The client API's hand-written Traefik route excludes legacy provider paths so
adapter traffic cannot be captured by the read API. Its disabled ingress stub is
an intentional operator compatibility constraint.

- Preserve the legacy provider-path exclusion unless that contract is
  deliberately removed.
- Preserve per-organization substitutions and namespace isolation.
- Treat the backtick-delimited ingress rule carefully because Kustomize
  replacements depend on its structure.
- Use the repository root as the Docker build context.
- Check `/README.md` before changing release classification, environment rollout,
  CI path filtering, or deployment behavior.
