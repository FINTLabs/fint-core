package no.fintlabs.consumer.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

    private final ConsumerConfiguration configuration;

    @Bean
    public RestClient consumerRestClient() {
        return RestClient.builder()
                .baseUrl(configuration.getPodUrl())
                .build();
    }

}
