package com.witboost.ontology.application;

import com.witboost.ontology.application.infrastructure.GraphRepository;
import com.witboost.ontology.config.GraphProperties;
import com.witboost.ontology.domain.descriptor.DataProductDescriptor;
import com.witboost.ontology.domain.graph.ConceptEntry;
import com.witboost.ontology.domain.graph.GraphPartition;
import com.witboost.ontology.domain.graph.ParseResult;
import com.witboost.ontology.domain.graph.ProvisionResult;
import com.witboost.ontology.domain.graph.ProvisionResult.Strategy;
import com.witboost.ontology.domain.graph.RelationEntry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the provision/unprovision lifecycle and the merged-graph read path.
 *
 * <p>On provision (Config B): if {@link PassthroughEngine} finds a JSON-LD payload in the
 * descriptor's {@code yaml-ld-path} field, it is stored verbatim and concept-mapping is skipped.
 *
 * <p>On provision (Config A): {@link MappingEngine} parses the descriptor → Jena model is built →
 * serialised to JSON-LD → upserted via {@link GraphRepository} → cache invalidated.
 *
 * <p>On unprovision: deletes the system partition and invalidates the cache.
 *
 * <p>On graph read ({@code GET /v1/graph/jsonld}): delegates to {@link GraphCacheService}, which
 * lazily merges all DB partitions if the cache is stale.
 */
@Service
@Slf4j
public class GraphService {

  private final GraphProperties graphProperties;
  private final MappingEngine mappingEngine;
  private final PassthroughEngine passthroughEngine;
  private final GraphCacheService graphCacheService;
  private final GraphRepository graphRepository;

  public GraphService(
      GraphProperties graphProperties,
      MappingEngine mappingEngine,
      PassthroughEngine passthroughEngine,
      GraphCacheService graphCacheService,
      GraphRepository graphRepository) {
    this.graphProperties = graphProperties;
    this.mappingEngine = mappingEngine;
    this.passthroughEngine = passthroughEngine;
    this.graphCacheService = graphCacheService;
    this.graphRepository = graphRepository;
  }

  /**
   * Provisions a data-product descriptor, choosing Config B (YAML-LD pass-through) when the
   * descriptor carries an embedded JSON-LD payload, or falling back to Config A (YAML-to-RDF
   * mapping) otherwise.
   *
   * <p>MDC keys populated during this call (cleared in the {@code finally} block):
   *
   * <ul>
   *   <li>{@code systemUrn} — the data product's system URN
   *   <li>{@code operation} — {@code "provision"}
   *   <li>{@code strategy} — {@code "passthrough"} or {@code "mapping"}
   *   <li>{@code conceptCount} — number of concept entries parsed (Config A only)
   * </ul>
   *
   * @return a {@link ProvisionResult} containing the system URN, the strategy used, and the list of
   *     IRIs materialised
   * @throws GraphValidationException if the descriptor fails validation
   */
  public ProvisionResult provision(DataProductDescriptor descriptor) {
    String systemUrn = descriptor.getId();
    MDC.put("systemUrn", systemUrn);
    MDC.put("operation", "provision");
    try {
      Optional<String> passthroughPayload = passthroughEngine.extractYamlLd(descriptor);
      if (passthroughPayload.isPresent()) {
        MDC.put("strategy", "passthrough");
        graphRepository.upsert(systemUrn, passthroughPayload.get());
        graphCacheService.invalidate();
        log.info("Provisioned system_urn={} via pass-through (Config B)", systemUrn);
        // Individual IRIs are embedded in the pass-through payload; only system URN is known here
        return new ProvisionResult(systemUrn, Strategy.PASSTHROUGH, List.of(systemUrn));
      }

      MDC.put("strategy", "mapping");
      ParseResult parseResult = mappingEngine.parse(descriptor);
      MDC.put("conceptCount", String.valueOf(parseResult.concepts().size()));
      String jsonLd = buildPartition(parseResult);
      graphRepository.upsert(systemUrn, jsonLd);
      graphCacheService.invalidate();
      log.info(
          "Provisioned system_urn={} via mapping (Config A), concepts={}",
          systemUrn,
          parseResult.concepts().size());

      List<String> provisionedIris =
          Stream.concat(
                  parseResult.root() != null ? Stream.of(parseResult.root().iri()) : Stream.empty(),
                  parseResult.concepts().stream().map(ConceptEntry::iri))
              .toList();
      return new ProvisionResult(systemUrn, Strategy.MAPPING, provisionedIris);
    } finally {
      MDC.clear();
    }
  }

  /**
   * Deletes the graph partition for the given system and invalidates the merged cache.
   *
   * <p>MDC keys populated during this call (cleared in the {@code finally} block):
   *
   * <ul>
   *   <li>{@code systemUrn} — the data product's system URN
   *   <li>{@code operation} — {@code "unprovision"}
   * </ul>
   */
  public void unprovision(DataProductDescriptor descriptor) {
    String systemUrn = descriptor.getId();
    MDC.put("systemUrn", systemUrn);
    MDC.put("operation", "unprovision");
    try {
      graphRepository.delete(systemUrn);
      graphCacheService.invalidate();
      log.info("Unprovisioned system_urn={}", systemUrn);
    } finally {
      MDC.clear();
    }
  }

  /** Returns the merged JSON-LD graph for all provisioned systems (cache-backed). */
  public String getJsonLd() {
    return graphCacheService.getOrBuild(this::buildMergedGraph);
  }

  // ── Private: Jena model building ─────────────────────────────────────────

