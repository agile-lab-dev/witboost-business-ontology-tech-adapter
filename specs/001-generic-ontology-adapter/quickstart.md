# Quickstart Validation Guide

**Date**: 2026-07-07 | **Plan**: [plan.md](plan.md)

This guide documents runnable validation scenarios that prove the Generic
Ontology Adapter feature works end-to-end. Each scenario maps to a Success
Criterion from the [spec](spec.md).

## Prerequisites

- Java 25 installed (or use `./mvnw` which uses the wrapper)
- PostgreSQL running on `localhost:5432` (or configure via env vars)
- Database `ontology_ta` created
- Adapter built: `./mvnw clean package -DskipTests`
- Adapter running: `java -jar target/ontology-tech-adapter-*.jar`

## Scenario 1: SKOS Preset — Backward Compatibility (SC-002)

**Config** (`application.yaml`):

```yaml
app:
  graph:
    preset: skos
```

**Provision request**:

```bash
curl -s -X POST http://localhost:8085/v1/provision \
  -H "Content-Type: application/json" \
  -d '{
    "descriptorKind": "DATAPRODUCT_DESCRIPTOR",
    "descriptor": "id: urn:dmb:dp:domain:registry-a:0\nname: Registry A\ndescription: Test SKOS\nbusiness_concepts:\n  - name: Concept A\n  - name: Concept B\n    relations:\n      - name: Concept A"
  }'
```

**Expected response**: `{"status": "COMPLETED", "info": {"publicInfo": {"valid": true}}}`

**Verify graph**:

```bash
curl -s http://localhost:8085/v1/graph/jsonld | python -m json.tool
```

**Expected**: JSON-LD containing:
- `wb:domain-registry-a-0` typed as `skos:ConceptScheme`
- `wb:domain-registry-a-0-concept-a` typed as `skos:Concept` with `skos:inScheme`
- `wb:domain-registry-a-0-concept-b` typed as `skos:Concept` with `skos:narrower` → concept-a
- Zero non-SKOS vocabulary URIs (except `wb:` and `rdf:`)

## Scenario 2: Custom Mode — OWL Ontology (SC-001)

**Config** (`application.yaml`):

```yaml
app:
  graph:
    preset: custom
    prefixes:
      literals:
        owl: "http://www.w3.org/2002/07/owl#"
        rdfs: "http://www.w3.org/2000/01/rdf-schema#"
    concept-mapping:
      type: "owl:Class"
      name-field: concept_name
      properties:
        "rdfs:label": "label"
        "rdfs:comment": "comment"
    relations-mapping:
      verb: "rdfs:subClassOf"
      verb-field: type
    descriptor:
      root:
        is-concept: true
        type: "owl:Ontology"
        verb: "rdfs:isDefinedBy"
```

**Provision request**:

```bash
curl -s -X POST http://localhost:8085/v1/provision \
  -H "Content-Type: application/json" \
  -d '{
    "descriptorKind": "DATAPRODUCT_DESCRIPTOR",
    "descriptor": "id: urn:dmb:dp:domain:ontology-a:0\nname: Ontology A\nconcept_name: My Ontology\nbusiness_concepts:\n  - concept_name: Animal\n    label: Animal\n    comment: A living organism\n    relations: []\n  - concept_name: Dog\n    label: Dog\n    comment: A domesticated carnivore\n    relations:\n      - concept_name: Animal\n        type: rdfs:subClassOf"
  }'
```

**Verify**: Graph contains `owl:Class` triples, zero `skos:` URIs.

## Scenario 3: Per-System Isolation (SC-003)

1. Provision System A (SKOS preset):
   ```bash
   curl -s -X POST http://localhost:8085/v1/provision \
     -H "Content-Type: application/json" \
     -d '{"descriptorKind":"DATAPRODUCT_DESCRIPTOR","descriptor":"id: urn:dmb:dp:domain:sys-a:0\nname: System A\nbusiness_concepts:\n  - name: Alpha"}'
   ```

2. Provision System B:
   ```bash
   curl -s -X POST http://localhost:8085/v1/provision \
     -H "Content-Type: application/json" \
     -d '{"descriptorKind":"DATAPRODUCT_DESCRIPTOR","descriptor":"id: urn:dmb:dp:domain:sys-b:0\nname: System B\nbusiness_concepts:\n  - name: Beta"}'
   ```

3. Unprovision System A:
   ```bash
   curl -s -X POST http://localhost:8085/v1/unprovision \
     -H "Content-Type: application/json" \
     -d '{"descriptorKind":"DATAPRODUCT_DESCRIPTOR","descriptor":"id: urn:dmb:dp:domain:sys-a:0\nname: System A\nbusiness_concepts:\n  - name: Alpha"}'
   ```

4. **Verify**: `GET /v1/graph/jsonld` returns System B's triples (`Beta`)
   but NOT System A's (`Alpha`).

5. **Verify DB**: `SELECT system_urn FROM graph_state` returns only
   `urn:dmb:dp:domain:sys-b:0`.

## Scenario 4: YAML-LD Pass-through (SC-004)

**Config**: Add `yaml-ld-path: graph_definition` to descriptor config.

**Provision**:

```bash
curl -s -X POST http://localhost:8085/v1/provision \
  -H "Content-Type: application/json" \
  -d '{"descriptorKind":"DATAPRODUCT_DESCRIPTOR","descriptor":"id: urn:dmb:dp:domain:passthrough:0\nname: Pass Through\ngraph_definition:\n  \"@context\":\n    owl: \"http://www.w3.org/2002/07/owl#\"\n  \"@graph\":\n    - \"@id\": \"http://example.com/test\"\n      \"@type\": \"owl:Ontology\""}'
```

**Verify**: `GET /v1/graph/jsonld` returns the embedded JSON-LD triples
(an `owl:Ontology` at `http://example.com/test`), with no adapter-injected
concept-mapping triples.

## Scenario 5: Prefix Validation Failure (FR-017)

**Config** (missing `owl` prefix):

```yaml
app:
  graph:
    preset: custom
    prefixes:
      literals:
        rdfs: "http://www.w3.org/2000/01/rdf-schema#"
    concept-mapping:
      type: "owl:Class"
```

**Validate**:

```bash
curl -s -X POST http://localhost:8085/v1/validate \
  -H "Content-Type: application/json" \
  -d '{"descriptorKind":"DATAPRODUCT_DESCRIPTOR","descriptor":"id: urn:dmb:dp:domain:test:0\nname: Test"}'
```

**Expected**: Validation error identifying `owl` as an unknown prefix in
`concept-mapping.type`.

## Scenario 6: Witboost URN Warning (FR-006)

**Config**: SKOS preset.

**Descriptor** with a URN as explicit IRI:

```yaml
id: urn:dmb:dp:domain:test:0
name: Test
business_concepts:
  - name: My Concept
    iri: "urn:dmb:cmp:domain:my-concept:0"
```

**Verify logs**: A WARNING-level log entry appears:
`Concept 'My Concept' has iri-field value 'urn:dmb:cmp:...' which is a
Witboost URN, not a valid HTTP IRI. Falling back to derived IRI.`

**Verify graph**: The concept's subject IRI is the derived IRI
(`wb:domain-test-0-my-concept`), not the URN.

## Running Tests

```bash
./mvnw test
```

All tests must pass. JaCoCo report at `target/site/jacoco/index.html`
must show ≥ 80 % line coverage for new/modified service classes.

See [data-model.md](data-model.md) for entity details and
[contracts/config-schema.md](contracts/config-schema.md) for the full
configuration reference.
