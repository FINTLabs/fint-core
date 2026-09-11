package no.novari.core.shared

import org.testcontainers.mongodb.MongoDBContainer

/**
 * MongoDB 8.0.4 is pinned on purpose. Later 8.0 images refuse to start on the Linux kernel that
 * Docker Desktop ships today (SERVER-121912). Move to the cluster's exact 8.0 patch once Docker
 * Desktop includes kernel 7.0.14 or newer.
 */
internal fun mongoTestContainer(): MongoDBContainer = MongoDBContainer("mongo:8.0.4")
