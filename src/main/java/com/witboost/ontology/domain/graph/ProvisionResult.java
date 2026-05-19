package com.witboost.ontology.domain.graph;

import java.util.List;

/**
 * Immutable summary of a completed provision operation, returned by {@link
 * com.witboost.ontology.application.GraphService#provision}.
 *
 * @param systemUrn the data-product system URN that was provisioned
 * @param strategy which config strategy was used to materialise the graph partition
 * @param provisionedIris ordered list of RDF subject IRIs written to the store; for {@link
 *     Strategy#MAPPING} this is root IRI (when present) followed by all concept IRIs; for {@link
 *     Strategy#PASSTHROUGH} this contains only the system URN since individual IRIs are embedded
 *     inside the opaque JSON-LD payload
 */
public record ProvisionResult(String systemUrn, Strategy strategy, List<String> provisionedIris) {

  /** How the graph partition was produced. */
  public enum Strategy {
    /** Config A — descriptor fields were mapped to RDF via the concept-mapping configuration. */
    MAPPING,
    /** Config B — a pre-built JSON-LD payload was stored verbatim from the descriptor field. */
    PASSTHROUGH
  }
}
