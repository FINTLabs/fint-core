---
name: gradle-security-pins
description: Review Gradle and Spring Boot security overrides after dependency updates to determine which transitive dependency pins remain necessary. Use when checking whether security constraints, forced versions, or BOM overrides can be removed, rather than for general dependency upgrades.
---

# Gradle Security Pins

Assess each security override against the dependency graph that would resolve without it. A safe version selected while the override is active does not prove it is redundant.

## Scope and authorization

Default to an assessment and proposed changes. Invoking this skill does not authorize edits to build files, lockfiles, or dependency declarations. Follow repository instructions and existing user authorization before creating experimental copies or applying changes. If experiments are not authorized, inspect available evidence and mark unproven conclusions as unverified.

Preserve existing working tree changes. Assess the current checkout, including uncommitted package updates. Use a supplied base revision or diff when available to understand removed overrides, but do not assume an override from an older revision is still present. Do not install a scanner or introduce a build plugin just to run this review.

## Find overrides and their owners

Inspect settings, project builds, version catalogs, Gradle properties, convention plugins, `buildSrc`, included builds, and dependency lockfiles. Look for constraints, `strictly`, `force`, resolution rules, substitutions, explicit versions on otherwise transitive dependencies, imported platforms, and Spring Boot dependency property overrides.

For each candidate, record its location, coordinates, version rule, security rationale, and affected projects and configurations. Security intent may come from `because`, comments, an advisory, or relevant history. If intent is unclear, report it rather than treating every explicit version as a security pin. A normal Gradle constraint is not necessarily an exact version pin.

Trace the actual source of dependency management. With Spring's dependency-management plugin, inspect the imported Boot BOM and overriding properties or later BOM imports. With native Gradle platforms, inspect platform constraints and enforced platforms. Do not infer the selected version from the Spring Boot plugin version alone.

In this repository, start with `buildSrc/build.gradle.kts` and `buildSrc/src/main/kotlin/spring-service-conventions.gradle.kts`, then inspect all three `fint-core-*` modules and their locks. Treat `buildSrc` as a separate build with its own graph and lockfile. Discover current overrides rather than hardcoding dependency names or safe versions.

## Verify the security rationale

Look up current vendor advisories, maintainer security advisories, or the relevant CVE/GHSA record. Record the advisory ID, source link, affected ranges, fixed releases on the relevant version line, and date checked. Check for newer advisories affecting the proposed version too. Use an existing scanner when available as additional evidence, and state its scope and database freshness when known.

A comment saying versions before a particular release are vulnerable is a lead, not proof of the current safe range. Do not compare versions lexically or assume one fix version applies to every release line. Account for prereleases, backports, relocations, forks, and changed Maven coordinates. Do not equate similarly named artifacts without evidence.

If the original advisory cannot be identified or sources disagree, mark the security conclusion unverified. Lack of a scanner finding does not prove the dependency is safe. Configuration-based non-exploitability alone does not establish that an override is redundant.

## Inspect resolution

Use the repository wrapper and configured JDK. Capture the resolved version, selection reasons, and all introducing paths with `dependencies` and `dependencyInsight`. Inspect production compile and runtime, tests, build tooling, and other resolvable configurations reached by the candidate. Do not use `--single-path` when establishing coverage.

These complete example commands apply if the candidate coordinates are present. Adapt coordinates and configuration names to the discovered build.

```sh
./gradlew :fint-core-client-api:dependencyInsight --configuration runtimeClasspath --dependency org.xerial.snappy:snappy-java
./gradlew :fint-core-adapter-gateway:dependencyInsight --configuration testRuntimeClasspath --dependency org.xerial.snappy:snappy-java
./gradlew :fint-core-shared:dependencyInsight --configuration compileClasspath --dependency org.xerial.snappy:snappy-java
./gradlew -p buildSrc dependencyInsight --configuration runtimeClasspath --dependency org.apache.commons:commons-lang3
```

These examples are starting points, not complete configuration coverage. Follow configurations through the build before declaring an artifact absent. A constraint can remain declared even when no dependency brings in its artifact. A direct dependency added as a security workaround may itself keep an artifact present, so distinguish removing its version from removing the dependency entirely.

Keep three versions separate in the findings

- The version currently resolved with the override and locks.
- The version selected without the override while retaining existing locks, if resolution succeeds.
- The version selected without the override and without the candidate's old lock constraint.

Gradle locks can preserve a safe version after the override is removed. This does not prove upstream dependencies or the BOM provide a safe baseline. Removing an override can also create a lock conflict. Neither outcome alone establishes whether the override is needed. `--refresh-dependencies` refreshes caches and does not remove lock constraints.

## Verify removal when authorized

Use an isolated copy containing current tracked and relevant untracked build inputs, including uncommitted updates. A clean checkout of HEAD is insufficient when updates are uncommitted. Do not copy credentials or unrelated personal files. Never change the source working tree to conduct the experiment.

Establish a resolving baseline, then remove only one candidate override in the copy. Inspect the result with existing locks. Next, release the candidate's lock constraints in affected builds and configurations using a controlled temporary lock change or supported locking configuration. Account for coupled BOM or family lock constraints that prevent observing upstream selection. Record exactly which lock constraints were relaxed and any unrelated dependency movement. Keep dependency verification enabled.

Resolve all affected configurations and inspect their paths again. If another security override masks the result, identify that relationship. Before recommending several removals together, resolve the combined removal too. One-at-a-time success does not prove the combined change is safe.

For a removal candidate, run affected build checks in the copy. A shared convention change in this repository affects all three service modules. A `buildSrc` change also needs build tooling validation. Review full lockfile diffs and changes to dependency families, substitutions, and selected variants. A passing build supports compatibility but does not replace advisory evidence.

If dependencies, advisories, the JDK, credentials, or checks are unavailable, record the specific limitation and leave the affected conclusion unverified. Do not repeatedly retry the same unresolved failure or replace missing evidence with a guess.

If implementation is authorized, apply only demonstrated removals and necessary lock updates to the working tree, preserve unrelated changes, and run affected required checks. Do not mix unrelated upgrades into pin cleanup.

## Decide and report

Use one row per override, with configuration-specific detail when results differ. Include declaration location, advisory links and checked date, current version, version without the override and old lock constraint, affected configurations, selection source, verdict, and verification performed.

- **Keep** when removal selects a version affected by a verified advisory in any relevant configuration, or the override has another demonstrated requirement. If the current pin itself is vulnerable, say it needs updating rather than endorsing the pinned version.
- **Safe to remove** when removal has been verified across relevant configurations and selected versions are outside the verified affected ranges, or the artifact is demonstrably no longer introduced. Confirm combined removals and required checks. State any remaining reliance on another override, and whether safety depends only on a lockfile. Do not label a lock-only safe result as an upstream fix.
- **Unverified** when advisory, resolution, coverage, or compatibility evidence is insufficient. Explain what would resolve the uncertainty.

Give exact proposed declaration removals and lock changes. Distinguish proposals from applied changes. Include verification commands, failed or skipped checks, and material compatibility concerns. If no overrides remain, report that finding and the inspected scope without inventing candidates. Assess previously removed pins only when requested.

Use these authoritative references when Gradle or Spring Boot behavior needs checking. Match documentation to the versions in the checkout.

- [Gradle dependency insight](https://docs.gradle.org/current/userguide/viewing_debugging_dependencies.html)
- [Gradle dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html)
- [Spring Boot dependency management](https://docs.spring.io/spring-boot/gradle-plugin/managing-dependencies.html)
