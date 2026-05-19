package com.witboost.ontology.application;

import com.witboost.ontology.config.GraphProperties;
import com.witboost.ontology.config.GraphProperties.ConceptMappingProperties;
import com.witboost.ontology.config.GraphProperties.RelationsMappingProperties;
import com.witboost.ontology.config.GraphProperties.RootProperties;
import com.witboost.ontology.domain.descriptor.DataProductDescriptor;
import com.witboost.ontology.domain.graph.ConceptEntry;
import com.witboost.ontology.domain.graph.ParseResult;
import com.witboost.ontology.domain.graph.RelationEntry;
import com.witboost.ontology.domain.validation.GraphValidationError;
import jakarta.validation.constraints.NotBlank;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Parses a data-product descriptor into structured {@link ConceptEntry} domain objects (FR-002,
 * FR-003, FR-004, FR-005, FR-006, FR-016, FR-017).
 *
 * <p>Implements Config A (YAML-to-RDF mapping). Config B (YAML-LD pass-through) is handled
 * separately by {@code PassthroughEngine}.
 *
 * <p>Validation errors are collected across all concepts and thrown together as a {@link
 * GraphValidationException} at the end of parsing so the operator receives the full error list in
 * one response.
 */
@Service
@Slf4j
public class MappingEngine {

  private final GraphProperties graphProperties;
  private final PrefixResolver prefixResolver;
  private final IriDerivationService iriDerivationService;

  public MappingEngine(
      GraphProperties graphProperties,
      PrefixResolver prefixResolver,
      IriDerivationService iriDerivationService) {
    this.graphProperties = graphProperties;
    this.prefixResolver = prefixResolver;
    this.iriDerivationService = iriDerivationService;
  }

  /**
   * Parses and fully validates a descriptor, returning a {@link ParseResult} containing the
   * effective prefix map, the optional root concept entry, and the list of child concepts.
   *
   * <p>This is the single public entrypoint for descriptor parsing. All validation (IRI resolution,
   * type resolution, prefix expansion, root concept validation) is performed here. Errors are
   * collected across all entries and thrown together so the operator receives the complete error
   * list in a single response.
   *
   * @throws GraphValidationException if any IRI, prefix, uniqueness, or root-config errors are
   *     found
   */
  public ParseResult parse(DataProductDescriptor descriptor) {
    Map<String, String> prefixMap = prefixResolver.resolve(descriptor.getExtraFields());

    List<GraphValidationError> errors = new ArrayList<>();
    // If root IRI resolution fails, throw immediately — all concept IRIs depend on it
    String effectiveRootIri = resolveEffectiveRootIri(descriptor, prefixMap);

    List<Map<String, Object>> rawConceptList = extractConceptList(descriptor.getExtraFields());
    log.info(
        "Parsing descriptor for system '{}' — found {} concept(s) under '{}'",
        descriptor.getId(),
        rawConceptList.size(),
        graphProperties.getDescriptor().getBusinessConceptsPath());
    Set<String> seenIris = new HashSet<>();
    List<ConceptEntry> concepts = new ArrayList<>();

    String conceptsPath = graphProperties.getDescriptor().getBusinessConceptsPath();

    int conceptIndex = 0;
    for (Map<String, Object> rawConceptFields : rawConceptList) {
      // Read name eagerly so we can include it in error location hints before IRI resolution.
      String name = getString(rawConceptFields, graphProperties.getConceptMapping().getNameField());
      String conceptLocation =
          name != null
              ? conceptsPath + "['" + name + "']"
              : conceptsPath + "[" + conceptIndex + "]";
      conceptIndex++;

      String iri = resolveConceptIri(rawConceptFields, effectiveRootIri, prefixMap, errors);
      if (iri == null) {
        log.error("Skipping {} — could not resolve subject IRI", conceptLocation);
        continue;
      }

      if (!seenIris.add(iri)) {
        log.error(
            "Skipping {} — IRI collision: <{}> already used by a previous concept",
            conceptLocation,
            iri);
        errors.add(GraphValidationError.iriCollision(iri));
        continue;
      }

      String type = resolveConceptType(rawConceptFields, prefixMap, errors, conceptLocation);
      if (type == null) {
        log.error("Skipping {} <{}> — could not resolve RDF type", conceptLocation, iri);
        errors.add(
            GraphValidationError.unresolvableType(
                graphProperties.getConceptMapping().getTypeField(), iri));
        continue;
      }
      Map<String, String> properties = resolveProperties(rawConceptFields, prefixMap, errors);
      List<RelationEntry> relations =
          resolveRelations(rawConceptFields, effectiveRootIri, prefixMap, errors, conceptLocation);
      log.debug(
          "Resolved {} <{}> as type <{}> with {} property triple(s) and {} relation(s)",
          conceptLocation,
          iri,
          type,
          properties.size(),
          relations.size());
      concepts.add(new ConceptEntry(iri, name, type, properties, relations));
    }

    // Parse and validate root concept when root.isConcept = true
    ConceptEntry rootEntry = null;
    String expandedRootVerb = null;
    if (Boolean.TRUE.equals(graphProperties.getDescriptor().getRoot().getIsConcept())) {
      try {
        rootEntry = parseRoot(descriptor, effectiveRootIri, prefixMap);
      } catch (GraphValidationException rootValidationException) {
        log.error(
            "Root concept validation failed with {} error(s)",
            rootValidationException.getErrors().size());
        errors.addAll(rootValidationException.getErrors());
      }
      // Validate root verb separately so its error joins the shared list.
      // A null result means no child→root structural links, which is intentional and not an error.
      // A configured verb whose prefix is unknown is a misconfiguration that must fail provision.
      expandedRootVerb = resolveRootVerb(descriptor, prefixMap, errors);
    }

    if (!errors.isEmpty()) throw new GraphValidationException(errors);
    Map<String, String> expandedReverseVerbMap = resolveReverseVerbMap(prefixMap, errors);
    if (!errors.isEmpty()) throw new GraphValidationException(errors);

    int totalConcepts = concepts.size() + (rootEntry != null ? 1 : 0);
    log.info(
        "Descriptor parsed successfully: {} concept(s) (root: {}), {} relation type(s) in reverse-verb map",
        totalConcepts,
        rootEntry != null,
        expandedReverseVerbMap.size());
    return new ParseResult(
        prefixMap, rootEntry, concepts, expandedRootVerb, expandedReverseVerbMap);
  }

