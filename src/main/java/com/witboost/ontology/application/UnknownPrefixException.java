package com.witboost.ontology.application;

/**
 * Thrown by {@link PrefixResolver} when a prefixed IRI value (e.g. {@code "owl:Class"}) references
 * a label that is not present in the resolved prefix map.
 *
 * <p>This is an intermediate exception caught by {@link MappingEngine}, which collects it into a
 * {@link com.witboost.ontology.domain.validation.GraphValidationError} and ultimately throws a
 * {@link GraphValidationException} once all concepts have been processed.
 */
public class UnknownPrefixException extends RuntimeException {

  private final String field;
  private final String prefix;

  public UnknownPrefixException(String field, String prefix) {
    super(
        field != null
            ? "Unknown prefix '" + prefix + "' referenced in field '" + field + "'"
            : "Unknown prefix '" + prefix + "'");
    this.field = field;
    this.prefix = prefix;
  }

  /** The config or descriptor field where the unknown prefix was encountered. */
  public String getField() {
    return field;
  }

  /** The unresolved prefix label (left of the colon). */
  public String getPrefix() {
    return prefix;
  }
}
