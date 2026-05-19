package com.witboost.ontology.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.witboost.ontology.WitboostNamespaces;
import com.witboost.ontology.config.GraphPreset;
import com.witboost.ontology.config.GraphProperties;
import com.witboost.ontology.config.SkosPresetDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PresetMergeServiceTest {

  private PresetMergeService service(GraphProperties props) {
    PresetMergeService svc = new PresetMergeService(props);
    svc.init();
    return svc;
  }

  // ── (a) SKOS preset with empty user config → all SKOS defaults applied ────

  @Test
  void skosPreset_emptyUserConfig_appliesAllSkosDefaults() {
    GraphProperties props = new GraphProperties();
    props.setPreset(GraphPreset.SKOS);
    service(props);

    assertThat(props.getConceptMapping().getType()).isEqualTo("skos:Concept");
    assertThat(props.getConceptMapping().getIriField()).isEqualTo("iri");
    assertThat(props.getConceptMapping().getNameField()).isEqualTo("name");
    assertThat(props.getConceptMapping().getTypeField()).isEqualTo("type");
    assertThat(props.getConceptMapping().getProperties())
        .containsEntry("skos:prefLabel", "name")
        .containsEntry("skos:definition", "description");
    assertThat(props.getRelationsMapping().getVerb()).isEqualTo("skos:narrower");
    assertThat(props.getRelationsMapping().getVerbField()).isEqualTo("type");
    assertThat(props.getRelationsMapping().getRelationsPath()).isEqualTo("relations");
    assertThat(props.getDescriptor().getRoot().getIsConcept()).isTrue();
    assertThat(props.getDescriptor().getRoot().getType()).isEqualTo("skos:ConceptScheme");
    assertThat(props.getDescriptor().getRoot().getVerb()).isEqualTo("skos:inScheme");
    assertThat(props.getDescriptor().getRoot().getProperties())
        .containsEntry("skos:prefLabel", "name")
        .containsEntry("skos:definition", "description");
    assertThat(props.getPrefixes().getLiterals())
        .containsEntry("skos", SkosPresetDefinition.SKOS_NS)
        .containsEntry("wb", WitboostNamespaces.WB_NS);
    assertThat(props.getDescriptor().getBusinessConceptsPath()).isEqualTo("business_concepts");
    assertThat(props.getConceptMapping().getTypeField()).isEqualTo("type");
  }

  // ── (b) SKOS preset with additionalProperties → merged onto preset ─────────

  @Test
  void skosPreset_additionalProperties_mergedOntoPresetProperties() {
    GraphProperties props = new GraphProperties();
    props.setPreset(GraphPreset.SKOS);
    props.getConceptMapping().getAdditionalProperties().put("wb:category", "category");
    service(props);

    assertThat(props.getConceptMapping().getProperties())
        .containsEntry("skos:prefLabel", "name") // preset default preserved
        .containsEntry("skos:definition", "description") // preset default preserved
        .containsEntry("wb:category", "category"); // user addition merged in
  }

  // ── (c) SKOS preset with type override → user value wins ──────────────────

  @Test
  void skosPreset_userOverrideOfConceptType_winsOverPreset() {
    GraphProperties props = new GraphProperties();
    props.setPreset(GraphPreset.SKOS);
    props.getConceptMapping().setType("owl:Class");
    service(props);

    assertThat(props.getConceptMapping().getType()).isEqualTo("owl:Class");
  }

  // ── user prefix added → merged, user wins on conflict ────────────────────

  @Test
  void skosPreset_userPrefixAdded_mergedWithSkosDefaults() {
    GraphProperties props = new GraphProperties();
    props.setPreset(GraphPreset.SKOS);
    props.getPrefixes().getLiterals().put("owl", "http://www.w3.org/2002/07/owl#");
    service(props);

    assertThat(props.getPrefixes().getLiterals())
        .containsEntry("skos", SkosPresetDefinition.SKOS_NS)
        .containsEntry("wb", WitboostNamespaces.WB_NS)
        .containsEntry("owl", "http://www.w3.org/2002/07/owl#");
  }

  @Test
  void skosPreset_userPrefixWinsOnConflict() {
    GraphProperties props = new GraphProperties();
    props.setPreset(GraphPreset.SKOS);
    props.getPrefixes().getLiterals().put("wb", "https://custom.example.com/type#");
    service(props);

    assertThat(props.getPrefixes().getLiterals().get("wb"))
        .isEqualTo("https://custom.example.com/type#");
  }

  // ── (d) CUSTOM preset → no defaults applied ───────────────────────────────

  @Test
  void customPreset_noDefaultsApplied_fieldsRemainAsConfigured() {
    GraphProperties props = new GraphProperties();
    props.setPreset(GraphPreset.CUSTOM);
    props.getConceptMapping().setType("owl:Class");
    service(props);

    // SKOS prefix NOT injected
    assertThat(props.getPrefixes().getLiterals()).doesNotContainKey("skos");
    // Type stays as user set it
    assertThat(props.getConceptMapping().getType()).isEqualTo("owl:Class");
    // Root not touched
    assertThat(props.getDescriptor().getRoot().getIsConcept()).isNull();
    // Relations verb and verbField stay null
    assertThat(props.getRelationsMapping().getVerb()).isNull();
    assertThat(props.getRelationsMapping().getVerbField()).isNull();
  }

  @Test
  void customPreset_additionalPropertiesStillMergedOntoProperties() {
    GraphProperties props = new GraphProperties();
    props.setPreset(GraphPreset.CUSTOM);
    props.getConceptMapping().getProperties().put("rdfs:label", "name");
    props.getConceptMapping().getAdditionalProperties().put("rdfs:label", "title"); // override
    props.getConceptMapping().getAdditionalProperties().put("owl:sameAs", "sameAs");
    service(props);

    assertThat(props.getConceptMapping().getProperties())
        .containsEntry("rdfs:label", "title") // additionalProperties wins
        .containsEntry("owl:sameAs", "sameAs");
  }

  // ── reverseVerbMap merge tests (FR-020) ───────────────────────────────────

  @Test
  void skosPreset_emptyUserConfig_reverseVerbMapHasThreeSkosEntries() {
    GraphProperties props = new GraphProperties();
    props.setPreset(GraphPreset.SKOS);
    service(props);

    assertThat(props.getRelationsMapping().getReverseVerbMap())
        .containsEntry("skos:narrower", "skos:broader")
        .containsEntry("skos:broader", "skos:narrower")
        .containsEntry("skos:related", "skos:related")
        .hasSize(3);
  }

  @Test
  void skosPreset_userOverrideOfReverseEntry_winsOverPresetDefault() {
    GraphProperties props = new GraphProperties();
    props.setPreset(GraphPreset.SKOS);
    // user overrides the skos:related symmetric entry to a custom predicate
    props.getRelationsMapping().getReverseVerbMap().put("skos:related", "ex:customInverse");
    service(props);

    assertThat(props.getRelationsMapping().getReverseVerbMap())
        .containsEntry("skos:narrower", "skos:broader")
        .containsEntry("skos:broader", "skos:narrower")
        .containsEntry("skos:related", "ex:customInverse"); // user wins
  }

  @Test
  void skosPreset_userAddsExtraReverseEntry_mergedOntoSkosDefaults() {
    GraphProperties props = new GraphProperties();
    props.setPreset(GraphPreset.SKOS);
    props.getRelationsMapping().getReverseVerbMap().put("owl:sameAs", "owl:sameAs");
    service(props);

    assertThat(props.getRelationsMapping().getReverseVerbMap())
        .containsEntry("skos:narrower", "skos:broader")
        .containsEntry("owl:sameAs", "owl:sameAs")
        .hasSize(4);
  }

  @Test
  void customPreset_reverseVerbMapEmpty_byDefault() {
    GraphProperties props = new GraphProperties();
    props.setPreset(GraphPreset.CUSTOM);
    service(props);

    assertThat(props.getRelationsMapping().getReverseVerbMap()).isEmpty();
  }
}
