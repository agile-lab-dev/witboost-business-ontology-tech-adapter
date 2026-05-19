package com.witboost.ontology.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.witboost.ontology.config.GraphProperties;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrefixResolverTest {

  private PrefixResolver resolver;

  @BeforeEach
  void setUp() {
    GraphProperties props = new GraphProperties();
    props.getPrefixes().getLiterals().put("skos", "http://www.w3.org/2004/02/skos/core#");
    props.getPrefixes().getLiterals().put("wb", "https://witboost.com/type#");
    resolver = new PrefixResolver(props);
  }

  @Test
  void knownPrefix_expandsToFullIri() {
    Map<String, String> map = Map.of("skos", "http://www.w3.org/2004/02/skos/core#");
    assertThat(resolver.expand("skos:Concept", map))
        .isEqualTo("http://www.w3.org/2004/02/skos/core#Concept");
  }

  @Test
  void fullIri_returnedAsIs() {
    Map<String, String> map = Map.of();
    assertThat(resolver.expand("http://example.org/Class", map))
        .isEqualTo("http://example.org/Class");
    assertThat(resolver.expand("https://example.org/Class", map))
        .isEqualTo("https://example.org/Class");
  }

  @Test
  void urnValue_returnedAsIs_notTreatedAsPrefix() {
    Map<String, String> map = Map.of("skos", "http://www.w3.org/2004/02/skos/core#");
    assertThat(resolver.expand("urn:dmb:dp:domain:registry:0", map))
        .isEqualTo("urn:dmb:dp:domain:registry:0");
  }

  @Test
  void unknownPrefix_throwsWithCorrectPrefixLabel() {
    Map<String, String> map = Map.of("skos", "http://www.w3.org/2004/02/skos/core#");
    assertThatThrownBy(() -> resolver.expand("owl:Class", map))
        .isInstanceOf(UnknownPrefixException.class)
        .satisfies(
            ex -> {
              UnknownPrefixException upe = (UnknownPrefixException) ex;
              assertThat(upe.getPrefix()).isEqualTo("owl");
            });
  }

  @Test
  void unknownPrefix_withFieldParam_includesFieldInException() {
    Map<String, String> map = Map.of("skos", "http://www.w3.org/2004/02/skos/core#");
    assertThatThrownBy(() -> resolver.expand("owl:Class", map, "concept-mapping.type"))
        .isInstanceOf(UnknownPrefixException.class)
        .satisfies(
            ex -> {
              UnknownPrefixException upe = (UnknownPrefixException) ex;
              assertThat(upe.getField()).isEqualTo("concept-mapping.type");
              assertThat(upe.getPrefix()).isEqualTo("owl");
            });
  }

  @Test
  void fieldDerivedPrefix_winsOverLiteralOnConflict() {
    GraphProperties props = new GraphProperties();
    props.getPrefixes().getLiterals().put("wb", "https://witboost.com/type#");
    props.getPrefixes().setField("context");
    PrefixResolver r = new PrefixResolver(props);

    Map<String, String> resolved =
        r.resolve(Map.of("context", Map.of("wb", "https://custom.example.com/type#")));
    assertThat(resolved.get("wb")).isEqualTo("https://custom.example.com/type#");
  }

  @Test
  void resolve_mergesStaticAndDescriptorPrefixes() {
    GraphProperties props = new GraphProperties();
    props.getPrefixes().getLiterals().put("skos", "http://www.w3.org/2004/02/skos/core#");
    props.getPrefixes().setField("context");
    PrefixResolver r = new PrefixResolver(props);

    Map<String, String> resolved =
        r.resolve(Map.of("context", Map.of("owl", "http://www.w3.org/2002/07/owl#")));
    assertThat(resolved)
        .containsEntry("skos", "http://www.w3.org/2004/02/skos/core#")
        .containsEntry("owl", "http://www.w3.org/2002/07/owl#");
  }

  @Test
  void resolve_noContextField_returnsLiteralsOnly() {
    Map<String, String> resolved = resolver.resolve(Map.of());
    assertThat(resolved)
        .containsEntry("skos", "http://www.w3.org/2004/02/skos/core#")
        .containsEntry("wb", "https://witboost.com/type#");
  }
}
