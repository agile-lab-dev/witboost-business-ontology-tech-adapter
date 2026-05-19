# Tasks: Generic Ontology Adapter

**Input**: Design documents from `specs/001-generic-ontology-adapter/`

**Branch**: `001-generic-ontology-adapter`

**Prerequisites**: plan.md ✅ | spec.md ✅ | research.md ✅ | data-model.md ✅ | contracts/ ✅

**Tests**: Unit tests included per constitution (JaCoCo ≥ 80 % gate). No TDD-first approach specified — tests accompany each implementation task.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no blocking dependencies)
- **[Story]**: Which user story this task belongs to (US1–US4)
- Exact file paths included in all descriptions

---

## Phase 1: Setup & Removal (Shared Infrastructure)

**Purpose**: Remove domain-sync machinery, clean up obsolete config, create new class skeletons. Must complete before any feature work begins.

- [X] T049 Fix SQL injection in `src/main/java/com/witboost/ontology/application/GraphService.java` — in `init()` (line ~95) and `saveGraph()` (line ~537), replace `String.format("...%s...", schemaName)` with bare table references (`graph_state`) since `currentSchema` is already set in the JDBC URL (`application.yaml` line 43); remove the `schemaName` field and `@Value("${app.db.schema}")` constructor parameter from `GraphService`; remove the `private final String schemaName` field and the `this.schemaName = null` test-only assignment. This is a pre-existing SQL injection that is also flagged by the constitution (Principle IX, v2.2.0).
- [X] T001 Delete `src/main/java/com/witboost/ontology/application/WitboostCatalogClient.java` and `src/main/java/com/witboost/ontology/config/WitboostCatalogProperties.java`
- [X] T002 Delete entire `src/main/java/com/witboost/ontology/domain/catalog/` package (5 classes: CatalogDomainItem, CatalogDomainMesh, CatalogDomainMetadata, CatalogDomainSpec, CatalogResponse)
- [X] T003 Remove `app.witboost.catalog.*` section from `src/main/resources/application.yaml` and remove any `@Autowired`/constructor reference to `WitboostCatalogClient` in `GraphService.java`
- [X] T004 [P] Create package directories: `src/main/java/com/witboost/ontology/domain/graph/` and `src/main/java/com/witboost/ontology/domain/validation/`
- [X] T005 Run `./mvnw compile` and confirm the project compiles cleanly with all deletions applied before proceeding

**Checkpoint**: Project compiles with zero references to deleted classes. `WitboostCatalogClient` and all catalog domain classes are gone. SQL injection in `GraphService` is patched (T049).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: New config classes, domain records, DB migration, and the `PresetMergeService`. All user stories depend on this foundation.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### 2A – Configuration POJOs (can all run in parallel)

- [X] T006 [P] Rewrite `src/main/java/com/witboost/ontology/config/GraphProperties.java` to the new schema: add `GraphPreset preset`, `PrefixProperties prefixes`, rewrite `DescriptorProperties` (add `yamlLdPath`, `yamlLdRequired`, `root: RootProperties`), rewrite `ConceptMappingProperties` (add `iriField`, `typeField`, `additionalProperties`), rewrite `RelationsMappingProperties` (add `verbField`, `relationsPath`). Keep `@ConfigurationProperties(prefix = "app.graph")`.
- [X] T007 [P] Create `src/main/java/com/witboost/ontology/config/GraphPreset.java` — enum with values `SKOS` and `CUSTOM`; add `@JsonProperty` / `toString` for lowercase YAML binding
- [X] T008 [P] Create `src/main/java/com/witboost/ontology/config/SkosPreset.java` — static factory class holding SKOS default values: prefix map (`skos`, `wb`), root defaults (`isConcept: true`, `type: skos:ConceptScheme`, `verb: skos:inScheme`), concept-mapping defaults (`type: skos:Concept`, `iriField: iri`, `nameField: name`, `properties: {skos:prefLabel→name, skos:definition→description}`), relations-mapping defaults (`verb: skos:narrower`, `relationsPath: relations`)

### 2B – Domain Records (can all run in parallel)

