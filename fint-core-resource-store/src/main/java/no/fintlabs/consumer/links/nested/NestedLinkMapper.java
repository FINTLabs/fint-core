package no.fintlabs.consumer.links.nested;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import no.fintlabs.reflection.ReflectionCache;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class NestedLinkMapper {

    private final ReflectionCache reflectionCache;

    @Getter
    private final Map<String, String> packageToUriMap;

    public NestedLinkMapper(ReflectionCache reflectionCache) {
        this.reflectionCache = reflectionCache;
        this.packageToUriMap = createPackageToUriMap();
    }

    private Map<String, String> createPackageToUriMap() {
        return reflectionCache.getAllResourceSubtypes().stream()
                .collect(Collectors.toMap(
                        s -> formatPackageName(s.getName()),
                        s -> createUriFromFormattedPackageName(formatPackageName(s.getName()))
                ));
    }

    private String createUriFromFormattedPackageName(String formattedPackageName) {
        return formattedPackageName.replace(".", "/");
    }

    private String formatPackageName(String packageName) {
        return packageName
                .replaceFirst("^no\\.fint\\.model(\\.resource)?\\.", "")
                .replaceFirst("Resource$", "")
                .toLowerCase();
    }

}
