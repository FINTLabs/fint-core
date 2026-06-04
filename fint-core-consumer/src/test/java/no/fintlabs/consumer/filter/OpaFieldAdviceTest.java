package no.fintlabs.consumer.filter;

import no.fintlabs.resource.server.config.OpaProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.accept.RequestedContentTypeResolver;
import org.springframework.web.server.ServerWebExchange;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpaFieldAdviceTest {

    private static final String KEY = "x-opa-fields";

    @SuppressWarnings("unchecked")
    private Set<String> invokeExtractSet(Object attribute) throws Exception {
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        when(exchange.getAttribute(KEY)).thenReturn(attribute);

        OpaFieldAdvice advice = new OpaFieldAdvice(
            ServerCodecConfigurer.create(),
            mock(RequestedContentTypeResolver.class),
            mock(OpaProperties.class)
        );

        Method m = OpaFieldAdvice.class
            .getDeclaredMethod("extractSet", ServerWebExchange.class, String.class);
        m.setAccessible(true);
        return (Set<String>) m.invoke(advice, exchange, KEY);
    }

    @Test
    void returnsEmptyWhenAttributeMissing() throws Exception {
        Set<String> result = invokeExtractSet(null);
        assertTrue(result.isEmpty());
        assertSame(Collections.emptySet(), result);
    }

    @Test
    void filtersAndLocksTheSet() throws Exception {
        Set<Object> raw = new LinkedHashSet<>();
        Collections.addAll(raw, "foo", "", 42, "bar");

        Set<String> result = invokeExtractSet(raw);

        assertEquals(Set.of("foo", "bar"), result);
        assertThrows(UnsupportedOperationException.class, () -> result.add("baz"));
    }

    @Test
    void filtersOutNonStringsAndNulls() throws Exception {
        Set<Object> raw = new LinkedHashSet<>();
        Collections.addAll(raw, "ok", 42, null, "");
        Set<String> result = invokeExtractSet(raw);
        assertEquals(Set.of("ok"), result);
    }

}
