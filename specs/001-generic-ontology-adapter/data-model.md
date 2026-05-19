# Data Model: Generic Ontology Adapter

**Date**: 2026-07-07 | **Plan**: [plan.md](plan.md)

## Entities

### GraphPreset (Enum)

Discriminates the mapping strategy applied during provisioning.

| Value | Description |
|-------|-------------|
| `SKOS` | Applies SKOS preset defaults; backward-compatible with pre-feature behaviour |
| `CUSTOM` | No preset; all mapping fields must be explicitly configured |

**Validation**: Must be one of the declared values. Parsing from config is
case-insensitive (`skos`, `SKOS`, `Skos` all accepted).

### GraphProperties (Configuration POJO)

Root config object bound to `app.graph.*` via `@ConfigurationProperties`.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `preset` | `GraphPreset` | — (required) | Mapping strategy selector |
| `prefixes` | `PrefixProperties` | (see below) | Prefix declaration config |
| `descriptor` | `DescriptorProperties` | (see below) | Descriptor parsing config |
| `conceptMapping` | `ConceptMappingProperties` | (see below) | Concept type/field mapping |
| `relationsMapping` | `RelationsMappingProperties` | (see below) | Relation verb/field mapping |

### PrefixProperties

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `literals` | `Map<String, String>` | `{wb: "https://witboost.com/type#"}` | Static prefix → namespace IRI map |
| `field` | `String` | `null` | Descriptor field name containing a `@context` object |

**Merge rule** (FR-007): At provision time, `literals` and field-derived
prefixes are merged into a single resolved prefix map. Field-derived values
win on conflict.

### DescriptorProperties

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `root` | `RootProperties` | (see below) | Root concept config |
| `businessConceptsPath` | `String` | `"business_concepts"` | Dot-notation path to concept list |
| `yamlLdPath` | `String` | `null` | If set, enables Config B pass-through |
| `yamlLdRequired` | `boolean` | `false` | Strict mode for yaml-ld-path |

### RootProperties

Configures whether the descriptor itself becomes an RDF resource. All fields
are optional overrides of the top-level `conceptMapping`/`relationsMapping`;
a null value means "fall back to the corresponding top-level value."

**Design note**: Although `RootProperties` mirrors a subset of fields from
`ConceptMappingProperties` and `RelationsMappingProperties`, it is intentionally
**not** a subclass of either. Reasons:
1. Java has no multiple inheritance of classes; extending one and composing the
   other would be asymmetric.
2. The semantics differ: `ConceptMappingProperties.type` is the default for
   *all* concepts; `RootProperties.type` is an override for *the root resource
   only*, with explicit null-fallback behaviour. Same name, different role —
   inheritance would imply IS-A, which is incorrect.
3. Spring `@ConfigurationProperties` inheritance is invisible to operators; the
   YAML path is unchanged regardless of the Java class hierarchy, so inheritance
   adds no operator benefit.
The flat override POJO is the clearest model. If duplication between these
classes becomes a maintenance burden, extract a `MappingOverride` value record
used by `PresetMergeService` — but keep the config POJOs separate.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `isConcept` | `boolean` | `false` | Whether to materialise the descriptor as an RDF resource |
| `type` | `String` | `null` | RDF class for the root resource (falls back to `conceptMapping.type`) |
| `verb` | `String` | `null` | Relation verb linking concepts to root (falls back to `relationsMapping.verb`) |
| `verbField` | `String` | `null` | Per-concept verb field override for root link |
| `iriField` | `String` | `null` | IRI field override for root resource |
| `nameField` | `String` | `null` | Name field override for root resource |
| `properties` | `Map<String, String>` | `{}` | Property mappings override for root |
| `additionalProperties` | `Map<String, String>` | `{}` | Extra properties merged onto root.properties |

### ConceptMappingProperties

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `type` | `String` | `null` (required in custom) | RDF class for concepts |
| `iriField` | `String` | `"iri"` | Concept field containing explicit IRI |
| `nameField` | `String` | `"name"` | Concept field for display name |
| `typeField` | `String` | `"type"` | Per-concept RDF type override field |
| `properties` | `Map<String, String>` | `{}` | Predicate IRI → descriptor field name |
| `additionalProperties` | `Map<String, String>` | `{}` | Merged onto properties (FR-014) |

