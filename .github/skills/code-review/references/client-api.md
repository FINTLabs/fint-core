# Client API and security rules

Read this reference for client API, authentication, OPA, organization isolation,
filtering, or pagination changes.

## Organization boundary

One county deployment stores data the county uses, including distinct datasets
for subordinate organizations.

- `x-org-id` participates in ingress routing, authorization, and collection
  selection through `ResourceCoordinate`.
- Organization identity is a storage boundary, not merely request metadata.
- A parent and subordinate organization, such as `abc.no` and `zxy.abc.no`, must
  not read each other's collections.
- Never fall back to the pod's county identity when a request names a subordinate
  organization.
- Never substitute another organization's data for an empty result.

## Client authorization

No FINT resource is public. Requests pass through NetIQ Access Manager and must
satisfy local checks before OPA makes the final resource-access decision.

- Convert the bearer token to a `CorePrincipal` of type `CLIENT`.
- Require the `fint-client` scope.
- Require the requested component role in `Roles`, normally
  `FINT_Client_<domain>_<package>`.
- Require `fintAssetIDs` to contain the exact requested `x-org-id`.
- When OPA is enabled, derive client identity from the JWT `cn` claim and require
  OPA to recognize it.
- OPA authorizes environment, domain, package, and resource according to choices
  the organization manages through its configuration interface.
- OPA may redact individual response fields. Never return the original
  unredacted resource after a restrictive decision.
- OPA failure, timeout, invalid response, or denial must fail closed.

## Read behavior

- Preserve stable cursor ordering, including the identifier tie-breaker.
- Forward and backward traversal must not skip or duplicate entries.
- Do not silently mix cursor and offset semantics.
- Preserve the distinction between creation, delivery, and modification times.
- Brief staleness during asynchronous sync processing is expected and is not by
  itself a correctness defect.
