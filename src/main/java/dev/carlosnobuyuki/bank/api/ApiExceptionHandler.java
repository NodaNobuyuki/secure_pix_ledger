package dev.carlosnobuyuki.bank.api;

import dev.carlosnobuyuki.bank.application.AccountNotFoundException;
import dev.carlosnobuyuki.bank.application.IdempotencyKeyReusedException;
import dev.carlosnobuyuki.bank.domain.InvalidTransferException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every error to an RFC 9457 problem (ADR-0011). Details never echo request values or name
 * internal types; unexpected errors are logged here and reported without details.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  // Seconds. Short, since the lock holder is another transfer that finishes in milliseconds.
  private static final String RETRY_AFTER_SECONDS = "1";

  @ExceptionHandler
  ProblemDetail accountNotFound(AccountNotFoundException e) {
    return Problems.of(
        HttpStatus.NOT_FOUND,
        Problems.ACCOUNT_NOT_FOUND,
        "Account not found",
        "Account not found.");
  }

  @ExceptionHandler
  ProblemDetail idempotencyKeyReused(IdempotencyKeyReusedException e) {
    return Problems.of(
        HttpStatus.UNPROCESSABLE_CONTENT,
        Problems.IDEMPOTENCY_KEY_REUSED,
        "Idempotency key reused",
        "This Idempotency-Key was already used for a different request.");
  }

  @ExceptionHandler
  ProblemDetail invalidTransfer(InvalidTransferException e) {
    // Domain messages are fixed strings written by us (e.g. "source and destination must differ").
    return Problems.of(
        HttpStatus.UNPROCESSABLE_CONTENT,
        Problems.INVALID_TRANSFER,
        "Invalid transfer",
        e.getMessage());
  }

  @ExceptionHandler
  ResponseEntity<ProblemDetail> accountBusy(PessimisticLockingFailureException e) {
    log.warn("Lock wait timed out: {}", e.getMostSpecificCause().getMessage());
    ProblemDetail problem =
        Problems.of(
            HttpStatus.SERVICE_UNAVAILABLE,
            Problems.ACCOUNT_BUSY,
            "Account busy",
            "The account is busy. Retry with the same Idempotency-Key.");
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
        .body(problem);
  }

  @ExceptionHandler
  ProblemDetail unexpected(Exception e) {
    log.error("Unhandled exception", e);
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
  }

  // Lists each invalid field with the constraint message, never the rejected value.
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException e,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<Map<String, String>> errors =
        e.getBindingResult().getFieldErrors().stream()
            .map(
                (FieldError error) ->
                    Map.of(
                        "field",
                        error.getField(),
                        "message",
                        String.valueOf(error.getDefaultMessage())))
            .toList();
    ProblemDetail problem = e.getBody();
    problem.setDetail("The request has invalid fields.");
    problem.setProperty("errors", errors);
    return handleExceptionInternal(e, problem, headers, status, request);
  }

  // Spring's default detail repeats the rejected value and the target Java type.
  @Override
  protected ResponseEntity<Object> handleTypeMismatch(
      TypeMismatchException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String name = e.getPropertyName() == null ? "a parameter" : "'" + e.getPropertyName() + "'";
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(status, "Invalid value for " + name + ".");
    return handleExceptionInternal(e, problem, headers, status, request);
  }
}
