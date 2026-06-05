# Fint Core Consumer

![Build Status](https://img.shields.io/badge/build-passing-brightgreen) ![Version](https://img.shields.io/badge/version-1.0.0-blue)

**Fint Core Consumer** streamlines and unifies existing consumer projects into a single, cohesive solution. It allows you to manage and modify consumer behavior through configuration, removing the need to maintain multiple separate consumers.

---

## 📖 Table of Contents
* [Versioning Strategy](#-versioning-strategy)
* [What is a Consumer?](#-what-is-a-consumer)
* [How It Works](#how-it-works)

---

## 🚀 Versioning Strategy

> **⚠️ Important Note on Release Tags**
>
> We use a specific tagging strategy to combine the **FINT-API** version with the **Information Model** version.

Release tags follow `v<service>[-rc.N]-<imodel>` — e.g. **`v1.0.0-4.0.30`** (stable) or
**`v1.0.0-rc.1-4.0.30`** (release candidate). `<service>` is the SemVer shared by both services and
`<imodel>` is the FINT information-model version. The repo-root `README.md` is the authoritative
versioning, CI and CD reference.

---

## 🧐 What is a Consumer?

A **Consumer** acts as the bridge between the FINT system and client applications. Its primary responsibilities are:
1.  **Serving Resources:** it exposes resources via a standard **HATEOAS REST API** (Spring MVC on virtual threads), **read-only** over the shared MongoDB resource store that `fint-core-provider-gateway` writes. It does **not** ingest resources from Kafka.
2.  **Forwarding writes:** client `POST`/`PUT`s are published as request events to Kafka for the adapter and tracked by correlation id; no client write touches the cache directly.

**Components & The Information Model**
In the FINT ecosystem, a Consumer belongs to a specific **Component**. A component is a logical grouping defined by the
[Information Model](https://informasjonsmodell.felleskomponent.no/docs?v=v3.21.10)
(e.g., `utdanning` -> `elev`).
 The consumer manages all resources related to that specific component.

---

## How It Works

1.  **Configuration:** the per-org `fint.org-id` plus domain/package settings determine which resources and topics this consumer serves.
2.  **Reflection:** the FINT model classes are scanned at startup to map packages → resource types.
3.  **Reads:** resources are served straight from the shared MongoDB resource store (written by `fint-core-provider-gateway`); back-links are merged in from the `backlinks` collection.
4.  **Writes:** client mutations are forwarded to adapters as Kafka request events; the adapter's response is consumed back and exposed via the `…/status/{corrId}` endpoint.

See the repo-root `CLAUDE.md` for the full architecture.
