package com.witboost.ontology;

/**
 * Canonical namespace constants for the Witboost Ontology Tech Adapter.
 *
 * <p>These constants represent adapter-level concerns shared across configuration presets, IRI
 * derivation, and test fixtures. They are <em>not</em> vocabulary-specific (e.g. SKOS) and must not
 * live inside any preset definition.
 */
public final class WitboostNamespaces {

  /** Default namespace for Witboost-derived IRIs ({@code wb:} prefix). */
  public static final String WB_NS = "https://witboost.com/type#";

  private WitboostNamespaces() {
    throw new java.lang.UnsupportedOperationException(
        "This is a utility class and cannot be instantiated");
  }
}
