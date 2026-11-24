package com.witboost.ontology.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.witboost.ontology.WitboostNamespaces;
import com.witboost.ontology.application.infrastructure.GraphRepository;
import com.witboost.ontology.config.GraphPreset;
import com.witboost.ontology.config.GraphProperties;
import com.witboost.ontology.config.SkosPresetDefinition;
import com.witboost.ontology.domain.descriptor.DataProductDescriptor;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Integration-style tests for the SKOS preset (US2 — backward compatibility).
 *
 * <p>Provisions a canonical SKOS descriptor through the full Config A pipeline with {@code preset:
 * skos} and verifies that the resulting JSON-LD graph contains the expected SKOS triples with no
 * adapter-injected non-SKOS/wb/rdf vocabularies.
 */
@ExtendWith(MockitoExtension.class)
class SkosPresetIntegrationTest {

  private static final String SKOS_NS = SkosPresetDefinition.SKOS_NS;
  private static final String WB_NS = WitboostNamespaces.WB_NS;
  private static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

  @Mock GraphRepository graphRepository;
  @Mock GraphCacheService graphCacheService;

  private GraphService graphService;
  private GraphProperties props;

  @BeforeEach
  void setUp() {
    props = new GraphProperties();
    props.setPreset(GraphPreset.SKOS);

    // Apply SKOS preset defaults exactly as Spring would at startup
    new PresetMergeService(props).init();

    PrefixResolver prefixResolver = new PrefixResolver(props);
    IriDerivationService iriDerivationService = new IriDerivationService(props);
    MappingEngine mappingEngine = new MappingEngine(props, prefixResolver, iriDerivationService);
    PassthroughEngine passthroughEngine = new PassthroughEngine(props, prefixResolver);

    graphService =
        new GraphService(
            props, mappingEngine, passthroughEngine, graphCacheService, graphRepository);
  }

  private DataProductDescriptor canonicalSkosDescriptor() {
    DataProductDescriptor descriptor = new DataProductDescriptor();
    descriptor.setId("urn:dmb:dp:domain:registry-a:0");
    descriptor.setExtraField("name", "Registry A");
    descriptor.setExtraField("description", "Test description");
    descriptor.setExtraField(
        "business_concepts",
        List.of(
            Map.of(
                "name", "Concept A",
                "description", "First concept",
                "relations", List.of(Map.of("name", "Concept B"))),
            Map.of(
                "name", "Concept B",
                "description", "Second concept")));
    return descriptor;
  }

  /**
   * Provisions a descriptor, captures the JSON-LD written to the repo, and parses it into a Jena
   * model for triple-level assertions.
   */
  private Model provisionAndCaptureModel(DataProductDescriptor descriptor) {
    AtomicReference<String> capturedJsonLd = new AtomicReference<>();
    doAnswer(
            invocation -> {
              capturedJsonLd.set(invocation.getArgument(1));
              return null;
            })
        .when(graphRepository)
        .upsert(anyString(), anyString());

    // passthroughEngine finds no yaml-ld-path configured → falls through to Config A
    graphService.provision(descriptor);

    String jsonLd = capturedJsonLd.get();
    assertThat(jsonLd).isNotNull();

    Model model = ModelFactory.createDefaultModel();
    try (ByteArrayInputStream inputStream =
        new ByteArrayInputStream(jsonLd.getBytes(StandardCharsets.UTF_8))) {
      RDFDataMgr.read(model, inputStream, Lang.JSONLD);
    } catch (Exception exception) {
      throw new RuntimeException(
          "Could not parse captured JSON-LD: " + exception.getMessage(), exception);
    }
    return model;
  }

  // ── T031: Canonical SKOS descriptor produces correct graph ───────────────

  @Test
  void provision_skosPreset_rootTypedAsConceptScheme() {
    Model model = provisionAndCaptureModel(canonicalSkosDescriptor());

    Resource root = model.getResource(WB_NS + "domain-registry-a-0");
    Resource conceptSchemeType = model.getResource(SKOS_NS + "ConceptScheme");
    assertThat(model.contains(root, RDF.type, conceptSchemeType))
        .as("Root should be typed as skos:ConceptScheme")
        .isTrue();
  }

