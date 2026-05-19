# Configuration Reference: Ontology Tech Adapter

All adapter configuration lives under the `app.graph` namespace in
`application.yaml`. Changes take effect on restart.

> **Spring Boot map-key syntax**: Spring Boot's `@ConfigurationProperties`
> binding treats `:` as a hierarchy separator. Any map key that contains a
> colon (e.g. a CURIE like `skos:prefLabel`) **must be wrapped in square
> brackets** so Spring preserves it verbatim:
>
> ```yaml
> # ✗ WRONG — Spring silently drops the entry
> properties:
>   "skos:prefLabel": "name"
>
> # ✓ CORRECT
> properties:
>   "[skos:prefLabel]": "name"
> ```
>
> This applies to every map-typed config field: `concept-mapping.properties`,
> `concept-mapping.additional-properties`, `root.properties`,
> `root.additional-properties`, and `relations-mapping.reverse-verb-map`.
> All examples in this document already use the correct bracket notation.

---

## Quick Start — Minimal SKOS Config

If you only need the standard SKOS vocabulary, no configuration is required
beyond setting the preset (which defaults to `skos` anyway):

```yaml
app:
  graph:
    preset: skos
```

The adapter will:
- Map `business_concepts` list entries as `skos:Concept` resources
- Type the root descriptor as `skos:ConceptScheme`
- Use `skos:inScheme` to link concepts to the root
- Use `skos:narrower` as the default relation verb
- Derive all IRIs from the system URN automatically

---

## Full Field Reference

### Top Level

| Field | Type | Default | Required | Description |
|-------|------|---------|----------|-------------|
| `preset` | `skos` \| `custom` | `skos` | No | Vocabulary preset. `skos` injects SKOS defaults; `custom` requires all fields to be configured explicitly. |

---

### `prefixes`

Controls how CURIE values (e.g. `skos:Concept`) are expanded to full IRIs.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `prefixes.literals` | `Map<String, String>` | SKOS preset: `{skos: "http://www.w3.org/2004/02/skos/core#", wb: "https://witboost.com/type#"}` | Static prefix map. User entries are merged on top of preset defaults; user wins on conflict. |
| `prefixes.field` | `String` | `null` | Descriptor field name whose value is read at provision time as a JSON-LD `@context` object. Field-derived prefixes win over `literals`. |

---

### `descriptor`

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `descriptor.business-concepts-path` | `String` | SKOS: `business_concepts` | Dot-notation path from the descriptor root to the list of concept entries (e.g. `concepts` or `data.items`). |
| `descriptor.yaml-ld-path` | `String` | `null` | Activates Config B (pass-through). When set, the adapter reads the JSON-LD payload from this descriptor field and stores it verbatim. |
| `descriptor.yaml-ld-required` | `Boolean` | `false` | When `true` and `yaml-ld-path` is configured but absent in the descriptor, returns a validation error. When `false`, falls through to Config A. |

---

### `descriptor.root`

Controls how the descriptor itself is materialised as an RDF resource (the "root concept"). Only applies when `is-concept: true`.


| Field                        | Type                    | Default                                                                  | Description                                                                                                                                                                                               |
|------------------------------|-------------------------|--------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `root.is-concept`            | `Boolean`               | SKOS: `true`                                                             | When `true`, the descriptor is provisioned as an RDF resource. When `false` or absent, only the concept list is provisioned.                                                                              |
| `root.type`                  | `String` (CURIE or IRI) | SKOS: `skos:ConceptScheme`                                               | RDF class assigned to the root resource. When absent, falls back to `concept-mapping.type`                                                                                                                |
| `root.type-field`            | `String`                | `null`                                                                   | Descriptor field name from which the root's RDF type is read per-deployment. Wins over `root.type` when present and non-blank. When absent, falls back to `concept-mapping.type-field`. |
| `root.verb`                  | `String` (CURIE or IRI) | SKOS: `skos:inScheme`                                                    | RDF predicate linking each child concept to the root resource.                                                                                                                                                                                                                         |
| `root.verb-field`            | `String`                | `null`                                                                   | Descriptor field name from which the root verb is read per-deployment. Wins over `root.verb` when present and non-blank.                                                                                                                                                               |
| `root.iri-field`             | `String`                | `null`                                                                   | Descriptor field name used as the root's subject IRI. When absent, falls back to `concept-mapping.iri-field`. When that is also absent or the field has no value, the IRI is derived from the system URN.                                                                              |
| `root.name-field`            | `String`                | `null`                                                                   | Descriptor field name used as the root's display name (affects IRI derivation when `iri-field` is also absent). When absent, falls back to `concept-mapping.name-field`.                                                                                                               |
| `root.properties`            | `Map<String, String>`   | SKOS: `{"[skos:prefLabel]": "name", "[skos:definition]": "description"}` | Property map for the root concept only. Keys are RDF predicates (CURIE or full IRI); values are descriptor field names.                                                                                   |
| `root.additional-properties` | `Map<String, String>`   | `{}`                                                                     | Merged onto `root.properties` after preset defaults are applied. Entries here win on key conflicts.                                                                                                       |

