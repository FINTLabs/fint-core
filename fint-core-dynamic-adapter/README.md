# fint-core-dynamic-adapter

Generates synthetic FINT resources as `FullSyncPage` JSON files. One-shot: configure, run, it
writes the pages and exits. No provider communication yet — the output files are shaped exactly
like the sync pages an adapter would `POST` to the provider-gateway, so a future publishing layer
can send them verbatim.

## Run

```bash
# full production-shaped dataset (~5.4M entities — needs a fat heap)
./gradlew :fint-core-dynamic-adapter:bootRun

# same proportions at 2% volume
./gradlew :fint-core-dynamic-adapter:bootRun --args='--synthetic.scale=0.02'

# override or extend the built-in dataset from a file (path relative to the module dir)
./gradlew :fint-core-dynamic-adapter:bootRun --args='--spring.config.additional-location=file:./my-overrides.yaml'
```

The default dataset lives in code (`config/Datasets.kt`): real production counts per resource,
taken as the per-resource maximum of Core 1/Core 2 cache sizes (Grafana snapshot, 2026-06-10).
Anything under `synthetic.resources` in config overrides the built-in spec per resource key —
set `count: 0` to drop a resource, add new keys to extend.

Output lands in `synthetic.output-dir` (default `build/synthetic-data`): one directory per
resource (`<domain>_<package>_<resource>/full-sync-page-NNNN.json`) plus a `manifest.json` that
doubles as the run's audit report — per resource: entity count, how many ended up without any
links, and link totals per relation (forward and autorelation back-links separately), plus
`notes` listing every required relation that could not be wired and every target pool that ran
dry. Feed it back when tuning the dataset.

## Configuration

```yaml
synthetic:
  seed: 42
  scale: 1.0
  page-size: 1000
  output-dir: build/synthetic-data
  org-id: fintlabs.no
  materialize-back-links: false
  resources:
    utdanning-elev-elev:
      count: 39511
    utdanning-kodeverk-skolear:
      count: 41
      fixed: true
    utdanning-elev-elevforhold:
      count: 44322
      relations:
        elev:
          cardinality: "1"
```

- **Resource keys** use the `domain-package-resource` format (`utdanning-elev-elev`). Unknown
  resources fail the run with the valid format in the error.
- **`count`** — the real (production-like) entity count, readable as-is. Effective count =
  `count × scale`, so `scale: 0.5` halves every resource while keeping proportions. `count: 0`
  drops the resource.
- **`fixed: true`** — exempt from scaling; for code lists and structural resources (kodeverk,
  skole) whose size doesn't grow with data volume.
- **Relations wire themselves.** Every relation in the metamodel whose target resource is part of
  the run is generated automatically: required relations always link (cardinality `1`), optional
  ones get `0..1`. Required relations whose target is missing from the run are logged as warnings.
  Two exceptions are never auto-wired: back-link sides owned by autorelation, and the losing side
  of a bidirectional pair without an autorelation rule (only one side may link independently or
  the directions would contradict each other).
- **`relations`** — overrides for the automatic wiring, keyed by the relation name on the source
  resource (validated against the metamodel). The target type is derived from the metamodel;
  override with `target: domain-package-resource` if needed.
- **`cardinality`** — `"1"`, `"0..1"`, `"2..5"`: how many distinct targets each source links to,
  drawn seeded-randomly from the target pool. Defaults to the model's multiplicity.
- **`seed`** — same seed + same config = identical identifiers, relation wiring, and field values.
- **`populate-fields`** — `true` (default) fills every resource field (names, dates, codes) with
  seeded random values via Instancio; identity (`systemId`) and `_links` are always the
  generator's own. `false` emits lean entries with only `systemId` + `_links`.

## Relation modes

The full relation graph is always built in memory; `materialize-back-links` controls emission:

- **`false` (default)** — relations covered by an autorelation rule (`RelationRuleRegistry`) are
  emitted on the forward side only. Syncing the output against a provider exercises autorelation,
  which must create the back-links itself.
- **`true`** — covered back-links are also written onto the target resources, so the output
  carries the complete bidirectional graph.

Relations *not* covered by any autorelation rule but bidirectional in the metamodel are emitted on
both sides in both modes.

Identifiers are emitted in the bare-id form (`SyncPageEntry.identifier` ==
`systemId.identifikatorverdi`, hrefs `systemid/<id>`) that autorelation resolves.

## Information model

`fintVersion` in `build.gradle` pins the model jars (currently `4.0.10`, matching the
provider-gateway) and decides which resources and relations the metamodel knows about.