  @Test
  void provision_skosPreset_rootHasPrefLabelAndDefinition() {
    Model model = provisionAndCaptureModel(canonicalSkosDescriptor());

    Resource root = model.getResource(WB_NS + "domain-registry-a-0");
    Property prefLabel = model.createProperty(SKOS_NS + "prefLabel");
    Property definition = model.createProperty(SKOS_NS + "definition");

    assertThat(model.contains(root, prefLabel, "Registry A"))
        .as("Root should have skos:prefLabel 'Registry A' from descriptor name field")
        .isTrue();
    assertThat(model.contains(root, definition, "Test description"))
        .as("Root should have skos:definition 'Test description' from descriptor description field")
        .isTrue();
  }

  @Test
  void provision_skosPreset_conceptsTypedAsSkosConceptAndLinkedViaInScheme() {
    Model model = provisionAndCaptureModel(canonicalSkosDescriptor());

    String rootIri = WB_NS + "domain-registry-a-0";
    String conceptAIri = WB_NS + "domain-registry-a-0-concept-a";
    String conceptBIri = WB_NS + "domain-registry-a-0-concept-b";
    Resource root = model.getResource(rootIri);
    Resource conceptA = model.getResource(conceptAIri);
    Resource conceptB = model.getResource(conceptBIri);
    Resource skosConcept = model.getResource(SKOS_NS + "Concept");
    Property inScheme = model.createProperty(SKOS_NS + "inScheme");

    assertThat(model.contains(conceptA, RDF.type, skosConcept))
        .as("Concept A should be typed as skos:Concept")
        .isTrue();
    assertThat(model.contains(conceptB, RDF.type, skosConcept))
        .as("Concept B should be typed as skos:Concept")
        .isTrue();

    assertThat(model.contains(conceptA, inScheme, root))
        .as("Concept A should link to root via skos:inScheme")
        .isTrue();
    assertThat(model.contains(conceptB, inScheme, root))
        .as("Concept B should link to root via skos:inScheme")
        .isTrue();
  }

  @Test
  void provision_skosPreset_prefLabelAndDefinitionAssertedAsLiterals() {
    Model model = provisionAndCaptureModel(canonicalSkosDescriptor());

    String conceptAIri = WB_NS + "domain-registry-a-0-concept-a";
    Resource conceptA = model.getResource(conceptAIri);
    Property prefLabel = model.createProperty(SKOS_NS + "prefLabel");
    Property definition = model.createProperty(SKOS_NS + "definition");

    assertThat(model.contains(conceptA, prefLabel, "Concept A"))
        .as("Concept A should have skos:prefLabel 'Concept A'")
        .isTrue();
    assertThat(model.contains(conceptA, definition, "First concept"))
        .as("Concept A should have skos:definition 'First concept'")
        .isTrue();
  }

  @Test
  void provision_skosPreset_narrowerRelationBetweenConcepts() {
    Model model = provisionAndCaptureModel(canonicalSkosDescriptor());

    String conceptAIri = WB_NS + "domain-registry-a-0-concept-a";
    String conceptBIri = WB_NS + "domain-registry-a-0-concept-b";
    Resource conceptA = model.getResource(conceptAIri);
    Resource conceptB = model.getResource(conceptBIri);
    Property narrower = model.createProperty(SKOS_NS + "narrower");

    assertThat(model.contains(conceptA, narrower, conceptB))
        .as("Concept A should have skos:narrower Concept B")
        .isTrue();
  }

  @Test
  void provision_skosPreset_noNonSkosWbRdfPredicates() {
    Model model = provisionAndCaptureModel(canonicalSkosDescriptor());

    model
        .listStatements()
        .toList()
        .forEach(
            statement -> {
              String predicateNs = statement.getPredicate().getNameSpace();
              assertThat(predicateNs)
                  .as(
                      "Predicate <%s> must be in SKOS, WB, or RDF namespace",
                      statement.getPredicate().getURI())
                  .satisfiesAnyOf(
                      ns -> assertThat(ns).startsWith(SKOS_NS),
                      ns -> assertThat(ns).startsWith(WB_NS),
                      ns -> assertThat(ns).startsWith(RDF_NS));
            });
  }

