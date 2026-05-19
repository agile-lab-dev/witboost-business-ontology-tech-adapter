package com.witboost.ontology.config;

/**
 * Preset definition for a fully user-defined mapping with no built-in defaults.
 *
 * <p>All methods delegate to the {@link GraphPresetDefinition} interface defaults, which return
 * {@code null} for scalars and an empty map for collections. This signals to {@link
 * com.witboost.ontology.application.PresetMergeService} that no automatic defaults should be
 * applied — the user is responsible for providing every required field explicitly in their {@code
 * application.yaml}.
 *
 * <p>Use this preset when targeting a custom ontology (OWL, RDFS, Dublin Core, domain-specific
 * vocabularies, etc.) that does not align with any built-in preset.
 */
public record CustomPresetDefinition() implements GraphPresetDefinition {
  // All methods inherit the null/empty defaults from GraphPresetDefinition.
}
