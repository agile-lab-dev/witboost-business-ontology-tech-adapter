package com.witboost.ontology.application;

import com.apicatalog.jsonld.JsonLd;
import com.apicatalog.jsonld.JsonLdError;
import com.apicatalog.jsonld.JsonLdErrorCode;
import com.apicatalog.jsonld.JsonLdOptions;
import com.apicatalog.jsonld.document.JsonDocument;
import com.apicatalog.jsonld.loader.DocumentLoader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.witboost.ontology.config.GraphProperties;
import com.witboost.ontology.domain.descriptor.DataProductDescriptor;
import com.witboost.ontology.domain.validation.GraphValidationError;
import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RiotException;
import org.apache.jena.sparql.util.IsoMatcher;

/** Adds missing root links without rewriting existing JSON-LD node descriptions. */
@Slf4j
final class RootVerbEnricher {

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final String DOCUMENT_PROPERTY = "urn:witboost:enrichment:document";
  private static final String OPERATION = "yaml-ld-enrichment.add-root-verb";
  private static final String FILTER_FIELD = "descriptor.yaml-ld-enrichment.add-root-verb.types";
  private static final DocumentLoader LOCAL_CONTEXTS_ONLY =
      (documentUrl, options) -> {
        throw new JsonLdError(JsonLdErrorCode.LOADING_REMOTE_CONTEXT_FAILED);
      };

  private final GraphProperties graphProperties;
  private final PrefixResolver prefixResolver;

  /** Uses the adapter settings and prefix resolver to configure root-link enrichment. */
  RootVerbEnricher(GraphProperties graphProperties, PrefixResolver prefixResolver) {
    this.graphProperties = graphProperties;
    this.prefixResolver = prefixResolver;
  }

  /** Analyzes expanded JSON-LD and adds missing root links to the original document. */
  String enrich(String payload, DataProductDescriptor descriptor, String field) {
    Configuration configuration = createRootEnrichmentConfiguration(descriptor);
    Dataset dataset = DatasetFactory.create();
    try {
      JsonNode parsed = OBJECT_MAPPER.readTree(payload);
      if (!(parsed instanceof ObjectNode original)) {
        throw createValidationException(
            field, "requires a JSON-LD document object in field '" + field + "'.");
      }
      JsonLdOptions options = new JsonLdOptions(LOCAL_CONTEXTS_ONLY);
      ObjectNode expanded = expandDocument(original, options, field);
      String graphKey = findGraphKey(original, options, field);
      List<Candidate> nodes = collectCandidates(original, graphKey, options, field);
      String rootIri = findRoot(nodes, configuration.rootType(), field);
      RDFParser.fromString(expanded.toString()).lang(Lang.JSONLD11).parse(dataset);
      Set<String> handled = collectSubjectsWithVerb(dataset, configuration.predicate());
      Set<String> additions = new LinkedHashSet<>();
      int added = 0;
      for (Candidate candidate : nodes) {
        JsonNode node = candidate.expanded();
        if (!isNamedNode(node) || !hasType(node, configuration.filter())) {
          continue;
        }
        String subjectIri = node.get("@id").asText();
        if (subjectIri.equals(rootIri) || !handled.add(subjectIri)) {
          continue;
        }
        appendLink(candidate.original(), configuration.predicate(), rootIri);
        additions.add(subjectIri);
        added++;
      }
      if (added > 0) {
        for (JsonNode node : expanded.get("@graph")) {
          if (additions.remove(node.path("@id").asText())) {
            appendLink((ObjectNode) node, configuration.predicate(), rootIri);
          }
        }
        dataset.asDatasetGraph().clear();
        RDFParser.fromString(expanded.toString()).lang(Lang.JSONLD11).parse(dataset);
        verifyAdditions(original, options, dataset, field);
      }
      log.debug(
          "JSON-LD root enrichment completed: directNodes={}, linksAdded={}",
          original.get(graphKey).size(),
          added);
      return added == 0 ? payload : OBJECT_MAPPER.writeValueAsString(original);
    } catch (JsonLdError expansionException) {
      throw createValidationException(
          field,
          "could not expand JSON-LD in field '"
              + field
              + "' ("
              + expansionException.getCode()
              + ")."
              + (expansionException.getCode() == JsonLdErrorCode.LOADING_REMOTE_CONTEXT_FAILED
                  ? " Remote contexts and @import are disabled."
                  : ""));
    } catch (JsonProcessingException | JsonException | RiotException processingException) {
      throw createValidationException(
          field, "requires valid JSON-LD with a supported graph shape in field '" + field + "'.");
    } finally {
      dataset.close();
    }
  }

