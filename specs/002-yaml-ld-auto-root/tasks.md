# Tasks: JSON-LD Auto-root Association

**Input**: [spec](spec.md), [plan](plan.md), [contract](contracts/auto-root.md), [validation guide](quickstart.md).
**Scope**: Existing Java adapter; no scaffolding, new dependencies, database changes, or new endpoints.
**Tests**: Extend existing cases and reuse fixtures; cover distinct regressions without duplicating matrices across layers.

Reuse existing prefix configuration, `PrefixResolver`, merged `root.type`/`root.verb`,
payload extraction/fallback, structured errors, and persistence/cache/ingestion paths.
Do not change their semantics or introduce another resolver, preset, or enrichment
framework. New behavior is limited to parsing the enabled payload, matching eligible
nodes against configured types, adding missing verbs to the root, and serialization.
Existing safety and preservation requirements in the contract still apply.

The compact spec has no named user stories. US1 maps to acceptance 1-2 (enrichment);
US2 maps to acceptance 3-4 (safe rejection and compatibility). Both are release requirements.
Paths below are repository-relative. `[P]` marks independent work within its phase.

## Phase 1: Setup

- [X] T001 Confirm Java 25 and the existing Titanium dependency through `pom.xml`; run `./mvnw -Dtest=PassthroughEngineTest,GraphServicePassthroughTest,TechAdapterControllerTest test` as the baseline, without adding dependencies.

## Phase 2: Foundational

- [X] T002 Add initialized `yamlLdEnrichment.addRootVerb` configuration objects with `enabled=false` and `types=[]` in `src/main/java/com/witboost/ontology/config/GraphProperties.java`; preserve existing root and preset defaults.

## Phase 3: US1 - Associate Eligible Nodes (P1)

**Goal**: Opted-in SKOS/custom graphs gain only the missing entity-to-root verbs.
**Independent check**: One root and two eligible nodes produce two verbs; filtering,
existing verbs, original triples, and repeat processing behave as specified.

- [X] T003 [P] [US1] Extend `src/test/java/com/witboost/ontology/application/PresetMergeServiceTest.java` only for binding/defaults of the new nested enrichment options and omitted objects; retain existing preset/root tests without duplicating them.
- [X] T004 [P] [US1] Extend `src/test/java/com/witboost/ontology/application/PassthroughEngineTest.java` with reusable JSON-LD input covering root selection, any-match filtering, duplicate descriptions, eligible node objects directly contained in the top-level `@graph` array, excluded nodes, existing memberships, and semantic/structural idempotence. Include one focused integration check that existing configuration expansion matches JSON-LD-expanded payload types; exercise context/alias/base/scoped-context handling without retesting the JSON-LD library or repeating existing Config A/prefix-resolution matrices.
- [X] T005 [US1] Expand enabled payloads with Titanium in `src/main/java/com/witboost/ontology/application/PassthroughEngine.java`; require the unnamed top-level graph envelope (including aliases), retain its direct node objects before RDF conversion, and install a rejecting document loader so remote contexts and `@import` cannot trigger I/O; never fetch ontology imports.
- [X] T006 [US1] Reuse effective merged `root.type`/`root.verb` and the existing `PrefixResolver` for configuration values, including the new optional type filter, in `src/main/java/com/witboost/ontology/application/PassthroughEngine.java`; add only unique-root selection, eligible-node matching, graph-wide existing-predicate checks using Jena, and missing absolute-IRI verbs. Preserve all triples and node placement on serialization; do not modify resolver/preset logic or create roots, inverse verbs, or verbs from a node to itself.
- [X] T007 [US1] Extend `src/test/java/com/witboost/ontology/application/GraphServicePassthroughTest.java` to verify enriched payload persistence and resulting triples through the merged graph read path, without repeating engine-level cases.

## Phase 4: US2 - Reject Safely and Preserve Compatibility (P1)

**Goal**: Validation and provisioning agree on failures; existing modes stay unchanged.
**Independent check**: Invalid input returns structured errors with no writes, cache
invalidation, ingestion, or network access; disabled and fallback paths retain behavior.

- [X] T008 [P] [US2] Extend `src/test/java/com/witboost/ontology/interfaces/rest/TechAdapterControllerTest.java` with a representative enrichment failure through the existing structured response path; reuse existing disabled/Config A validation coverage.
- [X] T009 [P] [US2] Extend `src/test/java/com/witboost/ontology/application/GraphServicePassthroughTest.java` to assert enrichment failure causes no upsert, cache invalidation, or ingestion; reuse existing missing-payload required/fallback and disabled-payload cases rather than duplicating them.
- [X] T010 [US2] Extend `src/test/java/com/witboost/ontology/application/PassthroughEngineTest.java` for enrichment-specific failures: missing/ambiguous roots, invalid configuration integration, blank/null filter entries, malformed JSON-LD, unsupported envelopes, remote contexts/`@import` rejected before network access, and disabled mode bypassing enrichment parsing/configuration checks. Rely on existing `PrefixResolverTest` coverage for resolver behavior itself.
- [X] T011 [US2] Surface enrichment failures through existing `GraphValidationException` handling in `src/main/java/com/witboost/ontology/application/PassthroughEngine.java`; leave extraction/required/fallback guards intact and bypass enrichment when disabled. Verify the existing shared call path without changing `src/main/java/com/witboost/ontology/interfaces/rest/TechAdapterController.java` or `src/main/java/com/witboost/ontology/application/GraphService.java` unless a failing integration check demonstrates a necessary local fix.

## Phase 5: Polish and Verification

- [X] T012 Add a concise enrichment configuration example and its defaults, candidate policy, absolute-IRI output, no-network restrictions, and errors to `docs/configuration.md`; link from `README.md` only where needed. Reuse existing root/prefix documentation; do not add debugging features or enable local enrichment by default.
- [X] T013 Run focused tests from `specs/002-yaml-ld-auto-root/quickstart.md`, then `./mvnw test`, `./mvnw spotless:check`, and `./mvnw spotbugs:check`; inspect `target/site/jacoco/index.html` for at least 80% line coverage, preserve existing security/release gates in `pom.xml` and `.gitlab-ci.yml`, and record results in `specs/002-yaml-ld-auto-root/quickstart.md`.

**Verification**: 122 tests pass, Spotless passes, line coverage is 88.07%.
The POM does not register the SpotBugs prefix; the full plugin invocation reports
76 medium findings, identical to untouched HEAD, and no new findings. See the
[validation results](quickstart.md#validation-results); the existing static-analysis
gate remains failing and is not waived by completing this checklist.

## Dependencies and Parallel Work

`T001 -> T002 -> US1 (T003-T007) -> US2 (T008-T011) -> T012 -> T013`.
Within US1, complete T003/T004 before T005/T006, then T007. Within US2, write
T008-T010 before T011. Run the smallest relevant test after each implementation change.

- **US1 parallel example**: T003 (configuration tests) and T004 (engine tests) touch different files.
- **US2 parallel example**: T008 (controller tests) and T009 (service tests) touch different files.
- Shared engine implementation is sequential; do not parallelize edits to the same file.

## Implementation Strategy

Build US1 as the MVP and verify its happy path independently. Complete US2 before
release; safety and compatibility are not optional follow-ups. Finish with operator
documentation and the existing quality gates. Default to existing classes and
utilities; no unrelated refactoring or generic enrichment framework.