  /**
   * Resolves the effective subject IRI for the root concept.
   *
   * <p>Priority:
   *
   * <ol>
   *   <li>{@code descriptor.root.iri-field} — if configured, read that field from the descriptor
   *       and expand the value as a full IRI.
   *   <li>{@code concept-mapping.iri-field} — fallback when {@code root.iri-field} is not set;
   *       reads the same-named field from the descriptor root. This mirrors how other root scalar
   *       fields fall back to their {@code concept-mapping} counterparts.
   *   <li>Neither field resolves to a usable value → derive the IRI from the system URN via {@link
   *       IriDerivationService}.
   * </ol>
   *
   * <p>In both cases, a URN value triggers a WARN and falls through to derivation (FR-006). An
   * unknown prefix is a hard validation error.
   *
   * @throws GraphValidationException if the resolved iri-field value contains an unknown prefix
   */
  private String resolveEffectiveRootIri(
      DataProductDescriptor descriptor, Map<String, String> prefixMap) {
    RootProperties root = graphProperties.getDescriptor().getRoot();
    // root.iri-field wins; fall back to concept-mapping.iri-field when not explicitly set
    String iriField =
        root.getIriField() != null
            ? root.getIriField()
            : graphProperties.getConceptMapping().getIriField();

    if (iriField != null) {
      String rawFieldValue = getString(descriptor.getExtraFields(), iriField);
      if (rawFieldValue != null && !rawFieldValue.isBlank()) {
        if (rawFieldValue.startsWith("urn:")) {
          log.warn(
              "Root iri-field '{}' value '{}' is a URN, not an IRI. "
                  + "Falling back to URN-derived IRI (FR-006).",
              iriField,
              rawFieldValue);
          // fall through to URN derivation
        } else {
          try {
            String expanded = prefixResolver.expand(rawFieldValue, prefixMap, iriField);
            log.debug("Root IRI resolved from field '{}': <{}>", iriField, expanded);
            return expanded;
          } catch (UnknownPrefixException unknownPrefixException) {
            throw new GraphValidationException(
                List.of(
                    GraphValidationError.unknownPrefix(
                        unknownPrefixException.getField(), unknownPrefixException.getPrefix())));
          }
        }
      }
    }
    String derived = iriDerivationService.deriveRootIri(descriptor.getId());
    log.debug("Root IRI derived from system URN '{}': <{}>", descriptor.getId(), derived);
    return derived;
  }