  /**
   * Builds a JSON-LD string representing the graph partition for a single system.
   *
   * <p>If the root is configured as a concept ({@code descriptor.root.isConcept = true}) and a root
   * entry is present in the {@link ParseResult}, the root resource is added first and every child
   * concept is linked back to it via the configured {@code root.verb} predicate (e.g. {@code
   * skos:inScheme}). Otherwise all concepts are added as standalone resources with no structural
   * parent link.
   *
   * @param parseResult the fully validated parse result produced by {@link MappingEngine#parse}
   * @return a JSON-LD string ready for persistence
   */
  private String buildPartition(ParseResult parseResult) {
    Model model = ModelFactory.createDefaultModel();
    parseResult.prefixMap().forEach(model::setNsPrefix);

    GraphProperties.RootProperties rootConfig = graphProperties.getDescriptor().getRoot();
    Map<String, String> reverseVerbMap = parseResult.reverseVerbMap();

    if (rootConfig.getIsConcept() && parseResult.root() != null) {
      String effectiveRootIri = parseResult.root().iri();
      addConceptToModel(model, parseResult.root(), null, null, reverseVerbMap);

      for (ConceptEntry concept : parseResult.concepts()) {
        addConceptToModel(model, concept, effectiveRootIri, parseResult.rootVerb(), reverseVerbMap);
      }
    } else {
      for (ConceptEntry concept : parseResult.concepts()) {
        addConceptToModel(model, concept, null, null, reverseVerbMap);
      }
    }

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    RDFDataMgr.write(outputStream, model, Lang.JSONLD);
    return outputStream.toString(StandardCharsets.UTF_8);
  }

  /**
   * Materialises a single {@link ConceptEntry} as Jena RDF triples and adds them to the model.
   *
   * <p>The following triples are emitted per concept:
   *
   * <ul>
   *   <li>{@code <concept.iri> rdf:type <concept.type>} — when {@code concept.type()} is non-null
   *   <li>{@code <concept.iri> <propertyKey> "propertyValue"} — one triple per property entry
   *   <li>{@code <concept.iri> <rootVerb> <rootIri>} — structural parent link, only when both
   *       {@code rootIri} and {@code rootVerb} are non-null
   *   <li>{@code <concept.iri> <relation.verb> <relation.targetIri>} — one triple per {@link
   *       RelationEntry} that carries a non-null verb
   * </ul>
   *
   * @param model the Jena model to mutate
   * @param concept the concept whose triples are to be added
   * @param rootIri the IRI of the root resource that this concept is linked to, or {@code null} if
   *     the concept has no structural parent (root disabled or is the root itself)
   * @param rootVerb the fully-expanded IRI of the predicate used for the parent link (e.g. {@code
   *     skos:inScheme} expanded), or {@code null} when {@code rootIri} is null
   * @param reverseVerbMap map from a fully-expanded forward verb IRI to its inverse verb IRI; when
   *     the forward verb appears as a key, the inverse triple {@code target → reverseVerb → source}
   *     is also added (FR-020). An empty map means no inverse triples are emitted.
   */
  private void addConceptToModel(
      Model model,
      ConceptEntry concept,
      String rootIri,
      String rootVerb,
      Map<String, String> reverseVerbMap) {
    var resource = model.createResource(concept.iri());
    if (concept.type() != null) {
      model.add(resource, RDF.type, model.createResource(concept.type()));
    }
    for (Map.Entry<String, String> propertyEntry : concept.properties().entrySet()) {
      model.add(resource, model.createProperty(propertyEntry.getKey()), propertyEntry.getValue());
    }
    // concept → root link
    if (rootIri != null && rootVerb != null) {
      model.add(resource, model.createProperty(rootVerb), model.createResource(rootIri));
    }
    // concept → concept relations (forward + optional inverse triple)
    for (RelationEntry relation : concept.relations()) {
      if (relation.verb() != null) {
        model.add(
            resource,
            model.createProperty(relation.verb()),
            model.createResource(relation.targetIri()));
        String reverseVerb = reverseVerbMap.get(relation.verb());
        if (reverseVerb != null) {
          model.add(
              model.createResource(relation.targetIri()),
              model.createProperty(reverseVerb),
              resource);
        }
      }
    }
  }

  /**
   * Reads all stored {@link GraphPartition}s from the repository and merges them into a single Jena
   * model, then serialises the result as JSON-LD.
   *
   * <p>Partitions that cannot be parsed (corrupt or incompatible JSON-LD) are skipped with a
   * warning rather than aborting the entire merge, so a single bad partition does not make the
   * graph endpoint unavailable.
   *
   * @return a JSON-LD string representing the union of all stored partitions, or an empty JSON-LD
   *     document when no partitions are present
   */
  private String buildMergedGraph() {
    List<GraphPartition> partitions = graphRepository.findAll();
    if (partitions.isEmpty()) {
      Model emptyModel = ModelFactory.createDefaultModel();
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      RDFDataMgr.write(outputStream, emptyModel, Lang.JSONLD);
      return outputStream.toString(StandardCharsets.UTF_8);
    }
    Model mergedModel = ModelFactory.createDefaultModel();
    for (GraphPartition partition : partitions) {
      try (ByteArrayInputStream inputStream =
          new ByteArrayInputStream(partition.content().getBytes(StandardCharsets.UTF_8))) {
        Model partitionModel = ModelFactory.createDefaultModel();
        RDFDataMgr.read(partitionModel, inputStream, Lang.JSONLD);
        mergedModel.add(partitionModel);
      } catch (IOException ioException) {
        log.error(
            "Could not parse partition for {}: {}",
            partition.systemUrn(),
            ioException.getMessage());
      }
    }
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    RDFDataMgr.write(outputStream, mergedModel, Lang.JSONLD);
    return outputStream.toString(StandardCharsets.UTF_8);
  }
}
