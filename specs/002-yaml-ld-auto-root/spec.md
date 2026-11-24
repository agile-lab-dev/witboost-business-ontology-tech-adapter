# Feature Specification: JSON-LD Auto-root Association

**Feature ID**: `002-yaml-ld-auto-root` | **Created**: 2026-11-12 | **Status**: Implemented

Independent enhancement to the existing adapter. Planning is on the current
`master` branch; the feature ID does not imply a Git branch was created.

## Requirements

- Add `descriptor.yaml-ld-enrichment.add-root-verb` with `enabled` (default `false`)
  and optional `types` (default empty: all typed candidates). The enrichment object
  allows future operations alongside `add-root-verb`; no others are introduced here.
- Reuse effective static `root.type` and `root.verb`, including preset defaults
  and explicit overrides. Detect exactly one named root among explicitly typed
  node objects directly contained in the top-level `@graph` array; reject missing
  or ambiguous roots.
- Add missing verbs from other named, explicitly typed node objects directly contained in the top-level
  `@graph` array to the root. A nonempty filter matches any listed type. Skip root,
  blank nodes, untyped references, and resources with an existing value for the
  membership predicate. Nodes described only elsewhere receive no new verb;
  their existing triples remain unchanged.
- Expand payload types/identifiers through JSON-LD contexts; resolve configuration
  CURIEs through adapter prefixes. Preserve original triples and graph membership
  boundaries. Use expansion only for analysis; retain the original contexts,
  aliases, identifiers, types, values, and structure in the output, adding only
  missing links with absolute predicate/target IRIs. Formatting may change.
  Repeated processing must return the same output.
- Validate through `/validate` and provisioning before writes or other side effects.
  Disabled behavior, Config A, and missing-payload required/fallback rules stay unchanged.

## Acceptance

1. One ontology and two eligible node objects directly contained in the top-level
  `@graph` array produce two verbs. A node described only inside a property value
  gains none. Optional filters exclude nonmatching types.
2. Existing memberships remain unchanged; duplicate root descriptions count once;
  multiple types on a node use any-match filtering. Repeated candidate descriptions
  receive a missing link only on the first eligible entry. Existing JSON structure
  and contexts are retained.
3. Missing/ambiguous root, invalid configuration, unsupported graph shape, or invalid
   JSON-LD produces a structured error without storage/cache/ingestion changes.
4. Disabled pass-through and Config A retain existing behavior.

## Non-goals

No OWL preset, RDF/XML input, root creation/IRI override, namespace filter, import
fetching, database migration, or REST schema changes. The precise input restrictions
and association policy are in the [contract](contracts/auto-root.md).
