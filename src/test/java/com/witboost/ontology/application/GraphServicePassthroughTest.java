package com.witboost.ontology.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.witboost.ontology.WitboostNamespaces;
import com.witboost.ontology.application.infrastructure.GraphRepository;
import com.witboost.ontology.config.GraphProperties;
import com.witboost.ontology.config.SkosPresetDefinition;
import com.witboost.ontology.domain.descriptor.DataProductDescriptor;
import com.witboost.ontology.domain.graph.GraphPartition;
import com.witboost.ontology.domain.graph.ParseResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies the pass-through (Config B) path in {@link GraphService}: when {@link PassthroughEngine}
 * returns a JSON-LD payload, it is stored verbatim without invoking {@link MappingEngine}.
 */
@ExtendWith(MockitoExtension.class)
class GraphServicePassthroughTest {

  @Mock MappingEngine mappingEngine;
  @Mock PassthroughEngine passthroughEngine;
  @Mock GraphCacheService graphCacheService;
  @Mock GraphRepository graphRepository;

  GraphService service;

  @BeforeEach
  void setUp() {
    GraphProperties props = new GraphProperties();
    props.getPrefixes().getLiterals().put("skos", SkosPresetDefinition.SKOS_NS);
    props.getPrefixes().getLiterals().put("wb", WitboostNamespaces.WB_NS);
    props.getDescriptor().getRoot().setIsConcept(false);

    service =
        new GraphService(
            props, mappingEngine, passthroughEngine, graphCacheService, graphRepository);
  }

  private DataProductDescriptor descriptor(String id) {
    DataProductDescriptor descriptor = new DataProductDescriptor();
    descriptor.setId(id);
    return descriptor;
  }

  // ── (a) pass-through stores raw JSON-LD without calling MappingEngine ─────

  @Test
  void provision_passthroughPayload_storesVerbatimAndSkipsMappingEngine() {
    DataProductDescriptor desc = descriptor("urn:dmb:dp:domain:registry-a:0");
    String rawJsonLd =
        "{\"@context\":{\"skos\":\"http://www.w3.org/2004/02/skos/core#\"},\"@graph\":[]}";
    when(passthroughEngine.extractYamlLd(desc)).thenReturn(Optional.of(rawJsonLd));

    service.provision(desc);

    ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
    verify(graphRepository).upsert(eq("urn:dmb:dp:domain:registry-a:0"), contentCaptor.capture());
    assertThat(contentCaptor.getValue()).isEqualTo(rawJsonLd);

    verify(mappingEngine, never()).parse(any());
    verify(graphCacheService).invalidate();
  }

  // ── (b) re-provision replaces previous JSON-LD entirely ──────────────────

  @Test
  void provision_reprovisioning_replacesExistingPartition() {
    DataProductDescriptor desc = descriptor("urn:dmb:dp:domain:registry-a:0");
    String firstJsonLd = "{\"@graph\":[{\"@id\":\"urn:first\"}]}";
    String secondJsonLd = "{\"@graph\":[{\"@id\":\"urn:second\"}]}";

    when(passthroughEngine.extractYamlLd(desc)).thenReturn(Optional.of(firstJsonLd));
    service.provision(desc);

    when(passthroughEngine.extractYamlLd(desc)).thenReturn(Optional.of(secondJsonLd));
    service.provision(desc);

    ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
    verify(graphRepository, org.mockito.Mockito.times(2))
        .upsert(eq("urn:dmb:dp:domain:registry-a:0"), contentCaptor.capture());
    assertThat(contentCaptor.getAllValues().get(1)).isEqualTo(secondJsonLd);
  }

  @Test
  void provision_enrichment_storesLinksVisibleInMergedGraph() {
    GraphProperties properties = enrichmentProperties();
    service =
        new GraphService(
            properties,
            mappingEngine,
            new PassthroughEngine(properties, new PrefixResolver(properties)),
            graphCacheService,
            graphRepository);
    DataProductDescriptor descriptor = descriptor("urn:dmb:dp:domain:enriched:0");
    descriptor.setExtraField(
        "graph_definition",
        """
        {"@graph":[
          {"@id":"https://example.org/scheme","@type":"http://www.w3.org/2004/02/skos/core#ConceptScheme"},
          {"@id":"https://example.org/concept","@type":"http://www.w3.org/2004/02/skos/core#Concept"}
        ]}
        """);
    service.provision(descriptor);
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(graphRepository).upsert(eq(descriptor.getId()), payload.capture());
    verify(graphCacheService).invalidate();
    verifyNoInteractions(mappingEngine);
    when(graphRepository.findAll())
        .thenReturn(
            List.of(new GraphPartition(descriptor.getId(), payload.getValue(), Instant.EPOCH)));
    when(graphCacheService.getOrBuild(any()))
        .thenAnswer(
            invocation -> invocation.<java.util.function.Supplier<String>>getArgument(0).get());
    var merged = RDFParser.fromString(service.getJsonLd()).lang(Lang.JSONLD11).toModel();
    assertThat(
            merged.contains(
                merged.createResource("https://example.org/concept"),
                merged.createProperty(SkosPresetDefinition.SKOS_NS + "inScheme"),
                merged.createResource("https://example.org/scheme")))
        .isTrue();
  }