  /** Resolves and validates the root type, predicate, and type filter using effective prefixes. */
  private Configuration createRootEnrichmentConfiguration(DataProductDescriptor descriptor) {
    Map<String, String> prefixes = prefixResolver.resolve(descriptor.getExtraFields());
    var root = graphProperties.getDescriptor().getRoot();
    String rootType = resolveConfigurationIri(root.getType(), "descriptor.root.type", prefixes);
    String predicate = resolveConfigurationIri(root.getVerb(), "descriptor.root.verb", prefixes);
    Set<String> filter = new LinkedHashSet<>();
    List<String> configuredTypes =
        graphProperties.getDescriptor().getYamlLdEnrichment().getAddRootVerb().getTypes();
    if (configuredTypes != null) {
      for (String type : configuredTypes) {
        filter.add(resolveConfigurationIri(type, FILTER_FIELD, prefixes));
      }
    }
    return new Configuration(rootType, predicate, filter);
  }

  /** Pairs each original top-level graph entry with its expansion under the document context. */
  private List<Candidate> collectCandidates(
      ObjectNode original, String graphKey, JsonLdOptions options, String field)
      throws JsonLdError, JsonProcessingException {
    List<Candidate> nodes = new ArrayList<>();
    ObjectNode entryDocument = original.deepCopy();
    for (JsonNode entry : original.get(graphKey)) {
      if (!(entry instanceof ObjectNode object)) {
        throw createValidationException(
            field, "requires node objects in the top-level @graph of field '" + field + "'.");
      }
      if (isSetObject(object, entryDocument, graphKey, options, field)) {
        continue;
      }
      entryDocument.set(graphKey, OBJECT_MAPPER.createArrayNode().add(object));
      for (JsonNode node : expandDocument(entryDocument, options, field).get("@graph")) {
        nodes.add(new Candidate(object, node));
      }
    }
    return nodes;
  }

  /** Detects set wrappers, including contextual aliases, by probing their expanded contents. */
  private boolean isSetObject(
      ObjectNode object,
      ObjectNode entryDocument,
      String graphKey,
      JsonLdOptions options,
      String field)
      throws JsonProcessingException {
    String probeIri = "urn:uuid:" + UUID.randomUUID();
    var fields = object.fields();
    while (fields.hasNext()) {
      var entry = fields.next();
      if (entry.getKey().equals("@context") || !entry.getValue().isContainerNode()) {
        continue;
      }
      ObjectNode probe = object.deepCopy();
      probe.putArray(entry.getKey()).addObject().put("@id", probeIri).put("@type", probeIri);
      entryDocument.set(graphKey, OBJECT_MAPPER.createArrayNode().add(probe));
      try {
        for (JsonNode node : expandDocument(entryDocument, options, field).get("@graph")) {
          if (node.path("@id").asText().equals(probeIri)) {
            return true;
          }
        }
      } catch (JsonLdError | GraphValidationException notSetProperty) {
        continue;
      }
    }
    return false;
  }

  /** Finds one distinct named root with the configured explicit type, or fails validation. */
  private String findRoot(List<Candidate> nodes, String rootType, String field) {
    Set<String> roots = new LinkedHashSet<>();
    for (Candidate candidate : nodes) {
      JsonNode node = candidate.expanded();
      if (isNamedNode(node) && hasType(node, Set.of(rootType))) {
        roots.add(node.get("@id").asText());
      }
    }
    if (roots.size() != 1) {
      throw createValidationException(
          field,
          "requires exactly one distinct named root matching descriptor.root.type='"
              + graphProperties.getDescriptor().getRoot().getType()
              + "' in the top-level @graph array of field '"
              + field
              + "'; found "
              + roots.size()
              + ".");
    }
    return roots.iterator().next();
  }

  /** Collects named subjects that already have the predicate in any graph of the dataset. */
  private Set<String> collectSubjectsWithVerb(Dataset dataset, String predicate) {
    Set<String> subjects = new LinkedHashSet<>();
    dataset
        .asDatasetGraph()
        .find()
        .forEachRemaining(
            quad -> {
              if (quad.getPredicate().getURI().equals(predicate) && quad.getSubject().isURI()) {
                subjects.add(quad.getSubject().getURI());
              }
            });
    return subjects;
  }

  /** Expands a temporary wrapper to retain and validate the unnamed top-level graph boundary. */
  private ObjectNode expandDocument(ObjectNode input, JsonLdOptions options, String field)
      throws JsonLdError, JsonProcessingException {
    JsonObject json;
    try (var reader = Json.createReader(new StringReader(input.toString()))) {
      json = reader.readObject();
    }
    JsonObject wrapper = Json.createObjectBuilder().add(DOCUMENT_PROPERTY, json).build();
    var expansion = JsonLd.expand(JsonDocument.of(wrapper)).options(options).get();
    JsonNode documents = OBJECT_MAPPER.readTree(expansion.toString());
    JsonNode values = documents.path(0).path(DOCUMENT_PROPERTY);
    JsonNode document = values.path(0);
    if (!values.isArray()
        || values.size() != 1
        || !document.isObject()
        || document.has("@id")
        || !document.path("@graph").isArray()) {
      throw createValidationException(
          field, "requires an unnamed top-level @graph array in field '" + field + "'.");
    }
    return (ObjectNode) document;
  }