---

### `concept-mapping`

Controls how each entry in the `business-concepts-path` list is mapped to RDF.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `concept-mapping.type` | `String` (CURIE or IRI) | SKOS: `skos:Concept` | Default RDF class assigned to each concept entry. Overridden per-entry by `type-field`. |
| `concept-mapping.type-field` | `String` | SKOS: `type` | Descriptor field name from which a per-concept RDF type override is read. When present and non-blank on an entry, wins over `concept-mapping.type`. |
| `concept-mapping.iri-field` | `String` | SKOS: `iri` | Descriptor field name whose value is used as the concept's subject IRI. When absent or blank, the IRI is derived from the system URN and the `name-field` value. |
| `concept-mapping.name-field` | `String` | SKOS: `name` | Descriptor field name used as the concept's display name (also used in IRI derivation). |
| `concept-mapping.properties` | `Map<String, String>` | SKOS: `{"[skos:prefLabel]": "name", "[skos:definition]": "description"}` | Property map applied to every concept entry. Keys are RDF predicates; values are descriptor field names. User entries merge on top of preset defaults. |
| `concept-mapping.additional-properties` | `Map<String, String>` | `{}` | Merged onto `concept-mapping.properties` last, after preset defaults and user `properties`. Entries here win on key conflicts. |

---

### `relations-mapping`

Controls how relation sub-entries inside each concept are mapped to RDF.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `relations-mapping.verb` | `String` (CURIE or IRI) | SKOS: `skos:narrower` | Default RDF predicate linking the concept to its related concept. Overridden per-relation by `verb-field`. |
| `relations-mapping.verb-field` | `String` | SKOS: `type` | Relation entry field name from which a per-relation verb override is read. When present and non-blank, wins over `relations-mapping.verb`. Unknown prefix causes a validation error. |
| `relations-mapping.relations-path` | `String` | SKOS: `relations` | Field name within each concept entry that contains its list of relation sub-entries. |
| `relations-mapping.reverse-verb-map` | `Map<String, String>` | SKOS: see below; custom: `{}` | Map from a forward verb IRI (or CURIE) to its inverse verb IRI (or CURIE). When a relation triple `A → verb → B` is emitted and `verb` is a key in this map, the adapter also emits `B → reverseVerb → A`. All keys and values must use registered prefixes or absolute IRIs. |

### Inverse Relation Triples

The `reverse-verb-map` field lets you declare bidirectional RDF semantics at the
adapter level. For each key-value pair, whenever the adapter emits a forward triple:

```
<A>  <verb>        <B>
```

it automatically also emits:

```
<B>  <reverseVerb> <A>
```

This is applied only to concept→concept relations. The `root.verb` link (concept→root)
is **not** subject to reverse-verb-map processing.

**Example**: with `preset: skos` and a descriptor entry `A skos:narrower B`, the graph
will contain all three triples:

| Subject | Predicate | Object |
|---------|-----------|--------|
| `A` | `skos:narrower` | `B` |
| `B` | `skos:broader` | `A` |

The `skos:related` entry is symmetric (`skos:related → skos:related`), so
`A skos:related B` automatically produces `B skos:related A`.

**Validation**: All keys and values are expanded at validate/provision time. An unknown
prefix in either the key or the value causes a structured validation error.

