# Client-api routing

Which backend Traefik sends a client request to. Update this table in the same PR every time the rules change, here
or in fint-infra-tools.

- **client-api** is core 2 in this repo, routed by `kustomize/`
- **core 2-kafka** is the old `fint-core-consumer-*`, routed by `api/core2/` in fint-infra-tools
- **core 1** is the `fint-consumer-*` in the `default` namespace, routed by `api/core/` and `beta/core/` in fint-infra-tools
- **none** means no route matches, so Traefik answers 404

| Path | api, orgs on the client-api | api, rogfk, telemark, trondelag, vestfold | beta | alpha |
|---|---|---|---|---|
| `/utdanning/vurdering/karakterverdi`, `elevfravar`, `fravarsregistrering` | client-api | core 2-kafka | client-api | client-api |
| `/utdanning/larling`, `/utdanning/ot` | client-api | core 2-kafka | client-api | client-api |
| rest of `/utdanning` | client-api for V4, otherwise core 1 | core 2-kafka for `V4`, otherwise core 1 | client-api | client-api |
| `/okonomi/regnskap` | client-api | core 2-kafka for trondelag, otherwise none | client-api | client-api |
| `/okonomi/faktura`, `/okonomi/kodeverk` | core 1 | core 1 | client-api | client-api |
| `/felles` except `/felles/kodeverk` | client-api | none | client-api | client-api |
| `/felles/kodeverk` | core 1 | core 1 | core 1 | client-api |
| `/ressurs` | client-api | none | client-api | client-api |
| `/personvern/samtykke` | none | core 2-kafka for rogfk and trondelag, otherwise none | client-api | client-api |
| `/personvern/kodeverk` | none | none | client-api | client-api |
| `/administrasjon` | core 1 | core 2-kafka | client-api for rogfk, telemark, trondelag and vestfold, otherwise core 1 | client-api |
| `/arkiv`, `/profilbilde` | core 1 | core 1 | core 1 | client-api |
| `/{domain}/{package}/provider` | never the client-api | never the client-api | never the client-api | never the client-api |

The orgs on the client-api in api are afk, agderfk, bfk, ffk, innlandetfylke, mrfylke, nfk, ofk, oslo.kommune,
tromsfylke and vlfk. The other four still have a `.disabled` overlay. V4 means `x-fint-model-version-override` or
`x-fint-model-version` is `v4` in any case, with the override winning. Core 2-kafka only matches `V4` in upper case.
In beta every org without its own overlay goes to the shared novari-no client-api, apart from `/felles/kodeverk`.
Alpha only routes novari.no to the client-api.
