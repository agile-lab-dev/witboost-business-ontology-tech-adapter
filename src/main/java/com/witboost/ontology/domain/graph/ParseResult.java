package com.witboost.ontology.domain.graph;

import java.util.List;
import java.util.Map;

/**
 * Immutable result of a full descriptor parse performed by {@link
 * com.witboost.ontology.application.MappingEngine#parse}.
 *
 * <p>Bundles everything the caller needs to build or store the RDF partition without making
 * additional calls back into {@code MappingEngine}:
 *
 * <ul>
 *   <li>{@link #prefixMap} — the effective prefix map resolved for this descriptor (static config
 *       merged with any descriptor-level {@code @context} field).
 *   <li>{@link #root} — the parsed root concept entry, or {@code null} when {@code
 *       descriptor.root.isConcept} is {@code false}.
 *   <li>{@link #concepts} — the parsed child concept entries (empty when none are configured).
 *   <li>{@link #rootVerb} — the fully-expanded IRI of the predicate used to link child concepts to
 *       the root (e.g. {@code http://www.w3.org/2004/02/skos/core#inScheme}). {@code null} when
 *       {@code descriptor.root.isConcept} is {@code false}, or when no verb is configured. Always
 *       non-null when a verb was configured — an unknown prefix is a hard validation error caught
 *       in {@code MappingEngine.parse()} before this result is returned.
 *   <li>{@link #reverseVerbMap} — map from a fully-expanded forward verb IRI to its fully-expanded
 *       inverse verb IRI (FR-020). Both keys and values are full IRIs (not CURIEs). An empty map
 *       means no inverse triples are emitted.
 * </ul>
 */
public record ParseResult(
    Map<String, String> prefixMap,
    ConceptEntry root,
    List<ConceptEntry> concepts,
    String rootVerb,
    Map<String, String> reverseVerbMap) {}
