package no.novari.fint.core.provider.datasync.ingest

import com.mongodb.MongoException
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.models.sync.SyncType
import no.novari.fint.core.provider.datasync.ResourceCacheWriter
import no.novari.fint.core.provider.datasync.SyncCompletionTracker
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataAccessException
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class SyncIngestListener(
    private val resourceCacheWriter: ResourceCacheWriter,
    private val syncCompletionTracker: SyncCompletionTracker,
    @Qualifier("syncIngestKafkaTemplate") private val kafkaTemplate: KafkaTemplate<String, SyncIngestRecord>,
    private val topics: SyncIngestTopics,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val batchTimer: Timer =
        Timer
            .builder("fint.provider.sync.ingest.batch")
            .description("Time spent writing one consumed batch into Mongo, including autorelation")
            .publishPercentiles(0.5, 0.95)
            .register(meterRegistry)

    private val recordCounter: Counter =
        Counter
            .builder("fint.provider.sync.ingest.records")
            .description("Sync records written into Mongo")
            .register(meterRegistry)

    private val deadLetterCounter: Counter =
        Counter
            .builder("fint.provider.sync.ingest.dead.letters")
            .description("Sync records published to the DLT")
            .register(meterRegistry)

    @KafkaListener(
        topics = ["#{@syncIngestTopics.topic}"],
        groupId = "#{@syncIngestTopics.groupId}",
        containerFactory = "syncIngestListenerContainerFactory",
    )
    fun onBatch(records: List<ConsumerRecord<String, SyncIngestRecord?>>) {
        val valid =
            records.mapNotNull { record ->
                record.value() ?: run {
                    log.error(
                        "Skipping undeserializable sync record: topic={}, partition={}, offset={}",
                        record.topic(),
                        record.partition(),
                        record.offset(),
                    )
                    null
                }
            }
        if (valid.isEmpty()) return
        val started = System.nanoTime()
        write(valid)
        track(valid)
        val elapsedNanos = System.nanoTime() - started
        batchTimer.record(elapsedNanos, TimeUnit.NANOSECONDS)
        recordCounter.increment(valid.size.toDouble())
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos)
        log.info(
            "Ingested batch: records={}, resourceKeys={}, duration={}ms, rate={}/s",
            valid.size,
            valid.asSequence().map { it.resourceKey }.distinct().count(),
            elapsedMs,
            if (elapsedMs > 0) valid.size * 1000L / elapsedMs else valid.size * 1000L,
        )
    }

    private fun write(records: List<SyncIngestRecord>) {
        records.groupBy { it.resourceKey to it.time }.forEach { (group, groupRecords) ->
            val (resourceKey, time) = group
            val entries = groupRecords.map { SyncPageEntry.of(it.identifier, it.resource) }
            try {
                resourceCacheWriter.writeBatch(resourceKey, entries, time)
            } catch (e: Exception) {
                rethrowIfTransient(e)
                salvage(resourceKey, time, groupRecords)
            }
        }
    }

    private fun salvage(
        resourceKey: String,
        time: Long,
        records: List<SyncIngestRecord>,
    ) {
        records.forEach { record ->
            try {
                resourceCacheWriter.write(resourceKey, record.identifier, record.resource, time)
            } catch (e: Exception) {
                rethrowIfTransient(e)
                deadLetter(record, e)
            }
        }
    }

    private fun deadLetter(
        record: SyncIngestRecord,
        cause: Exception,
    ) {
        deadLetterCounter.increment()
        log.error(
            "Dead-lettering sync record: resourceKey={}, identifier={}, corrId={}",
            record.resourceKey,
            record.identifier,
            record.corrId,
            cause,
        )
        kafkaTemplate
            .send(topics.dlt, "${record.resourceKey}:${record.identifier}", record)
            .whenComplete { _, error ->
                if (error != null) {
                    log.error(
                        "Failed to publish sync record to DLT: resourceKey={}, identifier={}",
                        record.resourceKey,
                        record.identifier,
                        error,
                    )
                }
            }
    }

    private fun track(records: List<SyncIngestRecord>) {
        records
            .filter { it.syncType == SyncType.FULL && it.corrId != null }
            .groupBy { it.corrId!! }
            .forEach { (corrId, group) ->
                val first = group.first()
                syncCompletionTracker.track(
                    first.resourceKey,
                    corrId,
                    first.totalSize,
                    group.minOf { it.time },
                    group.size,
                )
            }
    }

    private fun rethrowIfTransient(e: Exception) {
        if (e is DataAccessException || e is MongoException) throw e
    }
}
