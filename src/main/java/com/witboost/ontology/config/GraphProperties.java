package com.witboost.ontology.config;

import com.witboost.ontology.application.PresetMergeService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Top-level configuration for the graph adapter. Bound from the {@code app.graph.*} namespace.
 *
 * <p>When {@code preset = skos}, {@link PresetMergeService} fills in SKOS defaults for any field
 * left null. When {@code preset = custom}, every field must be configured explicitly.
 */
@ConfigurationProperties(prefix = "app.graph")
@Data
public class GraphProperties {

  /** Which vocabulary preset to apply. Defaults to {@code skos}. */
  private GraphPreset preset = GraphPreset.SKOS;

  /** Prefix declarations used to expand prefixed IRIs in config and descriptors. */
  private PrefixProperties prefixes = new PrefixProperties();

  /** Controls how the adapter reads the incoming descriptor. */
  private DescriptorProperties descriptor = new DescriptorProperties();

  /** Mapping rules for individual concept entries in the descriptor. */
  private ConceptMappingProperties conceptMapping = new ConceptMappingProperties();

  /** Mapping rules for relation entries inside each concept. */
  private RelationsMappingProperties relationsMapping = new RelationsMappingProperties();

  // ── Nested config classes ─────────────────────────────────────────────────

  @Data
  public static class PrefixProperties {
    /**
     * Static prefix map: label → namespace IRI. E.g. {@code skos:
     * "http://www.w3.org/2004/02/skos/core#"}.
     */
    private Map<String, String> literals = new LinkedHashMap<>();

    /**
     * Descriptor field name whose value is read at provision time as a JSON-LD {@code @context}
     * object. Prefixes from this field are merged with {@code literals}; field-derived prefixes win
     * on conflict.
     */
    private String field;
  }

  @Data
  public static class DescriptorProperties {
    /**
     * Dot-notation path from the descriptor root to the list of concept entries. E.g. {@code
     * business_concepts}.
     */
    private String businessConceptsPath;

    /**
     * When set, the adapter reads the JSON-LD payload from this descriptor field and stores it
     * verbatim (Config B / YAML-LD pass-through). Concept-mapping logic is bypassed.
     */
    private String yamlLdPath;

    /**
     * When {@code true} and {@code yamlLdPath} is set but the referenced field is absent, return a
     * validation error instead of falling back to Config A. Default: {@code false}.
     */
    private boolean yamlLdRequired = false;

    /**
     * Override settings that apply to the provisioned descriptor itself (the root concept). When
     * {@code isConcept = true} the descriptor is materialised as an RDF resource using these
     * settings.
     */
    private RootProperties root = new RootProperties();
  }

  @Data
  public static class RootProperties {
    /**
     * When {@code true}, the descriptor itself is provisioned as an RDF resource (root concept).
     */
    private Boolean isConcept;

    /** RDF class assigned to the root concept. Overrides {@code conceptMapping.type}. */
    private String type;

    /**
     * Descriptor field name from which the root concept's RDF type is read at provision time. When
     * present and non-blank, wins over {@code type}.
     */
    private String typeField;

    /**
     * Fixed relation verb used to link child concepts to the root. Overrides {@code
     * relationsMapping.verb}.
     */
    private String verb;

    /**
     * Descriptor field name from which the relation verb is read per-relation. Overrides {@code
     * relationsMapping.verbField}.
     */
    private String verbField;

    /** Descriptor field name used as the root concept's subject IRI. */
    private String iriField;

    /** Descriptor field name used as the root concept's display name. */
    private String nameField;

    /** Property map overrides for the root concept only. */
    private Map<String, String> properties = new LinkedHashMap<>();

    /** Additional properties merged onto {@code properties} for the root concept. */
    private Map<String, String> additionalProperties = new LinkedHashMap<>();
  }

  @Data
  public static class ConceptMappingProperties {
    /** RDF class assigned to each concept entry. E.g. {@code skos:Concept} or {@code owl:Class}. */
    private String type;

    /**
     * Descriptor field name whose value is read as the concept's subject IRI. When absent or null
     * for a given entry, the IRI is derived from the system URN.
     */
    private String iriField;

    /**
     * Descriptor field name from which the per-concept RDF type override is read. When present,
     * wins over {@code type}.
     */
    private String typeField;

    /** Descriptor field name used as the concept's display name (used in IRI derivation). */
    private String nameField;

    /**
     * Map from RDF property IRI (or prefixed shorthand) to descriptor field name. E.g. {@code
     * skos:prefLabel → name}.
     */
    private Map<String, String> properties = new LinkedHashMap<>();

    /**
     * Additional properties merged onto {@code properties} after preset defaults are applied.
     * Entries in this map win on key conflicts with {@code properties}.
     */
    private Map<String, String> additionalProperties = new LinkedHashMap<>();
  }

  @Data
  public static class RelationsMappingProperties {
    /**
     * Fixed relation verb applied to every relation entry when {@code verbField} is not set. E.g.
     * {@code skos:narrower}.
     */
    private String verb;

    /**
     * Descriptor field name from which the relation verb is read per-relation entry. When present,
     * wins over {@code verb}.
     */
    private String verbField;

    /**
     * Dot-notation path from each concept entry to its list of relation sub-entries. E.g. {@code
     * relations}.
     */
    private String relationsPath;

    /**
     * Map from a forward relation verb IRI (or CURIE) to its inverse verb IRI (or CURIE). When a
     * relation triple {@code A → verb → B} is emitted and {@code verb} appears as a key in this
     * map, the adapter also emits the inverse triple {@code B → reverseVerb → A} (FR-020).
     *
     * <p>The {@code preset: skos} preset pre-populates this map with the three standard SKOS
     * bidirectional pairs: {@code skos:narrower → skos:broader}, {@code skos:broader →
     * skos:narrower}, {@code skos:related → skos:related}. Custom presets default to an empty map
     * (no inverse triples emitted).
     *
     * <p>User-configured entries are merged on top of preset defaults; user entries win on
     * conflict. All keys and values are validated against the resolved prefix map at
     * validate/provision time.
     */
    private Map<String, String> reverseVerbMap = new LinkedHashMap<>();
  }
}
