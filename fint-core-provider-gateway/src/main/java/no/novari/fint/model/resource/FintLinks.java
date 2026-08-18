package no.novari.fint.model.resource;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;

import java.util.*;

public interface FintLinks {
    Map<String, List<Link>> getLinks();

    @JsonGetter("_links")
    default Map<String, List<Link>> getLinksIfPresent() {
        if (getLinks().isEmpty()) {
            return null;
        }
        return getLinks();
    }

    @JsonSetter("_links")
    default void setLinks(Map<String, List<Link>> links) {
        if (links != null) {
            getLinks().putAll(links);
        }
    }

    default void addLink(String key, Link link) {
        getLinks().computeIfAbsent(key, (k) -> new ArrayList<>()).add(link);
    }

    default void addSelf(Link link) { addLink("self", link); }

    default Map<String, List<Link>> createLinks() {
        return Collections.synchronizedMap(new LinkedHashMap<>());
    }

    @JsonIgnore
    default List<FintResource> getNestedResources() {
        return new ArrayList<>();
    }

    @JsonIgnore
    default List<Link> getSelfLinks() {
        return getLinks().get("self");
    }
}