- [X] T009 [P] Create `src/main/java/com/witboost/ontology/domain/graph/ConceptEntry.java` — Java record with fields: `String iri`, `String name`, `String type`, `Map<String, String> properties`, `List<RelationEntry> relations`. Include compact constructor validating non-null `iri`.
- [X] T010 [P] Create `src/main/java/com/witboost/ontology/domain/graph/RelationEntry.java` — Java record with fields: `String targetIri`, `String verb`
- [X] T011 [P] Create `src/main/java/com/witboost/ontology/domain/graph/GraphPartition.java` — Java record with fields: `String systemUrn`, `String content`, `java.time.Instant updatedAt`
- [X] T012 [P] Create `src/main/java/com/witboost/ontology/domain/validation/GraphValidationError.java` — record with fields: `String field`, `String message`, `String prefix` (nullable). Add static factory methods `unknownPrefix(String field, String prefix)`, `missingField(String field)`, `iriCollision(String iri)`.

### 2C – Database Migration

- [X] T013 Create `src/main/resources/db/migration/V2__per_system_storage.sql`:
  ```sql
  DROP TABLE IF EXISTS graph_state;
  CREATE TABLE graph_state (
      system_urn  VARCHAR     PRIMARY KEY,
      content     TEXT        NOT NULL,
      updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
  );
  ```

### 2D – Config Merge Service

- [X] T014 Create `src/main/java/com/witboost/ontology/application/PresetMergeService.java` — `@Service` with `@PostConstruct init()` that reads `GraphProperties`, detects `preset`, merges `SkosPreset` defaults under SKOS (scalar fields: null-fallback to preset; map fields: start from preset, `putAll` user-supplied), then merges `additionalProperties` onto `properties`. Write merged state back into the same `GraphProperties` bean. Constructor-inject `GraphProperties`.
- [X] T015 Write `src/test/java/com/witboost/ontology/application/PresetMergeServiceTest.java` — tests: (a) SKOS preset with empty user config → all SKOS defaults applied; (b) SKOS preset with user `additionalProperties` → merged onto preset properties; (c) SKOS preset with user override of `concept-mapping.type` → user value wins; (d) CUSTOM preset → no defaults applied, fields remain as configured

### 2E – Update application.yaml

- [X] T016 Update `src/main/resources/application.yaml` to remove old `domain-mappings`, `business-object-mappings`, `id-field`, `name-field`, `domains-field`, `narrower-field` keys and replace with the new `app.graph.preset`, `prefixes`, `descriptor`, `concept-mapping`, `relations-mapping` structure per the config schema in `plan.md`

**Checkpoint**: `./mvnw test` passes. New config classes load, `PresetMergeService` applies SKOS defaults correctly, domain records compile. DB migration V2 exists.

---

## Phase 3: User Story 1 – Custom Ontology / YAML-to-RDF Mapping (Priority: P1) 🎯 MVP

**Goal**: Full Config A pipeline — parse descriptor concept list, resolve IRIs, expand prefixes, write RDF triples, store per-system, serve via KGM endpoint.

**Independent Test**: Deploy with `preset: custom` and OWL config. Provision a descriptor with two OWL classes and a property. `GET /v1/graph/jsonld` returns graph with `owl:Class` triples and zero SKOS URIs.

### Implementation

