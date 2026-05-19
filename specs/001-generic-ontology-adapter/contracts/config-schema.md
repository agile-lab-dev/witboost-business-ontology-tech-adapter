# Configuration Schema Contract

**Date**: 2026-07-07 | **Plan**: [../plan.md](../plan.md)

This document defines the contract for the adapter's `app.graph.*`
configuration namespace. It is the interface between operators (who configure
the adapter via Helm values or `application.yaml`) and the adapter internals.

## Configuration Namespace: `app.graph`

### Top-level

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `preset` | `skos` \| `custom` | Yes | — | Selects the mapping strategy. `skos` applies preset defaults; `custom` requires explicit configuration of all mapping fields |

### `app.graph.prefixes`

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `literals` | `Map<String, String>` | No | `{wb: "https://witboost.com/type#"}` (always present; SKOS preset adds `skos`) | Static prefix label → namespace IRI map |
| `field` | `String` | No | `null` | Descriptor field name whose value is read as a JSON-LD `@context` object at provision time |

**Merge behaviour**: `literals` and field-derived prefixes are combined into a
single resolved prefix map. Field-derived entries win on key conflict.

### `app.graph.descriptor`

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `business-concepts-path` | `String` | No | `"business_concepts"` | Dot-notation path from descriptor root to the concept list |
| `yaml-ld-path` | `String` | No | `null` | If non-null, enables Config B (pass-through). The adapter reads JSON-LD from this descriptor field |
| `yaml-ld-required` | `boolean` | No | `false` | When `true` and `yaml-ld-path` is set but the field is absent, validation fails instead of falling through to Config A |

### `app.graph.descriptor.root`

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `is-concept` | `boolean` | No | `false` (SKOS preset: `true`) | Whether the descriptor itself becomes an RDF resource |
| `type` | `String` | No | `null` (SKOS: `skos:ConceptScheme`) | RDF class for the root resource. Falls back to `concept-mapping.type` |
| `verb` | `String` | No | `null` (SKOS: `skos:inScheme`) | Predicate linking each concept to the root resource |
| `verb-field` | `String` | No | `null` | Per-concept field overriding `verb` for the root link |
| `iri-field` | `String` | No | `null` | Field containing an explicit IRI for the root resource |
| `name-field` | `String` | No | `null` | Field for the root display name (for IRI derivation) |
| `properties` | `Map<String, String>` | No | `{}` | Property mappings override for the root resource only |
| `additionalProperties` | `Map<String, String>` | No | `{}` | Extra properties merged onto `root.properties` |

### `app.graph.concept-mapping`

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `type` | `String` | Yes (custom) | `null` (SKOS: `skos:Concept`) | RDF class for concepts |
| `iri-field` | `String` | No | `"iri"` | Concept field containing an explicit IRI |
| `name-field` | `String` | No | `"name"` | Concept field for display name |
| `type-field` | `String` | No | `"type"` | Per-concept field overriding `type` |
| `properties` | `Map<String, String>` | Yes (custom) | `{}` (SKOS: `{skos:prefLabel: name, skos:definition: description}`) | Predicate IRI → descriptor field name |
| `additionalProperties` | `Map<String, String>` | No | `{}` | Merged onto `properties`; entries here win on key conflict |

### `app.graph.relations-mapping`

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `verb` | `String` | Yes (custom) | `null` (SKOS: `skos:narrower`) | Default relation predicate IRI |
| `verb-field` | `String` | No | `"type"` (SKOS: `null`) | Per-relation field overriding `verb` |
| `relations-path` | `String` | No | `"relations"` | Field name for the relation list in each concept |

## SKOS Preset Defaults

When `preset: skos`, the following defaults are applied by `PresetMergeService`
**before** user config is merged on top:

```yaml
prefixes.literals:
  skos: "http://www.w3.org/2004/02/skos/core#"
  wb: "https://witboost.com/type#"
descriptor.root:
  is-concept: true
  type: "skos:ConceptScheme"
  verb: "skos:inScheme"
concept-mapping:
  type: "skos:Concept"
  iri-field: "iri"
  name-field: "name"
  type-field: "type"
  properties:
    "skos:prefLabel": "name"
    "skos:definition": "description"
relations-mapping:
  verb: "skos:narrower"
  verb-field: null
  relations-path: "relations"
```

