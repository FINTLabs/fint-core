# api overlays

One directory per org that runs the client-api and adapter gateway in the api
cluster. CD deploys every directory here on a push to `main`, except those
named `<org>.disabled`.

To move a county onto the client-api:

1. Remove its old core 2 consumers (`fint-core-consumer-*`) from the api
   cluster and from `api/core2/` in fint-infra-tools. Otherwise two V4
   backends compete for the same requests.
2. Rename `<org>.disabled` to `<org>` and merge to `main`.

The client-api routes come from `kustomize/base/consumer-ingress.yaml`, one
per domain. Every api overlay also gets `components/utdanning-gated` and
`components/okonomi-regnskap`, since core 1 still serves utdanning and okonomi
faktura and kodeverk in api. An org whose administrasjon is on core 2
also gets `components/administrasjon`. List `components/org` last, since it
fills in the routes the other components add. The routing policy lives in
`kustomize/rules-test/test.sh`, and the Ingress rules workflow checks it for
every overlay. [docs/routing.md](../../../docs/routing.md) explains the rules.
