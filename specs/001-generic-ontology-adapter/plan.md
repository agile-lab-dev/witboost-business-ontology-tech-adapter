# Implementation Plan: Generic Ontology Adapter

**Branch**: `001-generic-ontology-adapter` | **Date**: 2026-07-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-generic-ontology-adapter/spec.md`

## Summary

Replace the hardcoded SKOS vocabulary and domain-sync logic in `GraphService`
with a preset-driven, fully configurable mapping engine that supports any RDF
vocabulary (OWL, RDFS, custom) via two interchangeable strategies:

- **Config A (YAML-to-RDF mapping)**: Parse the descriptor's concept list
  using configurable type/property/relation mappings driven by `graph.preset`
  (`skos` preset or `custom`).
- **Config B (YAML-LD pass-through)**: When `descriptor.yaml-ld-path` is set
  and the referenced field is present, store the embedded JSON-LD verbatim.

Additionally: migrate from single-row storage to per-system-URN partitioning,
remove the Witboost catalog domain-sync client, introduce prefix-based IRI
resolution, and add deterministic IRI derivation from system URNs.

## Technical Context

**Language/Version**: Java 25 (eclipse-temurin:25-jre-alpine)

**Primary Dependencies**: Spring Boot 3.5.x (Spring MVC / Tomcat), Apache
Jena 4.10.0 (RDF model + JSON-LD serialisation), Lombok, Jackson (JSON + YAML),
OpenAPI Generator (interface-only)

**Storage**: PostgreSQL + Flyway (auto-migration on startup); single table
`graph_state` currently, migrated to per-system-URN keying

**Testing**: JUnit 5, Mockito, AssertJ, Spring Boot Test (`@WebMvcTest`,
`@SpringBootTest`), JaCoCo (line coverage ≥ 80 %)

**Target Platform**: Linux container (Docker/Kubernetes)

**Project Type**: Web service (Witboost provisioner / KGM source adapter)

**Performance Goals**: Provision/unprovision < 3 s for ≤ 200 concepts;
merged-graph read < 5 s for ≤ 100 systems (cached)

**Constraints**: ≤ 100 concurrently provisioned systems; maintenance window
acceptable for DB migration; structured logs only (no OTel spans this iteration)

**Scale/Scope**: ≤ 100 systems × ≤ 200 concepts each; single adapter instance

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Gate | Status |
|-----------|------|--------|
| I. Code Quality | Spotless (Google Java Format) clean; SpotBugs zero HIGH; typed layer boundaries | ✅ Plan uses typed records/POJOs for new config and domain classes |
| II. Testing | JaCoCo ≥ 80 %; every new public method tested; deterministic tests | ✅ All new services will have unit tests with Mockito; no network calls in tests |
| III. UX Consistency | Responses via OpenAPI; errors via @RestControllerAdvice; SLF4J+MDC logging | ✅ Existing controller contract preserved; new FR-019 adds structured logging with MDC |
| IV. Performance | Health < 500 ms p95; no blocking on shared pool; image < 500 MB | ✅ No new blocking I/O; graph cache avoids re-merge on reads |
| V. Architecture | Models in domain/model packages; constructor injection; validation in /validate | ✅ New config classes in `config`; new domain classes in `domain`; constructor DI throughout |
| VI. Type Safety | No Object in public signatures; no unjustified @SuppressWarnings | ⚠️ Current `Map<String,Object>` for ontology concept fields will remain internal; not exposed in public API. Existing `@SuppressWarnings("unchecked")` on `extractConcepts` will be replaced with typed extraction |
| VII. Test Hygiene | Real model instances; meaningful fixtures; no framework testing | ✅ Tests use real config POJOs and sample YAML descriptors |
| VIII. Developer Tooling | ./mvnw only; pre-commit hooks | ✅ No change to tooling |
| IX. Security | OWASP zero CVSS ≥ 9; no secrets in source; no SQL injection | ⚠️ **Pre-existing SQL injection** in `GraphService.saveGraph()` and `init()` — `schemaName` is interpolated via `String.format` into raw SQL. Fix tracked as T049 (Phase 1). New `GraphRepository` (T021) MUST use parameterised queries only. |
| X. User-Facing Documentation | Every config/API feature ships with operator docs including field reference and examples | ✅ `docs/configuration.md` will be created with this feature; included in the same MR |

**Post-design re-check**: Type Safety (VI) — the internal `Map<String,Object>`
for business object field access stays behind a typed `ConceptEntry` record
after refactoring, resolving the ⚠️. Security (IX) — the SQL injection is
fixed in Phase 1 (T049) and the new `GraphRepository` does not reproduce
the pattern, resolving the ⚠️.

## Expected Configuration Structure

This section is the **source of truth** for how the adapter configuration
behaves. Cross-referenced with `TODO.md` examples and the spec's FRs.

### Configuration Schema

```yaml
app:
  graph:
    preset: skos | custom                   # FR-001
    prefixes:                               # FR-007
      literals:                             # Static prefix map
        skos: "http://www.w3.org/2004/02/skos/core#"
        wb: "https://witboost.com/type#"
      field: null                           # Descriptor field containing @context
    descriptor:                             # FR-004, FR-009, FR-010
      root:
        is-concept: true                    # Whether descriptor itself is an RDF resource
        type: null                          # Override concept-mapping.type for root; absent → falls back to concept-mapping.type
        type-field: null                    # Override concept-mapping.type-field for root; absent → falls back to concept-mapping.type-field
        verb: null                          # Verb linking root → child concepts (no concept-mapping fallback)
        verb-field: null                    # Per-deployment verb field for root (no concept-mapping fallback)
        iri-field: null                     # Override concept-mapping.iri-field for root; absent → falls back to concept-mapping.iri-field
        name-field: null                    # Override concept-mapping.name-field for root; absent → falls back to concept-mapping.name-field
        properties: {}                      # Override concept-mapping.properties for root
        additionalProperties: {}            # Extra properties merged onto root.properties
      business-concepts-path: business_concepts  # Dot-notation to concept list
      yaml-ld-path: null                    # If set, enables Config B pass-through
      yaml-ld-required: false              # FR-010: strict mode for yaml-ld-path
    concept-mapping:                        # FR-002, FR-016
      type: null                            # RDF class for concepts (required in custom)
      iri-field: iri                        # Field containing explicit IRI (FR-005)
      name-field: name                      # Field for display name / label derivation
      type-field: type                      # Per-concept type override field
      properties: {}                        # Predicate IRI → descriptor field name
      additionalProperties: {}              # Merged onto properties (FR-014)
    relations-mapping:                      # FR-003, FR-020
      verb: null                            # Default relation predicate IRI
      verb-field: type                      # Per-relation verb override field
      relations-path: relations             # Field name for relation list in concepts
      reverse-verb-map: {}                  # FR-020: verb → inverse-verb (CURIE or full IRI)