  // ── T032: Regression — additionalProperties and type override ────────────

  @Test
  void provision_skosPreset_additionalProperties_mergedOntoOutput() {
    props.getConceptMapping().getAdditionalProperties().put("wb:category", "domain");
    // Re-run init so the late additionalProperties entry gets merged into properties
    // (mirrors what Spring does at startup: bind config → @PostConstruct init())
    new PresetMergeService(props).init();

    DataProductDescriptor descriptor = new DataProductDescriptor();
    descriptor.setId("urn:dmb:dp:domain:registry-a:0");
    descriptor.setExtraField(
        "business_concepts", List.of(Map.of("name", "Concept A", "domain", "Finance")));

    Model model = provisionAndCaptureModel(descriptor);

    Resource conceptA = model.getResource(WB_NS + "domain-registry-a-0-concept-a");
    Property categoryProp = model.createProperty(WB_NS + "category");

    assertThat(model.contains(conceptA, categoryProp, "Finance"))
        .as("Additional property wb:category mapped from 'domain' field should be present")
        .isTrue();
  }

  @Test
  void provision_skosPreset_userConceptTypeOverride_winsOverSkosDefault() {
    // Override concept type to owl:Class
    props.getPrefixes().getLiterals().put("owl", "http://www.w3.org/2002/07/owl#");
    props.getConceptMapping().setType("owl:Class");

    DataProductDescriptor descriptor = new DataProductDescriptor();
    descriptor.setId("urn:dmb:dp:domain:registry-a:0");
    descriptor.setExtraField("business_concepts", List.of(Map.of("name", "My Class")));

    Model model = provisionAndCaptureModel(descriptor);

    Resource conceptA = model.getResource(WB_NS + "domain-registry-a-0-my-class");
    Resource owlClass = model.getResource("http://www.w3.org/2002/07/owl#Class");
    Resource skosConcept = model.getResource(SKOS_NS + "Concept");

    assertThat(model.contains(conceptA, RDF.type, owlClass))
        .as("Concept should be typed as owl:Class per user override")
        .isTrue();
    assertThat(model.contains(conceptA, RDF.type, skosConcept))
        .as("Concept should NOT be typed as skos:Concept when overridden")
        .isFalse();
  }

  // ── Reverse-verb (FR-020) tests ───────────────────────────────────────────

  @Test
  void provision_skosPreset_narrowerRelationEmitsBroaderInverse() {
    // Concept A skos:narrower Concept B → also expect Concept B skos:broader Concept A
    Model model = provisionAndCaptureModel(canonicalSkosDescriptor());

    String conceptAIri = WB_NS + "domain-registry-a-0-concept-a";
    String conceptBIri = WB_NS + "domain-registry-a-0-concept-b";
    Resource conceptA = model.getResource(conceptAIri);
    Resource conceptB = model.getResource(conceptBIri);
    Property narrower = model.createProperty(SKOS_NS + "narrower");
    Property broader = model.createProperty(SKOS_NS + "broader");

    assertThat(model.contains(conceptA, narrower, conceptB))
        .as("Forward triple A skos:narrower B must be present")
        .isTrue();
    assertThat(model.contains(conceptB, broader, conceptA))
        .as("Inverse triple B skos:broader A must be emitted by reverse-verb-map (FR-020)")
        .isTrue();
  }

  @Test
  void provision_skosPreset_relatedRelationEmitsSymmetricInverse() {
    DataProductDescriptor descriptor = new DataProductDescriptor();
    descriptor.setId("urn:dmb:dp:domain:registry-a:0");
    descriptor.setExtraField(
        "business_concepts",
        List.of(
            Map.of(
                "name",
                "Concept A",
                "relations",
                List.of(Map.of("name", "Concept B", "type", "skos:related"))),
            Map.of("name", "Concept B")));

    Model model = provisionAndCaptureModel(descriptor);

    String conceptAIri = WB_NS + "domain-registry-a-0-concept-a";
    String conceptBIri = WB_NS + "domain-registry-a-0-concept-b";
    Resource conceptA = model.getResource(conceptAIri);
    Resource conceptB = model.getResource(conceptBIri);
    Property related = model.createProperty(SKOS_NS + "related");

    assertThat(model.contains(conceptA, related, conceptB))
        .as("Forward triple A skos:related B must be present")
        .isTrue();
    assertThat(model.contains(conceptB, related, conceptA))
        .as("Symmetric inverse triple B skos:related A must be emitted (FR-020)")
        .isTrue();
  }

