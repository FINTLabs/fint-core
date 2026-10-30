# Client-api routing

Which backend Traefik sends a client request to. Update this table in the same PR every time the rules change, here
or in fint-infra-tools.

- **client-api** is core 2 in this repo, routed by `kustomize/`
- **core 1** is the `fint-consumer-*` in the `default` namespace, routed by `api/core/` and `beta/core/` in fint-infra-tools
- **none** means no route matches, so Traefik answers 404

| Path | api | beta | alpha |
|---|---|---|---|
| `/utdanning/vurdering/karakterverdi`, `elevfravar`, `fravarsregistrering` | client-api | client-api | client-api |
| `/utdanning/larling`, `/utdanning/ot` | client-api | client-api | client-api |
| rest of `/utdanning` | client-api for V4, otherwise core 1 | client-api | client-api |
| `/okonomi/regnskap` | client-api | client-api | client-api |
| `/okonomi/faktura`, `/okonomi/kodeverk` | core 1 | client-api | client-api |
| `/felles` except `/felles/kodeverk` | client-api | client-api | client-api |
| `/felles/kodeverk` | core 1 | core 1 | client-api |
| `/ressurs` | client-api | client-api | client-api |
| `/personvern/samtykke` | client-api | client-api | client-api |
| `/personvern/kodeverk` | none | client-api | client-api |
| `/administrasjon` | client-api for rogfk, telemark, trondelag and vestfold, otherwise core 1 | client-api for rogfk, telemark, trondelag and vestfold, otherwise core 1 | client-api |
| `/arkiv`, `/profilbilde` | core 1 | core 1 | client-api |
| `/{domain}/{package}/provider` | never the client-api | never the client-api | never the client-api |

Every county runs its own client-api in api and beta. V4 means `x-fint-model-version-override` or
`x-fint-model-version` is `v4` in any case, with the override winning. In beta every org without its own overlay goes
to the shared novari-no client-api, apart from `/felles/kodeverk`. Alpha only routes novari.no to the client-api.
