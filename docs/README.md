# Docs

Short explanations of how fint-core works, with diagrams. Each page stands on its own.

| Page | What it explains |
|---|---|
| [events.md](events.md) | How a client's write becomes an event, how an adapter answers it, and how the client learns the outcome. |
| [routing.md](routing.md) | Which backend Traefik sends each client request to, per environment and org. |
| [live-read.md](live-read.md) | How a client reads straight from the source system with `Prefer: respond-async`, which is a READ event. |
| [page-limits.md](page-limits.md) | A proposed default and maximum page size for each resource, with the estimated bytes of a page. |
| [backwards-compatibility/](backwards-compatibility/README.md) | Where adapter-gateway treats an adapter differently from provider-gateway, and the PRDs for closing the gaps. |

The diagrams are written in [Mermaid](https://mermaid.js.org/)
