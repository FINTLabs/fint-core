package no.fintlabs.autorelation.model

sealed class AutoRelationException(
    override val message: String,
    val metricReason: MetricReason,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class ResourceConversionException(
    details: String?,
) : AutoRelationException("Resource conversion failed: $details", MetricReason.CONVERSION_FAILED)

class MissingMandatoryLinkException(
    relation: String,
) : AutoRelationException("Missing mandatory link for '$relation'", MetricReason.MISSING_MANDATORY_LINK)

class InvalidLinkException(
    relation: String,
) : AutoRelationException("Invalid link for '$relation'", MetricReason.INVALID_LINK)

class ResourceIdMismatchException(
    resourceId: String,
) : AutoRelationException(
        "ID mismatch: Resource ID '$resourceId' not found in payload",
        MetricReason.VALIDATION_ID_MISMATCH,
    )

class KafkaPublishException(
    message: String,
    cause: Throwable? = null,
) : AutoRelationException("Kafka publish failed for: $message", MetricReason.KAFKA_PUBLISH_ERROR, cause)

class DeepCopyException(
    targetClass: Class<*>,
    cause: Throwable,
) : AutoRelationException(
        "Deep copy failed for class '${targetClass.simpleName}'",
        MetricReason.DEEP_COPY_FAILED,
        cause,
    )

enum class MetricReason(
    val tagValue: String,
) {
    NO_TARGETS("no_targets"),
    CONVERSION_FAILED("conversion_failed"),
    DEEP_COPY_FAILED("deep_copy_failed"),
    MISSING_MANDATORY_LINK("missing_mandatory_link"),
    INVALID_LINK("invalid_link"),
    VALIDATION_ID_MISMATCH("validation_id_mismatch"),
    KAFKA_PUBLISH_ERROR("kafka_publish_error"),
    UNEXPECTED_ERROR("unexpected_error"),
}
