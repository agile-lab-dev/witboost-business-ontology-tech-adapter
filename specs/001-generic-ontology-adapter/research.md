# Research: Generic Ontology Adapter

**Date**: 2026-07-07 | **Plan**: [plan.md](plan.md)

## R-001: Apache Jena JSON-LD Merge Strategy

**Decision**: Use `Model.add(Model)` to merge per-system Jena models at read
time. Each system's JSON-LD blob is deserialised into a temporary `Model`, then
added to a shared merge model. Duplicate triples are silently dropped by Jena's
default `ModelFactory.createDefaultModel()` (backed by a hash set).

**Rationale**: This is the idiomatic Jena approach and has O(n) complexity where
n = total triples across all partitions. For the expected scale (≤ 100 systems ×
≤ 200 concepts × ~5 triples/concept = ~100K triples), this completes in well
under the 5-second target.

**Alternatives considered**:
- Named graphs (Jena `Dataset`): More semantically correct but adds complexity
  to the `GET /v1/graph/jsonld` endpoint (must flatten to a default graph).
  Overkill for current scale.
- Pre-merged JSON-LD string concatenation: Fragile; JSON-LD `@context` merging
  is non-trivial without a library. Rejected.

## R-002: Per-System Storage — Flyway Migration Strategy

**Decision**: Flyway migration `V2__per_system_storage.sql` will:
1. `DROP TABLE IF EXISTS graph_state`
2. `CREATE TABLE graph_state (system_urn VARCHAR PRIMARY KEY, content TEXT NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now())`

The old singleton row is intentionally lost; operators re-provision after
upgrade. This is acceptable because a maintenance window was explicitly
approved (spec Q5 / Assumptions).

**Rationale**: A simple drop-and-recreate avoids data migration complexity.
Re-provisioning regenerates all graph data from descriptors, which are the
source of truth. No data is lost permanently.

**Alternatives considered**:
- Rename + migrate: `ALTER TABLE graph_state ADD COLUMN system_urn`, then
  distribute the singleton blob into per-system rows. Would require parsing
  the existing JSON-LD to determine which triples belong to which system —
  impossible without the `wb:fromDescriptor` annotation, and that annotation
  doesn't carry a system URN. Rejected.

## R-003: Spring @ConfigurationProperties Map Merge Behaviour

**Decision**: Spring's `@ConfigurationProperties` replaces entire maps when
the same property key appears in a single YAML source. Preset defaults must
be defined in Java code (not YAML) and merged at startup by a dedicated
`PresetMergeService` bean.

**Rationale**: The SKOS preset supplies preset-specific defaults for `prefixes`,
`properties`, `concept-mapping`, and `relations-mapping`. These are not static
application properties — they depend on the `graph.preset` value. A
`@PostConstruct` or `@EventListener(ApplicationReadyEvent.class)` method in
`PresetMergeService` reads the current `GraphProperties`, identifies the preset,
applies the preset defaults for unset fields, and merges map fields with
user-supplied values taking priority.

**Merge rules**:
1. For map fields (`prefixes.literals`, `concept-mapping.properties`):
   start with preset defaults, `putAll(user-supplied)` — user wins on conflict.
2. For `additionalProperties`: merged onto `properties` after step 1.
3. For scalar fields (`type`, `verb`, etc.): user-supplied value wins if
   non-null; preset default otherwise.

**Alternatives considered**:
- Custom `PropertySource`: Could register a low-priority in-memory property
  source containing preset values. However, presets are preset-dependent — the
  preset value itself comes from properties, creating a circular dependency.
  Rejected per OP-005 decision.

## R-004: IRI Derivation — Slugification Algorithm

**Decision**: Use a simple deterministic slugification:
1. Strip `urn:dmb:dp:` prefix from system URN.
2. Replace all non-alphanumeric characters (except hyphens) with hyphens.
3. Collapse consecutive hyphens into one.
4. Lowercase the result.
5. Strip leading/trailing hyphens.

For concept names: same algorithm applied to the `name-field` value.

**Implementation**: A static utility method in `IriDerivationService`. No
external library needed — `String.replaceAll` with regex is sufficient.

```java
static String slugify(String input) {
    return input.toLowerCase()
        .replaceAll("[^a-z0-9-]", "-")
        .replaceAll("-{2,}", "-")
        .replaceAll("^-|-$", "");
}
```

**Rationale**: Deterministic and reversible enough for debugging. Matches the
examples in TODO.md (`domain:registry-a:0` → `domain-registry-a-0`).

## R-005: Prefix Resolution — Unknown Prefix Detection

**Decision**: Before writing any triples, all IRI-typed values (from both
config and descriptor) are expanded through the resolved prefix map. A value
containing `:` that does not start with `http://` or `https://` is treated as
a prefixed shorthand. The prefix label (left of `:`) is looked up in the
map. If not found → `GraphValidationError` with the field path and prefix.

**Implementation**: `PrefixResolver.expand(String value)` returns the full IRI
or throws `UnknownPrefixException`. Called during the validation phase
(FR-017) and again during graph construction.

**Edge cases**:
- `urn:` prefix: Witboost URNs start with `urn:`. These are detected
  separately and trigger the warning path (FR-006), not prefix expansion.
- Bare names (no colon): Treated as local names under the `wb:` namespace
  (current behaviour preserved).

## R-006: Graph Cache Invalidation

**Decision**: `GraphCacheService` holds a `volatile String cachedJsonLd` field.
On every `provision()` or `unprovision()`, the field is set to `null`. On
`getJsonLd()`, if `null`, the cache is rebuilt by loading all rows from
`graph_state`, deserialising each into a Jena `Model`, merging, and
serialising to JSON-LD. The result is stored in the volatile field.

**Rationale**: Simple, thread-safe (volatile guarantees visibility), and
avoids explicit locking. The worst case is two concurrent reads both
rebuilding the cache — both produce identical output, so the race is benign.
At ≤ 100 rows, rebuild cost is well under 5 seconds.

**Alternatives considered**:
- `ConcurrentHashMap` with `computeIfAbsent`: More complex, same outcome.
- Spring `@Cacheable` with `@CacheEvict`: Adds Spring Cache abstraction
  overhead for a single-entry cache. Unnecessary complexity.
- `ReadWriteLock`: Prevents concurrent rebuilds but adds locking complexity
  not justified at this scale.

## R-007: Removing Domain Sync — Impact Analysis

**Decision**: Delete the following classes and all references:
- `WitboostCatalogClient.java`
- `WitboostCatalogProperties.java`
- `CatalogDomainItem.java`, `CatalogDomainMesh.java`,
  `CatalogDomainMetadata.java`, `CatalogDomainSpec.java`,
  `CatalogResponse.java`

Remove from `GraphService`:
- `syncDomainsFromCatalog()`, `syncDomains()`, `findRootScheme()`,
  `linkConceptToDomain()`, `resolveDomainResource()`, `pruneUnlinkedDomains()`
- All `PROP_FROM_CATALOG`, `PROP_PLACEHOLDER`, `PROP_KIND` constants
- The `catalogClient` constructor parameter and field

Remove from `application.yaml`:
- `app.witboost.catalog.*` section

**Impact**: ~250 lines of production code removed. Test file
`GraphServiceTest.java` will need rewriting since most tests exercise the
domain sync path.

**Rationale**: OP-001 resolved — domains are provisioned via separate
descriptors. No adapter code for domain sync is needed.