**Merge strategy**: Follows the same rule as other map fields — preset entries form the
baseline; user-configured entries are layered on top (user wins on conflict). To suppress
a preset entry, override it with an empty string or remove it using `additionalProperties`.

---

## SKOS Preset Defaults

When `preset: skos`, the following defaults are applied at startup. Any field
you configure explicitly overrides the corresponding preset default.

```yaml
prefixes:
  literals:
    skos: "http://www.w3.org/2004/02/skos/core#"
    wb:   "https://witboost.com/type#"
descriptor:
  business-concepts-path: business_concepts
  root:
    is-concept: true
    type: "skos:ConceptScheme"
    verb: "skos:inScheme"
    properties:
      "[skos:prefLabel]": "name"
      "[skos:definition]": "description"
concept-mapping:
  type: "skos:Concept"
  iri-field: iri
  name-field: name
  type-field: type
  properties:
    "[skos:prefLabel]": "name"
    "[skos:definition]": "description"
relations-mapping:
  verb: "skos:narrower"
  verb-field: type
  relations-path: relations
  reverse-verb-map:
    "[skos:narrower]": "skos:broader"
    "[skos:broader]": "skos:narrower"
    "[skos:related]": "skos:related"
```

---

## Complete Configuration Reference (all fields)

The following block shows every available field with its default value and a
short inline comment. Use this as a starting point for your own
`application.yaml`.

```yaml
app:
  graph:
    preset: skos          # "skos" or "custom". skos injects SKOS defaults automatically.

    prefixes:
      literals:           # Static prefix → namespace IRI map (always active).
        skos: "http://www.w3.org/2004/02/skos/core#"
        wb:   "https://witboost.com/type#"
        # Add any additional prefixes your ontology requires.
      field: null         # Descriptor field whose value is a JSON-LD @context object.
                          # Prefixes from this field are merged at provision time,
                          # taking priority over literals on conflicts.

    descriptor:
      business-concepts-path: business_concepts  # Dot-notation path to the concept list field.

      yaml-ld-path: null          # If set, enables Config B (YAML-LD pass-through).
                                  # The adapter reads the JSON-LD payload from this
                                  # descriptor field and stores it verbatim.
      yaml-ld-required: false     # When true and yaml-ld-path is set but the field is
                                  # absent in the descriptor, returns a validation error.
                                  # When false, falls through to Config A without error.

      root:
        is-concept: true          # When true, the descriptor itself becomes an RDF resource.
        type: null                # RDF class for the root resource (e.g. "skos:ConceptScheme").
                                  # Falls back to concept-mapping.type when absent.
        type-field: null          # Descriptor field from which the root's RDF type is read
                                  # per-deployment. Falls back to concept-mapping.type-field.
        verb: null                # RDF predicate linking the root to each child concept
                                  # (e.g. "skos:inScheme"). No concept-mapping fallback.
        verb-field: null          # Descriptor field from which the root verb is read
                                  # per-deployment. No concept-mapping fallback.
        iri-field: null           # Descriptor field used as the root's subject IRI.
                                  # Falls back to concept-mapping.iri-field.
        name-field: null          # Descriptor field used as the root's display name.
                                  # Falls back to concept-mapping.name-field.
        properties: {}            # Predicate IRI → descriptor field name map for the root
                                  # resource only. Replaces concept-mapping.properties
                                  # entirely for the root when set.
        additional-properties: {} # Merged onto root.properties (these entries win on
                                  # key conflicts with root.properties).

    concept-mapping:
      type: null                  # RDF class assigned to every concept (required in custom
                                  # preset). E.g. "skos:Concept" or "owl:Class".
      iri-field: iri              # Descriptor field containing an explicit subject IRI.
                                  # When absent or blank, IRI is derived from the system URN
                                  # and the name-field value.
      name-field: name            # Descriptor field used as the concept's display name and
                                  # as input to IRI derivation when iri-field is absent.
      type-field: type            # Descriptor field from which a per-concept RDF type is
                                  # read. Overrides concept-mapping.type for that concept.
      properties: {}              # Map of RDF predicate IRI (or CURIE in square brackets)
                                  # to descriptor field name. Applied to every concept.
                                  # E.g. "[skos:prefLabel]": "name"
      additional-properties: {}   # Merged onto properties (these entries win on key
                                  # conflicts with preset-supplied properties).

    relations-mapping:
      verb: null                  # Default RDF predicate for all relations
                                  # (e.g. "skos:narrower"). Required in custom preset.
      verb-field: type            # Descriptor field from which a per-relation verb is read.
                                  # Overrides relations-mapping.verb for that relation.
      relations-path: relations   # Field name inside each concept entry that holds the
                                  # list of relation objects.
      reverse-verb-map: {}        # Map from a forward verb IRI/CURIE to its inverse verb.
                                  # When A → verb → B is emitted and verb is a key here,
                                  # B → reverseVerb → A is also emitted in the same partition.
                                  # The root.verb link is never subject to reverse processing.
                                  # E.g. "[skos:narrower]": "skos:broader"
```

