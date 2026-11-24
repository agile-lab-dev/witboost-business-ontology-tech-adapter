# Data Model: JSON-LD Auto-root Association

**Date**: 2026-11-12 | **Plan**: [plan.md](plan.md)

- Extend `GraphProperties.DescriptorProperties` with an initialized
  `yamlLdEnrichment` configuration object containing an initialized `addRootVerb`
  object: `enabled` (boolean, false) and `types` (list of strings, empty).
- The enrichment object groups operations for future extension; only
  `addRootVerb` is defined in this feature.
- Reuse `RootProperties.type` and `.verb` after preset merge/prefix expansion.
- Transient selection: distinct expanded root IRI, named IRIs and explicit type sets
  from node objects directly contained in the top-level `@graph` array, optional
  expanded filter types, and membership predicate IRI.
  Keep processing state inside the application boundary, not raw REST/domain maps.
- Lifecycle: extract -> expand/select/validate -> add missing verbs -> serialize
  -> existing upsert/cache lifecycle. Failures precede persistent state mutation.
- No persistent entity or migration. Validation rules: [contract](contracts/auto-root.md).
