# Implementation Plan: JSON-LD Auto-root Association

**Date**: 2026-11-12 | **Current Git branch**: `master` | **Spec**: [spec.md](spec.md)

## Summary

Opt-in Config B enrichment under `descriptor.yaml-ld-enrichment.add-root-verb`,
with `enabled`/`types` settings and existing static root type/verb settings.
The enrichment object allows future operations; only `add-root-verb` is in scope.
No changes to the original feature artifacts or Config A.

## Technical Context

Java 25, Spring Boot, Jackson, Jena 4.10.0 and explicitly declared Titanium 1.3.2;
PostgreSQL storage unchanged. JUnit/Mockito tests; existing platform/scale targets.
Use whole-document and per-entry expansion with local contexts; no network I/O,
inference, or cross-partition work. Per-entry expansion repeats context processing;
the pipeline is not a single linear document pass.

## Implementation Sequence

1. Extend `GraphProperties.DescriptorProperties` with initialized nested
   `yamlLdEnrichment.addRootVerb` objects (`enabled=false`, `types=[]`). Validate
   enabled configuration using merged preset settings and `PrefixResolver`;
   do not require `root.is-concept` or apply descriptor root overrides.
2. Extend `PassthroughEngine.extractYamlLd` after finding a payload: expand with
   Titanium, retain node objects directly contained in the top-level `@graph` array,
   detect root, and filter candidates.
   Delegate to `RootVerbEnricher`: use Jena for graph-wide existing-membership checks,
   track handled subjects explicitly, and add links to the original Jackson entries.
   Serialize the original document, retaining contexts, aliases, and node placement.
   Validate that contexts do not reinterpret the inserted links.
3. Keep `/validate` and provision on the same path. Extend structured validation
   errors; fail before writes, cache invalidation, or ingestion. Isolate processing
   in an application collaborator only if this reduces real complexity.
4. Extend existing pass-through/service/controller tests, reusing fixtures and
   covering distinct regressions rather than redundant matrices.
5. Update README/configuration reference with defaults, errors, and runnable SKOS
   and custom OWL examples. Do not enable enrichment in local configuration by default.

## Constitution Check

Pre/post-design: PASS. Preserve typed boundaries, constructor injection, early
validation, structured errors/MDC, and deterministic tests. No SQL or new runtime
dependency; Titanium is made explicit at its existing version. Required implementation gates: focused tests, Maven suite, Spotless,
SpotBugs, JaCoCo >=80%; existing security/release gates remain applicable.

## Artifacts

[Research](research.md), [data model](data-model.md), [contract](contracts/auto-root.md),
and [validation guide](quickstart.md). Implementation tasks: [tasks.md](tasks.md).
Implementation complete; tests and coverage pass. Existing SpotBugs findings remain
unchanged; see [validation results](quickstart.md#validation-results).
The repository lacks `update-agent-context.sh`; agent-context refresh is unavailable.
