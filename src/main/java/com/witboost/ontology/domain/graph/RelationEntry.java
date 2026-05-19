package com.witboost.ontology.domain.graph;

/** A single relation (RDF triple object + predicate) from one concept to another IRI. */
public record RelationEntry(String targetIri, String verb) {}
