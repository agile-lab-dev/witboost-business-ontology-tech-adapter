package com.witboost.ontology.domain.validation;

/**
 * A structured validation error produced during descriptor parsing or config validation (FR-017).
 * Use the static factory methods to create instances with the correct field and message.
 */
public record GraphValidationError(String field, String message, String prefix) {

  /** Unknown prefix label detected in a config or descriptor IRI value. */
  public static GraphValidationError unknownPrefix(String field, String prefix) {
    return new GraphValidationError(
        field,
        "Unknown prefix '"
            + prefix
            + "' in field '"
            + field
            + "'. "
            + "Cannot find prefix under Tech Adapter configuration, or under configured descriptor prefixes field if any.",
        prefix);
  }

  /** Required field is absent in the descriptor. */
  public static GraphValidationError missingField(String field) {
    return new GraphValidationError(
        field, "Required field '" + field + "' is missing from the descriptor.", null);
  }

  /** Two or more concepts within the same descriptor resolved to the same subject IRI. */
  public static GraphValidationError iriCollision(String iri) {
    return new GraphValidationError(
        "iri",
        "IRI collision: two or more concepts resolve to the same subject IRI '"
            + iri
            + "'. "
            + "Each concept must have a unique IRI within the same descriptor.",
        null);
  }

  /**
   * A concept entry has neither a usable {@code iri-field} value nor a {@code name-field} value, so
   * its subject IRI cannot be derived by any configured strategy.
   */
  public static GraphValidationError unresolvableIri(String iriField, String nameField) {
    String fieldHint =
        (iriField != null && nameField != null)
            ? "'"
                + iriField
                + "' (iri-field) and '"
                + nameField
                + "' (name-field) are both absent or blank"
            : (iriField != null)
                ? "'"
                    + iriField
                    + "' (iri-field) is absent or blank and no name-field is configured"
                : (nameField != null)
                    ? "no iri-field is configured and '"
                        + nameField
                        + "' (name-field) is absent or blank"
                    : "neither iri-field nor name-field is configured";
    return new GraphValidationError(
        iriField != null ? iriField : nameField,
        "Cannot resolve IRI for a concept entry: "
            + fieldHint
            + ". "
            + "Every concept must have a value for at least one of iri-field or name-field.",
        null);
  }

  /**
   * A concept entry has neither a usable per-concept {@code type-field} value nor a configured
   * {@code concept-mapping.type}, so no {@code rdf:type} triple can be emitted.
   */
  public static GraphValidationError unresolvableType(String typeField, String conceptIri) {
    String fieldHint =
        typeField != null
            ? "field '"
                + typeField
                + "' (type-field) is absent or blank in the concept entry "
                + "and 'concept-mapping.type' is not configured"
            : "'concept-mapping.type' is not configured and no 'type-field' is set";
    return new GraphValidationError(
        typeField != null ? typeField : "concept-mapping.type",
        "Cannot resolve RDF type for concept <"
            + conceptIri
            + ">: "
            + fieldHint
            + ". "
            + "Set 'app.graph.concept-mapping.type' or ensure every concept entry has a value "
            + "for the configured 'type-field'.",
        null);
  }

  /**
   * A relation entry has no resolvable RDF predicate: neither the per-relation {@code verb-field}
   * value nor the configured {@code relations-mapping.verb} could be expanded to a full IRI.
   * Without a predicate, the relation cannot be expressed as an RDF triple.
   */
  public static GraphValidationError unresolvableRelationVerb(String verbField, String targetIri) {
    String fieldHint =
        verbField != null
            ? "field '"
                + verbField
                + "' (verb-field) is absent or blank in the relation entry "
                + "and 'relations-mapping.verb' is not configured"
            : "'relations-mapping.verb' is not configured and no 'verb-field' is set";
    return new GraphValidationError(
        verbField != null ? verbField : "relations-mapping.verb",
        "Cannot resolve RDF predicate (verb) for relation targeting <"
            + targetIri
            + ">: "
            + fieldHint
            + ". "
            + "Set 'app.graph.relations-mapping.verb' or ensure every relation entry has a value "
            + "for the configured 'verb-field'.",
        null);
  }
}