```

### Example 1: `preset: skos` — Minimal Configuration

**Operator provides** (in `application.yaml` or Helm values):

```yaml
app:
  graph:
    preset: skos
```

**After SKOS preset merge** (FR-013, FR-014, OP-005):

```yaml
app:
  graph:
    preset: skos
    prefixes:
      literals:
        skos: "http://www.w3.org/2004/02/skos/core#"
        wb: "https://witboost.com/type#"
      field: null
    descriptor:
      root:
        is-concept: true
        type: "skos:ConceptScheme"
        verb: "skos:inScheme"
        properties:
          "[skos:prefLabel]": "name"
          "[skos:definition]": "description"
      business-concepts-path: business_concepts
      yaml-ld-path: null
      yaml-ld-required: false
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

**Sample descriptor**:

```yaml
id: urn:dmb:dp:domain:registry-a:0
name: Registry A
description: Test description
business_concepts:
  - name: Concept A
  - name: Concept B
    relations:
      - name: Concept C
  - name: Concept C
    relations:
      - name: Concept B
        type: "skos:broader"
      - iri: "wb:some-external-concept"
        type: "skos:related"
```

**Resulting RDF triples**:

| Subject | Predicate | Object |
|---------|-----------|--------|
| `wb:domain-registry-a-0` | `rdf:type` | `skos:ConceptScheme` |
| `wb:domain-registry-a-0` | `skos:prefLabel` | "Registry A" |
| `wb:domain-registry-a-0` | `skos:definition` | "Test description" |
| `wb:domain-registry-a-0-concept-a` | `rdf:type` | `skos:Concept` |
| `wb:domain-registry-a-0-concept-a` | `skos:inScheme` | `wb:domain-registry-a-0` |
| `wb:domain-registry-a-0-concept-a` | `skos:prefLabel` | "Concept A" |
| `wb:domain-registry-a-0-concept-b` | `rdf:type` | `skos:Concept` |
| `wb:domain-registry-a-0-concept-b` | `skos:inScheme` | `wb:domain-registry-a-0` |
| `wb:domain-registry-a-0-concept-b` | `skos:prefLabel` | "Concept B" |
| `wb:domain-registry-a-0-concept-b` | `skos:narrower` | `wb:domain-registry-a-0-concept-c` |
| `wb:domain-registry-a-0-concept-c` | `rdf:type` | `skos:Concept` |
| `wb:domain-registry-a-0-concept-c` | `skos:inScheme` | `wb:domain-registry-a-0` |
| `wb:domain-registry-a-0-concept-c` | `skos:prefLabel` | "Concept C" |
| `wb:domain-registry-a-0-concept-c` | `skos:broader` | `wb:domain-registry-a-0-concept-b` |
| `wb:domain-registry-a-0-concept-c` | `skos:related` | `wb:some-external-concept` |

