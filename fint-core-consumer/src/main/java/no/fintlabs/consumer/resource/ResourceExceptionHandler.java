package no.fintlabs.consumer.resource;

import lombok.extern.slf4j.Slf4j;
import no.fint.antlr.exception.FilterException;
import no.fintlabs.consumer.exception.resource.IdentificatorNotFoundException;
import no.fintlabs.consumer.exception.resource.ResourceNotFoundException;
import no.fintlabs.consumer.exception.resource.ResourceNotWriteableException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@Slf4j
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ResourceExceptionHandler {

    @ExceptionHandler(FilterException.class)
    public ResponseEntity<?> handleFilterException(FilterException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<?> resourceNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(IdentificatorNotFoundException.class)
    public ResponseEntity<?> identificatorNotFound(IdentificatorNotFoundException ex) {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(ResourceNotWriteableException.class)
    public ResponseEntity<?> resourceNotWriteable(ResourceNotWriteableException ex) {
        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body("Resource '%s' is not writeable. Contact FINT to enable write operations for this resource.".formatted(ex.getResourceName()));
    }

}
