package com.witboost.ontology.config;

import java.util.Map;

/**
 * Defines the default values supplied by a named preset when {@code preset} is configured in {@link
 * GraphProperties}.
 *
 * <p>Each method returns the preset's default for the corresponding configuration field. A return
 * value of {@code null} (scalars) or an empty map means "no default — user must configure
 * explicitly". This convention lets {@link com.witboost.ontology.application.PresetMergeService}
 * apply a simple null-fallback pattern without any preset-specific branching.
 *
 * <p>To add a new preset:
 *
 * <ol>
 *   <li>Create a record (or class) implementing this interface.
 *   <li>Override the methods that have meaningful defaults for the new preset.
 *   <li>Register it in {@link GraphPreset} by adding a new enum constant that references your
 *       implementation.
 * </ol>
 */
public interface GraphPresetDefinition {

  // ── Prefix defaults ────────────────────────────────────────────────────────

  /**
   * Prefix literals (label → namespace IRI) that the preset injects as a baseline. User-supplied
   * prefixes are layered on top and win on conflict.
   */
  default Map<String, String> prefixLiterals() {
    return Map.of();
  }

  // ── Descriptor defaults ────────────────────────────────────────────────────

  /** Default path inside the YAML descriptor where the list of ontology concepts lives. */
  default String businessConceptsPath() {
    return null;
  }

  // ── Root concept defaults ──────────────────────────────────────────────────

  /** Whether the root entity is itself a concept in the ontology. */
  default Boolean rootIsConcept() {
    return null;
  }

  /** RDF class (CURIE or IRI) assigned to the root entity. */
  default String rootType() {
    return null;
  }

  /** Descriptor field name from which the root entity's RDF type is read per-deployment. */
  default String rootTypeField() {
    return null;
  }

  /** RDF predicate (CURIE or IRI) linking root → each concept. */
  default String rootVerb() {
    return null;
  }

  /** Descriptor field name from which the root→child link verb is read per-deployment. */
  default String rootVerbField() {
    return null;
  }

  /** Descriptor field name used as the root entity's subject IRI. */
  default String rootIriField() {
    return null;
  }

  /** Descriptor field name used as the root entity's display name. */
  default String rootNameField() {
    return null;
  }

  /**
   * Base property mappings (RDF predicate → descriptor field) for the root concept. Applied as a
   * baseline; user-supplied {@code descriptor.root.properties} entries are layered on top.
   */
  default Map<String, String> rootProperties() {
    return Map.of();
  }

  // ── Concept mapping defaults ───────────────────────────────────────────────

  /** RDF type (CURIE or IRI) assigned to each mapped concept. */
  default String conceptType() {
    return null;
  }

  /** Descriptor field used to derive the concept IRI. */
  default String conceptIriField() {
    return null;
  }

  /** Descriptor field used as the human-readable label. */
  default String conceptNameField() {
    return null;
  }

  /**
   * Descriptor field name from which per-concept RDF type overrides are read. When present on a
   * concept entry, wins over {@code conceptType()}.
   */
  default String conceptTypeField() {
    return null;
  }

  /**
   * Base concept property mappings (RDF predicate → descriptor field). User-supplied {@code
   * conceptMapping.properties} entries are layered on top.
   */
  default Map<String, String> conceptProperties() {
    return Map.of();
  }

  // ── Relations mapping defaults ─────────────────────────────────────────────

  /** RDF predicate (CURIE or IRI) expressing a relation between two concepts. */
  default String relationsVerb() {
    return null;
  }

  /** Descriptor field (on each concept) containing the list of related concept identifiers. */
  default String relationsPath() {
    return null;
  }

  /**
   * Descriptor field name on each relation entry from which the per-relation RDF predicate is read.
   * When a non-blank value is found on an entry, it wins over {@code relationsVerb()}. A value with
   * an unknown prefix causes a validation error.
   */
  default String relationsVerbField() {
    return null;
  }

  /**
   * Map from a forward relation verb IRI (or CURIE) to its inverse verb IRI (or CURIE) (FR-020).
   *
   * <p>When a relation triple {@code A → verb → B} is emitted and {@code verb} appears as a key in
   * this map, the adapter also emits the inverse triple {@code B → reverseVerb → A} in the same
   * graph partition.
   *
   * <p>An empty map (the default) means no inverse triples are emitted. Override this method to
   * provide preset-specific bidirectional defaults (e.g. SKOS narrower/broader pairs).
   */
  default Map<String, String> relationsReverseVerbMap() {
    return Map.of();
  }
}
