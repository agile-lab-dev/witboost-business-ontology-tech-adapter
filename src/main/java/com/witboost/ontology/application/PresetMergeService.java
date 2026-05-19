package com.witboost.ontology.application;

import com.witboost.ontology.config.GraphPresetDefinition;
import com.witboost.ontology.config.GraphProperties;
import com.witboost.ontology.config.GraphProperties.ConceptMappingProperties;
import com.witboost.ontology.config.GraphProperties.RootProperties;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Merges preset defaults into {@link GraphProperties} at startup (FR-013, FR-014, FR-015).
 *
 * <p>The merge strategy is preset-agnostic: it retrieves the {@link GraphPresetDefinition}
 * associated with the configured preset and applies its values as null-fallbacks for scalars and as
 * a baseline for maps. User-supplied configuration always wins on conflict.
 *
 * <ul>
 *   <li>Scalar fields: null-fallback — preset value is used only when the user did not configure
 *       the field.
 *   <li>Map fields ({@code prefixes.literals}, {@code conceptMapping.properties}): start from
 *       preset defaults, then {@code putAll} user-supplied entries — user values win on conflict.
 *   <li>{@code additionalProperties}: merged onto {@code properties} last, with {@code
 *       additionalProperties} winning on conflict. This step is always applied regardless of
 *       preset.
 * </ul>
 *
 * <p>To add a new preset, create a record implementing {@link GraphPresetDefinition} and register
 * it in {@link com.witboost.ontology.config.GraphPreset}. This class requires no changes.
 */
@Service
@Slf4j
public class PresetMergeService {

  private final GraphProperties props;

  public PresetMergeService(GraphProperties props) {
    this.props = props;
  }

  @PostConstruct
  public void init() {
    GraphPresetDefinition presetDefinition = props.getPreset().definition();
    applyDefaults(presetDefinition);
    mergeAdditionalProperties();
    log.info(
        "PresetMergeService: preset={}, conceptType={}, relationsVerb={}",
        props.getPreset(),
        props.getConceptMapping().getType(),
        props.getRelationsMapping().getVerb());
  }

  private void applyDefaults(GraphPresetDefinition presetDefinition) {
    // prefixes.literals: preset first, then user entries override
    Map<String, String> mergedPrefixes = new LinkedHashMap<>(presetDefinition.prefixLiterals());
    mergedPrefixes.putAll(props.getPrefixes().getLiterals());
    props.getPrefixes().setLiterals(mergedPrefixes);

    // descriptor.businessConceptsPath
    if (props.getDescriptor().getBusinessConceptsPath() == null) {
      props.getDescriptor().setBusinessConceptsPath(presetDefinition.businessConceptsPath());
    }

    // descriptor.root: scalar null-fallbacks
    RootProperties root = props.getDescriptor().getRoot();
    if (root.getIsConcept() == null) root.setIsConcept(presetDefinition.rootIsConcept());
    if (root.getType() == null) root.setType(presetDefinition.rootType());
    if (root.getTypeField() == null) root.setTypeField(presetDefinition.rootTypeField());
    if (root.getVerb() == null) root.setVerb(presetDefinition.rootVerb());
    if (root.getVerbField() == null) root.setVerbField(presetDefinition.rootVerbField());
    if (root.getIriField() == null) root.setIriField(presetDefinition.rootIriField());
    if (root.getNameField() == null) root.setNameField(presetDefinition.rootNameField());

    // root.properties: preset first, then user entries override
    Map<String, String> mergedRootProperties =
        new LinkedHashMap<>(presetDefinition.rootProperties());
    mergedRootProperties.putAll(root.getProperties());
    root.setProperties(mergedRootProperties);

    // conceptMapping: scalar null-fallbacks
    ConceptMappingProperties conceptMapping = props.getConceptMapping();
    if (conceptMapping.getType() == null) conceptMapping.setType(presetDefinition.conceptType());
    if (conceptMapping.getIriField() == null)
      conceptMapping.setIriField(presetDefinition.conceptIriField());
    if (conceptMapping.getNameField() == null)
      conceptMapping.setNameField(presetDefinition.conceptNameField());
    if (conceptMapping.getTypeField() == null)
      conceptMapping.setTypeField(presetDefinition.conceptTypeField());

    // conceptMapping.properties: preset first, then user entries override
    Map<String, String> mergedProperties =
        new LinkedHashMap<>(presetDefinition.conceptProperties());
    mergedProperties.putAll(conceptMapping.getProperties());
    conceptMapping.setProperties(mergedProperties);

    // relationsMapping: scalar null-fallbacks
    if (props.getRelationsMapping().getVerb() == null) {
      props.getRelationsMapping().setVerb(presetDefinition.relationsVerb());
    }
    if (props.getRelationsMapping().getVerbField() == null) {
      props.getRelationsMapping().setVerbField(presetDefinition.relationsVerbField());
    }
    if (props.getRelationsMapping().getRelationsPath() == null) {
      props.getRelationsMapping().setRelationsPath(presetDefinition.relationsPath());
    }

    // relationsMapping.reverseVerbMap: preset first, then user entries override (FR-020)
    Map<String, String> mergedReverseVerbMap =
        new LinkedHashMap<>(presetDefinition.relationsReverseVerbMap());
    mergedReverseVerbMap.putAll(props.getRelationsMapping().getReverseVerbMap());
    props.getRelationsMapping().setReverseVerbMap(mergedReverseVerbMap);
  }

  private void mergeAdditionalProperties() {
    // conceptMapping.additionalProperties merged onto properties (additionalProperties win)
    ConceptMappingProperties conceptMapping = props.getConceptMapping();
    if (!conceptMapping.getAdditionalProperties().isEmpty()) {
      conceptMapping.getProperties().putAll(conceptMapping.getAdditionalProperties());
    }

    // root.additionalProperties merged onto root.properties
    RootProperties root = props.getDescriptor().getRoot();
    if (!root.getAdditionalProperties().isEmpty()) {
      root.getProperties().putAll(root.getAdditionalProperties());
    }
  }
}
