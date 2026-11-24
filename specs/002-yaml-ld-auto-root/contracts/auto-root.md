# Pass-through Auto-root Contract

All fields are under `app.graph.descriptor`.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `yaml-ld-enrichment.add-root-verb.enabled` | boolean | `false` | Enable association for present pass-through JSON-LD. |
| `yaml-ld-enrichment.add-root-verb.types` | list of IRI/CURIE strings | `[]` | Optional entity-type any-match filter; empty accepts all typed candidates. |
| `root.type` | IRI/CURIE string | Existing preset default | Required effective type for root detection. |
| `root.verb` | IRI/CURIE string | Existing preset default | Required effective membership predicate. |

## Configuration

```yaml
app:
  graph:
    descriptor:
      yaml-ld-enrichment:
        add-root-verb:
          enabled: false
          types: []
```

`yaml-ld-enrichment` groups operations for future extension. Only `add-root-verb`
is defined here; omitting either object preserves the defaults above.

- Enabled configuration requires nonblank `yaml-ld-path`, static root type, and
  static root verb. Resolve config CURIEs with existing adapter prefixes after
  preset merging. Reject unknown prefixes, invalid IRIs, blank/null filter entries.
- Omitted preset remains SKOS; custom requires explicit root type/verb. Root
  `is-concept`, `iri-field`, `type-field`, `verb-field`, and property mappings do
  not participate. No root creation, descriptor override, or URN derivation.
- Missing payload follows existing `yaml-ld-required` and Config A fallback rules.
  Disabled enrichment adds no parsing, normalization, or configuration validation.

## Selection And Association

- Enabled input must be a document object with an unnamed top-level `@graph` array
  (including a context-defined keyword alias). Reject bare arrays, documents without
  that envelope, and named-graph wrappers clearly; disabled behavior is unchanged.
- Standards-based JSON-LD expansion resolves payload contexts, aliases, scoped/base
  contexts, and scalar/array types. Retain which node objects are directly contained
  in the top-level `@graph` array before flattening or RDF conversion. Node objects
  described only inside property values or nested graphs are not candidates; their
  existing triples remain intact. A named, typed node also described in the top-level
  array is eligible.
- Detect exactly one distinct named subject explicitly matching `root.type` among
  node objects directly contained in the top-level `@graph` array, independently
  of the filter. Duplicate descriptions count once.
- Candidates are other named, explicitly typed node objects directly contained in
  the top-level `@graph` array; a nonempty filter requires any matching type.
  Skip root, blank nodes, and untyped references.
  Explicitly described external terms can qualify: this is not ownership inference.
- Preserve every input triple. Add `subject root.verb root` only if the subject has
  no value for that predicate anywhere in the input graph; preserve differing
  existing values, including aliases. Add no inverse verbs or verbs from a node
  to itself. For repeated subjects, add the link only to the first eligible entry;
  explicitly track handled subjects independently of the RDF dataset.
- Expansion is analysis-only. Preserve the original contexts, aliases, identifiers,
  scalar/array representations, values, and node placement. Add new links using
  the absolute predicate IRI and an `@id` object containing the absolute root IRI.
  Append to any existing full-IRI key without replacing its values. Reject contexts
  that reinterpret these additions rather than rewriting the existing context.
- Reprocessing returns the same output. Serialization may change whitespace and
  formatting on the first pass; a pass with no additions returns its input unchanged.

## Errors And Safety

- Do not fetch imports or remote contexts. Reject remote context URLs, including
  `@import`, with a controlled document loader when enabled. Inline namespace IRIs
  are not remote context documents and remain valid.
- `/validate` and provisioning use identical checks and structured errors for bad
  configuration, malformed JSON/JSON-LD, unsupported shape, missing/ambiguous root,
  and expansion failure. Fail before writes, cache invalidation, or ingestion.

No REST response, persistence schema, built-in preset, or Config A changes.