## Validation Rules (FR-017)

The adapter MUST reject provisioning at validation time if:

1. `preset` is absent or not one of `skos`, `custom`
2. In `custom` preset: `concept-mapping.type` is null
3. In `custom` preset: `relations-mapping.verb` is null and no `verb-field` configured
4. Any IRI-typed value uses a prefix label not in the resolved prefix map
5. Two concepts within the same descriptor resolve to the same subject IRI
6. `yaml-ld-required: true` and `yaml-ld-path` is set but the field is absent

## REST Endpoints (Unchanged Signatures)

| Method | Path | Success Response | User Error Response | System Error |
|--------|------|-----------------|---------------------|--------------|
| `POST` | `/v1/validate` | 200 `ValidationResult{valid: true}` | **200** `ValidationResult{valid: false, error: {errors: [...]}}` | 500 |
| `POST` | `/v1/provision` | 200 `ProvisioningStatus{status: COMPLETED}` | **200** `ProvisioningStatus{status: FAILED, info.publicInfo.valid: false}` | 400 (malformed request) / 500 |
| `POST` | `/v1/unprovision` | 200 `ProvisioningStatus{status: COMPLETED}` | **200** `ProvisioningStatus{status: FAILED, info.publicInfo.valid: false}` | 400 (malformed request) / 500 |
| `GET` | `/v1/graph/jsonld` | 200 JSON-LD body | N/A | 500 |

**Key rule**: Descriptor-level and configuration validation failures (FR-017) MUST be returned as HTTP 200 with the appropriate error payload — never as 400 or 422. HTTP 400 is reserved for malformed HTTP requests (unparseable JSON body, missing required HTTP-level fields). There is no 422 in this API.

### Error Response Population Rule

Whenever a `RequestValidationError` (HTTP 400) or `SystemError` (HTTP 500) is produced, **all available fields MUST be populated**. The `errors` / `error` fields are `deprecated: true` in the OpenAPI schema but are still required for backward compatibility — include them. Prefer the newer fields:

| Field | Type | Rule |
|-------|------|------|
| `errors` / `error` | `string[]` / `string` | **Required** — fill for backward compat even though deprecated |
| `userMessage` | `string` | **Required** — friendly, operator-readable sentence (no stack traces) |
| `moreInfo.problems` | `string[]` | **Required** — one entry per technical problem (e.g. each constraint violation or parse error) |
| `moreInfo.solutions` | `string[]` | **Required** — actionable fix suggestions; use `[]` only if no useful suggestion exists |
| `input` | `string` | **If known** — the descriptor or file content / path that raised the error |
| `inputErrorField` | `string` | **If known** — dot-path to the offending field (e.g. `descriptor`, `specific.concepts[0].iri`) |

**Examples**:

```json
// 400 — malformed JSON body
{
  "errors": ["JSON parse error: Unexpected character"],
  "userMessage": "The request body could not be parsed. Please verify the JSON is valid and the Content-Type header is application/json.",
  "moreInfo": {
    "problems": ["JSON parse error: Unexpected character '\"' at line 3, column 12"],
    "solutions": ["Ensure the request body is valid JSON", "Set Content-Type: application/json"]
  }
}

// 400 — missing required field (Jakarta Bean Validation)
{
  "errors": ["descriptor: must not be blank"],
  "userMessage": "The request is missing required fields.",
  "inputErrorField": "descriptor",
  "moreInfo": {
    "problems": ["Field 'descriptor' is required but was not provided"],
    "solutions": ["Include the 'descriptor' field in the request body with a valid YAML descriptor string"]
  }
}

// 500 — unexpected runtime error
{
  "error": "NullPointerException in GraphService.provision",
  "userMessage": "An unexpected error occurred. Please contact the platform administrator.",
  "moreInfo": {
    "problems": ["NullPointerException in GraphService.provision at line 42"],
    "solutions": ["Check the adapter logs for the full stack trace", "Retry the operation; if the error persists open a support ticket"]
  }
}
```

Note: The tech adapter endpoint paths are generated from the OpenAPI spec and
use the existing Witboost provisioner contract. Only the KGM source endpoint
(`/v1/graph/jsonld`) is a custom path.