  /**
   * Resolves the effective predicate IRI used to link child concepts to the root.
   *
   * <p>Priority (mirrors the {@code typeField → type} pattern throughout this engine):
   *
   * <ol>
   *   <li>{@code root.verb-field} — if configured, read the named field from the descriptor and
   *       expand it. A non-blank value that cannot be expanded (unknown prefix) is added to the
   *       shared error list and {@code null} is returned immediately.
   *   <li>{@code root.verb} — the static configured verb (e.g. {@code "skos:inScheme"}). An
   *       unknown-prefix error is added to the shared list if expansion fails.
   *   <li>Neither configured → returns {@code null} (no child→root links; intentional, no error).
   * </ol>
   *
   * @param descriptor the incoming descriptor (used to read {@code verbField} value)
   * @param prefixMap the effective prefix map for the current provision
   * @param errors shared error list; unknown-prefix failures are appended here
   * @return the fully-expanded verb IRI, or {@code null} when no verb is configured or resolvable
   */
  private String resolveRootVerb(
      DataProductDescriptor descriptor,
      Map<String, String> prefixMap,
      List<GraphValidationError> errors) {
    RootProperties root = graphProperties.getDescriptor().getRoot();

    return fallback(
        descriptor.getExtraFields(),
        root::getVerbField,
        (rawVerb) -> expandOrError(rawVerb, prefixMap, root.getVerbField(), errors),
        root::getVerb,
        (verb) ->
            expandOrError(
                verb, prefixMap, "adapter-config:app.graph.descriptor.root.verb", errors));
  }

