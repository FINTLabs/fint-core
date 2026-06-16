package no.fintlabs.consumer.kafka.entity;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class EntityLoggingService {

    private final ConcurrentHashMap<String, BatchLogger> loggerMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public void startLogging(String resourceName) {
        BatchLogger logger = loggerMap.computeIfAbsent(resourceName, BatchLogger::new);
        logger.incrementCount();
    }

    private class BatchLogger {
        private final String resourceName;
        private int count = 0;
        private long startTime;
        private ScheduledFuture<?> futureTask;

        public BatchLogger(String resourceName) {
            this.resourceName = resourceName;
            this.startTime = System.currentTimeMillis();
            scheduleLogging();
        }

        public synchronized void incrementCount() {
            count++;
            if (futureTask != null && !futureTask.isDone()) {
                futureTask.cancel(false);
            }
            scheduleLogging();
        }

        private void scheduleLogging() {
            futureTask = scheduler.schedule(this::logAndReset, 1000, TimeUnit.MILLISECONDS);
        }

        private synchronized void logAndReset() {
            long duration = System.currentTimeMillis() - startTime;
            log.info("Consumed {} {} in {} ms", count, resourceName, duration);

            loggerMap.remove(resourceName);

            count = 0;
            startTime = System.currentTimeMillis();
        }
    }
}
