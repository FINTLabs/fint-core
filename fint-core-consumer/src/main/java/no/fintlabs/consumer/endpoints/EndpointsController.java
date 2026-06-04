package no.fintlabs.consumer.endpoints;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class EndpointsController {

    private final EndpointsCache endpointsCache;

    @GetMapping
    public Map<String, Map<String, Object>> getDefaultEndpoints() {
        return endpointsCache.getEndpoints();
    }

}
