package com.witboost.ontology.application;

import com.witboost.ontology.domain.validation.GraphValidationError;
import com.witboost.provisioning.model.common.FailedOperation;
import com.witboost.provisioning.model.common.Problem;
import java.util.List;

/**
 * Thrown when one or more descriptor-level or config-level validation errors are detected during
 * parsing (FR-017). Wraps a structured {@link GraphValidationError} list.
 *
 * <p>In the {@code /v1/validate} endpoint this exception is caught internally and returned as a
 * {@code 200 {valid: false}} response (per the OpenAPI contract for validation).
 *
 * <p>In the {@code /v1/provision} and {@code /v1/unprovision} endpoints it is caught internally and
 * translated to a {@code 400 RequestValidationError} response via {@link #toFailedOperation()}.
 */
public class GraphValidationException extends RuntimeException {

  private final List<GraphValidationError> errors;

  public GraphValidationException(List<GraphValidationError> errors) {
    super("Graph validation failed with " + errors.size() + " error(s)");
    this.errors = List.copyOf(errors);
  }

  /** Returns the immutable list of structured validation errors. */
  public List<GraphValidationError> getErrors() {
    return errors;
  }

  /**
   * Converts the validation errors into a {@link FailedOperation} suitable for building a {@code
   * 400 RequestValidationError} HTTP response. Each {@link GraphValidationError} is mapped to a
   * {@link Problem} containing the full error message.
   */
  public FailedOperation toFailedOperation() {
    List<Problem> problems =
        errors.stream()
            .map(e -> new Problem(e.message()))
            .collect(java.util.stream.Collectors.toList());
    return new FailedOperation(
        "Descriptor validation failed: " + errors.size() + " error(s) detected", problems);
  }
}
