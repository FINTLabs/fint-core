package no.fintlabs.adapter.gateway.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import no.fintlabs.adapter.gateway.event.InvalidOrgIdException;
import no.fintlabs.adapter.gateway.event.InvalidResponseFintEventException;
import no.fintlabs.adapter.gateway.event.NoRequestFoundException;
import no.fintlabs.adapter.gateway.event.v2.InvalidEventAnswerException;
import no.fintlabs.adapter.gateway.register.AdapterNotRegisteredException;
import no.fintlabs.adapter.gateway.register.InvalidAdapterCapabilityException;
import no.fintlabs.adapter.gateway.security.InvalidJwtException;
import no.fintlabs.adapter.gateway.sync.InvalidSyncPageEntryException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import tools.jackson.core.JacksonException;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@ControllerAdvice
@RequiredArgsConstructor
public class ExceptionController {


    @ExceptionHandler(InvalidResponseFintEventException.class)
    public ResponseEntity<String> handleInvalidResponseFintEventException(Throwable e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    @ExceptionHandler(InvalidEventAnswerException.class)
    public ResponseEntity<ProblemDetail> handleInvalidEventAnswerException(
            InvalidEventAnswerException exception,
            HttpServletRequest request
    ) {
        return problemResponse(badRequestProblem(exception.getMessage(), request));
    }

    /**
     * Answers a body that fails Bean Validation with one entry per field, so the adapter sees
     * every problem at once instead of fixing them one request at a time.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())
                ))
                .toList();

        ProblemDetail problem = badRequestProblem("The request body is not valid.", request);
        problem.setProperty("errors", errors);
        return problemResponse(problem);
    }

    @ExceptionHandler(AdapterNotRegisteredException.class)
    public ResponseEntity<String> handleAdapterNotRegisteredException(Throwable e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }

    @ExceptionHandler(InvalidSyncPageEntryException.class)
    public ResponseEntity<String> handleInvalidSyncPageEntryException(Throwable e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }

    @ExceptionHandler(InvalidAdapterCapabilityException.class)
    public ResponseEntity<String> handleInvalidAdapterCapabilityException(Throwable e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }

    @ExceptionHandler(JacksonException.class)
    public ResponseEntity<Void> handleJacksonException(Throwable e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    @ExceptionHandler({NoRequestFoundException.class})
    public ResponseEntity<?> handleNoRequestFoundException(NoRequestFoundException exception) {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler({InvalidOrgIdException.class})
    public ResponseEntity<?> handleInvalidOrgIdException(InvalidOrgIdException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler({InvalidJwtException.class})
    public ResponseEntity<?> handleInvalidJwtException(InvalidJwtException exception) {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(UnknownTopicOrPartitionException.class)
    public ResponseEntity<String> handleUnknownTopicOrPartitionException(UnknownTopicOrPartitionException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("""
                The adapter has probably not called the '/register' endpoint. \
                Also, you need to check if the entity endpoint is in the capability list.\
                """);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = badRequestProblem(resolveDetail(exception), request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private String resolveDetail(HttpMessageNotReadableException exception) {
        Throwable cause = exception.getCause();
        if (cause == null) {
            return "Required request body is missing";
        }
        return cause.getMessage() != null ? cause.getMessage() : "Request body could not be parsed";
    }

    private ResponseEntity<ProblemDetail> problemResponse(ProblemDetail problem) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private ProblemDetail badRequestProblem(String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Bad Request");
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

}
