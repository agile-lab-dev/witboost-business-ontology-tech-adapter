package com.witboost.ontology.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.witboost.ontology.config.GraphProperties;
import com.witboost.ontology.domain.descriptor.DataProductDescriptor;
import com.witboost.ontology.domain.validation.GraphValidationError;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Implements Config B (YAML-LD pass-through): reads a JSON-LD payload directly from a descriptor
 * field and optionally enriches root links before storing the system's graph partition, bypassing
 * all concept-mapping logic (FR-010, FR-011, FR-012).
 *
 * <p>The pass-through path is activated when {@code descriptor.yaml-ld-path} is configured in
 * {@code application.yaml}. If the field is absent at provision time:
 *
 * <ul>
 *   <li>When {@code yaml-ld-required: false} (default), the engine returns {@link Optional#empty()}
 *       and the caller falls back to Config A.
 *   <li>When {@code yaml-ld-required: true}, the engine throws a {@link GraphValidationException}
 *       so the provision fails with a clear error.
 * </ul>
 *
 * <p>The descriptor field value may be a JSON string or a structured object (Map). In the latter
 * case, it is serialized to a JSON string using {@link ObjectMapper}.
 */
@Service
@Slf4j
public class PassthroughEngine {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final GraphProperties graphProperties;
  private final RootVerbEnricher rootVerbEnricher;

  public PassthroughEngine(GraphProperties graphProperties, PrefixResolver prefixResolver) {
    this.graphProperties = graphProperties;
    this.rootVerbEnricher = new RootVerbEnricher(graphProperties, prefixResolver);
  }

  /**
   * Attempts to extract a JSON-LD string from the descriptor's {@code yaml-ld-path} field.
   *
   * @param descriptor the incoming data-product descriptor
   * @return {@link Optional#of(String)} with the JSON-LD string when the path is configured and the
   *     field is present; {@link Optional#empty()} when the path is not configured or the field is
   *     absent and {@code yaml-ld-required} is {@code false}
   * @throws GraphValidationException if the path is configured, the field is absent, and {@code
   *     yaml-ld-required} is {@code true}, or if the field value cannot be serialized to JSON
   */
  public Optional<String> extractYamlLd(DataProductDescriptor descriptor) {
    String yamlLdPath = graphProperties.getDescriptor().getYamlLdPath();
    boolean enrichmentEnabled =
        graphProperties.getDescriptor().getYamlLdEnrichment().getAddRootVerb().isEnabled();
    if (yamlLdPath == null || yamlLdPath.isBlank()) {
      if (enrichmentEnabled) {
        throw RootVerbEnricher.createValidationException(
            "descriptor.yaml-ld-path", "requires a nonblank descriptor.yaml-ld-path.");
      }
      return Optional.empty();
    }

    Object fieldValue = descriptor.getExtraFields().get(yamlLdPath);
    if (fieldValue == null) {
      if (graphProperties.getDescriptor().isYamlLdRequired()) {
        log.error(
            "Required yaml-ld field '{}' is absent in descriptor {}",
            yamlLdPath,
            descriptor.getId());
        throw new GraphValidationException(List.of(GraphValidationError.missingField(yamlLdPath)));
      }
      log.debug(
          "yaml-ld field '{}' absent; falling through to YAML to RDF mapping config", yamlLdPath);
      return Optional.empty();
    }

    String jsonLdString = toJsonString(fieldValue, yamlLdPath);
    if (enrichmentEnabled) {
      jsonLdString = rootVerbEnricher.enrich(jsonLdString, descriptor, yamlLdPath);
    }
    log.debug(
        "Extracted yaml-ld pass-through from field '{}' for descriptor {}",
        yamlLdPath,
        descriptor.getId());
    return Optional.of(jsonLdString);
  }

  /**
   * Converts a field value to its JSON string representation.
   *
   * <p>If the value is already a {@link String}, it is returned as-is. Otherwise it is serialized
   * with {@link ObjectMapper}.
   *
   * @param fieldValue the raw value read from the descriptor's extra fields
   * @param fieldPath the dot-path used to identify the field in error messages
   * @return the JSON string representation of the field value
   * @throws GraphValidationException if Jackson cannot serialize the value
   */
  private String toJsonString(Object fieldValue, String fieldPath) {
    if (fieldValue instanceof String stringValue) {
      return stringValue;
    }
    try {
      return OBJECT_MAPPER.writeValueAsString(fieldValue);
    } catch (Exception jsonProcessingException) {
      throw new GraphValidationException(List.of(GraphValidationError.missingField(fieldPath)));
    }
  }
}
