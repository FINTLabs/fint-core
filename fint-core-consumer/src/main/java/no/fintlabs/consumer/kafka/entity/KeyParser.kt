package no.fintlabs.consumer.kafka.entity

import org.apache.kafka.clients.consumer.ConsumerRecord

const val ENTITY_KEY_DELIMITER = "\u001F"

/**
 * Extracts the unique identifier for a resource from the record key.
 *
 * Entity topics can contain multiple resource types that share identifiers,
 * so keys are prefixed with the resource name: `resourceName\u001Fidentifier`.
 * This extracts only the identifier part, stripping the resource name prefix.
 *
 * For legacy keys without the delimiter, the entire key is returned as-is.
 */
fun ConsumerRecord<String, *>.extractIdentifier(): String =
    key().substringAfter(ENTITY_KEY_DELIMITER, missingDelimiterValue = key())
