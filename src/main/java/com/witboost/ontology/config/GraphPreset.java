package com.witboost.ontology.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Selects which concept-mapping strategy the adapter uses when no yaml-ld-path is active.
 *
 * <p>Each constant carries a {@link GraphPresetDefinition} that encapsulates its default values. To
 * add a new preset, create a record implementing {@link GraphPresetDefinition} and add a new
 * constant here — no other classes need to change.
 */
public enum GraphPreset {
  SKOS(new SkosPresetDefinition()),
  CUSTOM(new CustomPresetDefinition());

  private final GraphPresetDefinition definition;

  GraphPreset(GraphPresetDefinition definition) {
    this.definition = definition;
  }

  /** Returns the default-value definition for this preset. */
  public GraphPresetDefinition definition() {
    return definition;
  }

  @JsonValue
  public String toValue() {
    return name().toLowerCase();
  }

  @JsonCreator
  public static GraphPreset fromValue(String value) {
    if (value == null) return SKOS;
    return switch (value.toLowerCase()) {
      case "skos" -> SKOS;
      case "custom" -> CUSTOM;
      default ->
          throw new IllegalArgumentException(
              "Unknown graph preset: '" + value + "'. Supported values: skos, custom");
    };
  }
}