- [X] T017 [P] [US1] Create `src/main/java/com/witboost/ontology/application/PrefixResolver.java` — `@Service`; constructor-inject `GraphProperties`; method `Map<String, String> resolve(Map<String, Object> descriptorFields)` merges `prefixes.literals` with field-derived `@context` (field wins); method `String expand(String value, Map<String, String> prefixMap)` expands prefixed shorthands to full IRIs; throws `UnknownPrefixException` for unknown prefix labels (not `urn:` — those go to FR-006 path). Also create `src/main/java/com/witboost/ontology/application/UnknownPrefixException.java`.
- [X] T018 [P] [US1] Create `src/main/java/com/witboost/ontology/application/IriDerivationService.java` — `@Service`; method `String deriveRootIri(String systemUrn)` strips `urn:dmb:dp:`, slugifies, expands with `wb:` prefix; method `String deriveConceptIri(String rootIri, String conceptName)` appends `-` + slugified name; method `String slugify(String input)` lowercases, replaces non-`[a-z0-9-]` with `-`, collapses consecutive hyphens, strips leading/trailing hyphens.
- [X] T019 [US1] Create `src/main/java/com/witboost/ontology/application/MappingEngine.java` — `@Service`; constructor-inject `GraphProperties`, `PrefixResolver`, `IriDerivationService`; primary method `List<ConceptEntry> parse(DataProductDescriptor descriptor)` that: (1) resolves prefix map; (2) extracts concept list from `businessConceptsPath`; (3) for each concept: resolve IRI (explicit `iri-field` → check for `urn:` warning → expand; else derive); (4) resolve `type` (per-concept `typeField` override wins, else `conceptMapping.type`); (5) resolve `properties` → mapped field values; (6) resolve `relations` list → `RelationEntry` per entry using `verb-field` override or default `verb`; (7) check intra-descriptor IRI uniqueness → throw `GraphValidationError` on collision; returns `List<ConceptEntry>`. Separately: method `ConceptEntry parseRoot(DataProductDescriptor descriptor, String rootIri)` when `root.isConcept` is true.
- [X] T020 [US1] Rewrite `src/main/java/com/witboost/ontology/application/GraphService.java` — remove all domain-sync methods (`syncDomainsFromCatalog`, `syncDomains`, `findRootScheme`, `linkConceptToDomain`, `resolveDomainResource`, `pruneUnlinkedDomains`), remove `WitboostCatalogClient` dependency; keep `init()` (adapt to load from new per-system schema — load all rows and merge into in-memory model), keep `getJsonLd()` delegating to `GraphCacheService`; rewrite `provision(DataProductDescriptor)` to call `MappingEngine.parse()` → build Jena model from `ConceptEntry` list → call `GraphRepository.upsert(systemUrn, jsonLd)` → invalidate cache; rewrite `unprovision()` to call `GraphRepository.delete(systemUrn)` → invalidate cache; remove all SKOS.* hardcoded vocabulary references. Constructor-inject `MappingEngine`, `GraphCacheService`, `GraphRepository`, `GraphProperties`.
- [X] T021 [US1] Create `src/main/java/com/witboost/ontology/application/GraphRepository.java` — `@Repository` wrapping `JdbcTemplate`; methods: `void upsert(String systemUrn, String content)` (PostgreSQL upsert on PK), `void delete(String systemUrn)`, `List<GraphPartition> findAll()`. Keeps DB logic isolated from service layer. **Security**: ALL SQL MUST use `?` parameterised placeholders for data values; NEVER use `String.format` or string concatenation to build SQL; rely on `currentSchema` set in the JDBC URL so table references need no schema qualification (e.g. `graph_state`, not `{schema}.graph_state`). This is the replacement for the SQL injection found in the pre-existing `GraphService.saveGraph()`/`init()` (fixed by T049).
- [X] T022 [US1] Write `src/test/java/com/witboost/ontology/application/PrefixResolverTest.java` — tests: (a) known prefix expands correctly; (b) `urn:` value not treated as unknown prefix (returns as-is); (c) unknown prefix throws `UnknownPrefixException` with field identified; (d) field-derived prefix wins over literal on conflict
- [X] T023 [US1] Write `src/test/java/com/witboost/ontology/application/IriDerivationServiceTest.java` — tests: (a) `urn:dmb:dp:domain:registry-a:0` → `https://witboost.com/type#domain-registry-a-0`; (b) concept IRI derivation appends slugified name; (c) slugify handles colons, spaces, uppercase; (d) leading/trailing hyphens stripped
- [X] T024 [US1] Write `src/test/java/com/witboost/ontology/application/MappingEngineTest.java` — tests: (a) custom preset produces correct `ConceptEntry` list with expanded IRIs; (b) explicit `iri-field` value used directly; (c) `urn:` in `iri-field` → warning logged, derived IRI used; (d) intra-descriptor IRI collision → `GraphValidationError`; (e) `type-field` per-concept override wins over default; (f) relation entries resolved with verb-field override
- [X] T025 [US1] Write `src/test/java/com/witboost/ontology/application/GraphServiceTest.java` (rewrite) — tests: (a) provision calls MappingEngine, writes to GraphRepository, invalidates cache; (b) unprovision deletes from GraphRepository, invalidates cache; (c) root concept added as RDF resource when `root.isConcept: true`; use `@ExtendWith(MockitoExtension.class)`, mock all dependencies

**Checkpoint**: US1 fully functional. `./mvnw test` green. OWL descriptor provisions to correct triples; no SKOS URIs in output.

---

## Phase 4: User Story 4 – Per-System Graph Storage (Priority: P2)

**Goal**: Merged-graph cache with atomic per-system upsert/delete. US4 is prerequisite infrastructure for US3 and must be completed before US3.

**Independent Test**: Provision System A and System B. Unprovision System A. `SELECT system_urn FROM graph_state` returns only System B's URN. `GET /v1/graph/jsonld` returns only System B's triples.

> Note: `GraphRepository` was introduced in T021 (US1). This phase adds `GraphCacheService` and wires the KGM endpoint.

### Implementation

