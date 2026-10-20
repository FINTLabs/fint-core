package no.fintlabs.adapter.gateway.event.request

import com.mongodb.client.ChangeStreamIterable
import com.mongodb.client.MongoChangeStreamCursor
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.changestream.ChangeStreamDocument
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.adapter.gateway.config.EventLongPollProperties
import org.awaitility.kotlin.await
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import java.time.Duration

class EventInsertWatcherTest {
    private val cursor = mockk<MongoChangeStreamCursor<ChangeStreamDocument<Document>>>(relaxed = true)
    private val arrivals = EventArrivals()
    private val watcher =
        EventInsertWatcher(mongoTemplateOpening(cursor), arrivals, EventLongPollProperties())

    @AfterEach
    fun stopWatcher() {
        watcher.stop()
    }

    @Test
    fun `a stream the server has closed is given up instead of read in a busy loop`() {
        every { cursor.tryNext() } returns null
        every { cursor.serverCursor } returns null

        watcher.start()

        await.atMost(Duration.ofSeconds(2)).untilAsserted { verify { cursor.close() } }
        await.atMost(Duration.ofSeconds(2)).until { !arrivals.watched }
        verify(exactly = 1) { cursor.tryNext() }
    }

    private fun mongoTemplateOpening(cursor: MongoChangeStreamCursor<ChangeStreamDocument<Document>>): MongoTemplate {
        val iterable = mockk<ChangeStreamIterable<Document>>()
        every { iterable.maxAwaitTime(any(), any()) } returns iterable
        every { iterable.cursor() } returns cursor

        val database = mockk<MongoDatabase>()
        every { database.watch(any<List<Bson>>()) } returns iterable

        return mockk<MongoTemplate>().also { every { it.db } returns database }
    }
}
