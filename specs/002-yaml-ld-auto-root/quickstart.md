# Validation Guide: JSON-LD Auto-root Association

Prerequisites: Java 25, Maven wrapper, mocked
storage/external clients; no live database or network for focused tests.
See the [contract](contracts/auto-root.md) and [data model](data-model.md).

```sh
./mvnw -Dtest=PresetMergeServiceTest,PassthroughEngineTest,GraphServicePassthroughTest,SkosPresetIntegrationTest,TechAdapterControllerTest test
```

Existing engine, service, and controller tests cover these scenarios:
1. Default SKOS: one scheme plus two typed concepts described as node objects directly
   contained in the top-level `@graph` array gains `skos:inScheme` verbs;
   verify resulting triples through the merged graph read path.
2. Custom OWL using full IRIs, then configured CURIEs: filter classes/properties;
   only enrich eligible node objects directly contained in the top-level `@graph`
   array. Skip nonmatching individuals, untyped references, blank nodes, and root.
   Nodes described only inside property values or nested graphs receive no new verb.
   Preserve existing triples and memberships. A named, typed node also described
   in the top-level array remains eligible.
3. Exercise inline contexts, aliases, base/scoped contexts, scalar/array types,
   duplicate descriptions, and reprocessing: retain the original document's contexts,
   aliases, IDs, types, values, and node placement. Add a link only to the first
   eligible description of a repeated subject. A second pass returns identical text.
   Test existing full-IRI and aliased links, append-only updates to null/empty values,
   and rejection of contexts that suppress the inserted predicate.
4. Reject invalid config/prefix/filter entries, malformed JSON-LD, unsupported
   envelope, missing/multiple roots, and remote contexts in validation/provision.
   Assert no upsert, cache invalidation, or ingestion trigger on failure.
5. Flag disabled and missing-payload fallback preserve existing behavior; Config A
   regression tests continue passing. Reuse fixtures; avoid redundant matrices.

Then run `./mvnw test`, `./mvnw spotless:check` and inspect JaCoCo >=80%.
The current POM does not register `spotbugs:check`; invoke the plugin explicitly:

```sh
./mvnw com.github.spotbugs:spotbugs-maven-plugin:4.9.8.2:check
```

## Validation Results

### Output Preservation Update

Verified on 2026-11-18 with Java 25.0.1:

- Full suite: 123 tests, zero failures/errors/skips; existing fixtures extended.
- Spotless: PASS; formatting limited to touched Java files.
- JaCoCo: 841/950 covered lines (88.53%), above the 80% requirement.
- SpotBugs 4.9.8.2: FAIL on 76 medium findings, zero high findings, matching
   the previously recorded totals below. No findings reference `RootVerbEnricher`.
- Editor diagnostics and `git diff --check`: PASS.
- No live database, KGM, release/security scans, or performance benchmarks were run.

### Initial Implementation

Verified on 2026-11-12 with Java 25.0.1:

- Full suite: 122 tests, zero failures/errors/skips.
- Spotless: PASS; formatting limited to touched Java files.
- JaCoCo: 775/880 covered lines (88.07%), above the 80% requirement.
- SpotBugs 4.9.8.2: FAIL on 76 existing medium findings, zero high findings.
   An offline analysis of an untouched HEAD snapshot produced the same findings
   by type, priority, class, method, and field; this feature adds none. Generated
   models and existing mutable DTO/configuration exposure warnings were not changed.
   The unversioned plugin resolved to 4.10.5.0 and crashed loading duplicate core
   plugins, hence the explicit version above. No POM/plugin changes were made.
- No live database, KGM, or outbound HTTP was required by the tests. Remote-context
   rejection tests assert no interactions with the default HTTP document loader.
- Existing release/security scans and performance targets remain required; they
   were not executed as part of this local feature verification.