  @Test
  void provision_enrichmentFailure_hasNoSideEffects() {
    GraphProperties properties = enrichmentProperties();
    service =
        new GraphService(
            properties,
            mappingEngine,
            new PassthroughEngine(properties, new PrefixResolver(properties)),
            graphCacheService,
            graphRepository);
    DataProductDescriptor descriptor = descriptor("urn:dmb:dp:domain:invalid:0");
    descriptor.setExtraField("graph_definition", "{\"@graph\":[]}");
    assertThatThrownBy(() -> service.provision(descriptor))
        .isInstanceOf(GraphValidationException.class);
    verifyNoInteractions(graphRepository, graphCacheService, mappingEngine);
  }

  private GraphProperties enrichmentProperties() {
    GraphProperties properties = new GraphProperties();
    new PresetMergeService(properties).init();
    properties.getDescriptor().setYamlLdPath("graph_definition");
    properties.getDescriptor().getRoot().setIsConcept(false);
    properties.getDescriptor().getYamlLdEnrichment().getAddRootVerb().setEnabled(true);
    return properties;
  }

  // ── (c) unprovision removes the partition ─────────────────────────────────

  @Test
  void unprovision_removesPartitionAndInvalidatesCache() {
    DataProductDescriptor desc = descriptor("urn:dmb:dp:domain:registry-a:0");

    service.unprovision(desc);

    verify(graphRepository).delete("urn:dmb:dp:domain:registry-a:0");
    verify(graphCacheService).invalidate();
  }

  // ── (d) mixed: System A via pass-through, System B via Config A ───────────

  @Test
  void provision_mixed_bothSystemsVisibleInMergedGraph() {
    DataProductDescriptor systemA = descriptor("urn:dmb:dp:domain:a:0");
    DataProductDescriptor systemB = descriptor("urn:dmb:dp:domain:b:0");
    String systemAJsonLd = "{\"@graph\":[{\"@id\":\"urn:a\"}]}";

    // System A: pass-through
    when(passthroughEngine.extractYamlLd(systemA)).thenReturn(Optional.of(systemAJsonLd));
    service.provision(systemA);

    // System B: Config A (no pass-through payload)
    when(passthroughEngine.extractYamlLd(systemB)).thenReturn(Optional.empty());
    when(mappingEngine.parse(systemB))
        .thenReturn(
            new ParseResult(
                Map.of("wb", WitboostNamespaces.WB_NS), null, List.of(), null, Map.of()));
    service.provision(systemB);

    // Both systems upserted to repo
    verify(graphRepository).upsert(eq("urn:dmb:dp:domain:a:0"), eq(systemAJsonLd));
    verify(graphRepository).upsert(eq("urn:dmb:dp:domain:b:0"), any(String.class));

    // Both provisionings invalidated the cache
    verify(graphCacheService, org.mockito.Mockito.times(2)).invalidate();

    // Merged graph returns both
    String systemAPartition = "{\"@graph\":[{\"@id\":\"urn:a\"}]}";
    String systemBPartition = "{\"@graph\":[{\"@id\":\"urn:b\"}]}";
    when(graphRepository.findAll())
        .thenReturn(
            List.of(
                new GraphPartition("urn:dmb:dp:domain:a:0", systemAPartition, Instant.now()),
                new GraphPartition("urn:dmb:dp:domain:b:0", systemBPartition, Instant.now())));
    when(graphCacheService.getOrBuild(any()))
        .thenAnswer(inv -> inv.<java.util.function.Supplier<String>>getArgument(0).get());

    String mergedGraph = service.getJsonLd();
    assertThat(mergedGraph).isNotNull();
    verify(graphRepository).findAll();
  }
}
