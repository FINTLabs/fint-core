package no.fintlabs.consumer.admin;

import java.util.Date;

public record CacheEntry(Date lastUpdated, Integer size) {
}

