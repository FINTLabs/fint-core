package no.fintlabs.consumer.filter;

import com.fasterxml.jackson.databind.ser.FilterProvider;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import no.fintlabs.resource.server.config.OpaProperties;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.converter.json.MappingJacksonValue;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.HandlerResult;
import org.springframework.web.reactive.accept.RequestedContentTypeResolver;
import org.springframework.web.reactive.result.method.annotation.ResponseBodyResultHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OpaFieldAdvice extends ResponseBodyResultHandler {

    private static final String FIELDS_KEY = "x-opa-fields";
    private static final String RELS_KEY = "x-opa-relations";
    private static final Set<String> EMPTY = Collections.emptySet();

    private final OpaProperties opaProperties;

    public OpaFieldAdvice(ServerCodecConfigurer codecs,
                          RequestedContentTypeResolver resolver,
                          OpaProperties opaProperties) {
        super(codecs.getWriters(), resolver);
        this.opaProperties = opaProperties;
    }

    @NotNull
    @Override
    public Mono<Void> handleResult(@NotNull ServerWebExchange exchange,
                                   @NotNull HandlerResult result) {
        Object body;

        if (opaProperties.getEnabled()) {
            Set<String> fields = extractSet(exchange, FIELDS_KEY);
            Set<String> rels = extractSet(exchange, RELS_KEY);
            body = applyPruning(result.getReturnValue(), fields, rels);
        } else {
            body = result.getReturnValue();
        }

        return super.handleResult(
                exchange,
                new HandlerResult(result.getHandler(), body, result.getReturnTypeSource())
        );
    }

    private Set<String> extractSet(ServerWebExchange exchange, String key) {
        Object attr = exchange.getAttribute(key);
        if (attr instanceof Set<?> raw) {
            Set<String> safe = raw.stream()
                    .filter(o -> o instanceof String)
                    .map(o -> (String) o)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            return safe.isEmpty()
                    ? EMPTY
                    : Collections.unmodifiableSet(safe);
        }
        return EMPTY;
    }

    private MappingJacksonValue applyPruning(Object value, Set<String> fields, Set<String> rels) {
        FilterProvider fp = new SimpleFilterProvider()
                .addFilter("opaFilter", new OpaFilter(fields, rels))
                .setFailOnUnknownId(false);

        MappingJacksonValue mappingJacksonValue = new MappingJacksonValue(value);
        mappingJacksonValue.setFilters(fp);
        return mappingJacksonValue;
    }
}
