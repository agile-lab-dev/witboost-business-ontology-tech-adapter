# Research: JSON-LD Auto-root Association

**Date**: 2026-11-12 | **Plan**: [plan.md](plan.md)

- **Decision**: Group `enabled` and `types` under
  `descriptor.yaml-ld-enrichment.add-root-verb`; reuse effective static root type/verb.
  **Rationale**: Keep operation settings together and allow future enrichment
  operations without adding flat descriptor options. Only root-verb enrichment is
  in scope. **Alternatives**: Flat flag/list options obscure their relationship;
  an OWL preset, separate root settings, or explicit descriptor root IRI are unnecessary.
- **Decision**: Declare Titanium JSON-LD 1.1 directly, using version 1.3.2 already
  supplied by Jena ARQ 4.10.0. Expansion is analysis-only: retain the original
  Jackson document and associate each direct graph entry with its expansion by
  expanding it inside a copy of the original document envelope and context.
  Public expansion probes identify graph aliases without internal context APIs.
  An analysis-only wrapper retains the unnamed graph boundary during expansion;
  it is never stored. Use Jena for graph-wide checks, then add absolute-IRI links
  to the original objects and serialize the original document. Verify that the
  result's RDF is exactly the original RDF plus the intended links, rejecting
  contexts that reinterpret the inserted predicate or target.
  **Rationale**: Context-correct selection without rewriting existing contexts,
  aliases, types, identifiers, values, or node placement. Only whitespace and
  formatting may change. The first eligible description receives a missing link;
  an explicit handled-subject set prevents additions to repeated descriptions.
  **Alternatives**: Raw JSON key matching mishandles aliases/contexts; RDF-only
  selection loses which node objects are directly contained in the top-level
  `@graph` array.
- **Decision**: Explicit unnamed top-level graph envelope and inline contexts when
  enabled. **Rationale**: Bounded selection and no external I/O. **Alternatives**:
  arbitrary graph shapes, promotion of node objects from property values or nested
  graphs into the top-level `@graph` array,
  or remote context fetching broaden scope.
- **Decision**: Shared `PassthroughEngine.extractYamlLd` integration.
  **Rationale**: Validation controller and provisioning already use this path.
  A dedicated `RootVerbEnricher` owns analysis, selection, and append-only updates;
  the pass-through engine only extracts the payload and delegates when enabled.
  **Alternatives**: Provision-only processing delays user feedback.

No unresolved design clarifications. Expansion, duplicate descriptions, aliases,
and structural idempotence require focused implementation tests.
