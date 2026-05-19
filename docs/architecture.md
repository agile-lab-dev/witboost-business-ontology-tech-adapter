# Architecture: Ontology Tech Adapter

## Overview

The Ontology Tech Adapter is a Witboost provisioner and KGM (Knowledge
Graph Manager) source adapter. It receives YAML descriptors at provision time
and stores an RDF graph (in JSON-LD) that is later queried through a dedicated
read endpoint.

The adapter supports two interchangeable storage strategies, selected
automatically at provision time based on the descriptor contents:

- **Config A — YAML-to-RDF mapping**: A configurable mapping engine reads the
  descriptor's concept list and emits RDF triples according to a preset
  (SKOS, OWL, custom vocabulary).
- **Config B — YAML-LD pass-through**: When a designated descriptor field
  contains an embedded JSON-LD payload, it is stored verbatim without any
  concept-mapping logic.

---

## Config A vs Config B Decision Flow

```
POST /v1/provision  ──►  PassthroughEngine.extractYamlLd()
                              │
                    ┌─────────┴──────────┐
                    │                    │
              yaml-ld-path         yaml-ld-path
              field present         field absent
                    │                    │
                    ▼                    ▼
           Store JSON-LD        yaml-ld-required?
             verbatim           ┌────────┴────────┐
               (Config B)       │ true            │ false
                                ▼                 ▼
                         Return FAILED      MappingEngine.parse()
                                            Serialise → JSON-LD
                                            Store partition
                                              (Config A)
```

---

## Per-System Storage Model

Each provisioned system occupies one row in the `graph_state` table, keyed by
`system_urn`. The read endpoint (`GET /v1/graph/jsonld`) merges all rows into a
single JSON-LD document before returning.

```
┌─────────────────────────────────────────────┐
│                  graph_state                │
├──────────────────────┬──────────────────────┤
│  system_urn (PK)     │  content (JSON-LD)   │
├──────────────────────┼──────────────────────┤
│ urn:dmb:dp:dom:a:0   │ {"@graph": [...]}    │
│ urn:dmb:dp:dom:b:0   │ {"@graph": [...]}    │
└──────────────────────┴──────────────────────┘
                         │
                         ▼  GET /v1/graph/jsonld
                  GraphCacheService
                  (merge all partitions,
                   cache until next write)
```

Unprovision removes the row for the target system. Other systems are
unaffected.

---

## Preset System

The adapter is configured with a `preset` that supplies vocabulary defaults.
At startup, `PresetMergeService` merges the preset's defaults into
`GraphProperties` following a layered priority:

```
additionalProperties  (highest — always wins)
      ▲
user application.yaml properties
      ▲
preset defaults       (lowest — baseline)
```

Scalar fields use null-fallback (user value kept if already set). Map fields
(`prefixes.literals`, `concept-mapping.properties`, `descriptor.root.properties`)
start from the preset and user entries are layered on top.

Built-in presets:

| Preset | Use case |
|--------|----------|
| `skos` | W3C SKOS concept schemes and concept hierarchies (default) |
| `custom` | Any other vocabulary (OWL, RDFS, domain-specific) — all fields must be configured explicitly |

---

## IRI Derivation Algorithm

When a concept entry has no explicit `iri-field` value, the adapter derives a
deterministic HTTP IRI from the system URN and the concept name.

**Worked example**:

```
Input URN:   urn:dmb:dp:domain:registry-a:0
Step 1:      Strip "urn:dmb:dp:" prefix  →  domain:registry-a:0
Step 2:      Replace ":" with "-"        →  domain-registry-a-0
Step 3:      Expand with wb: prefix      →  https://witboost.com/type#domain-registry-a-0
                                            ↑ This is the root IRI

Concept:     name = "Concept A"
Step 4:      Slugify name               →  concept-a
Step 5:      Root slug + "-" + slug     →  domain-registry-a-0-concept-a
Step 6:      Expand with wb: prefix     →  https://witboost.com/type#domain-registry-a-0-concept-a
```

If a concept provides an `iri-field` value, it is expanded against the
configured prefix map directly. Values that are already full HTTP IRIs are
used as-is. Witboost URN values (starting with `urn:`) fall back to derived
IRIs with a warning log.

---

## Endpoint List

| Method | Path | Description |
|--------|------|-------------|
| POST | `/v1/validate` | Validate a descriptor without storing |
| POST | `/v1/provision` | Provision (or re-provision) a system |
| POST | `/v1/unprovision` | Remove a system partition |
| GET  | `/v1/graph/jsonld` | Retrieve the merged graph as JSON-LD |

Endpoint behaviour by HTTP status:

| Status | When | Endpoints |
|--------|------|-----------|
| `200 OK` | Successful provision/unprovision or valid descriptor | all |
| `200 OK` with `valid: false` | Descriptor failed validation | `/v1/validate` only |
| `400 Bad Request` (`RequestValidationError`) | Descriptor invalid during provision/unprovision | `/v1/provision`, `/v1/unprovision` |
| `500 Internal Server Error` | Unexpected runtime failure | all |

#### Provision success response body

On a successful provision, both `info.publicInfo` and `info.privateInfo` contain
a `ProvisionResult` payload:

```json
{
  "status": "COMPLETED",
  "result": "OK",
  "info": {
    "publicInfo": {
      "systemUrn": "urn:dmb:dp:domain:registry-a:0",
      "strategy": "mapping",
      "provisionedIris": [
        "https://witboost.com/type#domain-registry-a-0",
        "https://witboost.com/type#domain-registry-a-0-account"
      ]
    },
    "privateInfo": { "...same as publicInfo..." }
  }
}
```

`strategy` is either `mapping` (Config A) or `passthrough` (Config B). For
`passthrough`, `provisionedIris` contains only the system URN because individual
IRIs are embedded inside the opaque JSON-LD payload and not parsed by the adapter.

---

## Component Map

```
interfaces/rest/
  TechAdapterController      ← HTTP entry points; catches GraphValidationException
  GlobalExceptionHandler     ← Spring-level errors (malformed JSON, missing fields)

application/
  MappingEngine              ← Config A: descriptor → ParseResult → ConceptEntry list
  PassthroughEngine          ← Config B: descriptor → Optional<JSON-LD string>
  GraphService               ← Orchestrates provision / unprovision / read
  GraphCacheService          ← In-memory merged graph cache (invalidated on write)
  PresetMergeService         ← Merges preset defaults into GraphProperties at startup
  PrefixResolver             ← Expands CURIE values; throws UnknownPrefixException
  IriDerivationService       ← Derives deterministic IRI from system URN + concept name

application/infrastructure/
  GraphRepository            ← JDBC upsert/delete/findAll for graph_state table

config/
  GraphProperties            ← @ConfigurationProperties for app.graph.*
  GraphPreset (enum)         ← SKOS | CUSTOM
  GraphPresetDefinition      ← Interface implemented by each preset record
  SkosPresetDefinition       ← SKOS vocabulary defaults

domain/graph/
  ConceptEntry               ← Immutable record: iri, name, type, properties, relations
  RelationEntry              ← Immutable record: targetIri, verb
  ParseResult                ← Immutable record: prefixMap, root, concepts, rootVerb, reverseVerbMap
  ProvisionResult            ← Immutable record: systemUrn, strategy (MAPPING|PASSTHROUGH), provisionedIris
  GraphPartition             ← Immutable record: systemUrn, content, updatedAt

domain/validation/
  GraphValidationError       ← record: field, message, prefix; factory methods
  GraphValidationException   ← Unchecked; wraps List<GraphValidationError>
```