  @Test
  void provision_skosPreset_broaderRelationEmitsNarrowerInverse() {
    DataProductDescriptor descriptor = new DataProductDescriptor();
    descriptor.setId("urn:dmb:dp:domain:registry-a:0");
    descriptor.setExtraField(
        "business_concepts",
        List.of(
            Map.of(
                "name",
                "Concept A",
                "relations",
                List.of(Map.of("name", "Concept B", "type", "skos:broader"))),
            Map.of("name", "Concept B")));

    Model model = provisionAndCaptureModel(descriptor);

    String conceptAIri = WB_NS + "domain-registry-a-0-concept-a";
    String conceptBIri = WB_NS + "domain-registry-a-0-concept-b";
    Resource conceptA = model.getResource(conceptAIri);
    Resource conceptB = model.getResource(conceptBIri);
    Property broader = model.createProperty(SKOS_NS + "broader");
    Property narrower = model.createProperty(SKOS_NS + "narrower");

    assertThat(model.contains(conceptA, broader, conceptB))
        .as("Forward triple A skos:broader B must be present")
        .isTrue();
    assertThat(model.contains(conceptB, narrower, conceptA))
        .as("Inverse triple B skos:narrower A must be emitted (FR-020)")
        .isTrue();
  }

  @Test
  void provision_customPreset_emptyReverseVerbMap_noInverseTriples() {
    // Custom preset with no reverseVerbMap → only forward triple emitted
    props = new GraphProperties();
    props.setPreset(com.witboost.ontology.config.GraphPreset.CUSTOM);
    props.getPrefixes().getLiterals().put("skos", SKOS_NS);
    props.getPrefixes().getLiterals().put("wb", WB_NS);
    props.getDescriptor().setBusinessConceptsPath("business_concepts");
    props.getDescriptor().getRoot().setIsConcept(false);
    props.getConceptMapping().setType("skos:Concept");
    props.getConceptMapping().setIriField("iri");
    props.getConceptMapping().setNameField("name");
    props.getConceptMapping().setTypeField("type");
    props.getRelationsMapping().setVerb("skos:narrower");
    props.getRelationsMapping().setRelationsPath("relations");
    // reverseVerbMap left empty — no inverse triples expected

    new PresetMergeService(props).init();
    PrefixResolver prefixResolver = new PrefixResolver(props);
    IriDerivationService iriDerivationService = new IriDerivationService(props);
    MappingEngine mappingEngine = new MappingEngine(props, prefixResolver, iriDerivationService);
    PassthroughEngine passthroughEngine = new PassthroughEngine(props, prefixResolver);
    graphService =
        new GraphService(
            props, mappingEngine, passthroughEngine, graphCacheService, graphRepository);

    DataProductDescriptor descriptor = new DataProductDescriptor();
    descriptor.setId("urn:dmb:dp:domain:registry-a:0");
    descriptor.setExtraField(
        "business_concepts",
        List.of(
            Map.of("name", "Concept A", "relations", List.of(Map.of("name", "Concept B"))),
            Map.of("name", "Concept B")));

    Model model = provisionAndCaptureModel(descriptor);

    String conceptAIri = WB_NS + "domain-registry-a-0-concept-a";
    String conceptBIri = WB_NS + "domain-registry-a-0-concept-b";
    Resource conceptA = model.getResource(conceptAIri);
    Resource conceptB = model.getResource(conceptBIri);
    Property narrower = model.createProperty(SKOS_NS + "narrower");
    Property broader = model.createProperty(SKOS_NS + "broader");

    assertThat(model.contains(conceptA, narrower, conceptB))
        .as("Forward triple A skos:narrower B should be present")
        .isTrue();
    assertThat(model.contains(conceptB, broader, conceptA))
        .as("Inverse triple must NOT be emitted when reverseVerbMap is empty")
        .isFalse();
  }
}