- [X] T026 [US4] Create `src/main/java/com/witboost/ontology/application/GraphCacheService.java` — `@Service`; holds `volatile String cachedJsonLd`; method `String getOrBuild(Supplier<String> builder)` — returns cached value if non-null, else calls builder, stores, returns; method `void invalidate()` sets field to `null`. The builder supplier (provided by `GraphService`) loads all rows from `GraphRepository.findAll()`, deserialises each into a Jena `Model`, merges all models via `Model.add(Model)`, serialises to JSON-LD.
- [X] T027 [US4] Modify `src/main/java/com/witboost/ontology/interfaces/rest/KgmSourceController.java` to delegate `getJsonLd()` to `GraphCacheService` instead of `GraphService.getJsonLd()` directly. Remove `synchronized` from `GraphService.getJsonLd()` — cache handles this.
- [X] T028 [US4] Write `src/test/java/com/witboost/ontology/application/GraphCacheServiceTest.java` — tests: (a) cache returns existing value without calling builder on second call; (b) after `invalidate()`, next call invokes builder again; (c) concurrent invalidate + read produces a valid (non-null) result
- [X] T029 [US4] Write `src/test/java/com/witboost/ontology/interfaces/rest/KgmSourceControllerTest.java` (update) — verify `GET /v1/graph/jsonld` returns correct `Content-Type: application/ld+json` and delegates to `GraphCacheService`

**Checkpoint**: US4 fully functional. Two systems can be provisioned and unprovisioned independently. Cache invalidates and rebuilds correctly.

---

## Phase 5: User Story 2 – SKOS Preset & Backward Compatibility (Priority: P2)

**Goal**: `preset: skos` produces semantically identical output to the pre-feature baseline. Depends on US1 (MappingEngine must exist).

**Independent Test**: Provision an existing SKOS descriptor against upgraded adapter with `preset: skos`, no other config changes. Resulting graph is semantically equivalent to pre-feature output.

### Implementation

- [X] T030 [P] [US2] Verify `PresetMergeService` (T014–T015) applies all SKOS defaults: `skos:ConceptScheme` root type, `skos:inScheme` root verb, `skos:Concept` concept type, `skos:prefLabel`/`skos:definition` properties, `skos:narrower` relation verb. Add any missing SKOS default wiring.
- [X] T031 [US2] Write `src/test/java/com/witboost/ontology/application/SkosPresetIntegrationTest.java` — provision a canonical SKOS descriptor (name, description, nested concepts with narrower relations) in `preset: skos` configuration; assert output graph contains: root typed `skos:ConceptScheme`, concepts typed `skos:Concept`, `skos:inScheme` triples, `skos:prefLabel` literals, `skos:narrower` relations; assert zero non-SKOS/wb/rdf triples
- [X] T032 [US2] Write regression test in `src/test/java/com/witboost/ontology/application/SkosPresetIntegrationTest.java` — user `additionalProperties: {wb:category: category}` merges onto preset properties; user override of `concept-mapping.type` wins over SKOS default

**Checkpoint**: US2 fully functional. SKOS backward compatibility proven by test. Existing SKOS deployments can upgrade without descriptor changes.

---

## Phase 6: User Story 3 – JSON-LD Pass-through / Config B (Priority: P3)

**Goal**: When `yaml-ld-path` is configured and the field is present in the descriptor, store the embedded JSON-LD verbatim as the system's graph partition. Depends on US4 (per-system storage must exist).

**Independent Test**: Configure `yaml-ld-path: graph_definition`. Provision a descriptor with a JSON-LD object at `graph_definition`. `GET /v1/graph/jsonld` returns those exact triples with no adapter-injected concept-mapping triples.

### Implementation

- [X] T033 [P] [US3] Create `src/main/java/com/witboost/ontology/application/PassthroughEngine.java` — `@Service`; constructor-inject `GraphProperties`; method `Optional<String> extractYamlLd(DataProductDescriptor descriptor)` reads the field at `descriptor.yamlLdPath` from `descriptor.extraFields`; returns `Optional.empty()` if path not configured or field absent; returns `Optional.of(jsonLdString)` if present and non-null
- [X] T034 [US3] Update `GraphService.provision()` to check `PassthroughEngine.extractYamlLd()` first: if present → store directly via `GraphRepository.upsert()` (skip `MappingEngine`); if absent and `yamlLdRequired: true` → add `GraphValidationError` and throw; if absent and `yamlLdRequired: false` → fall through to `MappingEngine` path
- [X] T035 [US3] Write `src/test/java/com/witboost/ontology/application/PassthroughEngineTest.java` — tests: (a) `yaml-ld-path` not configured → `Optional.empty()`; (b) field present → returns JSON-LD string; (c) field absent, `yaml-ld-required: false` → `Optional.empty()` (fall-through); (d) field absent, `yaml-ld-required: true` → throws validation error identifying missing field
- [X] T036 [US3] Write `src/test/java/com/witboost/ontology/application/GraphServicePassthroughTest.java` — integration-style tests (Mockito): (a) pass-through path stores raw JSON-LD without calling MappingEngine; (b) re-provision replaces previous JSON-LD entirely; (c) unprovision removes the partition; (d) mixed deployment: System A via pass-through, System B via Config A — both visible in merged graph

