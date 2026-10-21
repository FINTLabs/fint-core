package no.novari.core.shared.json

import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.ext.javatime.deser.LocalDateDeserializer
import tools.jackson.databind.ext.javatime.deser.LocalDateTimeDeserializer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalQueries

/**
 * Reads the model's dates from any ISO 8601 date or date-time, with or without an offset, or
 * from epoch milliseconds. Any other input goes to Jackson's own deserializer for the type.
 */
class LenientDateDeserializer<T : Any> private constructor(
    private val type: Class<T>,
    private val fallback: ValueDeserializer<T>,
    private val convert: (SentDateTime) -> T,
) : StdDeserializer<T>(type) {
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): T? =
        // clients and adapters on a model with java.util.Date send `.SSS+00:00` or epoch millis
        when {
            parser.hasToken(JsonToken.VALUE_NUMBER_INT) -> {
                convert(SentDateTime.ofEpochMilli(parser.longValue))
            }

            parser.hasToken(JsonToken.VALUE_STRING) && parser.string.isNotBlank() -> {
                SentDateTime.parse(parser.string.trim())?.let(convert)
                    ?: type.cast(context.handleWeirdStringValue(type, parser.string, "expected an ISO 8601 date or date-time"))
            }

            else -> {
                fallback.deserialize(parser, context)
            }
        }

    companion object {
        /** A date-time with an offset is moved to UTC, the zone the response writes; one without is taken as UTC already. */
        val LOCAL_DATE_TIME = LenientDateDeserializer(LocalDateTime::class.java, LocalDateTimeDeserializer.INSTANCE, SentDateTime::inUtc)

        /** A date-time with an offset gives its date in Oslo, so midnight in Oslo sent as 22:00Z keeps its day. */
        val LOCAL_DATE = LenientDateDeserializer(LocalDate::class.java, LocalDateDeserializer.INSTANCE, SentDateTime::dateInOslo)
    }
}

private class SentDateTime private constructor(
    private val local: LocalDateTime,
    private val offset: ZoneOffset?,
) {
    fun inUtc(): LocalDateTime = offset?.let { local.atOffset(it).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime() } ?: local

    fun dateInOslo(): LocalDate = offset?.let { local.atOffset(it).atZoneSameInstant(OSLO).toLocalDate() } ?: local.toLocalDate()

    companion object {
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

        fun ofEpochMilli(millis: Long): SentDateTime =
            SentDateTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC), ZoneOffset.UTC)

        fun parse(text: String): SentDateTime? {
            val parsed =
                try {
                    DATE_OR_DATE_TIME.parse(text)
                } catch (_: DateTimeParseException) {
                    return null
                }
            val time = parsed.query(TemporalQueries.localTime()) ?: LocalTime.MIDNIGHT
            return SentDateTime(LocalDateTime.of(LocalDate.from(parsed), time), parsed.query(TemporalQueries.offset()))
        }
    }
}