  /** Finds the original graph key or alias by expanding copies containing a probe node. */
  private String findGraphKey(ObjectNode original, JsonLdOptions options, String field)
      throws JsonProcessingException {
    String probeIri = "urn:uuid:" + UUID.randomUUID();
    var fields = original.fields();
    while (fields.hasNext()) {
      var entry = fields.next();
      if (!entry.getValue().isArray() || entry.getKey().equals("@context")) {
        continue;
      }
      ObjectNode probe = original.deepCopy();
      probe.putArray(entry.getKey()).addObject().put("@id", probeIri).put("@type", probeIri);
      try {
        for (JsonNode node : expandDocument(probe, options, field).get("@graph")) {
          if (node.path("@id").asText().equals(probeIri)) {
            return entry.getKey();
          }
        }
      } catch (JsonLdError | GraphValidationException notGraphArray) {
        continue;
      }
    }
    throw createValidationException(
        field, "requires an unnamed top-level @graph array in field '" + field + "'.");
  }

  /** Adds a root reference under the predicate key without discarding existing values. */
  private void appendLink(ObjectNode node, String predicate, String rootIri) {
    ObjectNode link = OBJECT_MAPPER.createObjectNode().put("@id", rootIri);
    JsonNode existing = node.get(predicate);
    if (existing == null) {
      node.set(predicate, link);
    } else if (existing instanceof ArrayNode values) {
      values.add(link);
    } else {
      node.set(predicate, OBJECT_MAPPER.createArrayNode().add(existing).add(link));
    }
  }

  /** Rejects changes whose RDF meaning or graph placement differs from the expected additions. */
  private void verifyAdditions(
      ObjectNode original, JsonLdOptions options, Dataset expected, String field)
      throws JsonLdError, JsonProcessingException {
    Dataset actual = DatasetFactory.create();
    try {
      RDFParser.fromString(expandDocument(original, options, field).toString())
          .lang(Lang.JSONLD11)
          .parse(actual);
      if (!IsoMatcher.isomorphic(expected.asDatasetGraph(), actual.asDatasetGraph())) {
        throw createValidationException(
            field,
            "cannot add root links without changing their meaning under the contexts in field '"
                + field
                + "'.");
      }
    } finally {
      actual.close();
    }
  }

  /** Checks whether the expanded node has a valid absolute IRI rather than a blank-node ID. */
  private boolean isNamedNode(JsonNode node) {
    return node.hasNonNull("@id") && prefixResolver.isAbsoluteIri(node.get("@id").asText());
  }

  /** Matches any explicit type in the filter; an empty filter accepts any explicitly typed node. */
  private boolean hasType(JsonNode node, Set<String> filter) {
    JsonNode types = node.get("@type");
    if (types == null || !types.isArray() || types.isEmpty()) {
      return false;
    }
    for (JsonNode type : types) {
      if (filter.isEmpty() || filter.contains(type.asText())) {
        return true;
      }
    }
    return false;
  }

  /** Resolves a configured CURIE or IRI and reports invalid values against its field. */
  private String resolveConfigurationIri(String value, String field, Map<String, String> prefixes) {
    if (value == null || value.isBlank()) {
      String presetGuidance =
          FILTER_FIELD.equals(field)
              ? ""
              : " With preset 'custom', configure it explicitly; preset 'skos' supplies a default.";
      throw createValidationException(
          field,
          "When root-verb enrichment is enabled, '"
              + field
              + "' must be a nonblank absolute IRI or configured CURIE."
              + presetGuidance);
    }
    try {
      String expanded = prefixResolver.expand(value, prefixes, field);
      if (!prefixResolver.isAbsoluteIri(expanded)) {
        throw createValidationException(
            field, "requires '" + field + "' to resolve to a valid absolute IRI.");
      }
      return expanded;
    } catch (UnknownPrefixException unknownPrefixException) {
      GraphValidationError error =
          GraphValidationError.unknownPrefix(field, unknownPrefixException.getPrefix());
      throw new GraphValidationException(
          List.of(
              new GraphValidationError(
                  error.field(), OPERATION + ": " + error.message(), error.prefix())));
    }
  }

  /** Creates a structured validation error naming the enrichment operation and failing field. */
  static GraphValidationException createValidationException(String field, String message) {
    return new GraphValidationException(
        List.of(new GraphValidationError(field, OPERATION + " " + message, null)));
  }

  private record Configuration(String rootType, String predicate, Set<String> filter) {}

  private record Candidate(ObjectNode original, JsonNode expanded) {}
}