**IRI derivation** (FR-005):
- Root: `urn:dmb:dp:domain:registry-a:0` → strip `urn:dmb:dp:` → `domain:registry-a:0` → slugify → `domain-registry-a-0` → expand `wb:` → `https://witboost.com/type#domain-registry-a-0`
- Concept A: root IRI + `-` + slugify("Concept A") → `wb:domain-registry-a-0-concept-a`

### Example 2: `preset: skos` with Overrides

**Operator provides**:

```yaml
app:
  graph:
    preset: skos
    concept-mapping:
      additionalProperties:
        "[wb:category]": "category"
```

**After merge** (FR-014): `additionalProperties` merges onto preset `properties`:

```yaml
properties:
  "[skos:prefLabel]": "name"           # from SKOS preset
  "[skos:definition]": "description"   # from SKOS preset
  "[wb:category]": "category"          # from user additionalProperties
```

If the user instead wrote:

```yaml
concept-mapping:
  properties:
    "[skos:prefLabel]": "display_name"
```

The user value wins on the `skos:prefLabel` key — the merged result is:

```yaml
properties:
  "[skos:prefLabel]": "display_name"   # user override wins
  "[skos:definition]": "description"   # from SKOS preset
```

### Example 3: `preset: custom` — OWL Ontology

**Operator provides**:

```yaml
app:
  graph:
    preset: custom
    prefixes:
      literals:
        owl: "http://www.w3.org/2002/07/owl#"
        rdfs: "http://www.w3.org/2000/01/rdf-schema#"
        express: "http://example.com/express#"
    descriptor:
      root:
        is-concept: true
        type: "owl:Ontology"
        verb: "rdfs:isDefinedBy"
      business-concepts-path: business_concepts
    concept-mapping:
      type: "owl:ObjectProperty"
      iri-field: iri
      name-field: concept_name
      type-field: type
      properties:
        "[rdfs:label]": "label"
        "[rdfs:comment]": "comment"
    relations-mapping:
      verb: "rdfs:isDefinedBy"
      verb-field: type
      relations-path: relations
```

