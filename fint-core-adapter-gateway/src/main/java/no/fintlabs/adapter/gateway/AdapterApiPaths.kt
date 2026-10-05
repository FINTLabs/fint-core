package no.fintlabs.adapter.gateway

/**
 * The base paths of the gateway. V1 is the adapter API adapters use today and is kept as it is,
 * until it is removed as a whole. V2 is the full adapter API: the same register, heartbeat,
 * status and sync endpoints, and the new event endpoints. Admin, actuator and the API docs are
 * not part of the adapter API, so they live under ROOT without a version.
 */
object AdapterApiPaths {
    const val V1 = "/provider"
    const val ROOT = "/adapter"
    const val V2 = "$ROOT/v2"
}
