package com.witboost.ontology.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;

import com.apicatalog.jsonld.loader.DocumentLoader;
import com.apicatalog.jsonld.loader.HttpLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.witboost.ontology.config.GraphProperties;
import com.witboost.ontology.domain.descriptor.DataProductDescriptor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PassthroughEngineTest {

  private static final String SCHEME = "http://www.w3.org/2004/02/skos/core#";
  private static final String ONTOLOGY = "https://example.org/ontology";
  private static final String MEMBERSHIP = "http://www.w3.org/2000/01/rdf-schema#isDefinedBy";
  private static final String OWL_GRAPH =
      """
      {
        "@context": {
          "@base": "https://example.org/",
          "@vocab": "http://www.w3.org/2002/07/owl#",
          "owl": "http://www.w3.org/2002/07/owl#",
          "rdfs": "http://www.w3.org/2000/01/rdf-schema#",
          "items": "@graph", "kind": "@type",
          "belongsTo": "rdfs:isDefinedBy",
          "ScopedClass": {"@id": "owl:Class", "@context": {
            "label": "http://www.w3.org/2000/01/rdf-schema#label"
          }},
          "related": {"@id": "https://example.org/related", "@context": {
            "Category": "http://www.w3.org/2002/07/owl#Class"
          }}
        },
        "kind": ["owl:NamedIndividual"],
        "items": [
          {"@id": "ontology", "kind": "owl:Ontology"},
          {"@id": "ontology", "kind": ["owl:Ontology"]},
          {"@id": "Class", "kind": ["owl:NamedIndividual", "owl:Class"],
           "http://www.w3.org/2000/01/rdf-schema#isDefinedBy": [],
           "related": {"@id": "Nested", "kind": "Category"}},
          {"@id": "Class", "kind": "owl:Class"},
          {"@id": "Property", "kind": "owl:ObjectProperty",
           "http://www.w3.org/2000/01/rdf-schema#isDefinedBy": null},
          {"@id": "External", "kind": "Class"},
          {"@id": "https://external.org/Term", "kind": "owl:Class"},
          {"@id": "FullExisting", "kind": "owl:Class",
           "http://www.w3.org/2000/01/rdf-schema#isDefinedBy": [{"@id": "elsewhere"}]},
          {"@id": "AliasExisting", "kind": "owl:Class",
           "@nest": {"belongsTo": {"@id": "elsewhere"}}},
          {"@id": "Scoped", "kind": "ScopedClass", "label": "Scoped class"},
          {"@context": {"@base": "https://local.example/"},
           "@id": "Local", "@nest": {"kind": "owl:Class"}},
          {"@id": "Existing", "kind": "owl:Class"},
          {"@id": "Holder", "related": {"@id": "Existing",
           "rdfs:isDefinedBy": {"@id": "elsewhere"}},
           "@included": [{"@id": "Included", "kind": "owl:Class"}]},
          {"@id": "Individual", "kind": "owl:NamedIndividual"},
          {"@id": "Reference"},
          {"@id": "_:blank", "kind": "owl:Class"},
          {"@id": "graph", "@graph": [{"@id": "InGraph", "kind": "owl:Class"}]}
        ]
      }
      """;

  private PassthroughEngine engine;
  private GraphProperties props;

  @BeforeEach
  void setUp() {
    props = new GraphProperties();
    engine = new PassthroughEngine(props, new PrefixResolver(props));
  }

  private DataProductDescriptor descriptor(Map<String, Object> extra) {
    DataProductDescriptor descriptor = new DataProductDescriptor();
    descriptor.setId("urn:dmb:dp:domain:registry-a:0");
    extra.forEach(descriptor::setExtraField);
    return descriptor;
  }

  // ── (a) yaml-ld-path not configured → Optional.empty() ───────────────────

  @Test
  void extractYamlLd_pathNotConfigured_returnsEmpty() {
    // yamlLdPath is null by default
    Optional<String> result = engine.extractYamlLd(descriptor(Map.of("some_field", "value")));
    assertThat(result).isEmpty();
  }

  // ── (b) field present → returns JSON-LD string ────────────────────────────

  @Test
  void extractYamlLd_fieldPresent_returnsJsonString() {
    props.getDescriptor().setYamlLdPath("graph_definition");
    String jsonLd = "{\"@graph\":[{\"@id\":\"urn:example\"}]}";
    DataProductDescriptor desc = descriptor(Map.of("graph_definition", jsonLd));

    Optional<String> result = engine.extractYamlLd(desc);

    assertThat(result).isPresent();
    assertThat(result.get()).isEqualTo(jsonLd);
    props.getDescriptor().getRoot().setType("bad:prefix");
    assertThat(enrich("not JSON-LD")).isEqualTo("not JSON-LD");
  }

  // ── field present as Map → serialized to JSON string ─────────────────────

  @Test
  void extractYamlLd_fieldPresentAsMap_serializedToJson() {
    props.getDescriptor().setYamlLdPath("graph_definition");
    Map<String, Object> graphObject =
        Map.of("@graph", java.util.List.of(Map.of("@id", "urn:example")));
    DataProductDescriptor desc = descriptor(Map.of("graph_definition", graphObject));

    Optional<String> result = engine.extractYamlLd(desc);

    assertThat(result).isPresent();
    assertThat(result.get()).contains("@graph").contains("@id").contains("urn:example");
  }

  // ── (c) field absent, yaml-ld-required: false → Optional.empty() (fall-through) ──

  @Test
  void extractYamlLd_fieldAbsent_notRequired_returnsEmpty() {
    props.getDescriptor().setYamlLdPath("graph_definition");
    props.getDescriptor().setYamlLdRequired(false);
    DataProductDescriptor desc = descriptor(Map.of()); // field absent

    Optional<String> result = engine.extractYamlLd(desc);

    assertThat(result).isEmpty();
  }

  // ── (d) field absent, yaml-ld-required: true → throws validation error ───

  @Test
  void extractYamlLd_fieldAbsent_required_throwsValidationError() {
    props.getDescriptor().setYamlLdPath("graph_definition");
    props.getDescriptor().setYamlLdRequired(true);
    DataProductDescriptor desc = descriptor(Map.of()); // field absent

    assertThatThrownBy(() -> engine.extractYamlLd(desc))
        .isInstanceOf(GraphValidationException.class)
        .satisfies(
            ex -> {
              var errors = ((GraphValidationException) ex).getErrors();
              assertThat(errors).hasSize(1);
              assertThat(errors.get(0).field()).isEqualTo("graph_definition");
            });
  }

  @Test
  void enrichment_skosDefaults_addsOnlyMissingLinks() {
    new PresetMergeService(props).init();
    props.getDescriptor().setYamlLdPath("graph_definition");
    props.getDescriptor().getYamlLdEnrichment().getAddRootVerb().setEnabled(true);
    String payload =
        """
        {"@graph":[
          {"@id":"https://example.org/scheme","@type":"http://www.w3.org/2004/02/skos/core#ConceptScheme"},
          {"@id":"https://example.org/A","@type":"http://www.w3.org/2004/02/skos/core#Concept"},
          {"@id":"https://example.org/B","@type":"http://www.w3.org/2004/02/skos/core#Concept"}
        ]}
        """;
    Model result = model(enrich(payload));
    assertThat(
            result
                .listStatements(
                    null,
                    result.createProperty(SCHEME + "inScheme"),
                    (org.apache.jena.rdf.model.RDFNode) null)
                .toList())
        .hasSize(2);
    assertThat(result.size()).isEqualTo(5);
  }

  @Test
  void enrichment_contextExpandedTypes_filterDirectNodesAndPreserveStructure() throws Exception {
    enableOwl();
    props
        .getDescriptor()
        .getYamlLdEnrichment()
        .getAddRootVerb()
        .setTypes(List.of("owl:Class", "owl:ObjectProperty"));
    String result = enrich(OWL_GRAPH);
    Model graph = model(result);
    assertThat(
            graph
                .listStatements(
                    null, graph.createProperty(MEMBERSHIP), graph.createResource(ONTOLOGY))
                .toList())
        .extracting(statement -> statement.getSubject().getURI())
        .containsExactlyInAnyOrder(
            "https://example.org/Class",
            "https://example.org/Property",
            "https://example.org/External",
            "https://external.org/Term",
            "https://example.org/Scoped",
            "https://local.example/Local");
    assertThat(
            graph.contains(
                graph.createResource("https://example.org/Existing"),
                graph.createProperty(MEMBERSHIP),
                graph.createResource("https://example.org/elsewhere")))
        .isTrue();
    Model expected = model(OWL_GRAPH);
    expected.add(
        graph.listStatements(
            null, graph.createProperty(MEMBERSHIP), graph.createResource(ONTOLOGY)));
    assertThat(graph.isIsomorphicWith(expected)).isTrue();
    var originalDataset = org.apache.jena.query.DatasetFactory.create();
    var resultDataset = org.apache.jena.query.DatasetFactory.create();
    try {
      RDFParser.fromString(OWL_GRAPH).lang(Lang.JSONLD11).parse(originalDataset);
      RDFParser.fromString(result).lang(Lang.JSONLD11).parse(resultDataset);
      assertThat(
              resultDataset
                  .getNamedModel("https://example.org/graph")
                  .isIsomorphicWith(originalDataset.getNamedModel("https://example.org/graph")))
          .isTrue();
    } finally {
      originalDataset.close();
      resultDataset.close();
    }
    var mapper = new ObjectMapper();
    var original = mapper.readTree(OWL_GRAPH);
    var output = mapper.readTree(result);
    assertThat(output.get("@context")).isEqualTo(original.get("@context"));
    assertThat(output.get("items").size()).isEqualTo(original.get("items").size());
    assertThat(output.get("items").get(3)).isEqualTo(original.get("items").get(3));
    assertThat(output.get("items").get(4).get(MEMBERSHIP).get(0).isNull()).isTrue();
    assertThat(output.get("items").get(4).get(MEMBERSHIP).size()).isEqualTo(2);
    var withoutAdditions = output.deepCopy();
    for (int index = 0; index < original.get("items").size(); index++) {
      var node =
          (com.fasterxml.jackson.databind.node.ObjectNode) withoutAdditions.get("items").get(index);
      var previous = original.get("items").get(index).get(MEMBERSHIP);
      if (previous == null) {
        node.remove(MEMBERSHIP);
      } else {
        node.set(MEMBERSHIP, previous);
      }
    }
    assertThat(withoutAdditions).isEqualTo(original);
    String repeated = enrich(result);
    assertThat(repeated).isEqualTo(result);
    assertThat(model(repeated).isIsomorphicWith(graph)).isTrue();
    assertThat(new ObjectMapper().readTree(repeated))
        .isEqualTo(new ObjectMapper().readTree(result));
  }

  @Test
  void enrichment_emptyFilter_acceptsAllTypedDirectNodesExceptRoot() {
    enableOwl();
    Model graph = model(enrich(OWL_GRAPH));
    assertThat(
            graph.contains(
                graph.createResource("https://example.org/Individual"),
                graph.createProperty(MEMBERSHIP),
                graph.createResource(ONTOLOGY)))
        .isTrue();
    assertThat(graph.contains(graph.createResource(ONTOLOGY), graph.createProperty(MEMBERSHIP)))
        .isFalse();
    assertThat(
            graph.contains(
                graph.createResource("https://example.org/Nested"),
                graph.createProperty(MEMBERSHIP)))
        .isFalse();
  }

  @ParameterizedTest
  @CsvSource({"true,not JSON", "false,{}"})
  void enrichment_trailingContent_returnsStructuredError(boolean needsLinks, String trailing) {
    enableOwl();
    String payload =
        needsLinks
            ? OWL_GRAPH
            : """
              {"@graph":[{"@id":"https://example.org/ontology",
                "@type":"http://www.w3.org/2002/07/owl#Ontology"}]}
              """;
    assertThatThrownBy(() -> enrich(payload + trailing))
        .isInstanceOf(GraphValidationException.class)
        .satisfies(
            failure ->
                assertThat(((GraphValidationException) failure).getErrors().getFirst().field())
                    .isEqualTo("graph_definition"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"@set", "members", "localMembers"})
  void enrichment_setWrappers_preserveContentsAndEnrichOnlyDirectNodes(String setKey)
      throws Exception {
    enableOwl();
    String payload =
        """
        {
          "@context": {"owl":"http://www.w3.org/2002/07/owl#", "members":"@set"},
          "@graph": [
            {"@id":"https://example.org/ontology", "@type":"owl:Ontology"},
            {"@context":{"localMembers":"@set"}, "%s":[
              {"@id":"https://example.org/Wrapped", "@type":"owl:Class"}
            ]},
            {"@id":"https://example.org/Direct", "@type":"owl:Class"}
          ]
        }
        """
            .formatted(setKey);
    var mapper = new ObjectMapper();
    var original = mapper.readTree(payload);
    String result = enrich(payload);
    var output = mapper.readTree(result);
    assertThat(output.path("@graph").get(1)).isEqualTo(original.path("@graph").get(1));
    Model graph = model(result);
    assertThat(
            graph
                .listStatements(
                    null, graph.createProperty(MEMBERSHIP), graph.createResource(ONTOLOGY))
                .toList())
        .extracting(statement -> statement.getSubject().getURI())
        .containsExactly("https://example.org/Direct");
    ((com.fasterxml.jackson.databind.node.ArrayNode) original.get("@graph")).remove(2);
    String noChanges = mapper.writeValueAsString(original);
    assertThat(enrich(noChanges)).isEqualTo(noChanges);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not JSON",
        "[]",
        "{\"@id\":\"https://example.org/root\",\"@type\":\"http://www.w3.org/2002/07/owl#Ontology\"}",
        "{\"@graph\":[]}",
        "{\"@context\":{\"owl\":\"http://www.w3.org/2002/07/owl#\",\"http://www.w3.org/2000/01/rdf-schema#isDefinedBy\":null},\"@graph\":[{\"@id\":\"https://example.org/root\",\"@type\":\"owl:Ontology\"},{\"@id\":\"https://example.org/Class\",\"@type\":\"owl:Class\"}]}",
        "{\"@context\":{\"items\":\"@graph\"},\"items\":{\"@id\":\"https://example.org/root\",\"@type\":\"http://www.w3.org/2002/07/owl#Ontology\"}}",
        "{\"@id\":\"https://example.org/named\",\"@graph\":[]}",
        "{\"@graph\":[{\"@id\":\"https://example.org/a\",\"@type\":\"http://www.w3.org/2002/07/owl#Ontology\"},{\"@id\":\"https://example.org/b\",\"@type\":\"http://www.w3.org/2002/07/owl#Ontology\"}]}",
        "{\"@context\":\"https://example.invalid/context\",\"@graph\":[]}",
        "{\"@context\":{\"@import\":\"https://example.invalid/context\"},\"@graph\":[]}",
        "{\"@context\":{\"related\":{\"@id\":\"https://example.org/related\",\"@context\":\"https://example.invalid/scoped\"}},\"@graph\":[]}"
      })
  void enrichment_invalidPayload_returnsStructuredError(String payload) {
    enableOwl();
    DocumentLoader networkLoader = mock(DocumentLoader.class);
    try (var loaderFactory = mockStatic(HttpLoader.class)) {
      loaderFactory.when(HttpLoader::defaultInstance).thenReturn(networkLoader);
      assertThatThrownBy(() -> enrich(payload))
          .isInstanceOf(GraphValidationException.class)
          .satisfies(
              failure -> {
                var errors = ((GraphValidationException) failure).getErrors();
                assertThat(errors).hasSize(1);
                assertThat(errors.getFirst().field()).isEqualTo("graph_definition");
                assertThat(errors.getFirst().message())
                    .startsWith("yaml-ld-enrichment.add-root-verb ")
                    .contains("graph_definition");
                if (payload.startsWith("{\"@context\":\"https://example.invalid")) {
                  assertThat(errors.getFirst().message()).contains("Remote contexts and @import");
                } else if (!payload.contains("example.invalid")) {
                  assertThat(errors.getFirst().message()).doesNotContain("are disabled");
                }
              });
      verifyNoInteractions(networkLoader);
    }
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "type|",
        "type|missing:Ontology",
        "type|relative",
        "verb|''",
        "verb|https://bad host/predicate",
        "filter|",
        "filter|''",
        "filter|_:blank"
      },
      delimiter = '|')
  void enrichment_invalidConfiguration_returnsConfigurationField(String setting, String value) {
    enableOwl();
    String field;
    if (setting.equals("type")) {
      props.getDescriptor().getRoot().setType(value);
      field = "descriptor.root.type";
    } else if (setting.equals("verb")) {
      props.getDescriptor().getRoot().setVerb(value);
      field = "descriptor.root.verb";
    } else {
      props
          .getDescriptor()
          .getYamlLdEnrichment()
          .getAddRootVerb()
          .setTypes(java.util.Arrays.asList(value));
      field = "descriptor.yaml-ld-enrichment.add-root-verb.types";
    }
    assertThatThrownBy(() -> enrich(OWL_GRAPH))
        .isInstanceOf(GraphValidationException.class)
        .satisfies(
            failure -> {
              assertThat(((GraphValidationException) failure).getErrors().getFirst().field())
                  .isEqualTo(field);
              assertThat(((GraphValidationException) failure).getErrors().getFirst().message())
                  .startsWith("yaml-ld-enrichment.add-root-verb")
                  .contains(field);
              if (value == null || value.isBlank()) {
                String message =
                    ((GraphValidationException) failure).getErrors().getFirst().message();
                assertThat(message).contains("When root-verb enrichment is enabled");
                if (setting.equals("filter")) {
                  assertThat(message).doesNotContain("preset");
                } else {
                  assertThat(message)
                      .contains(
                          "With preset 'custom', configure it explicitly",
                          "preset 'skos' supplies a default");
                }
              }
            });
  }

  @Test
  void enrichment_missingPayload_preservesFallbackButRequiresConfiguredPath() {
    enableOwl();
    props.getDescriptor().getRoot().setType(null);
    assertThat(engine.extractYamlLd(descriptor(Map.of()))).isEmpty();
    props.getDescriptor().setYamlLdRequired(true);
    assertThatThrownBy(() -> engine.extractYamlLd(descriptor(Map.of())))
        .isInstanceOf(GraphValidationException.class);
    props.getDescriptor().setYamlLdPath(null);
    assertThatThrownBy(() -> engine.extractYamlLd(descriptor(Map.of())))
        .isInstanceOf(GraphValidationException.class)
        .satisfies(
            failure ->
                assertThat(((GraphValidationException) failure).getErrors().getFirst().field())
                    .isEqualTo("descriptor.yaml-ld-path"));
  }

  private void enableOwl() {
    props.getDescriptor().setYamlLdPath("graph_definition");
    props.getDescriptor().getYamlLdEnrichment().getAddRootVerb().setEnabled(true);
    props.getDescriptor().getRoot().setType("owl:Ontology");
    props.getDescriptor().getRoot().setVerb("rdfs:isDefinedBy");
    props.getPrefixes().getLiterals().put("owl", "http://www.w3.org/2002/07/owl#");
    props.getPrefixes().getLiterals().put("rdfs", "http://www.w3.org/2000/01/rdf-schema#");
  }

  private String enrich(String payload) {
    return engine.extractYamlLd(descriptor(Map.of("graph_definition", payload))).orElseThrow();
  }

  private Model model(String payload) {
    Model graph = ModelFactory.createDefaultModel();
    var dataset = org.apache.jena.query.DatasetFactory.create();
    try {
      RDFParser.fromString(payload).lang(Lang.JSONLD11).parse(dataset);
      graph.add(dataset.getDefaultModel());
      graph.add(dataset.getUnionModel());
      return graph;
    } finally {
      dataset.close();
    }
  }
}
