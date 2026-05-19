package com.witboost.ontology.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.witboost.ontology.WitboostNamespaces;
import com.witboost.ontology.application.infrastructure.GraphRepository;
import com.witboost.ontology.config.GraphProperties;
import com.witboost.ontology.config.SkosPresetDefinition;
import com.witboost.ontology.domain.descriptor.DataProductDescriptor;
import com.witboost.ontology.domain.graph.GraphPartition;
import com.witboost.ontology.domain.graph.ParseResult;
import com.witboost.ontology.domain.graph.ProvisionResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GraphServiceTest {

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
    props.getDescriptor().setBusinessConceptsPath("business_concepts");
    props.getDescriptor().getRoot().setIsConcept(false);
    props.getConceptMapping().setType(SkosPresetDefinition.SKOS_NS + "Concept");
    props.getConceptMapping().setIriField("iri");
    props.getConceptMapping().setNameField("name");
    props.getRelationsMapping().setVerb(SkosPresetDefinition.SKOS_NS + "narrower");

    service =
        new GraphService(
            props, mappingEngine, passthroughEngine, graphCacheService, graphRepository);
  }

  private DataProductDescriptor descriptor(String id) {
    DataProductDescriptor d = new DataProductDescriptor();
    d.setId(id);
    return d;
  }

  @Test
  void provision_callsMappingEngine_writesRepo_invalidatesCache() {
    DataProductDescriptor desc = descriptor("urn:dmb:dp:domain:registry-a:0");
    when(passthroughEngine.extractYamlLd(desc)).thenReturn(Optional.empty());
    when(mappingEngine.parse(desc))
        .thenReturn(
            new ParseResult(
                Map.of("wb", WitboostNamespaces.WB_NS), null, List.of(), null, Map.of()));

    ProvisionResult result = service.provision(desc);

    assertThat(result.strategy()).isEqualTo(ProvisionResult.Strategy.MAPPING);
    assertThat(result.systemUrn()).isEqualTo("urn:dmb:dp:domain:registry-a:0");
    assertThat(result.provisionedIris()).isEmpty();
    InOrder order = inOrder(mappingEngine, graphRepository, graphCacheService);
    order.verify(mappingEngine).parse(desc);
    order.verify(graphRepository).upsert(eq("urn:dmb:dp:domain:registry-a:0"), anyString());
    order.verify(graphCacheService).invalidate();
  }

  @Test
  void unprovision_deletesFromRepo_invalidatesCache() {
    DataProductDescriptor desc = descriptor("urn:dmb:dp:domain:registry-a:0");

    service.unprovision(desc);

    InOrder order = inOrder(graphRepository, graphCacheService);
    order.verify(graphRepository).delete("urn:dmb:dp:domain:registry-a:0");
    order.verify(graphCacheService).invalidate();
  }

  @Test
  void getJsonLd_delegatesToCache() {
    when(graphCacheService.getOrBuild(any())).thenReturn("{\"@graph\":[]}");
    service.getJsonLd();
    verify(graphCacheService).getOrBuild(any());
  }

  @Test
  void provision_noConcepts_writesEmptyJsonLdToRepo() {
    DataProductDescriptor desc = descriptor("urn:dmb:dp:domain:empty:0");
    when(passthroughEngine.extractYamlLd(desc)).thenReturn(Optional.empty());
    when(mappingEngine.parse(desc))
        .thenReturn(new ParseResult(Map.of(), null, List.of(), null, Map.of()));

    service.provision(desc);

    verify(graphRepository).upsert(eq("urn:dmb:dp:domain:empty:0"), anyString());
    verify(graphCacheService).invalidate();
  }

  @Test
  void getJsonLd_cacheMiss_buildsFromPartitions() {
    when(graphRepository.findAll())
        .thenReturn(
            List.of(
                new GraphPartition("urn:sys1", "{\"@context\":{},\"@graph\":[]}", Instant.now())));
    when(graphCacheService.getOrBuild(any()))
        .thenAnswer(inv -> inv.<java.util.function.Supplier<String>>getArgument(0).get());

    service.getJsonLd();

    verify(graphRepository).findAll();
  }

  @Test
  void provision_passthroughPayloadPresent_storesDirectlySkipsMappingEngine() {
    DataProductDescriptor desc = descriptor("urn:dmb:dp:domain:registry-a:0");
    String passthroughJsonLd = "{\"@graph\":[{\"@id\":\"urn:example\"}]}";
    when(passthroughEngine.extractYamlLd(desc)).thenReturn(Optional.of(passthroughJsonLd));

    ProvisionResult result = service.provision(desc);

    assertThat(result.strategy()).isEqualTo(ProvisionResult.Strategy.PASSTHROUGH);
    assertThat(result.systemUrn()).isEqualTo("urn:dmb:dp:domain:registry-a:0");
    assertThat(result.provisionedIris()).containsExactly("urn:dmb:dp:domain:registry-a:0");
    verify(graphRepository).upsert("urn:dmb:dp:domain:registry-a:0", passthroughJsonLd);
    verify(graphCacheService).invalidate();
    verify(mappingEngine, never()).parse(any());
  }
}
