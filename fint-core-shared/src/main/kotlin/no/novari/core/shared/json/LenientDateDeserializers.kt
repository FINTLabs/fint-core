package no.novari.core.shared.json

import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.ext.javatime.deser.LocalDateDeserializer
import tools.jackson.databind.ext.javatime.deser.LocalDateTimeDeserializer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.Temporal

/**
 * Reads a [LocalDateTime] from any ISO 8601 date or date-time, or from epoch milliseconds.
 * An offset is moved to UTC, the zone the response writes; a date-time without one is UTC already.
 */
class LenientLocalDateTimeDeserializer : StdDeserializer<LocalDateTime>(LocalDateTime::class.java) {
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): LocalDateTime? =
        when (val sent = readSent(parser, context, handledType())) {
            is OffsetDateTime -> sent.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()
            is LocalDateTime -> sent
            is LocalDate -> sent.atStartOfDay()
            else -> LocalDateTimeDeserializer.INSTANCE.deserialize(parser, context)
        }
}

/**
 * Reads a [LocalDate] from any ISO 8601 date or date-time, or from epoch milliseconds.
 * A date-time with an offset gives its date in Oslo, so midnight in Oslo sent as 22:00Z keeps its day.
 */
class LenientLocalDateDeserializer : StdDeserializer<LocalDate>(LocalDate::class.java) {
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): LocalDate? =
        when (val sent = readSent(parser, context, handledType())) {
            is OffsetDateTime -> sent.atZoneSameInstant(OSLO).toLocalDate()
            is LocalDateTime -> sent.toLocalDate()
            is LocalDate -> sent
            else -> LocalDateDeserializer.INSTANCE.deserialize(parser, context)
        }
}

private val OSLO = ZoneId.of("Europe/Oslo")

private val DATE_OR_DATE_TIME: DateTimeFormatter =
    DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .append(DateTimeFormatter.ISO_LOCAL_DATE)
        .optionalStart()
        .appendLiteral('T')
        .append(DateTimeFormatter.ISO_LOCAL_TIME)
        .optionalStart()
        .parseLenient()
        .appendOffset("+HH", "Z") // lenient, so +02, +0200 and +02:00 all parse
        .toFormatter()

/**
 * The sent value as an [OffsetDateTime], [LocalDateTime] or [LocalDate], depending on how much
 * it says. Null for input that is left to Jackson's own deserializer, such as an empty string.
 */
private fun readSent(
    parser: JsonParser,
    context: DeserializationContext,
    type: Class<*>,
): Temporal? =
    // clients and adapters on a model with java.util.Date send `.SSS+00:00` or epoch millis
    when {
        parser.hasToken(JsonToken.VALUE_NUMBER_INT) -> {
            Instant.ofEpochMilli(parser.longValue).atOffset(ZoneOffset.UTC)
        }

        parser.hasToken(JsonToken.VALUE_STRING) && parser.string.isNotBlank() -> {
            try {
                DATE_OR_DATE_TIME.parseBest(parser.string.trim(), OffsetDateTime::from, LocalDateTime::from, LocalDate::from) as Temporal
            } catch (_: DateTimeParseException) {
                throw context.weirdStringException(parser.string, type, "expected an ISO 8601 date or date-time")
            }
        }

        else -> {
            null
        }
    }
