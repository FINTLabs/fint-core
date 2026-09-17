package no.fintlabs.client.admin;

import java.util.Date;

public record CacheEntry(Date lastUpdated, Integer size) {
}
