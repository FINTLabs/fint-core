package no.fintlabs.consumer.resource.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import no.fintlabs.consumer.exception.resource.ResourceNotFoundException;
import no.fintlabs.consumer.resource.context.ResourceContext;
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

    private final ResourceContext resourceContext;

    @Pointcut("execution(* no.fintlabs.consumer.resource.ResourceController.*(..)) && args(resource,..)")
    public void resourceMethods(String resource) {
    }

    @Before(value = "resourceMethods(resource)", argNames = "resource")
    public void checkResource(String resource) {
        if (!resourceContext.getResourceNames().contains(resource.toLowerCase())) throw new ResourceNotFoundException();
    }

}
