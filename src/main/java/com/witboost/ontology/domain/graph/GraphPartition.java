package com.witboost.ontology.domain.graph;

import java.time.Instant;

/**
 * One system's JSON-LD graph partition as stored in the database. {@code systemUrn} is the primary
 * key; {@code content} is the serialised JSON-LD string.
 */
public record GraphPartition(String systemUrn, String content, Instant updatedAt) {}
