# api overlays

One directory per org that runs the client-api and adapter gateway in the api
cluster. CD deploys every directory here on a push to `main`, except those
named `<org>.disabled`.

To move a county onto the client-api:

1. Remove its old core 2 consumers (`fint-core-consumer-*`) from the api
   cluster and from `api/core2/` in fint-infra-tools. Otherwise two V4
   backends compete for the same requests.
2. Rename `<org>.disabled` to `<org>` and merge to `main`.

The ingress rule comes from `kustomize/base/consumer-ingress.yaml` and only
takes a request away from core 1 when the client asks for V4. The Ingress
rules workflow checks that for every overlay, disabled ones included.
