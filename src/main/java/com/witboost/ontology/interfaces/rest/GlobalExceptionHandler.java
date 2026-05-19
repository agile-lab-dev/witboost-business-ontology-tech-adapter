package com.witboost.ontology.interfaces.rest;

import com.witboost.ontology.application.GraphValidationException;
import com.witboost.ontology.interfaces.rest.models.ErrorMoreInfo;
import com.witboost.ontology.interfaces.rest.models.RequestValidationError;
import com.witboost.ontology.interfaces.rest.models.SystemError;
import com.witboost.provisioning.model.common.FailedOperation;
import com.witboost.provisioning.model.common.Problem;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Handles HTTP-level and descriptor-level errors that propagate out of controller methods.
 *
 * <ul>
 *   <li>{@link GraphValidationException} — thrown by {@code /v1/provision} and {@code
 *       /v1/unprovision} when descriptor validation fails → HTTP 400 {@code
 *       RequestValidationError}. Built via {@link FailedOperation} and {@link Problem} from the
 *       Witboost framework model, mirroring the framework's own {@code ErrorBuilder} pattern.
 *   <li>Malformed request body / bean validation → HTTP 400 {@code RequestValidationError}.
 *   <li>Unexpected {@link RuntimeException} → HTTP 500 {@code SystemError}.
 * </ul>
 *
 * <p>Note: {@code GraphValidationException} thrown inside {@code /v1/validate} is caught
 * <em>internally</em> by the controller and returned as {@code 200 {valid: false}} — it never
 * reaches this handler.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

  /**
   * Descriptor-level validation failure during provision/unprovision → 400 RequestValidationError.
   */
  @ExceptionHandler(GraphValidationException.class)
  public ResponseEntity<RequestValidationError> handleGraphValidationException(
      GraphValidationException ex) {
    FailedOperation failedOperation = ex.toFailedOperation();
    List<String> problems =
        failedOperation.problems().stream().map(Problem::getMessage).collect(Collectors.toList());
    List<String> solutions =
        List.of(
            "Verify the descriptor fields match the adapter configuration",
            "If the problem persists, contact the platform team");
    log.warn(
        "Descriptor validation failed during provisioning ({} error(s)): {}",
        problems.size(),
        problems);
    RequestValidationError error =
        new RequestValidationError(problems)
            .userMessage(failedOperation.message())
            .moreInfo(new ErrorMoreInfo(problems, solutions));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
  }

  /** Malformed or unparseable JSON/YAML request body → 400 RequestValidationError. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<RequestValidationError> handleUnreadableMessage(
      HttpMessageNotReadableException ex) {
    String rawMessage = ex.getMessage() != null ? ex.getMessage() : "Unreadable request body";
    log.warn("Malformed request body: {}", rawMessage);
    RequestValidationError error =
        new RequestValidationError(List.of(rawMessage))
            .userMessage("The request body could not be parsed. Please verify the JSON is valid.")
            .moreInfo(
                new ErrorMoreInfo(
                    List.of(rawMessage),
                    List.of(
                        "Ensure the request body is valid JSON and the"
                            + " Content-Type header is application/json")));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
  }

  /**
   * Jakarta Bean Validation failure on a {@code @Valid}-annotated request body → 400. One problem
   * entry per constraint violation; {@code inputErrorField} set to the first offender.
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<RequestValidationError> handleBeanValidation(
      MethodArgumentNotValidException ex) {
    List<String> problems =
        ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .collect(Collectors.toList());
    String firstField =
        ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(FieldError::getField)
            .orElse(null);
    log.warn("Bean validation failed: {}", problems);
    RequestValidationError error =
        new RequestValidationError(problems)
            .userMessage("The request body is invalid. Please check the highlighted fields.")
            .inputErrorField(firstField)
            .moreInfo(
                new ErrorMoreInfo(
                    problems,
                    List.of(
                        "Check the request body against the API schema and correct all listed"
                            + " fields")));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
  }

  /**
   * Unexpected runtime exception → 500 SystemError. {@code GraphValidationException} must never
   * reach this handler (it is caught by {@link #handleGraphValidationException} first).
   */
  @ExceptionHandler(RuntimeException.class)
  public ResponseEntity<SystemError> handleRuntimeException(RuntimeException ex) {
    String msg = ex.getMessage() != null ? ex.getMessage() : "Unexpected error";
    log.error("Unexpected error", ex);
    SystemError error =
        new SystemError(msg)
            .userMessage("An unexpected error occurred. Please contact the platform team.")
            .moreInfo(
                new ErrorMoreInfo(List.of(msg), List.of("Check the adapter logs for details")));
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
  }
}
