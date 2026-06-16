package no.fintlabs.consumer.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.config.PathMatchConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.server.WebFilter;

@Configuration
@RequiredArgsConstructor
public class WebFluxConfig implements WebFluxConfigurer {
    private final ConsumerConfiguration consumerConfiguration;

    private static final String CORE_VERSION_HEADER = "x-core-version";

    @Override
    public void configurePathMatching(PathMatchConfigurer configurer) {
        configurer.setUseTrailingSlashMatch(true);
    }

    /*
     * Adds core version header to all HTTP responses
     */
    @Bean
    public WebFilter coreVersionHeaderFilter() {
        return (exchange, chain) -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            if (!headers.containsKey(CORE_VERSION_HEADER)) {
                headers.add(CORE_VERSION_HEADER, consumerConfiguration.getCoreVersionHeader());
            }
            return chain.filter(exchange);
        };
    }
}