### RelationsMappingProperties

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `verb` | `String` | `null` (required in custom) | Default relation predicate IRI |
| `verbField` | `String` | `"type"` | Per-relation verb override field |
| `relationsPath` | `String` | `"relations"` | Field name for relation list in concepts |

### ConceptEntry (Domain Record)

Typed representation of a parsed concept from the descriptor. Replaces the
current `Map<String, Object>` raw access pattern.

| Field | Type | Description |
|-------|------|-------------|
| `iri` | `String` | Resolved subject IRI (derived or explicit, after prefix expansion) |
| `name` | `String` | Display name from `nameField` |
| `type` | `String` | RDF class IRI (from `typeField` override or `conceptMapping.type`) |
| `properties` | `Map<String, String>` | Resolved property predicate IRI → literal value |
| `relations` | `List<RelationEntry>` | Parsed relations |
| `rawFields` | `Map<String, Object>` | Original descriptor fields (for property resolution) |

### RelationEntry (Domain Record)

| Field | Type | Description |
|-------|------|-------------|
| `targetIri` | `String` | Resolved target IRI (derived from name or explicit) |
| `verb` | `String` | Resolved predicate IRI (from `verbField` override or default verb) |

### GraphPartition (Domain Record)

Represents one system's graph data in the database.

| Field | Type | Description |
|-------|------|-------------|
| `systemUrn` | `String` | Primary key — the Witboost system URN |
| `content` | `String` | Serialised JSON-LD string |
| `updatedAt` | `Instant` | Last provision/update timestamp |

### GraphValidationError (Domain Class)

Structured validation error returned by `POST /validate`.

| Field | Type | Description |
|-------|------|-------------|
| `field` | `String` | Config key or descriptor field path where the error was found |
| `message` | `String` | Human-readable error description |
| `prefix` | `String` | (optional) The unrecognised prefix label, if applicable |

## Database Schema

```sql
CREATE TABLE graph_state (
    system_urn  VARCHAR     PRIMARY KEY,
    content     TEXT        NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

## Relationships

```
GraphProperties
  ├── preset: GraphPreset
  ├── prefixes: PrefixProperties
  ├── descriptor: DescriptorProperties
  │     ├── root: RootProperties
  │     ├── yamlLdPath → triggers Config B
  │     └── businessConceptsPath → locates concept list
  ├── conceptMapping: ConceptMappingProperties
  │     └── (properties + additionalProperties) → merged by PresetMergeService
  └── relationsMapping: RelationsMappingProperties

DataProductDescriptor
  ├── id (system URN) → IriDerivationService → root IRI
  ├── extraFields[businessConceptsPath] → List<ConceptEntry>
  └── extraFields[yamlLdPath] → raw JSON-LD (Config B)

ConceptEntry
  ├── iri → subject in RDF triple
  ├── type → rdf:type object
  ├── properties → property triples
  └── relations → List<RelationEntry>
        └── (targetIri, verb) → relation triple

GraphPartition
  └── system_urn → graph_state table PK
```

## State Transitions

### Provision Lifecycle (per system URN)

```
[Not Provisioned] ──provision──→ [Provisioned]
     ↑                                │
     │                                │ provision (re-provision)
     │                                ↓
     │                          [Provisioned] (replaced)
     │                                │
     └────────unprovision─────────────┘
```

- **Not Provisioned → Provisioned**: INSERT into `graph_state` (or UPSERT).
  Cache invalidated.
- **Provisioned → Provisioned**: UPSERT replaces the row atomically. Cache
  invalidated.
- **Provisioned → Not Provisioned**: DELETE from `graph_state`. Cache
  invalidated.

### Config Resolution Pipeline

```
[Raw Config] → PresetMergeService → [Resolved Config]
                    │
                    ├── preset == SKOS? → merge SkosPreset defaults
                    └── preset == CUSTOM? → no preset; validate required fields
```