---

## Worked Examples

### Example 1: SKOS Minimal

**Config** (`application.yaml`):

```yaml
app:
  graph:
    preset: skos
```

**Sample descriptor**:

```yaml
id: urn:dmb:dp:domain:registry-a:0
name: Registry A
description: Test description
business_concepts:
  - name: Concept A
    description: This is Concept A
  - name: Concept B
    description: This is Concept B
    relations:
      - name: Concept A
```

**Resulting triples** (representative):

| Subject | Predicate | Object |
|---------|-----------|--------|
| `wb:domain-registry-a-0` | `rdf:type` | `skos:ConceptScheme` |
| `wb:domain-registry-a-0` | `skos:prefLabel` | "Registry A" |
| `wb:domain-registry-a-0` | `skos:definition` | "Test description" |
| `wb:domain-registry-a-0-concept-a` | `rdf:type` | `skos:Concept` |
| `wb:domain-registry-a-0-concept-a` | `skos:prefLabel` | "Concept A" |
| `wb:domain-registry-a-0-concept-a` | `skos:definition` | "This is Concept A" |
| `wb:domain-registry-a-0-concept-a` | `skos:inScheme` | `wb:domain-registry-a-0` |
| `wb:domain-registry-a-0-concept-b` | `rdf:type` | `skos:Concept` |
| `wb:domain-registry-a-0-concept-b` | `skos:narrower` | `wb:domain-registry-a-0-concept-a` |
| `wb:domain-registry-a-0-concept-a` | `skos:broader` | `wb:domain-registry-a-0-concept-b` |
| `wb:domain-registry-a-0-concept-b` | `skos:inScheme` | `wb:domain-registry-a-0` |

---

### Example 2: SKOS with Overrides

Add a custom property and use per-relation verb overrides:

```yaml
app:
  graph:
    preset: skos
    concept-mapping:
      additional-properties:
        "[wb:category]": "category"
    relations-mapping:
      verb-field: type   # already the SKOS default; shown for clarity
```

**Sample descriptor** (partial):

```yaml
business_concepts:
  - name: Concept C
    category: Finance
    relations:
      - name: Concept B
        type: "skos:broader"    # overrides default skos:narrower
      - name: Concept D
        type: "skos:related"    # overrides default skos:narrower
```

**Additional triples emitted**:

| Subject | Predicate | Object |
|---------|-----------|--------|
| `wb:...-concept-c` | `wb:category` | "Finance" |
| `wb:...-concept-c` | `skos:broader` | `wb:...-concept-b` |
| `wb:...-concept-b` | `skos:narrower` | `wb:...-concept-c` |
| `wb:...-concept-c` | `skos:related` | `wb:...-concept-d` |
| `wb:...-concept-d` | `skos:related` | `wb:...-concept-c` |

---

### Example 3: Custom OWL Ontology

```yaml
app:
  graph:
    preset: custom
    prefixes:
      literals:
        owl:  "http://www.w3.org/2002/07/owl#"
        rdfs: "http://www.w3.org/2000/01/rdf-schema#"
        wb:   "https://witboost.com/type#"
    descriptor:
      business-concepts-path: business_concepts
      root:
        is-concept: true
        type: "owl:Ontology"
        verb: "rdfs:isDefinedBy"
    concept-mapping:
      type: "owl:ObjectProperty"
      name-field: concept_name
      type-field: type
      properties:
        "[rdfs:label]":   "label"
        "[rdfs:comment]": "comment"
    relations-mapping:
      verb: "rdfs:isDefinedBy"
      verb-field: type
      relations-path: relations
```

