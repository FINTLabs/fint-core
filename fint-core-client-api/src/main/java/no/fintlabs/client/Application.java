package no.fintlabs.client;

import no.novari.core.shared.store.ResourceStoreProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"no.fintlabs", "no.novari"})
@ConfigurationPropertiesScan
// TODO: Move away from ConfigurationPropertiesScan and use EnableConfigurationProperties instead
@EnableConfigurationProperties(ResourceStoreProperties.class)
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

}