**Sample descriptor**:

```yaml
id: urn:dmb:dp:domain:express:0
name: express
concept_name: "http://example.com/express#"
business_concepts:
  - concept_name: "Company"
    label: "Company"
    type: "owl:Class"
    comment: "A company or organization that might order products"
    relations: []
  - concept_name: "Product"
    label: "Product"
    type: "owl:Class"
    comment: "A product that may be sent to a company"
    relations: []
  - concept_name: "send"
    label: "Send to"
    comment: "Send very very fast to the specified company"
    relations:
      - concept_name: "Company"
        type: "rdfs:domain"
      - concept_name: "Product"
        type: "rdfs:range"
```

**Resulting RDF triples**:

| Subject | Predicate | Object                         |
|---------|-----------|--------------------------------|
| `wb:domain-express-0` | `rdf:type` | `owl:Ontology`                 |
| `wb:domain-express-0-company` | `rdf:type` | `owl:Class`                    |
| `wb:domain-express-0-company` | `rdfs:label` | "Company"                      |
| `wb:domain-express-0-company` | `rdfs:comment` | "A company or organization..." |
| `wb:domain-express-0-company` | `rdfs:isDefinedBy` | `wb:domain-express-0`          |
| `wb:domain-express-0-product` | `rdf:type` | `owl:Class`                    |
| `wb:domain-express-0-product` | `rdfs:label` | "Product"                      |
| `wb:domain-express-0-product` | `rdfs:comment` | "A product..."                 |
| `wb:domain-express-0-product` | `rdfs:isDefinedBy` | `wb:domain-express-0`          |
| `wb:domain-express-0-send` | `rdf:type` | `owl:ObjectProperty`           |
| `wb:domain-express-0-send` | `rdfs:label` | "Send to"                      |
| `wb:domain-express-0-send` | `rdfs:comment` | "Send very very fast..."       |
| `wb:domain-express-0-send` | `rdfs:isDefinedBy` | `wb:domain-express-0`          |
| `wb:domain-express-0-send` | `rdfs:domain` | `wb:domain-express-0-company`  |
| `wb:domain-express-0-send` | `rdfs:range` | `wb:domain-express-0-product`  |

Note: `type-field: type` causes `"owl:Class"` to override `concept-mapping.type`
for Company and Product, while "send" falls back to `owl:ObjectProperty`.

### Example 4: Config B — YAML-LD Pass-through

**Operator provides**:

```yaml
app:
  graph:
    preset: skos           # irrelevant when yaml-ld-path triggers
    descriptor:
      yaml-ld-path: graph_definition
      yaml-ld-required: false
```

**Sample descriptor** (field present → Config B activates):

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

→ The JSON-LD at `graph_definition` is stored verbatim. No concept-mapping.

**Same config, but descriptor WITHOUT `graph_definition`**:

```yaml
id: urn:dmb:dp:domain:sales:0
name: Sales
business_concepts:
  - name: Account
```

→ Since `yaml-ld-required: false` (default), adapter falls through to Config A
  (`preset: skos`) and maps `Account` as a `skos:Concept`.

If `yaml-ld-required: true`, the adapter would return a validation error:
*"Field 'graph_definition' required by yaml-ld-path is absent in descriptor"*.

### Example 5: Prefix Validation Failure (FR-017)

**Operator provides**:

```yaml
app:
  graph:
    preset: custom
    prefixes:
      literals:
        rdfs: "http://www.w3.org/2000/01/rdf-schema#"
    concept-mapping:
      type: "owl:Class"    # ← ERROR: 'owl' prefix not registered
```

→ Validation error: *"Unknown prefix 'owl' in config key concept-mapping.type
  value 'owl:Class'. Register it in prefixes.literals or prefixes.field."*

