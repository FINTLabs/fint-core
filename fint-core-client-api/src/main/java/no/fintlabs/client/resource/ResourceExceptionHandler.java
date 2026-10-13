package no.fintlabs.client.resource;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import no.fint.antlr.exception.FilterException;
import no.fintlabs.client.exception.resource.IdentificatorNotFoundException;
import no.fintlabs.client.exception.resource.ResourceNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
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

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<?> missingRequestHeader(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<?> resourceNotFound(ResourceNotFoundException ex) {
        log.debug(ex.getMessage());
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(IdentificatorNotFoundException.class)
    public ResponseEntity<?> identificatorNotFound(IdentificatorNotFoundException ex) {
        return ResponseEntity.notFound().build();
    }

    /**
     * The full read's status and first bytes were already sent when it failed, so there is nothing to
     * answer. Writing a body here would append it to the truncated one. Taking the response as a
     * parameter and returning nothing tells Spring the response is handled, so nothing is rendered.
     * The writer has logged and counted the failure.
     */
    @ExceptionHandler(ResourceStreamAbortedException.class)
    public void streamAborted(ResourceStreamAbortedException ex, HttpServletResponse response) {
    }

}
