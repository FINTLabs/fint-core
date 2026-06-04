package no.fintlabs.consumer.links;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import no.novari.fint.model.resource.FintLinks;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class LinkParser {

    public void removeNulls(FintLinks resource) {
        resource.getLinks().entrySet().removeIf(entry -> entry.getValue() == null);

        resource.getLinks().forEach((relationName, links) -> {
            links.removeIf(Objects::isNull);
            links.removeIf(link -> link.getHref() == null);
        });

        resource.getLinks().entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

}