### Example 6: Witboost URN Warning (FR-006)

**Descriptor with Witboost URN as explicit IRI**:

```yaml
business_concepts:
  - name: "My Concept"
    iri: "urn:dmb:cmp:domain:my-concept:0"
```

→ Warning: *"Concept 'My Concept' has iri-field value 'urn:dmb:cmp:domain:my-concept:0'
  which is a Witboost URN (starts with 'urn:'), not a valid HTTP IRI.
  Falling back to derived IRI."*
→ Derived IRI used instead: `wb:<root-iri-slug>-my-concept`

## Project Structure

### Documentation (this feature)

```text
specs/001-generic-ontology-adapter/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0: research findings
├── data-model.md        # Phase 1: entity and data model
├── quickstart.md        # Phase 1: validation guide
├── contracts/           # Phase 1: interface contracts
│   └── config-schema.md # Configuration schema contract
├── checklists/
│   └── requirements.md  # Spec quality checklist
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### User-Facing Documentation (repository root)

```text
docs/
├── configuration.md     # NEW: complete config reference for operators
└── architecture.md      # NEW: high-level adapter architecture overview
```

### Source Code (repository root)

```text
src/main/java/com/witboost/ontology/
├── application/
│   ├── GraphService.java              # Refactored: orchestrates provision/unprovision
│   ├── MappingEngine.java             # NEW: Config A concept-mapping logic
│   ├── PassthroughEngine.java         # NEW: Config B yaml-ld pass-through logic
│   ├── PrefixResolver.java            # NEW: prefix map resolution + IRI expansion
│   ├── IriDerivationService.java      # NEW: deterministic IRI derivation from URN
│   ├── PresetMergeService.java        # NEW: preset-aware config merge (OP-005)
│   ├── GraphCacheService.java         # NEW: merged-graph cache with invalidation
│   ├── KgmClient.java                # EXISTING: unchanged
│   └── WitboostCatalogClient.java     # REMOVED
├── config/
│   ├── GraphProperties.java           # REWRITTEN: new config schema
│   ├── GraphPreset.java                 # NEW: enum (SKOS, CUSTOM)
│   ├── SkosPreset.java                # NEW: SKOS default values
│   ├── KgmProperties.java            # EXISTING: unchanged
│   └── WitboostCatalogProperties.java # REMOVED
├── domain/
│   ├── descriptor/
│   │   └── DataProductDescriptor.java # EXISTING: unchanged (uses @JsonAnySetter)
│   ├── catalog/                       # REMOVED (all classes)
│   ├── graph/
│   │   ├── ConceptEntry.java          # NEW: typed record for parsed concept
│   │   ├── RelationEntry.java         # NEW: typed record for parsed relation
│   │   ├── ParseResult.java           # NEW: record holding prefix map, root, concepts, rootVerb, reverseVerbMap
│   │   ├── ProvisionResult.java       # NEW: record returned by GraphService.provision() (systemUrn, strategy, provisionedIris)
│   │   └── GraphPartition.java        # NEW: record for system URN → JSON-LD pair
│   └── validation/
│       └── GraphValidationError.java  # NEW: structured validation error
├── interfaces/rest/
│   ├── TechAdapterController.java     # MODIFIED: adds validation dispatch
│   └── KgmSourceController.java       # MODIFIED: uses GraphCacheService
└── OntologyTechAdapterApplication.java  # EXISTING: unchanged

src/main/resources/
├── application.yaml                   # MODIFIED: new config structure
└── db/migration/
    ├── V1__init.sql                   # EXISTING: singleton graph_state
    └── V2__per_system_storage.sql     # NEW: per-system-URN keying

