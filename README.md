# fint-core-felleskomponent

Monorepo for the FINT core platform that brokers FINT resources between adapters
(data sources) and county clients. Two deployable services plus a shared engine:

| Module | Role |
|---|---|
| `fint-core-provider-gateway` | Adapter-facing service. **Sole writer** of FINT resources — handles sync ingest, autorelation, and eviction into MongoDB. |
| `fint-core-consumer` | Client-facing service. **Read-only** over the resource store. |
| `fint-core-resource-store` | Shared library (the "resource engine"): cache/Mongo layer, autorelation, link mapping. Depended on by both services. |

The platform runs **per org** — one provider + one consumer per org, each with its
own MongoDB — so deployments are scaled and isolated per org.

---

## Versioning

Releases are git tags in the form:

```
v<service>[-rc.<n>]-<imodel>[-<suffix>]
```

| Part | Example | Meaning |
|---|---|---|
| `<service>` | `1.0.0` | SemVer for **both** the consumer and provider. They are released together (tightly coupled), so they share one service version. |
| `<imodel>` | `4.0.30` | The second version, **tightly coupled to this service** (the FINT information-model / contract version the build targets). It is tracked in the tag and carried verbatim into the Docker image tag, so a running build always tells you which imodel it speaks. |
| `-rc.<n>` | `-rc.1` | Release-candidate marker. |
| `-<suffix>` | `-my-branch` | Optional branch suffix for ad-hoc test builds (see below). |

Examples:

| Tag | Kind |
|---|---|
| `v1.0.0-4.0.30` | Stable release |
| `v1.0.0-rc.1-4.0.30` | Release candidate |
| `v1.0.0-rc.1-4.0.30-my-branch` | Branch test build |

The full version string (everything after the leading `v`) becomes the Docker
image tag, e.g. `ghcr.io/<owner>/fint-core-consumer-db:1.0.0-rc.1-4.0.30`.

### What a tag deploys

CD classifies a tag by an **anchored** regex `^<semver>(-rc.N)?-<imodel>$`. A tag
that ends exactly at the imodel is a *clean* release; anything trailing the imodel
(a branch suffix) is a *branch build*.

| Tag | Classified as | Builds image | Auto-deploys to | `:latest` |
|---|---|---|---|---|
| `v1.0.0-rc.1-4.0.30` | release candidate | ✅ | **beta** | no |
| `v1.0.0-4.0.30` | stable | ✅ | **beta + api** | yes |
| `v1.0.0-rc.1-4.0.30-<branch>` | branch build | ✅ | **nothing** (manual only) | no |

- **rc** is the one to watch — it ships to beta automatically.
- **stable** (no `-rc.`) ships to beta **and** api, and updates `:latest`.
- **branch builds** still build and push an image (tagged with the full version,
  branch suffix included) but never deploy automatically — deploy them by hand.
- **alpha is never automatic.** It is reached only through a manual run.

### Manual deploys

Use the **Run workflow** button on the `CD` workflow (`workflow_dispatch`) to deploy
an already-built version to a chosen environment. Pick the environment
(`alpha` / `beta` / `api`) and paste the version (e.g. `1.0.0-rc.1-4.0.30-my-branch`).
This is how alpha and branch builds reach a cluster.

---

## CI

`.github/workflows/ci.yml` runs on pull requests and pushes to `main`. It is
**path-filtered** so unrelated changes don't rebuild everything:

- A change under `fint-core-consumer/**` tests only the consumer.
- A change under `fint-core-provider-gateway/**` tests only the provider.
- A change to the shared module (`fint-core-resource-store/**`) or root Gradle
  files tests **both**.

Each module job runs `./gradlew :<module>:check` (unit + Testcontainers integration
tests; Docker is available on the runner). The `ci-ok` job is a single gate that is
green when nothing failed — make it the required status check for branch protection
(skipped module jobs count as ok).

---

## CD

`.github/workflows/cd.yml` builds and deploys (see the tag table above).

1. **build-and-push** — matrix over the two services; `docker/metadata-action`
   tags each image with the version, a `sha-<short>` tag, and `:latest` (clean
   stable tags only); pushes to GHCR via the built-in `GITHUB_TOKEN`.
2. **deploy** — matrix over the target environments. Per env it logs into Azure,
   sets the AKS context, then for every org under `kustomize/overlays/<env>/*`:
   ensures the namespace, renders the overlay, swaps in the release image tag, and
   `kubectl apply`s both Applications.

### Clusters & secrets

| Env | AKS cluster | Resource group | Azure creds secret |
|---|---|---|---|
| alpha | `aks-alpha-fint-2021-11-18` | `rg-aks-alpha` | `AKS_ALPHA_FINT_GITHUB` |
| beta | `aks-beta-fint-2021-11-23` | `rg-aks-beta` | `AKS_BETA_FINT_GITHUB` |
| api | `aks-api-fint-2022-02-08` | `rg-aks-api` | `AKS_API_FINT_GITHUB` |

Each `AKS_<ENV>_FINT_GITHUB` secret holds the Azure service-principal credentials
JSON for that cluster. Image push uses `GITHUB_TOKEN` (no extra secret).

---

## Deployment manifests (kustomize)

Deploy units are FLAIS `Application` CRDs (`fintlabs.no/v1alpha1`). The tree lives
at the repo root:

```
kustomize/
  base/
    consumer.yaml          # the two Applications, with REPLACE placeholders
    provider.yaml
    kustomization.yaml
  components/org/           # one component; fans per-overlay values into both Apps
  overlays/
    <env>/<org>/
      kustomization.yaml   # identical for every org leaf
      org-values.yaml      # the only per-org file
```

`kustomize build kustomize/overlays/<env>/<org>` renders **both** the consumer and
provider Application for that org, fully substituted.

### Adding an org

Copy an existing leaf and edit only `org-values.yaml`:

```yaml
data:
  id-dotted: agderfk.no          # orgId, labels, org-id env
  id-dashed: agderfk-no          # namespace + kafka acl prefix
  id-underscore: agderfk_no      # instance label
  host: beta.felleskomponent.no  # ingress host (env)
  base-url: https://beta.felleskomponent.no
  onepassword-itempath: vaults/aks-beta-vault/items/<item>
```

The Mongo connection string is **not** in config — it comes from the
`onePassword.itemPath` secret, which the FLAIS operator injects.

---

## Local development

`docker-compose.yaml` (repo root) brings up Kafka + Kafka UI, MongoDB, Postgres,
and a toxiproxy in front of Mongo for latency testing.

```
docker compose up -d
./gradlew :fint-core-provider-gateway:bootRun
./gradlew :fint-core-consumer:bootRun
```