**Sample descriptor** (partial):

```yaml
id: urn:dmb:dp:domain:express:0
name: express
business_concepts:
  - concept_name: Company
    label: Company
    type: "owl:Class"
    comment: A company or organization
  - concept_name: Product
    label: Product
    type: "owl:Class"
    comment: A product that may be sent to a company
  - concept_name: send
    label: Send to
    comment: Send a product to a company
    relations:
      - concept_name: Company
        type: "rdfs:domain"
      - concept_name: Product
        type: "rdfs:range"
```

**Resulting RDF triples**:

| Subject | Predicate | Object |
|---------|-----------|--------|
| `wb:domain-express-0` | `rdf:type` | `owl:Ontology` |
| `wb:domain-express-0` | `rdfs:isDefinedBy` | `wb:domain-express-0` |
| `wb:domain-express-0-company` | `rdf:type` | `owl:Class` |
| `wb:domain-express-0-company` | `rdfs:label` | "Company" |
| `wb:domain-express-0-company` | `rdfs:comment` | "A company or organization" |
| `wb:domain-express-0-company` | `rdfs:isDefinedBy` | `wb:domain-express-0` |
| `wb:domain-express-0-product` | `rdf:type` | `owl:Class` |
| `wb:domain-express-0-product` | `rdfs:label` | "Product" |
| `wb:domain-express-0-product` | `rdfs:comment` | "A product that may be sent to a company" |
| `wb:domain-express-0-product` | `rdfs:isDefinedBy` | `wb:domain-express-0` |
| `wb:domain-express-0-send` | `rdf:type` | `owl:ObjectProperty` |
| `wb:domain-express-0-send` | `rdfs:label` | "Send to" |
| `wb:domain-express-0-send` | `rdfs:comment` | "Send a product to a company" |
| `wb:domain-express-0-send` | `rdfs:isDefinedBy` | `wb:domain-express-0` |
| `wb:domain-express-0-send` | `rdfs:domain` | `wb:domain-express-0-company` |
| `wb:domain-express-0-send` | `rdfs:range` | `wb:domain-express-0-product` |

Note: `Company` and `Product` use `type: "owl:Class"` from `type-field`, overriding the
default `owl:ObjectProperty`. `send` has no explicit `type` so it falls back to
`concept-mapping.type`. Relation targets are resolved by name to the sibling concept IRIs.

---

### Example 4: YAML-LD Pass-through (Config B)

```yaml
app:
  graph:
    preset: skos
    descriptor:
      yaml-ld-path: graph_definition
      yaml-ld-required: false
```

**Sample descriptor with field present** → stored verbatim, concept-mapping skipped:

```yaml
id: urn:dmb:dp:domain:analytics:0
name: Analytics
graph_definition:
  "@context":
    owl: "http://www.w3.org/2002/07/owl#"
  "@graph":
    - "@id": "http://example.com/analytics"
      "@type": "owl:Ontology"
```

**Same config, descriptor without `graph_definition`** → falls through to
Config A (SKOS preset maps `business_concepts` as normal).

---

## Common Validation Errors

| Error message | Cause | Fix |
|---------------|-------|-----|
| `Unknown prefix 'X' in concept-mapping.type value 'X:Y'` | CURIE uses prefix not registered in `prefixes.literals` | Add `X: "http://..."` to `prefixes.literals` |
| `Unknown prefix 'X' in relation entry type field 'X:Y'` | A relation entry's `type` field uses an unregistered prefix | Register the prefix or correct the CURIE |
| `IRI collision: 'wb:...' is used by more than one concept` | Two concept entries produce the same derived IRI (same name) | Give each concept a unique `name` or set explicit `iri-field` values |
| `Cannot resolve type for concept '...'` | `type-field` is configured but the concept entry has no matching field | Add the field to the entry or remove the `type-field` override |
| `Field 'X' required by yaml-ld-path is absent in descriptor` | `yaml-ld-required: true` and the pass-through field is missing | Add the field to the descriptor or set `yaml-ld-required: false` |
| `Unknown prefix 'X' in adapter-config:app.graph.descriptor.root.type` | `root.type` uses an unregistered prefix | Register the prefix or fix the CURIE in config |

