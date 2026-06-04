package no.fintlabs.consumer.filter;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.PropertyWriter;
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import lombok.extern.slf4j.Slf4j;
import no.novari.fint.model.resource.FintLinks;
import no.novari.fint.model.resource.Link;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


@Slf4j
public class OpaFilter extends SimpleBeanPropertyFilter {

    private static final String LINKS_FIELD = "_links";
    private static final String SELF_RELATION = "self";
    private static final int PATH_SEGMENT_INDEX = 4;

    private final Set<String> allowedFields;
    private final Set<String> allowedRelations;

    public OpaFilter(Set<String> fields, Set<String> relations) {
        this.allowedFields = createAllowedFieldsSet(fields);
        this.allowedRelations = createNormalizedSet(relations);
    }

    @Override
    protected boolean include(BeanPropertyWriter writer) {
        return isFieldAllowed(writer.getName());
    }

    @Override
    protected boolean include(PropertyWriter writer) {
        return isFieldAllowed(writer.getName());
    }

    @Override
    public void serializeAsField(Object pojo, JsonGenerator generator, SerializerProvider provider, PropertyWriter writer) throws Exception {
        if (isLinksField(writer.getName()) && pojo instanceof FintLinks fintLinks) {
            serializeFilteredLinks(fintLinks, generator, provider, writer);
            return;
        }

        if (include(writer)) {
            super.serializeAsField(pojo, generator, provider, writer);
        }
    }

    private Set<String> createAllowedFieldsSet(Set<String> fields) {
        Set<String> normalizedFields = createNormalizedSet(fields);
        normalizedFields.add(LINKS_FIELD);
        return normalizedFields;
    }

    private Set<String> createNormalizedSet(Set<String> input) {
        return input.stream()
            .map(String::toLowerCase)
            .collect(Collectors.toSet());
    }

    private boolean isFieldAllowed(String fieldName) {
        if (isLinksField(fieldName)) {
            return true;
        }

        boolean allowed = allowedFields.contains(fieldName.toLowerCase());
        logFieldAccess(fieldName, allowed);
        return allowed;
    }

    private boolean isLinksField(String fieldName) {
        return LINKS_FIELD.equals(fieldName);
    }

    private void logFieldAccess(String fieldName, boolean allowed) {
        log.trace("Field '{}' -> keep: {}", fieldName, allowed);
    }

    private void serializeFilteredLinks(FintLinks fintLinks, JsonGenerator generator, SerializerProvider provider, PropertyWriter writer) throws Exception {
        Map<String, List<Link>> filteredLinks = filterLinks(fintLinks.getLinks());

        generator.writeFieldName(writer.getName());
        provider.defaultSerializeValue(filteredLinks, generator);
    }

    private Map<String, List<Link>> filterLinks(Map<String, List<Link>> links) {
        return links.entrySet().stream()
            .filter(this::isRelationAllowed)
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                this::processLinkEntry,
                (existing, replacement) -> existing,
                LinkedHashMap::new
            ))
            .entrySet().stream()
            .filter(entry -> !entry.getValue().isEmpty())
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (existing, replacement) -> existing,
                LinkedHashMap::new
            ));
    }

    private boolean isRelationAllowed(Map.Entry<String, List<Link>> entry) {
        String relationKey = entry.getKey();
        return isSelfRelation(relationKey) || allowedRelations.contains(relationKey.toLowerCase());
    }

    private List<Link> processLinkEntry(Map.Entry<String, List<Link>> entry) {
        return isSelfRelation(entry.getKey())
            ? filterSelfLinks(entry.getValue())
            : entry.getValue();
    }

    private boolean isSelfRelation(String relationKey) {
        return SELF_RELATION.equals(relationKey);
    }

    private List<Link> filterSelfLinks(List<Link> links) {
        return links.stream()
            .filter(this::isLinkAllowed)
            .collect(Collectors.toList());
    }

    private boolean isLinkAllowed(Link link) {
        try {
            String pathSegment = extractPathSegment(link.getHref());
            return pathSegment != null && allowedFields.contains(pathSegment);
        } catch (Exception e) {
            return false;
        }
    }

    private String extractPathSegment(String href) {
        String[] pathSegments = URI.create(href).getPath().split("/");
        return pathSegments.length > PATH_SEGMENT_INDEX
            ? pathSegments[PATH_SEGMENT_INDEX].toLowerCase()
            : null;
    }
}