**Checkpoint**: US3 fully functional. Pass-through round-trip verified: embedded JSON-LD stored verbatim, no adapter-injected triples.

---

## Phase 7: Validation Endpoint & Error Handling (Cross-cutting)

**Purpose**: Wire FR-017 validation into `POST /v1/validate`; add `@RestControllerAdvice`; ensure all validation errors surface cleanly.

- [X] T037 Create `src/main/java/com/witboost/ontology/interfaces/rest/GlobalExceptionHandler.java` — `@RestControllerAdvice` handling **HTTP-level errors only** (descriptor-level validation is handled inside each controller method, not here). Whenever a `RequestValidationError` or `SystemError` is produced, **all fields must be populated**: keep `errors[]` / `error` (deprecated but required for backward compat), and also set `userMessage` (friendly operator-facing sentence), `moreInfo.problems[]` (technical problem list, one entry per issue), `moreInfo.solutions[]` (actionable fix suggestions where known), `inputErrorField` (dot-path to the offending field, when determinable): (a) `HttpMessageNotReadableException` (malformed/unparseable JSON body) → **400** `RequestValidationError{errors: [rawMessage], userMessage: "The request body could not be parsed. Please verify the JSON is valid.", moreInfo: {problems: [rawMessage], solutions: ["Ensure the request body is valid JSON and the Content-Type header is application/json"]}, inputErrorField: null}`; (b) `MethodArgumentNotValidException` (Jakarta Bean Validation on `@Valid` request body) → **400** `RequestValidationError` with one problem entry per constraint violation and `inputErrorField` set to the violated field name; (c) uncaught `RuntimeException` → **500** `SystemError{error: message, userMessage: "An unexpected error occurred. Please contact the platform team.", moreInfo: {problems: [message], solutions: ["Check the adapter logs for details"]}}`. `GraphValidationException` is **NOT** handled here — each controller method catches it internally and converts it to the correct 200 response. **NO 422 responses are ever produced by this adapter.**
- [X] T038 Create `src/main/java/com/witboost/ontology/application/GraphValidationException.java` — unchecked exception wrapping `List<GraphValidationError>`; thrown by `MappingEngine`, `PrefixResolver`, `PassthroughEngine` on validation failures
- [X] T039 Update `src/main/java/com/witboost/ontology/interfaces/rest/TechAdapterController.java` following the OpenAPI contract exactly — **no 422 anywhere**: (a) `validate()`: deserialise descriptor, call `MappingEngine.parse()` / pass-through check; **catch `GraphValidationException` internally** → return `200 ResponseEntity<ValidationResult>` with `valid=false` and `error.errors` listing each `GraphValidationError` message; on success → `200 ValidationResult{valid: true}`; (b) `provision()`: call `GraphService.provision()`; **catch `GraphValidationException` internally** → return `200 ResponseEntity<ProvisioningStatus>` with `status=FAILED` and `info.publicInfo=ValidationResult{valid: false, error.errors=[...]}` per the OpenAPI `failedProvision` example; on success → `200 ProvisioningStatus{status: COMPLETED, info.publicInfo.valid: true}`; (c) `unprovision()`: same FAILED/COMPLETED pattern; (d) remove raw `catch (Exception e) { return ResponseEntity.status(500)... }` blocks — unexpected exceptions propagate to `GlobalExceptionHandler` → 500; malformed JSON body is handled by Spring + `GlobalExceptionHandler` → 400 `RequestValidationError`.
- [X] T040 Write `src/test/java/com/witboost/ontology/interfaces/rest/TechAdapterControllerTest.java` — `@WebMvcTest`; **validate endpoint** (all return HTTP 200 per OpenAPI): (a) valid descriptor → `{"valid": true}`; (b) unknown prefix in config → HTTP 200 `{"valid": false, "error": {"errors": ["Unknown prefix 'owl' in concept-mapping.type"]}}`; (c) `yaml-ld-required: true` field absent → HTTP 200 `{"valid": false, ...}`; (d) IRI collision within descriptor → HTTP 200 `{"valid": false, ...}`. **provision endpoint**: (e) valid descriptor → HTTP 200 `{"status": "COMPLETED", "info": {"publicInfo": {"valid": true}}}`; (f) descriptor-level validation error → HTTP 200 `{"status": "FAILED", "info": {"publicInfo": {"valid": false, "error": {"errors": [...]}}}}`. **Error handling** (assert full RequestValidationError / SystemError population — not just the deprecated field): (g) malformed JSON body on any endpoint → HTTP 400 with `userMessage` non-null AND `moreInfo.problems` non-empty AND `errors` non-empty (backward compat); (h) missing `descriptor` field in request body → HTTP 400 with `inputErrorField` set to the field path `descriptor` and `moreInfo.solutions` non-empty. **No test expects HTTP 422.**

