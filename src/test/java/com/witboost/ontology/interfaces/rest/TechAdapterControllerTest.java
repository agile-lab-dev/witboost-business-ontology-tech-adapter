package com.witboost.ontology.interfaces.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.witboost.ontology.application.GraphService;
import com.witboost.ontology.application.GraphValidationException;
import com.witboost.ontology.application.KgmClient;
import com.witboost.ontology.application.MappingEngine;
import com.witboost.ontology.application.PassthroughEngine;
import com.witboost.ontology.application.PrefixResolver;
import com.witboost.ontology.application.PresetMergeService;
import com.witboost.ontology.config.GraphProperties;
import com.witboost.ontology.domain.descriptor.DataProductDescriptor;
import com.witboost.ontology.domain.graph.ParseResult;
import com.witboost.ontology.domain.graph.ProvisionResult;
import com.witboost.ontology.domain.validation.GraphValidationError;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TechAdapterController.class)
class TechAdapterControllerTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean GraphService graphService;
  @MockitoBean KgmClient kgmClient;
  @MockitoBean MappingEngine mappingEngine;
  @MockitoBean PassthroughEngine passthroughEngine;

  private static final String VALID_REQUEST =
      """
      {
        "descriptorKind": "DATAPRODUCT_DESCRIPTOR",
        "descriptor": "id: urn:dmb:dp:domain:test:0\\nname: Test",
        "removeData": false
      }
      """;

  // ── validate endpoint (always HTTP 200) ───────────────────────────────────

  @Test
  void validate_validDescriptor_returns200WithValidTrue() throws Exception {
    when(mappingEngine.parse(any()))
        .thenReturn(new ParseResult(Map.of(), null, List.of(), null, Map.of()));

    mockMvc
        .perform(
            post("/v1/validate").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(true));
  }

  @Test
  void validate_graphValidationException_returns200WithValidFalse() throws Exception {
    doThrow(
            new GraphValidationException(
                List.of(GraphValidationError.unknownPrefix("concept-mapping.type", "owl"))))
        .when(mappingEngine)
        .parse(any());

    mockMvc
        .perform(
            post("/v1/validate").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(false))
        .andExpect(jsonPath("$.error.errors[0]").exists());
  }

  // ── provision endpoint ────────────────────────────────────────────────────

  @Test
  void provision_validDescriptor_returns200WithStatusCompleted() throws Exception {
    when(graphService.provision(any()))
        .thenReturn(
            new ProvisionResult(
                "urn:dmb:dp:domain:test:0",
                ProvisionResult.Strategy.MAPPING,
                List.of("http://example.com/ConceptA")));

    mockMvc
        .perform(
            post("/v1/provision").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.info.publicInfo.strategy").value("mapping"))
        .andExpect(jsonPath("$.info.publicInfo.systemUrn").value("urn:dmb:dp:domain:test:0"))
        .andExpect(
            jsonPath("$.info.publicInfo.provisionedIris[0]").value("http://example.com/ConceptA"))
        .andExpect(
            jsonPath("$.info.privateInfo.provisionedIris[0]").value("http://example.com/ConceptA"));
  }

  @Test
  void provision_graphValidationException_returns400WithRequestValidationError() throws Exception {
    doThrow(
            new GraphValidationException(
                List.of(GraphValidationError.missingField("business_concepts"))))
        .when(graphService)
        .provision(any());

    mockMvc
        .perform(
            post("/v1/provision").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.userMessage").isNotEmpty())
        .andExpect(jsonPath("$.errors[0]").exists())
        .andExpect(jsonPath("$.moreInfo.problems").isArray())
        .andExpect(jsonPath("$.moreInfo.solutions").isArray());
  }

  // ── error handling (GlobalExceptionHandler) ───────────────────────────────

  @Test
  void validate_malformedJsonBody_returns400WithUserMessage() throws Exception {
    mockMvc
        .perform(
            post("/v1/validate").contentType(MediaType.APPLICATION_JSON).content("{invalid json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.userMessage").isNotEmpty())
        .andExpect(jsonPath("$.moreInfo.problems").isArray())
        .andExpect(jsonPath("$.errors").isArray());
  }

  @Test
  void validate_noRequestBody_returns400() throws Exception {
    mockMvc
        .perform(post("/v1/validate").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
  }

  @Test
  void validate_yamlLdRequiredButAbsent_returns200WithValidFalse() throws Exception {
    doThrow(
            new GraphValidationException(
                List.of(GraphValidationError.missingField("graph_definition"))))
        .when(passthroughEngine)
        .extractYamlLd(any());

    mockMvc
        .perform(
            post("/v1/validate").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(false))
        .andExpect(jsonPath("$.error.errors[0]").exists());
  }

  @Test
  void validate_passthroughPayloadPresent_returns200WithValidTrueSkipsMappingEngine()
      throws Exception {
    when(passthroughEngine.extractYamlLd(any())).thenReturn(Optional.of("{\"@graph\":[]}"));

    mockMvc
        .perform(
            post("/v1/validate").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(true));
  }

  @Test
  void enrichmentFailure_validateAndProvision_returnStructuredErrorsWithoutIngestion()
      throws Exception {
    GraphProperties properties = new GraphProperties();
    new PresetMergeService(properties).init();
    properties.getDescriptor().setYamlLdPath("graph_definition");
    properties.getDescriptor().getYamlLdEnrichment().getAddRootVerb().setEnabled(true);
    PassthroughEngine engine = new PassthroughEngine(properties, new PrefixResolver(properties));
    when(passthroughEngine.extractYamlLd(any()))
        .thenAnswer(invocation -> engine.extractYamlLd(invocation.getArgument(0)));
    String request =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "descriptorKind",
                    "DATAPRODUCT_DESCRIPTOR",
                    "removeData",
                    false,
                    "descriptor",
                    "id: urn:dmb:dp:domain:test:0\ngraph_definition: '{\"@graph\":[]}'"));
    mockMvc
        .perform(post("/v1/validate").contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(false))
        .andExpect(
            jsonPath("$.error.errors[0]")
                .value(
                    org.hamcrest.Matchers.containsString(
                        "yaml-ld-enrichment.add-root-verb requires exactly one distinct named root matching descriptor.root.type='skos:ConceptScheme' in the top-level @graph array of field 'graph_definition'; found 0.")));
    verifyNoInteractions(graphService, mappingEngine, kgmClient);
    when(graphService.provision(any()))
        .thenAnswer(
            invocation -> {
              engine.extractYamlLd(invocation.<DataProductDescriptor>getArgument(0));
              throw new AssertionError("Expected enrichment to reject the payload");
            });
    mockMvc
        .perform(post("/v1/provision").contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.errors[0]")
                .value(
                    org.hamcrest.Matchers.containsString(
                        "yaml-ld-enrichment.add-root-verb requires exactly one distinct named root matching descriptor.root.type='skos:ConceptScheme' in the top-level @graph array of field 'graph_definition'; found 0.")));
    verifyNoInteractions(mappingEngine, kgmClient);
  }

  // ── unprovision endpoint ──────────────────────────────────────────────────

  @Test
  void unprovision_validDescriptor_returns200WithStatusCompleted() throws Exception {
    mockMvc
        .perform(
            post("/v1/unprovision").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));
  }

  @Test
  void unprovision_graphValidationException_returns400WithRequestValidationError()
      throws Exception {
    doThrow(
            new GraphValidationException(
                List.of(GraphValidationError.missingField("business_concepts"))))
        .when(graphService)
        .unprovision(any());

    mockMvc
        .perform(
            post("/v1/unprovision").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.userMessage").isNotEmpty())
        .andExpect(jsonPath("$.errors[0]").exists())
        .andExpect(jsonPath("$.moreInfo.problems").isArray())
        .andExpect(jsonPath("$.moreInfo.solutions").isArray());
  }

  // ── GlobalExceptionHandler: unexpected RuntimeException → 500 ─────────────

  @Test
  void provision_unexpectedRuntimeException_returns500WithSystemError() throws Exception {
    doThrow(new RuntimeException("something exploded")).when(graphService).provision(any());

    mockMvc
        .perform(
            post("/v1/provision").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error").value("Provision failed: something exploded"))
        .andExpect(jsonPath("$.userMessage").isNotEmpty())
        .andExpect(jsonPath("$.moreInfo.problems").isArray());
  }
}
