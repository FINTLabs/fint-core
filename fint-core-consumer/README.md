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

Our release tags follow this format: `[API Version]_[Information Model Version]`

### Format Breakdown

| Release Type | Format | Example | Explanation |
| :--- | :--- | :--- | :--- |
| **Stable** | `X.Y.Z_A.B.C` | **1.0.0_3.21.10** | **1.0.0** is the FINT-API version.<br>**3.21.10** is the Information Model version. |
| **Pre-Release** | `X.Y.Z-rc.N_A.B.C` | **1.0.0-rc.1_3.21.10** | Same as above, but includes `-rc.1` to indicate it is a Release Candidate. |

---

## 🧐 What is a Consumer?

A **Consumer** acts as the bridge between the FINT system and client applications. Its primary responsibilities are:
1.  **Consuming Resources:** It listens to Kafka topics to ingest data (resources).
2.  **Caching:** It stores these resources locally to ensure fast access and high availability.
3.  **Serving Data:** It exposes these resources via a standard **HATEOAS REST API**.

**Components & The Information Model**
In the FINT ecosystem, a Consumer belongs to a specific **Component**. A component is a logical grouping defined by the
[Information Model](https://informasjonsmodell.felleskomponent.no/docs?v=v3.21.10)
(e.g., `utdanning` -> `elev`).
 The consumer manages all resources related to that specific component.

---

## How It Works
This project uses the provided configuration settings to **dynamically determine** the domain and package of the resources to be handled.

1.  **Configuration:** The app reads the domain and package settings (e.g., `utdanning` / `vurdering`).
2.  **Reflection:** Java Reflection is employed to scan and manage these resources automatically.
4.  **Kafka Data Flow:** (Coming Soon - Explanation of how data flows from Kafka -> Cache)
5.  **Eviction:** (Coming Soon - Explanation of cache eviction policies)
5.  **API-documentation:** (Coming Soon - OpenAPI documentation)