**Checkpoint**: Validation endpoint surfaces all FR-017 errors as structured responses. No raw 500s from controller.

---

## Phase 8: Logging & MDC (FR-019)

**Purpose**: Add structured SLF4J/MDC log entries to provision/unprovision per constitution Principle III.

- [X] T041 Update `src/main/java/com/witboost/ontology/application/GraphService.java` — add MDC context in `provision()` and `unprovision()`: `MDC.put("systemUrn", descriptor.getId())`, `MDC.put("strategy", preset/passthrough)`, `MDC.put("conceptCount", ...)`, `MDC.put("operation", "provision"|"unprovision")`; log INFO on success with outcome; log WARN for URN-as-IRI fallback (FR-006); clear MDC in `finally` block

**Checkpoint**: Provision/unprovision log entries contain `systemUrn`, `strategy`, `conceptCount`, `operation`.

---

## Phase 9: Documentation (Constitution Principle X)

**Purpose**: Ship operator-facing docs in the same MR as the code changes per constitution.

- [X] T042 [P] Create `docs/architecture.md` — high-level adapter overview: what it does, Config A vs Config B decision flow (diagram in ASCII or Mermaid), per-system storage model, preset system, IRI derivation algorithm with worked example, endpoint list. Target audience: platform engineers.
- [X] T043 [P] Create `docs/configuration.md` — complete operator reference: quick-start (minimal SKOS config), full field reference table per subsection (`prefixes`, `descriptor`, `descriptor.root`, `concept-mapping`, `relations-mapping`) with type/default/required/description columns, SKOS preset defaults table, 4 worked examples (SKOS minimal, SKOS with overrides, custom OWL, YAML-LD pass-through) each with sample descriptor + expected triples, common validation errors with exact messages, migration guide from pre-feature version.

**Checkpoint**: `docs/` directory contains both files. Each example in `configuration.md` is runnable via `quickstart.md` scenarios.

---

## Phase 10: Polish & Cross-Cutting Concerns

**Purpose**: Final cleanup, coverage verification, Spotless, and quickstart validation.

- [ ] T044 Run `./mvnw spotless:apply` and commit formatting corrections across all new/modified Java files
- [ ] T045 Run `./mvnw test` and verify JaCoCo HTML report at `target/site/jacoco/index.html` shows ≥ 80 % line coverage for all new service classes (`MappingEngine`, `PassthroughEngine`, `PrefixResolver`, `IriDerivationService`, `PresetMergeService`, `GraphCacheService`, `GraphRepository`)
- [ ] T046 Run `./mvnw compile` with SpotBugs enabled and resolve any HIGH/CRITICAL findings; document any necessary suppressions in `suppressions/suppression.xml` with justification
- [ ] T047 [P] Verify the 6 quickstart scenarios from `specs/001-generic-ontology-adapter/quickstart.md` pass against a running adapter with a live PostgreSQL instance
- [X] T048 [P] Remove all `PROP_FROM_CATALOG`, `PROP_FROM_DESCRIPTOR`, `PROP_PLACEHOLDER`, `PROP_KIND` constants from `GraphService.java` if not used in the new implementation; remove the test-only `GraphService(String graphFile)` constructor and replace with proper test fixtures

**Checkpoint**: All tests green. Coverage ≥ 80 %. Spotless clean. SpotBugs zero HIGH. Quickstart scenarios pass.

---

## Phase 11: Reverse-Verb Map (FR-020)

**Purpose**: When a relation triple `A → verb → B` is emitted, automatically emit the inverse `B → reverseVerb → A` if the verb appears as a key in `relations-mapping.reverse-verb-map`. The SKOS preset defaults this map to the three standard SKOS bidirectional pairs. Removes the hardcoded TODO stub in `GraphService`.

