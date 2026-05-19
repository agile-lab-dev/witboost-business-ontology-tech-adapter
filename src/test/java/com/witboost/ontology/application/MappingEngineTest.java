package com.witboost.ontology.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.witboost.ontology.WitboostNamespaces;
import com.witboost.ontology.config.GraphProperties;
import com.witboost.ontology.config.SkosPresetDefinition;
import com.witboost.ontology.domain.descriptor.DataProductDescriptor;
import com.witboost.ontology.domain.graph.ConceptEntry;
import com.witboost.ontology.domain.validation.GraphValidationError;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MappingEngineTest {

  private static final String SKOS_NS = SkosPresetDefinition.SKOS_NS;

  private MappingEngine engine;
  private GraphProperties props;

  @BeforeEach
  void setUp() {
    props = new GraphProperties();
    props.getPrefixes().getLiterals().put("skos", SkosPresetDefinition.SKOS_NS);
    props.getPrefixes().getLiterals().put("wb", WitboostNamespaces.WB_NS);
    props.getDescriptor().setBusinessConceptsPath("business_concepts");
    props.getConceptMapping().setIriField("iri");
    props.getConceptMapping().setNameField("name");
    props.getConceptMapping().setTypeField("type");
    props.getConceptMapping().setType("skos:Concept");
    props.getConceptMapping().getProperties().put("skos:prefLabel", "name");
    props.getRelationsMapping().setVerb("skos:narrower");
    props.getRelationsMapping().setRelationsPath("relations");

    PrefixResolver prefixResolver = new PrefixResolver(props);
    IriDerivationService iriDerivationService = new IriDerivationService(props);
    engine = new MappingEngine(props, prefixResolver, iriDerivationService);
  }

  private DataProductDescriptor descriptor(Map<String, Object> extra) {
    DataProductDescriptor d = new DataProductDescriptor();
    d.setId("urn:dmb:dp:domain:registry-a:0");
    extra.forEach(d::setExtraField);
    return d;
  }

  @Test
  void parse_nameOnly_producesConceptWithDerivedIriAndExpandedType() {
    DataProductDescriptor desc =
        descriptor(Map.of("business_concepts", List.of(Map.of("name", "Concept A"))));

    List<ConceptEntry> concepts = engine.parse(desc).concepts();

    assertThat(concepts).hasSize(1);
    ConceptEntry c = concepts.get(0);
    assertThat(c.iri()).isEqualTo(WitboostNamespaces.WB_NS + "domain-registry-a-0-concept-a");
    assertThat(c.name()).isEqualTo("Concept A");
    assertThat(c.type()).isEqualTo(SkosPresetDefinition.SKOS_NS + "Concept");
    assertThat(c.properties())
        .containsEntry(SkosPresetDefinition.SKOS_NS + "prefLabel", "Concept A");
  }

  @Test
  void parse_explicitIriField_usedDirectly() {
    DataProductDescriptor desc =
        descriptor(
            Map.of(
                "business_concepts",
                List.of(Map.of("name", "Concept A", "iri", "wb:explicit-concept"))));

    List<ConceptEntry> concepts = engine.parse(desc).concepts();

    assertThat(concepts.get(0).iri()).isEqualTo(WitboostNamespaces.WB_NS + "explicit-concept");
  }

  @Test
  void parse_urnInIriField_fallsBackToNameDerivation() {
    DataProductDescriptor desc =
        descriptor(
            Map.of(
                "business_concepts",
                List.of(
                    Map.of(
                        "name", "Concept A",
                        "iri", "urn:dmb:dp:domain:registry-a:0:concept-a"))));

    List<ConceptEntry> concepts = engine.parse(desc).concepts();

    assertThat(concepts.get(0).iri())
        .isEqualTo(WitboostNamespaces.WB_NS + "domain-registry-a-0-concept-a");
  }

  @Test
  void parse_iriCollision_throwsGraphValidationExceptionWithIriCollisionError() {
    DataProductDescriptor desc =
        descriptor(
            Map.of(
                "business_concepts",
                List.of(Map.of("name", "Concept A"), Map.of("name", "Concept A"))));

    assertThatThrownBy(() -> engine.parse(desc))
        .isInstanceOf(GraphValidationException.class)
        .satisfies(
            ex -> {
              List<GraphValidationError> errors = ((GraphValidationException) ex).getErrors();
              assertThat(errors).hasSize(1);
              assertThat(errors.get(0).field()).isEqualTo("iri");
            });
  }

  @Test
  void parse_typeFieldOverride_winsOverConfigDefault() {
    props.getPrefixes().getLiterals().put("owl", "http://www.w3.org/2002/07/owl#");
    DataProductDescriptor desc =
        descriptor(
            Map.of("business_concepts", List.of(Map.of("name", "My Class", "type", "owl:Class"))));

    List<ConceptEntry> concepts = engine.parse(desc).concepts();

    assertThat(concepts.get(0).type()).isEqualTo("http://www.w3.org/2002/07/owl#Class");
  }

  @Test
  void parse_relationVerbFieldOverride_usedPerRelation() {
    props.getRelationsMapping().setVerbField("type");
    DataProductDescriptor desc =
        descriptor(
            Map.of(
                "business_concepts",
                List.of(
                    Map.of(
                        "name",
                        "Concept C",
                        "relations",
                        List.of(Map.of("name", "Concept B", "type", "skos:broader"))))));

    List<ConceptEntry> concepts = engine.parse(desc).concepts();

    assertThat(concepts.get(0).relations()).hasSize(1);
    assertThat(concepts.get(0).relations().get(0).verb())
        .isEqualTo(SkosPresetDefinition.SKOS_NS + "broader");
  }

  @Test
  void parse_unknownPrefixInType_throwsWithUnknownPrefixError() {
    DataProductDescriptor desc =
        descriptor(
            Map.of(
                "business_concepts",
                List.of(Map.of("name", "My Class", "type", "unknownPrefix:Class"))));

    assertThatThrownBy(() -> engine.parse(desc))
        .isInstanceOf(GraphValidationException.class)
        .satisfies(
            ex -> {
              List<GraphValidationError> errors = ((GraphValidationException) ex).getErrors();
              assertThat(errors).anyMatch(e -> "unknownPrefix".equals(e.prefix()));
            });
  }

  // ── Root verbField tests ──────────────────────────────────────────────────

  @Test
  void parse_rootIriField_notSet_fallsBackToConceptMappingIriField() {
    // root.iri-field not configured → should fall back to concept-mapping.iri-field ("iri")
    // and read descriptor root field "iri" = "skos:ConceptScheme"
    props.getPrefixes().getLiterals().put("owl", "http://www.w3.org/2002/07/owl#");
    props.getDescriptor().getRoot().setIsConcept(true);
    props.getDescriptor().getRoot().setType("skos:ConceptScheme");
    props.getDescriptor().getRoot().setVerb("skos:inScheme");
    // root.iriField left null → concept-mapping.iriField = "iri" is the fallback
    // descriptor has iri: "owl:Ontology" at root level
    DataProductDescriptor desc = new DataProductDescriptor();
    desc.setId("urn:dmb:dp:domain:registry-a:0");
    desc.setExtraField("iri", "owl:Ontology");
    desc.setExtraField("business_concepts", List.of(Map.of("name", "Concept A")));

    var result = engine.parse(desc);

    assertThat(result.root().iri()).isEqualTo("http://www.w3.org/2002/07/owl#Ontology");
  }

  @Test
  void parse_rootIriField_explicit_winsOverConceptMappingIriField() {
    // root.iri-field explicitly set to "root_iri" → takes priority over concept-mapping.iriField
    props.getPrefixes().getLiterals().put("owl", "http://www.w3.org/2002/07/owl#");
    props.getDescriptor().getRoot().setIsConcept(true);
    props.getDescriptor().getRoot().setType("skos:ConceptScheme");
    props.getDescriptor().getRoot().setVerb("skos:inScheme");
    props.getDescriptor().getRoot().setIriField("root_iri");
    // descriptor has both "iri" and "root_iri" — root_iri must win
    DataProductDescriptor desc = new DataProductDescriptor();
    desc.setId("urn:dmb:dp:domain:registry-a:0");
    desc.setExtraField("iri", "owl:Ontology"); // concept-mapping.iriField target (ignored)
    desc.setExtraField("root_iri", "skos:Concept"); // root.iriField target (wins)
    desc.setExtraField("business_concepts", List.of(Map.of("name", "Concept A")));

    var result = engine.parse(desc);

    assertThat(result.root().iri()).isEqualTo(SKOS_NS + "Concept");
  }

  @Test
  void parse_rootVerbField_readsFromDescriptorAndWinsOverStaticVerb() {
    props.getDescriptor().getRoot().setIsConcept(true);
    props.getDescriptor().getRoot().setType("skos:ConceptScheme");
    props.getDescriptor().getRoot().setVerb("skos:inScheme");
    props.getDescriptor().getRoot().setVerbField("scheme_verb");

    // Descriptor supplies skos:broader for the verb field — should win over skos:inScheme
    DataProductDescriptor desc =
        descriptor(
            Map.of(
                "scheme_verb",
                "skos:broader",
                "business_concepts",
                List.of(Map.of("name", "Concept A"))));

    var result = engine.parse(desc);

    assertThat(result.rootVerb()).isEqualTo(SkosPresetDefinition.SKOS_NS + "broader");
  }

  @Test
  void parse_rootVerbField_absentInDescriptor_fallsBackToStaticVerb() {
    props.getDescriptor().getRoot().setIsConcept(true);
    props.getDescriptor().getRoot().setType("skos:ConceptScheme");
    props.getDescriptor().getRoot().setVerb("skos:inScheme");
    props
        .getDescriptor()
        .getRoot()
        .setVerbField("scheme_verb"); // configured but absent in descriptor

    DataProductDescriptor desc =
        descriptor(Map.of("business_concepts", List.of(Map.of("name", "Concept A"))));

    var result = engine.parse(desc);

    assertThat(result.rootVerb()).isEqualTo(SkosPresetDefinition.SKOS_NS + "inScheme");
  }

  @Test
  void parse_rootVerbField_unknownPrefix_throwsValidationError() {
    props.getDescriptor().getRoot().setIsConcept(true);
    props.getDescriptor().getRoot().setType("skos:ConceptScheme");
    props.getDescriptor().getRoot().setVerbField("scheme_verb");

    DataProductDescriptor desc =
        descriptor(
            Map.of(
                "scheme_verb",
                "unknown:verb",
                "business_concepts",
                List.of(Map.of("name", "Concept A"))));

    assertThatThrownBy(() -> engine.parse(desc))
        .isInstanceOf(GraphValidationException.class)
        .satisfies(
            ex -> {
              List<GraphValidationError> errors = ((GraphValidationException) ex).getErrors();
              assertThat(errors).anyMatch(e -> "unknown".equals(e.prefix()));
            });
  }

  @Test
  void parse_rootStaticVerb_unknownPrefix_throwsValidationError() {
    props.getDescriptor().getRoot().setIsConcept(true);
    props.getDescriptor().getRoot().setType("skos:ConceptScheme");
    props.getDescriptor().getRoot().setVerb("badPrefix:inScheme"); // unknown prefix

    DataProductDescriptor desc =
        descriptor(Map.of("business_concepts", List.of(Map.of("name", "Concept A"))));

    assertThatThrownBy(() -> engine.parse(desc))
        .isInstanceOf(GraphValidationException.class)
        .satisfies(
            ex -> {
              List<GraphValidationError> errors = ((GraphValidationException) ex).getErrors();
              assertThat(errors).anyMatch(e -> "badPrefix".equals(e.prefix()));
            });
  }

  // ── reverseVerbMap resolution tests (FR-020) ──────────────────────────────

  @Test
  void parse_validReverseVerbMap_expandedInParseResult() {
    props.getRelationsMapping().getReverseVerbMap().put("skos:narrower", "skos:broader");
    props.getRelationsMapping().getReverseVerbMap().put("skos:broader", "skos:narrower");

    DataProductDescriptor desc =
        descriptor(Map.of("business_concepts", List.of(Map.of("name", "Concept A"))));

    var result = engine.parse(desc);

    assertThat(result.reverseVerbMap())
        .containsEntry(SKOS_NS + "narrower", SKOS_NS + "broader")
        .containsEntry(SKOS_NS + "broader", SKOS_NS + "narrower");
  }

  @Test
  void parse_emptyReverseVerbMap_noErrors_emptyMapInResult() {
    // reverseVerbMap left empty (default)
    DataProductDescriptor desc =
        descriptor(Map.of("business_concepts", List.of(Map.of("name", "Concept A"))));

    var result = engine.parse(desc);

    assertThat(result.reverseVerbMap()).isEmpty();
  }

  @Test
  void parse_reverseVerbMapUnknownPrefixInKey_throwsValidationError() {
    props.getRelationsMapping().getReverseVerbMap().put("unknownPfx:narrower", "skos:broader");

    DataProductDescriptor desc =
        descriptor(Map.of("business_concepts", List.of(Map.of("name", "Concept A"))));

    assertThatThrownBy(() -> engine.parse(desc))
        .isInstanceOf(GraphValidationException.class)
        .satisfies(
            ex -> {
              List<GraphValidationError> errors = ((GraphValidationException) ex).getErrors();
              assertThat(errors).anyMatch(e -> "unknownPfx".equals(e.prefix()));
            });
  }

  @Test
  void parse_reverseVerbMapUnknownPrefixInValue_throwsValidationError() {
    props.getRelationsMapping().getReverseVerbMap().put("skos:narrower", "badPfx:broader");

    DataProductDescriptor desc =
        descriptor(Map.of("business_concepts", List.of(Map.of("name", "Concept A"))));

    assertThatThrownBy(() -> engine.parse(desc))
        .isInstanceOf(GraphValidationException.class)
        .satisfies(
            ex -> {
              List<GraphValidationError> errors = ((GraphValidationException) ex).getErrors();
              assertThat(errors).anyMatch(e -> "badPfx".equals(e.prefix()));
            });
  }
}
