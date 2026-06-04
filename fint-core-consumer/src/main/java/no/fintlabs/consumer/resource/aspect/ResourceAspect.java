package no.fintlabs.consumer.resource.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import no.fintlabs.consumer.exception.resource.ResourceNotFoundException;
import no.novari.metamodel.MetamodelService;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
@Order(0)
public class ResourceAspect {

    private final MetamodelService metamodelService;

    @Pointcut("execution(* no.fintlabs.consumer.resource.ResourceController.*(..)) && args(domainName, packageName, resource, ..)")
    public void resourceMethods(String domainName, String packageName, String resource) {
    }

    @Before(value = "resourceMethods(domainName, packageName, resource)", argNames = "domainName,packageName,resource")
    public void checkResource(String domainName, String packageName, String resource) {
        if (metamodelService.getResource(domainName, packageName, resource) == null) {
            throw new ResourceNotFoundException();
        }
    }

}
