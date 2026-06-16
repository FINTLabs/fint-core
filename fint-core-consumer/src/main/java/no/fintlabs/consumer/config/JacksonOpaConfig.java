package no.fintlabs.consumer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import no.fintlabs.consumer.filter.interfaces.OpaFilter;
import no.fintlabs.reflection.ReflectionCache;
import org.springframework.context.annotation.Configuration;


@Configuration
@RequiredArgsConstructor
public class JacksonOpaConfig {

    private final ReflectionCache reflectionCache;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void addMixIns() {
        reflectionCache.getAllResourceSubtypes()
                .forEach(type -> objectMapper.addMixIn(type, OpaFilter.class));

        objectMapper.setFilterProvider(
                new SimpleFilterProvider().setFailOnUnknownId(false)
        );
    }

}