  /**
   * Parses and validates the root concept. Self-contained: collects all root-related errors
   * (missing/unknown type, unresolvable property predicates) and throws {@link
   * GraphValidationException} if any are found.
   */
  private ConceptEntry parseRoot(
      DataProductDescriptor descriptor, String rootIri, Map<String, String> prefixMap) {
    RootProperties root = graphProperties.getDescriptor().getRoot();
    ConceptMappingProperties cm = graphProperties.getConceptMapping();
    List<GraphValidationError> errors = new ArrayList<>();

    // root fields fall back to their concept-mapping counterparts when not explicitly set
    String effectiveTypeField =
        root.getTypeField() != null ? root.getTypeField() : cm.getTypeField();
    String effectiveType = root.getType() != null ? root.getType() : cm.getType();
    String effectiveNameField =
        root.getNameField() != null ? root.getNameField() : cm.getNameField();

    // Resolve and validate root type: typeField (descriptor) wins over type (config)
    String type =
        fallback(
            descriptor.getExtraFields(),
            () -> effectiveTypeField,
            (rootType) -> expandOrError(rootType, prefixMap, effectiveTypeField, errors),
            () -> effectiveType,
            (defaultType) ->
                expandOrError(
                    defaultType,
                    prefixMap,
                    "adapter-config:app.graph.descriptor.root.type",
                    errors));

    if (type == null) {
      errors.add(
          GraphValidationError.unresolvableType(
              effectiveTypeField != null
                  ? effectiveTypeField
                  : "adapter-config:app.graph.descriptor.root.type",
              rootIri));
    }

    // Resolve root property predicates
    Map<String, String> properties =
        resolveRootProperties(descriptor.getExtraFields(), root, prefixMap, errors);

    if (!errors.isEmpty()) throw new GraphValidationException(errors);

    String name = getString(descriptor.getExtraFields(), effectiveNameField);
    log.debug("Root concept resolved: <{}> type=<{}>", rootIri, type);
    return new ConceptEntry(rootIri, name, type, properties, List.of());
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> extractConceptList(Map<String, Object> fields) {
    String path = graphProperties.getDescriptor().getBusinessConceptsPath();
    if (path == null || path.isBlank()) return List.of();
    String[] parts = path.split("\\.");
    Object current = fields.get(parts[0]);
    for (int i = 1; i < parts.length; i++) {
      if (!(current instanceof Map<?, ?> nestedMap)) return List.of();
      current = nestedMap.get(parts[i]);
    }
    if (!(current instanceof List<?> conceptList)) return List.of();
    return conceptList.stream()
        .filter(rawElement -> rawElement instanceof Map)
        .map(rawElement -> (Map<String, Object>) rawElement)
        .toList();
  }

  private String resolveConceptIri(
      Map<String, Object> rawConceptFields,
      String rootIri,
      Map<String, String> prefixMap,
      List<GraphValidationError> errors) {
    ConceptMappingProperties conceptMapping = graphProperties.getConceptMapping();
    if (conceptMapping.getIriField() != null) {
      String rawIri = getString(rawConceptFields, conceptMapping.getIriField());
      if (rawIri != null && !rawIri.isBlank()) {
        if (rawIri.startsWith("urn:")) {
          log.warn(
              "Value '{}' in iri-field '{}' is a URN, not an IRI. "
                  + "Falling back to name-based IRI derivation (FR-006).",
              rawIri,
              conceptMapping.getIriField());
          // fall through to name derivation
        } else {
          return expandOrError(rawIri, prefixMap, conceptMapping.getIriField(), errors);
        }
      }
    }
    // Derive from name field
    if (conceptMapping.getNameField() != null) {
      String conceptName = getString(rawConceptFields, conceptMapping.getNameField());
      if (conceptName != null && !conceptName.isBlank()) {
        return iriDerivationService.deriveConceptIri(rootIri, conceptName);
      }
    }
    errors.add(
        GraphValidationError.unresolvableIri(
            conceptMapping.getIriField(), conceptMapping.getNameField()));
    return null;
  }

  private String resolveConceptType(
      Map<String, Object> rawConceptFields,
      Map<String, String> prefixMap,
      List<GraphValidationError> errors,
      String conceptLocation) {
    ConceptMappingProperties conceptMapping = graphProperties.getConceptMapping();
    return fallback(
        rawConceptFields,
        conceptMapping::getTypeField,
        (perConceptType) ->
            expandOrError(
                perConceptType,
                prefixMap,
                conceptLocation + "." + conceptMapping.getTypeField(),
                errors),
        conceptMapping::getType,
        (type) ->
            expandOrError(
                type, prefixMap, "adapter-config:app.graph.concept-mapping.type", errors));
  }

  private Map<String, String> resolveProperties(
      Map<String, Object> rawConceptFields,
      Map<String, String> prefixMap,
      List<GraphValidationError> errors) {
    Map<String, String> resolvedProperties = new LinkedHashMap<>();
    for (Map.Entry<String, String> propertyEntry :
        graphProperties.getConceptMapping().getProperties().entrySet()) {
      String predicateCurie = propertyEntry.getKey();
      String descriptorFieldName = propertyEntry.getValue();
      String expandedPredicate =
          expandOrError(
              predicateCurie,
              prefixMap,
              "adapter-config:app.graph.concept-mapping.properties." + predicateCurie,
              errors);
      if (expandedPredicate != null) {
        String fieldValue = getString(rawConceptFields, descriptorFieldName);
        if (fieldValue != null) resolvedProperties.put(expandedPredicate, fieldValue);
      }
    }
    return resolvedProperties;
  }

  private Map<String, String> resolveRootProperties(
      Map<String, Object> descriptorFields,
      RootProperties root,
      Map<String, String> prefixMap,
      List<GraphValidationError> errors) {
    Map<String, String> resolvedProperties = new LinkedHashMap<>();
    for (Map.Entry<String, String> propertyEntry : root.getProperties().entrySet()) {
      String expandedPredicate =
          expandOrError(
              propertyEntry.getKey(),
              prefixMap,
              "adapter-config:app.graph.descriptor.root.properties." + propertyEntry.getKey(),
              errors);
      if (expandedPredicate != null) {
        String fieldValue = getString(descriptorFields, propertyEntry.getValue());
        if (fieldValue != null) resolvedProperties.put(expandedPredicate, fieldValue);
      }
    }
    return resolvedProperties;
  }

  @SuppressWarnings("unchecked")
  private List<RelationEntry> resolveRelations(
      Map<String, Object> rawConceptFields,
      String rootIri,
      Map<String, String> prefixMap,
      List<GraphValidationError> errors,
      String conceptLocation) {
    RelationsMappingProperties relationsMapping = graphProperties.getRelationsMapping();
    if (relationsMapping.getRelationsPath() == null) return List.of();

    Object relationsValue = rawConceptFields.get(relationsMapping.getRelationsPath());
    if (!(relationsValue instanceof List<?> relationsList)) return List.of();

    List<RelationEntry> resolvedRelations = new ArrayList<>();
    int relIndex = 0;
    for (Object rawItem : relationsList) {
      if (!(rawItem instanceof Map<?, ?> rawRelationEntry)) {
        relIndex++;
        continue;
      }
      Map<String, Object> relationFields = (Map<String, Object>) rawRelationEntry;
      String relLocation = conceptLocation + ".relations[" + relIndex + "]";
      relIndex++;

      String targetIri = resolveConceptIri(relationFields, rootIri, prefixMap, errors);
      // If IRI is null, errors array contains an error that will make the operation fail after
      // processing all ends
      if (targetIri == null) continue;

      String verb =
          resolveRelationVerb(relationFields, prefixMap, errors, relationsMapping, relLocation);
      if (verb == null) {
        errors.add(
            GraphValidationError.unresolvableRelationVerb(
                relationsMapping.getVerbField(), targetIri));
        continue;
      }
      resolvedRelations.add(new RelationEntry(targetIri, verb));
      log.debug("Resolved {}: --[<{}>]--> <{}>", relLocation, verb, targetIri);
    }
    return resolvedRelations;
  }

  private String resolveRelationVerb(
      Map<String, Object> relationFields,
      Map<String, String> prefixMap,
      List<GraphValidationError> errors,
      RelationsMappingProperties relationsMapping,
      String relLocation) {
    return fallback(
        relationFields,
        relationsMapping::getVerbField,
        (rawVerb) ->
            expandOrError(
                rawVerb, prefixMap, relLocation + "." + relationsMapping.getVerbField(), errors),
        relationsMapping::getVerb,
        (verb) ->
            expandOrError(
                verb, prefixMap, "adapter-config:app.graph.relations-mapping.verb", errors));
  }

  private String getString(Map<String, Object> fieldMap, String fieldName) {
    if (fieldMap == null || fieldName == null) return null;
    Object fieldValue = fieldMap.get(fieldName);
    return fieldValue != null ? fieldValue.toString() : null;
  }

  /**
   * Expands all keys and values in the configured {@code relations-mapping.reverse-verb-map} using
   * the effective prefix map, collecting unknown-prefix errors into the shared error list (FR-020).
   *
   * @param prefixMap the effective prefix map for the current provision
   * @param errors shared error list; unknown-prefix failures are appended here
   * @return a new map with both keys and values fully expanded to absolute IRIs
   */
  private Map<String, String> resolveReverseVerbMap(
      Map<String, String> prefixMap, List<GraphValidationError> errors) {
    Map<String, String> raw = graphProperties.getRelationsMapping().getReverseVerbMap();
    if (raw == null || raw.isEmpty()) return Map.of();

    Map<String, String> expanded = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : raw.entrySet()) {
      String expandedKey =
          expandOrError(
              entry.getKey(),
              prefixMap,
              "adapter-config:app.graph.relations-mapping.reverse-verb-map[key]=" + entry.getKey(),
              errors);
      String expandedValue =
          expandOrError(
              entry.getValue(),
              prefixMap,
              "adapter-config:app.graph.relations-mapping.reverse-verb-map[value]="
                  + entry.getValue(),
              errors);
      if (expandedKey != null && expandedValue != null) {
        expanded.put(expandedKey, expandedValue);
      }
    }
    return expanded;
  }

