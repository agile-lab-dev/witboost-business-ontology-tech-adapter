package com.witboost.ontology.interfaces.rest;

import com.witboost.ontology.application.GraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/graph")
@Slf4j
public class KgmSourceController {

  private final GraphService graphService;

  public KgmSourceController(GraphService graphService) {
    this.graphService = graphService;
  }

  /**
   * GET /v1/graph/jsonld
   *
   * <p>Returns the current RDF graph as JSON-LD, as required by the KGM Source Adapter ingestion
   * interface. The {@code sourceId} parameter is accepted for compatibility but ignored — this
   * adapter maintains a single graph.
   */
  @GetMapping(value = "/jsonld", produces = "application/ld+json")
  public ResponseEntity<String> getGraphJsonLd(
      @RequestParam(value = "sourceId", required = false) String sourceId) {
    log.info("GET /v1/graph/jsonld sourceId={}", sourceId);
    String jsonLd = graphService.getJsonLd();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/ld+json"))
        .body(jsonLd);
  }
}
