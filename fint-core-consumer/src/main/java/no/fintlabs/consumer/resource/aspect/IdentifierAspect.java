package no.fintlabs.consumer.resource.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import no.fintlabs.consumer.exception.resource.IdentificatorNotFoundException;
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
@Order(1)
public class IdentifierAspect {

    private final ResourceContext resourceContext;

    @Pointcut("execution(* no.fintlabs.consumer.resource.ResourceController.*(..)) && args(resource, idField, ..) && @annotation(no.fintlabs.consumer.resource.aspect.IdFieldCheck)")
    public void resourceMethods(String resource, String idField) {
    }

    @Before(value = "resourceMethods(resource,idField)", argNames = "resource,idField")
    public void checkIdField(String resource, String idField) {
        if (!resourceContext.resourceHasIdField(resource, idField))
            throw new IdentificatorNotFoundException();
    }

}
