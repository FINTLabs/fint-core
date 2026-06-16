package no.fintlabs.consumer.kafka;

public class KafkaConstants {

    public static String ENTITY_RETENTION_TIME = "entity-retention-time";
    public static String TOPIC_RETENTION_TIME = "topic-retention-time";
    public static String SYNC_CORRELATION_ID = "sync-correlation-id";
    public static String SYNC_TOTAL_SIZE = "sync-total-size";
    public static String SYNC_TYPE = "sync-type";
    public static String LAST_MODIFIED = ENTITY_RETENTION_TIME; // TODO: Deprecate ENTITY_RETENTION_TIME in the future, plan for removal in provider-gateway
    public static String RESOURCE_NAME = "resource-name";

}
