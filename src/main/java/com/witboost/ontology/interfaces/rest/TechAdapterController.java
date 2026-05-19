package com.witboost.ontology.interfaces.rest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.witboost.ontology.application.GraphService;
import com.witboost.ontology.application.GraphValidationException;
import com.witboost.ontology.application.KgmClient;
import com.witboost.ontology.application.MappingEngine;
import com.witboost.ontology.application.PassthroughEngine;
import com.witboost.ontology.domain.descriptor.DataProductDescriptor;
import com.witboost.ontology.domain.graph.ProvisionResult;
import com.witboost.ontology.domain.validation.GraphValidationError;
import com.witboost.ontology.interfaces.rest.api.TechAdapterApi;
import com.witboost.ontology.interfaces.rest.models.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implements the Witboost Tech Adapter OpenAPI contract.
 *
 * <p>Response codes per the OpenAPI spec:
 *
 * <ul>
 *   <li>{@code /v1/validate} → always HTTP 200 ({@code valid: true} or {@code valid: false});
 *       descriptor-level errors appear as {@code valid: false} in the body.
 *   <li>{@code /v1/provision} → HTTP 200 ({@code COMPLETED}) on success; HTTP 400 ({@code
 *       RequestValidationError}) when descriptor validation fails; HTTP 500 on unexpected errors
 *       (handled by {@link GlobalExceptionHandler}).
 *   <li>{@code /v1/unprovision} → same as provision.
 * </ul>
 */
@RestController
@Slf4j
public class TechAdapterController implements TechAdapterApi {

  private static final ObjectMapper YAML_MAPPER =
      new YAMLMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private final GraphService graphService;
  private final KgmClient kgmClient;
  private final MappingEngine mappingEngine;
  private final PassthroughEngine passthroughEngine;

  public TechAdapterController(
      GraphService graphService,
      KgmClient kgmClient,
      MappingEngine mappingEngine,
      PassthroughEngine passthroughEngine) {
    this.graphService = graphService;
    this.kgmClient = kgmClient;
    this.mappingEngine = mappingEngine;
    this.passthroughEngine = passthroughEngine;
  }

  /** Validates a descriptor. Always returns HTTP 200 — validation errors appear in the body. */
  @Override
  public ResponseEntity<ValidationResult> validate(ProvisioningRequest provisioningRequest) {
    log.info("Received validate request");
    try {
      DataProductDescriptor descriptor =
          YAML_MAPPER.readValue(provisioningRequest.getDescriptor(), DataProductDescriptor.class);
      log.info("Validating descriptor id={}", descriptor.getId());
      Optional<String> passthroughPayload = passthroughEngine.extractYamlLd(descriptor);
      if (passthroughPayload.isEmpty()) {
        // Config A: validate via mapping engine
        mappingEngine.parse(descriptor);
      }
      log.info("Validation succeeded id={}", descriptor.getId());
      return ResponseEntity.ok(new ValidationResult(true));
    } catch (GraphValidationException e) {
      List<String> messages = toMessages(e);
      log.warn("Validation failed with {} error(s): {}", messages.size(), messages);
      return ResponseEntity.ok(new ValidationResult(false).error(new ValidationError(messages)));
    } catch (Exception e) {
      log.error("Validate request could not be processed: {}", e.getMessage());
      throw new RuntimeException("Descriptor validation failed: " + e.getMessage(), e);
    }
  }

  /**
   * Provisions a descriptor. Returns HTTP 200 on success. Descriptor-level validation errors
   * ({@link GraphValidationException}) are not caught here — they propagate to {@link
   * GlobalExceptionHandler} which returns HTTP 400 {@code RequestValidationError}.
   */
  @Override
  public ResponseEntity<ProvisioningStatus> provision(ProvisioningRequest provisioningRequest) {
    log.info("Received provision request");
    try {
      DataProductDescriptor descriptor =
          YAML_MAPPER.readValue(provisioningRequest.getDescriptor(), DataProductDescriptor.class);
      log.info("Provisioning descriptor id={}", descriptor.getId());
      ProvisionResult result = graphService.provision(descriptor);
      kgmClient.triggerIngestion();
      log.info(
          "Provision completed id={} strategy={} iris={}",
          descriptor.getId(),
          result.strategy(),
          result.provisionedIris().size());
      Map<String, Object> provisionInfo =
          Map.of(
              "systemUrn", result.systemUrn(),
              "strategy", result.strategy().name().toLowerCase(),
              "provisionedIris", result.provisionedIris());
      return ResponseEntity.ok(
          new ProvisioningStatus()
              .status(ProvisioningStatus.StatusEnum.COMPLETED)
              .result("OK")
              .info(new Info(provisionInfo, provisionInfo)));
    } catch (GraphValidationException e) {
      log.warn(
          "Provision failed with {} validation error(s): {}", e.getErrors().size(), toMessages(e));
      throw e; // propagates to GlobalExceptionHandler → HTTP 400
    } catch (Exception e) {
      log.error("Provision request could not be processed: {}", e.getMessage());
      throw new RuntimeException("Provision failed: " + e.getMessage(), e);
    }
  }

  /**
   * Unprovisions a descriptor. Returns HTTP 200 on success. Descriptor-level validation errors
   * ({@link GraphValidationException}) are not caught here — they propagate to {@link
   * GlobalExceptionHandler} which returns HTTP 400 {@code RequestValidationError}.
   */
  @Override
  public ResponseEntity<ProvisioningStatus> unprovision(ProvisioningRequest provisioningRequest) {
    log.info("Received unprovision request");
    try {
      DataProductDescriptor descriptor =
          YAML_MAPPER.readValue(provisioningRequest.getDescriptor(), DataProductDescriptor.class);
      log.info("Unprovisioning descriptor id={}", descriptor.getId());
      graphService.unprovision(descriptor);
      kgmClient.triggerIngestion();
      log.info("Unprovision completed id={}", descriptor.getId());
      return ResponseEntity.ok(
          new ProvisioningStatus()
              .status(ProvisioningStatus.StatusEnum.COMPLETED)
              .result("OK")
              .info(new Info(new ValidationResult(true), null)));
    } catch (GraphValidationException e) {
      log.warn(
          "Unprovision failed with {} validation error(s): {}",
          e.getErrors().size(),
          toMessages(e));
      throw e; // propagates to GlobalExceptionHandler → HTTP 400
    } catch (Exception e) {
      log.error("Unprovision request could not be processed: {}", e.getMessage());
      throw new RuntimeException("Unprovision failed: " + e.getMessage(), e);
    }
  }

  @Override
  public ResponseEntity<ProvisioningStatus> getStatus(String token) {
    return ResponseEntity.status(501).build();
  }

  @Override
  public ResponseEntity<ProvisioningStatus> terminateProvisioning(String token) {
    return ResponseEntity.status(501).build();
  }

  @Override
  public ResponseEntity<ProvisioningStatus> updateacl(UpdateAclRequest updateAclRequest) {
    return ResponseEntity.status(501).build();
  }

  @Override
  public ResponseEntity<ReverseProvisioningStatus> runReverseProvisioning(
      ReverseProvisioningRequest reverseProvisioningRequest) {
    return ResponseEntity.status(501).build();
  }

  @Override
  public ResponseEntity<ReverseProvisioningStatus> getReverseProvisioningStatus(String token) {
    return ResponseEntity.status(501).build();
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private List<String> toMessages(GraphValidationException e) {
    return e.getErrors().stream().map(GraphValidationError::message).collect(Collectors.toList());
  }
}