**Independent Test**: With `preset: skos`, provision a descriptor with `A skos:narrower B`. Verify the graph contains both `A skos:narrower B` and `B skos:broader A`.

### Implementation

- [X] T050 Update `src/main/java/com/witboost/ontology/config/GraphProperties.java` — add `private Map<String, String> reverseVerbMap = new HashMap<>()` field to `RelationsMappingProperties`; annotate with `@Valid`; add getter. Spring will bind `reverse-verb-map:` YAML entries.
- [X] T051 Update `src/main/java/com/witboost/ontology/config/GraphPresetDefinition.java` — add default method `default Map<String, String> relationsReverseVerbMap() { return Map.of(); }` (already present from prior session work — verify and leave if already done).
- [X] T052 Update `src/main/java/com/witboost/ontology/config/SkosPresetDefinition.java` — override `relationsReverseVerbMap()` to return `Map.of("skos:narrower", "skos:broader", "skos:broader", "skos:narrower", "skos:related", "skos:related")`.
- [X] T053 Update `src/main/java/com/witboost/ontology/application/PresetMergeService.java` — in `init()`, wire `reverseVerbMap` using the same merge pattern as `properties`: start with `preset.relationsReverseVerbMap()` as baseline, then `putAll` from user-configured `graphProperties.getRelationsMapping().getReverseVerbMap()` so user entries win on conflict.
- [X] T054 Update `src/main/java/com/witboost/ontology/domain/graph/ParseResult.java` — add 5th field `Map<String, String> reverseVerbMap`; update the compact record constructor; update all 4 call sites that construct `ParseResult` (in `MappingEngine` and `PassthroughEngine`).
- [X] T055 Update `src/main/java/com/witboost/ontology/application/MappingEngine.java` — in `parse()`, after building the prefix map, expand and validate all keys and values of the merged `reverseVerbMap` using `prefixResolver.expand()`; collect unknown-prefix errors into the shared error list; store the **expanded** (full-IRI) map in `ParseResult`.
- [X] T056 Update `src/main/java/com/witboost/ontology/application/GraphService.java` — in `addConceptToModel()`: (a) for each `RelationEntry`, after emitting the forward triple `source → verb → target`, look up `reverseVerb = parseResult.reverseVerbMap().get(verb)`; (b) if non-null, emit `target → reverseVerb → source`; (c) remove the hardcoded TODO stub (lines ~215-222) that currently hacks `skos:broader` for narrower relations only.
- [X] T057 [P] Write or update tests:
  - `PresetMergeServiceTest`: assert that with `preset: skos` the merged `reverseVerbMap` contains all three SKOS pairs; assert that a user-supplied entry for `"skos:related": "ex:custom"` overrides the preset default.
  - `MappingEngineTest`: (a) valid map with known prefixes → expanded map in `ParseResult`; (b) unknown prefix in key → error collected; (c) unknown prefix in value → error collected; (d) empty map → no errors, `reverseVerbMap` is empty.
  - `SkosPresetIntegrationTest` (or new `GraphServiceReverseVerbTest`): provision descriptor with `A skos:narrower B` → assert graph contains `A skos:narrower B` AND `B skos:broader A`; provision with `A skos:related B` → assert both forward and reverse `skos:related` triples present.
- [X] T058 [P] Update `docs/configuration.md` — add `reverse-verb-map` row to the `relations-mapping` field reference table (type: `Map<String,String>`, default: `{}` for custom / SKOS pairs for skos preset, required: no); add a prose section "Inverse Relation Triples" explaining the mechanism with a worked example showing forward + reverse triples; update the SKOS defaults table to include the three map entries.

**Checkpoint**: Reverse-verb triples emitted correctly for SKOS preset. Custom presets produce no reverse triples by default. Validation rejects unknown prefixes in the map. Hardcoded TODO stub removed.

---

### Phase Dependencies

```
Phase 1 (Setup/Removal)
  └── Phase 2 (Foundational: config POJOs, domain records, DB migration, PresetMergeService)
        ├── Phase 3 (US1: MappingEngine, PrefixResolver, IriDerivationService, GraphService rewrite)
        │     └── Phase 4 (US4: GraphCacheService, KGM endpoint wiring) [US4 depends on GraphRepository from US1]
        │           ├── Phase 5 (US2: SKOS backward compat — depends on MappingEngine)
        │           └── Phase 6 (US3: pass-through — depends on per-system storage)
        └── Phase 7 (Validation — depends on MappingEngine from US1)
Phase 8 (Logging — depends on US1 GraphService)
Phase 9 (Docs — can start after Phase 3 design is stable)
Phase 10 (Polish — depends on all implementation phases)
```

