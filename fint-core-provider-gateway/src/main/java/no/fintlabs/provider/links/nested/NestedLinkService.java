package no.fintlabs.provider.links.nested;

import lombok.extern.slf4j.Slf4j;
import no.fintlabs.provider.config.ProviderProperties;
import no.novari.fint.model.resource.FintLinks;
import no.fintlabs.provider.links.LinkParser;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class NestedLinkService {

    private final ProviderProperties configuration;
    private final LinkParser linkParser;
    private final Map<String, String> packageToUriMap;

    public NestedLinkService(ProviderProperties configuration, NestedLinkMapper nestedLinkMapper, LinkParser linkParser) {
        this.configuration = configuration;
        this.linkParser = linkParser;
        this.packageToUriMap = nestedLinkMapper.getPackageToUriMap();
    }

    public void mapNestedLinks(FintLinks resource) {
        resource.getNestedResources().forEach(fintLinks -> {
            mapLinks(fintLinks);
            mapNestedLinks(fintLinks);
        });
    }

    private void mapLinks(FintLinks fintLinks) {
        linkParser.removeNulls(fintLinks);

        fintLinks.getLinks().values().stream()
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .forEach(link -> link.setVerdi(getLink(link.getHref())));
    }

    public String getLink(String link) {
        int variableRefEndIndex = link.indexOf('}');
        if (link.startsWith("${") && variableRefEndIndex > 3) {
            var ref = link.substring(2, variableRefEndIndex);
            var packagePath = packageToUriMap.get(ref);
            return configuration.getBaseUrl() + "/" + link.replace("${" + ref + "}", packagePath);
        }

        if (link.startsWith("/")) {
            return configuration.getBaseUrl() + link;
        }

        return populateProtocol(link);
    }

    public String populateProtocol(String href) {
        if (href.startsWith("http://")) {
            return href.replace("http://", "https://");
        } else {
            return href;
        }
    }

}
