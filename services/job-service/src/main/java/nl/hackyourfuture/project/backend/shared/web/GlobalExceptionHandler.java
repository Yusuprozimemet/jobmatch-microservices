package nl.hackyourfuture.project.backend.shared.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Handle exceptions and return problem detail responses.
 *
 * <p>job-service's copy (Day 17): trimmed to the {@code ResponseStatusException} handler only.
 * The other three handlers in the monolith's copy handle exceptions {@code jobs} never raises:
 * validation, bad credentials, and duplicate key. The monolith's copy is the other half of the
 * same contract; the tests that cross into job-service keep the two in step.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Falls back to the status phrase when Spring's own exception has no message.
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatus(ResponseStatusException ex) {
        String reason = ex.getReason();
        String detail = reason != null && !reason.isBlank()
                ? reason
                : HttpStatus.valueOf(ex.getStatusCode().value()).getReasonPhrase();
        return ProblemDetail.forStatusAndDetail(ex.getStatusCode(), detail);
    }
}