### User Story Dependencies

| Story | Depends On | Can Parallelize With |
|-------|-----------|----------------------|
| US1 (P1) | Phase 2 complete | — (first story) |
| US4 (P2) | US1 (GraphRepository) | US2 starts independently |
| US2 (P2) | Phase 2 + US1 (MappingEngine) | US4 (after its own GraphRepository dep) |
| US3 (P3) | US4 (per-system storage) | US2 (independent) |

### Within Each Story

- Config POJOs → domain records → service classes → controller wiring → tests

### Parallel Opportunities

- T006, T007, T008 (config POJOs) — all different files
- T009, T010, T011, T012 (domain records) — all different files
- T017, T018 (PrefixResolver, IriDerivationService) — different files, no deps
- T022, T023 (tests for T017, T018) — after their implementation tasks
- T026 (GraphCacheService) — independent of T030 (US2 verification)
- T033 (PassthroughEngine) — parallel with T030 (US2)
- T042, T043 (docs) — parallel with each other
- T044, T045, T046, T047, T048 (polish) — mostly parallel

---

## Parallel Example: Phase 2 Foundational

```
Parallel batch 1 (all different files, no deps):
  Task: T006 Rewrite GraphProperties.java
  Task: T007 Create GraphPreset.java enum
  Task: T008 Create SkosPreset.java
  Task: T009 Create ConceptEntry.java record
  Task: T010 Create RelationEntry.java record
  Task: T011 Create GraphPartition.java record
  Task: T012 Create GraphValidationError.java

Sequential:
  Task: T013 Create V2__per_system_storage.sql
  Task: T014 Create PresetMergeService.java (depends on T006, T008)
  Task: T015 Write PresetMergeServiceTest.java (depends on T014)
  Task: T016 Update application.yaml
```

## Parallel Example: US1

```
Parallel batch (different files):
  Task: T017 Create PrefixResolver.java + UnknownPrefixException.java
  Task: T018 Create IriDerivationService.java

Then sequential:
  Task: T019 Create MappingEngine.java (depends on T017, T018)
  Task: T020 Rewrite GraphService.java (depends on T019, T021)
  Task: T021 Create GraphRepository.java

Parallel tests (after implementation):
  Task: T022 PrefixResolverTest.java
  Task: T023 IriDerivationServiceTest.java
  Task: T024 MappingEngineTest.java
  Task: T025 GraphServiceTest.java (rewrite)
```

---

## Implementation Strategy

### MVP First (User Story 1 + Storage)

1. Complete Phase 1: Setup & Removal
2. Complete Phase 2: Foundational (config, records, DB, PresetMergeService)
3. Complete Phase 3: US1 (MappingEngine pipeline)
4. Complete Phase 4: US4 (GraphCacheService, per-system isolation)
5. **STOP and VALIDATE**: Custom ontology provisions correctly, isolation verified
6. Deploy/demo if ready

### Incremental Delivery

1. Phase 1+2 → Clean foundation with new config
2. Phase 3+4 → Custom ontology + storage isolation (MVP)
3. Phase 5 → SKOS backward compatibility
4. Phase 6 → YAML-LD pass-through
5. Phase 7 → Validation endpoint hardening
6. Phase 8+9+10 → Logging, docs, polish

### Total Task Count: 58 tasks

| Phase | Tasks | Stories |
|-------|-------|---------|
| Phase 1: Setup | T049, T001–T005 | — |
| Phase 2: Foundational | T006–T016 | — |
| Phase 3: US1 | T017–T025 | US1 |
| Phase 4: US4 | T026–T029 | US4 |
| Phase 5: US2 | T030–T032 | US2 |
| Phase 6: US3 | T033–T036 | US3 |
| Phase 7: Validation | T037–T040 | Cross-cutting |
| Phase 8: Logging | T041 | Cross-cutting |
| Phase 9: Docs | T042–T043 | Cross-cutting |
| Phase 10: Polish | T044–T048 | Cross-cutting |
| Phase 11: Reverse-Verb Map | T050–T058 | FR-020 |

---

## Notes

- [P] tasks = different files, no dependencies on incomplete tasks
- [Story] label maps each task to the user story it delivers
- Each user story phase is independently completable and testable
- Tests accompany implementation (not TDD-first, but co-delivered)
- `./mvnw spotless:apply` after every coding session
- Commit after each checkpoint
- Constitution gates: Spotless ✅, SpotBugs zero HIGH ✅, JaCoCo ≥ 80 % ✅, docs in same MR ✅

