package no.fintlabs.provider

import org.testcontainers.mongodb.MongoDBContainer

/**
 * MongoDB 8.0.4 is intentionally pinned for local development and tests. Later MongoDB 8.0
 * images refuse to start on Docker Desktop's LinuxKit 7.0.12 kernel because of SERVER-121912.
 * Upgrade to the cluster's exact 8.0 patch when Docker Desktop includes Linux kernel 7.0.14+.
 */
internal fun mongoTestContainer(): MongoDBContainer = MongoDBContainer("mongo:8.0.4").withReplicaSet()