src/test/java/com/witboost/ontology/
├── application/
│   ├── GraphServiceTest.java          # REWRITTEN
│   ├── MappingEngineTest.java         # NEW
│   ├── PassthroughEngineTest.java     # NEW
│   ├── PrefixResolverTest.java        # NEW
│   ├── IriDerivationServiceTest.java  # NEW
│   ├── PresetMergeServiceTest.java    # NEW
│   └── GraphCacheServiceTest.java     # NEW
├── config/
│   └── SkosPresetTest.java            # NEW
├── interfaces/rest/
│   ├── KgmSourceControllerTest.java   # MODIFIED
│   └── TechAdapterControllerTest.java # NEW
└── OntologyTechAdapterApplicationTest.java  # EXISTING
```

**Structure Decision**: The existing layered structure
(`application`/`config`/`domain`/`interfaces`) is preserved per Constitution
Principle V. The monolithic `GraphService` is decomposed into focused
single-responsibility services. The `catalog` domain package is deleted entirely.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| `PresetMergeService` as separate class | Mode-aware merge of preset defaults with user config at startup (OP-005) | Inline merge in `GraphProperties` would mix configuration binding with business logic; violates single responsibility |
| `GraphCacheService` as separate class | Decouples cache lifecycle from provisioning logic; testable in isolation | Caching inside `GraphService.getJsonLd()` (current approach) makes it impossible to test cache invalidation separately |

## User-Facing Documentation Plan

Per Constitution Principle X, every feature that introduces or modifies
operator-facing configuration must ship with corresponding documentation
in the same MR. This feature requires two new documents.

### `docs/architecture.md` — Adapter Architecture Overview

Target audience: platform engineers deploying and operating the adapter.

**Contents**:
1. **What the adapter does**: Receives Witboost YAML descriptors, transforms
   them into RDF triples following configurable vocabulary mappings, stores
   them as JSON-LD partitions in PostgreSQL, and exposes the merged graph
   via a KGM source endpoint.
2. **Two mapping strategies**: Config A (YAML-to-RDF mapping via `preset`)
   and Config B (YAML-LD pass-through via `yaml-ld-path`). A diagram
   showing the decision flow: yaml-ld-path set? → field present? →
   Config B; else → Config A.
3. **Per-system storage model**: One DB row per system URN. Merged graph
   cached in memory, invalidated on provision/unprovision.
4. **Presets**: What `preset: skos` provides out of the box vs. what
   `preset: custom` requires the operator to configure.
5. **IRI derivation**: How subject IRIs are generated when no explicit IRI
   is provided. Include the slugification algorithm with a worked example.
6. **Endpoints**: The provisioning API (validate/provision/unprovision) and
   the KGM source endpoint (`GET /v1/graph/jsonld`).

### `docs/configuration.md` — Configuration Reference

Target audience: platform engineers writing Helm values or `application.yaml`.

**Contents**:
1. **Quick start**: Minimal `preset: skos` config that works out of the box.
2. **Complete field reference**: Every `app.graph.*` field documented as a
   table with columns: Field, Type, Default, Required, Description. Group
   by subsection (`prefixes`, `descriptor`, `descriptor.root`,
   `concept-mapping`, `relations-mapping`).
3. **Preset defaults**: What `preset: skos` populates automatically and how
   operator overrides interact with it (merge strategy).
4. **Examples** (at least 3):
   - **SKOS minimal**: `preset: skos` with no overrides + sample descriptor
     + expected triples (adapted from plan Example 1).
   - **SKOS with overrides**: Adding custom properties via
     `additionalProperties` (adapted from plan Example 2).
   - **Custom OWL ontology**: Full `preset: custom` config with explicit
     prefixes, type, verb, properties (adapted from plan Example 3).
   - **YAML-LD pass-through**: `yaml-ld-path` config + descriptor with
     embedded JSON-LD (adapted from plan Example 4).
5. **Validation errors**: Common misconfiguration scenarios and the exact
   error messages the adapter returns (unknown prefix, missing required
   field, Witboost URN warning).
6. **Migration guide**: What changes from the pre-feature version (removed
   domain sync, new config namespace, DB re-provisioning requirement).

