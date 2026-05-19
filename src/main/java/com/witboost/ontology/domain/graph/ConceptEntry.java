package com.witboost.ontology.domain.graph;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable representation of a single RDF concept entry extracted from a descriptor. Subject
 * IRI is guaranteed non-null; other fields may be null when not configured.
 */
public record ConceptEntry(
    String iri,
    String name,
    String type,
    Map<String, String> properties,
    List<RelationEntry> relations) {

  public ConceptEntry {
    Objects.requireNonNull(iri, "ConceptEntry.iri must not be null");
  }
}
