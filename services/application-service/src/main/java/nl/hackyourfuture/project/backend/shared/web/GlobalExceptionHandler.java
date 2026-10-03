package nl.hackyourfuture.project.backend.shared.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Handle exceptions and return problem detail responses.
 *
 * <p>application-service's copy (Day 25): trimmed to the validation and {@code ResponseStatusException}
 * handlers. The saved-jobs controller has {@code @Valid} request bodies, and answers a duplicate save
 * with its own 409; bad credentials and a duplicate email are identity's. The monolith's copy is the
 * other half of the same contract; the tests that cross into application-service keep the two in step.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.merge(
                        error.getField(),
                        Objects.requireNonNullElse(error.getDefaultMessage(), ""), (a, b) -> a + "; " + b)
                );

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setDetail("One or more fields are invalid");
        problem.setProperty("errors", errors);
        return problem;
    }

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
