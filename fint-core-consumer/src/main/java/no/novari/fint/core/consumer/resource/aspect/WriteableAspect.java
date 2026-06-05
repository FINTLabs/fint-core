package no.novari.fint.core.consumer.resource.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import no.novari.fint.core.consumer.exception.resource.ResourceNotWriteableException;
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
@Order(2)
public class WriteableAspect {

    private final ResourceContext resourceContext;

    @Pointcut("execution(* no.novari.fint.core.consumer.resource.ResourceController.*(..)) && args(domainName, packageName, resource, ..) && @annotation(WriteableResource)")
    public void resourceMethods(String domainName, String packageName, String resource) {
    }

    @Before(value = "resourceMethods(domainName, packageName, resource)", argNames = "domainName,packageName,resource")
    public void checkWriteable(String domainName, String packageName, String resource) {
        if (!resourceContext.resourceIsWriteable(ResourceRef.keyOf(domainName, packageName, resource)))
            throw new ResourceNotWriteableException(resource);
    }

}