  /**
   * Implements the override mechanism for value retrieval. It first searches the input descriptor
   * for the configured field using getDescriptorField(). If present, it calls getDescriptorField()
   * and returns its value. If not present, it calls process() using the value from getValue()
   *
   * @param rawConceptFields Descriptor raw fields
   * @param getDescriptorField Supplier function which returns the field to search on the descriptor
   * @param ifFieldPresent Function that processes when the field is present on the descriptor. If
   *     called, it will immediately return and won't fall back to getValue/process
   * @param getValue Supplier function which returns the value to be used as fallback mechanism when
   *     the input descriptor doesn't contain the configured field
   * @param process Function that processes the value supplied by getValue()
   * @return The mapped value for this call, either by picking it from the descriptor, or processing
   *     the default value.
   * @param <T> Return type of the function, usually a String
   */
  private <T> T fallback(
      Map<String, Object> rawConceptFields,
      Supplier<String> getDescriptorField,
      Function<@NotBlank String, T> ifFieldPresent,
      Supplier<String> getValue,
      Function<String, T> process) {
    if (getDescriptorField.get() != null) {
      String fieldValue = getString(rawConceptFields, getDescriptorField.get());
      if (fieldValue != null && !fieldValue.isBlank()) {
        return ifFieldPresent.apply(fieldValue);
      }
    }
    if (getValue.get() != null) {
      return process.apply(getValue.get());
    }
    return null;
  }

  /**
   * Expands {@code curie} using the effective prefix map, appending an {@link
   * GraphValidationError#unknownPrefix} to {@code errors} on failure. Returns {@code null} when
   * {@code curie} is null/blank or when expansion fails (error is already recorded).
   */
  private String expandOrError(
      String curie,
      Map<String, String> prefixMap,
      String fieldHint,
      List<GraphValidationError> errors) {
    if (curie == null || curie.isBlank()) return null;
    try {
      return prefixResolver.expand(curie, prefixMap, fieldHint);
    } catch (UnknownPrefixException e) {
      log.error(
          "Unknown prefix '{}' in '{}' while expanding '{}' — add the prefix to app.graph.prefixes or the descriptor context",
          e.getPrefix(),
          fieldHint,
          curie);
      errors.add(GraphValidationError.unknownPrefix(e.getField(), e.getPrefix()));
      return null;
    }
  }
}
