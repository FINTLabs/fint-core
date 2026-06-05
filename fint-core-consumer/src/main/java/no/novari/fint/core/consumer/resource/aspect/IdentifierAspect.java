package no.novari.fint.core.consumer.resource.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import no.novari.fint.core.consumer.exception.resource.IdentificatorNotFoundException;
import no.novari.fint.core.shared.resource.ResourceRef;
import no.novari.fint.core.shared.resource.context.ResourceContext;
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

    @Pointcut("execution(* no.novari.fint.core.consumer.resource.ResourceController.*(..)) && args(domainName, packageName, resource, idField, ..) && @annotation(no.novari.fint.core.consumer.resource.aspect.IdFieldCheck)")
    public void resourceMethods(String domainName, String packageName, String resource, String idField) {
    }

    @Before(value = "resourceMethods(domainName, packageName, resource, idField)", argNames = "domainName,packageName,resource,idField")
    public void checkIdField(String domainName, String packageName, String resource, String idField) {
        if (!resourceContext.resourceHasIdField(ResourceRef.keyOf(domainName, packageName, resource), idField))
            throw new IdentificatorNotFoundException();
    }

}
