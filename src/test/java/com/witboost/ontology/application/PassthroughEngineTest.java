package com.witboost.ontology.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.witboost.ontology.config.GraphProperties;
import com.witboost.ontology.domain.descriptor.DataProductDescriptor;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PassthroughEngineTest {

  private PassthroughEngine engine;
  private GraphProperties props;

  @BeforeEach
  void setUp() {
    props = new GraphProperties();
    engine = new PassthroughEngine(props);
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
}
