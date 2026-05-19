package com.witboost.ontology.config;

import com.witboost.ontology.WitboostNamespaces;
import java.util.Map;

/**
 * Preset definition for the W3C SKOS (Simple Knowledge Organization System) vocabulary.
 *
 * <p>Provides a ready-to-use configuration baseline for adapters that publish concept schemes,
 * concepts, and narrower/broader relations using the standard SKOS namespace. Users only need
 * {@code app.graph.preset: skos} in their {@code application.yaml} and may override individual
 * fields as needed.
 *
 * <p>This is a no-arg record, so it has no mutable state and can be safely held as a singleton
 * inside the {@link GraphPreset#SKOS} enum constant.
 */
public record SkosPresetDefinition() implements GraphPresetDefinition {

  public static final String SKOS_NS = "http://www.w3.org/2004/02/skos/core#";

  @Override
  public Map<String, String> prefixLiterals() {
    return Map.of("skos", SKOS_NS, "wb", WitboostNamespaces.WB_NS);
  }

  @Override
  public String businessConceptsPath() {
    return "business_concepts";
  }

  @Override
  public Boolean rootIsConcept() {
    return true;
  }

  @Override
  public String rootType() {
    return "skos:ConceptScheme";
  }

  @Override
  public String rootVerb() {
    return "skos:inScheme";
  }

  @Override
  public Map<String, String> rootProperties() {
    return Map.of(
        "skos:prefLabel", "name",
        "skos:definition", "description");
  }

  @Override
  public String conceptType() {
    return "skos:Concept";
  }

  @Override
  public String conceptIriField() {
    return "iri";
  }

  @Override
  public String conceptNameField() {
    return "name";
  }

  @Override
  public String conceptTypeField() {
    return "type";
  }

  @Override
  public Map<String, String> conceptProperties() {
    return Map.of(
        "skos:prefLabel", "name",
        "skos:definition", "description");
  }

  @Override
  public String relationsVerb() {
    return "skos:narrower";
  }

  @Override
  public String relationsPath() {
    return "relations";
  }

  /**
   * SKOS supports multiple inter-concept relation types (narrower, broader, related). Reading the
   * verb from the {@code type} field of each relation entry lets descriptors express the specific
   * predicate per relation; the static {@code verb = skos:narrower} serves as the fallback when no
   * per-relation type is provided.
   */
  @Override
  public String relationsVerbField() {
    return "type";
  }

  /**
   * SKOS defines bidirectional semantic pairs: {@code skos:narrower/skos:broader} and the symmetric
   * {@code skos:related}. When a forward triple is emitted, the adapter automatically emits the
   * corresponding inverse triple so the graph remains fully navigable in both directions (FR-020).
   */
  @Override
  public Map<String, String> relationsReverseVerbMap() {
    return Map.of(
        "skos:narrower", "skos:broader",
        "skos:broader", "skos:narrower",
        "skos:related", "skos:related");
  }
}
