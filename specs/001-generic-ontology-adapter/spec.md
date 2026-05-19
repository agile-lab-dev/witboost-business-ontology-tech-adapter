# Feature Specification: Generic Ontology Adapter

**Feature Branch**: `001-generic-ontology-adapter`

**Created**: 2026-07-06

**Status**: Draft

---

## Context

The Ontology Tech Adapter currently reads a Witboost YAML
descriptor and builds an RDF graph stored as JSON-LD in PostgreSQL,
then exposes it as a KGM source. The current implementation is
hardwired to SKOS vocabulary (`skos:Concept`, `skos:ConceptScheme`,
`skos:narrower`, `skos:inScheme`, `skos:prefLabel`) and to
Witboost's domain catalog concept. This makes the adapter unusable
for teams that work with OWL, RDFS, or any custom ontology.

This feature replaces that hardwired model with two interchangeable
mapping strategies:

- **Config A – YAML-to-RDF mapping**: The adapter reads the
  descriptor's concept list and transforms it to RDF triples
  according to a fully configurable vocabulary. A `skos` preset
  retains current behaviour for backward compatibility.

- **Config B – YAML-LD pass-through**: When `descriptor.yaml-ld-path`
  is configured, the adapter reads the JSON-LD payload from that
  field and stores it directly as the system's graph, bypassing
  concept mapping entirely. If `yaml-ld-path` is absent or null,
  the adapter falls through to Config A using the active preset.

---

## Clarifications

### Session 2026-07-07

- Q: What is the expected maximum number of concurrently provisioned systems? → A: Primarily ≤ 100 systems (small/medium platform deployment); growth to a few hundred is possible but not the design target.
- Q: What telemetry signals must the adapter emit per provision/unprovision operation? → A: Structured logs only (SLF4J + MDC) in this iteration; OTel spans deferred to a future iteration.
- Q: When simultaneous provision requests arrive, what consistency guarantee is required for the merged-graph cache? → A: Last-write-wins per system URN; cache invalidated on every write and rebuilt only on next read. No explicit locking required.
- Q: Should IRI uniqueness be enforced globally (across partitions) or locally (within the descriptor being provisioned)? → A: Local enforcement only — uniqueness is validated within the descriptor being provisioned; cross-system IRI collisions are the operator's responsibility.
- Q: Must the schema migration from single-row to per-system storage be zero-downtime, or is a maintenance window acceptable? → A: Maintenance window acceptable; Flyway migration may drop and recreate the table in a single step.

---

## User Scenarios & Testing

### User Story 1 – OWL/Custom Ontology via YAML-to-RDF Mapping (Priority: P1)

A data platform engineer wants to publish an OWL ontology for
their domain using the same Witboost provisioning workflow that
SKOS teams already use. Today they cannot — the adapter silently
produces SKOS-flavoured triples regardless of what vocabulary
they configure. With this feature they configure `preset: custom`,
point the adapter at OWL URIs (e.g. `owl:Class`, `rdfs:domain`),
and the correct RDF is produced and exposed via the KGM source
endpoint.

**Why this priority**: Unlocking non-SKOS vocabularies is the
primary driver of this feature; everything else builds on it.

**Independent Test**: Deploy the adapter with a `custom` preset
config referencing OWL prefixes. Provision a descriptor containing
OWL classes and properties. Query `/v1/graph/jsonld` and verify
the returned graph contains `owl:Class` triples and **no** SKOS
vocabulary URIs.

**Acceptance Scenarios**:

1. **Given** a deployment with `preset: custom`, OWL prefixes, and
   `concept-mapping.type: owl:Class`, **When** a descriptor with
   two OWL classes and a property is provisioned, **Then** the
   stored graph contains exactly those three subjects typed with
   their configured OWL classes, zero SKOS URIs, and all
   configured `properties` mappings resolved.

2. **Given** a concept in the descriptor with an explicit `iri`
   field containing a valid HTTP IRI, **When** the concept is
   provisioned, **Then** the RDF subject equals that IRI directly.

3. **Given** a concept without an `iri` field (or with `iri` absent),
   **When** the concept is provisioned, **Then** the subject IRI is
   derived as the root IRI (system URN stripped of `urn:dmb:dp:`,
   slugified, and expanded with the `wb:` prefix) concatenated with
   a hyphen and the slugified concept name, ensuring global
   uniqueness.

4. **Given** `descriptor.root.is-concept: true` and `root.type`
   configured, **When** a descriptor is provisioned, **Then** the
   descriptor itself becomes an RDF resource typed as `root.type`
   with IRI derived from the system URN per the root derivation
   algorithm, and each concept is linked to it via `root.verb`.

5. **Given** a concept whose explicit `iri` field is a Witboost URN
   (e.g. `urn:dmb:...`), **When** provisioned, **Then** the adapter
   logs a WARNING, ignores the invalid URN, and uses the derived IRI
   for that concept's subject instead.

6. **Given** an unprovision request, **When** processed, **Then**
   all triples belonging to that system's graph partition are
   removed without affecting triples from other systems.

---

### User Story 2 – SKOS Preset with Backward Compatibility (Priority: P2)

A team already using the adapter in SKOS preset must continue working
without any changes to their descriptors or deployment config.
The `preset: skos` preset re-applies the hardcoded SKOS vocabulary
as configurable defaults, so existing deployments remain
functionally identical after the upgrade.

**Why this priority**: Existing SKOS users must not be broken by
this change. Backward compatibility validates the preset mechanism.

**Independent Test**: Take an existing SKOS descriptor and
provision it against the upgraded adapter with `preset: skos` and
no other changes. The resulting graph MUST be byte-for-byte
equivalent to what the previous version produced.

**Acceptance Scenarios**:

1. **Given** `preset: skos` and a descriptor identical to the
   pre-feature baseline, **When** provisioned, **Then** the
   resulting JSON-LD graph is semantically equivalent to the
   pre-feature output (same triples, same IRI patterns).

2. **Given** `preset: skos` with an **override** of the default
   `skos:prefLabel` mapping to a different field name, **When**
   provisioned, **Then** the override takes effect and the default
   is not used.

3. **Given** `preset: skos` and a descriptor that previously relied
   on catalog domain sync, **When** provisioned after the upgrade,
   **Then** concept triples are provisioned correctly; the absence
   of domain-sync does not cause an error. Domain resources are
   provisioned as separate descriptors (OP-001).

---

### User Story 3 – JSON-LD Pass-through (Config B) (Priority: P3)

A data platform engineer already has their ontology expressed as
JSON-LD (e.g. an existing OWL file or a hand-crafted context).
Rather than re-expressing it as YAML concept lists, they embed the
JSON-LD payload directly in a named field of their Witboost
descriptor and configure `descriptor.yaml-ld-path` to point to
that field. The adapter detects the path, reads the JSON-LD, and
stores it verbatim as the system graph — no concept-mapping logic
is applied regardless of what `preset` is configured.

**Why this priority**: Valuable for advanced teams but not needed
to unblock the majority of use cases; depends on per-system storage
being in place.

**Independent Test**: Set `descriptor.yaml-ld-path: graph_definition`
in any preset. Provision a descriptor that contains a valid JSON-LD
object at `graph_definition`. Call `/v1/graph/jsonld` and verify
the response is the stored JSON-LD payload (possibly re-serialised)
with no adapter-injected concept-mapping triples.

**Acceptance Scenarios**:

1. **Given** `yaml-ld-path` is set to `graph_definition` and the
   descriptor contains a valid JSON-LD object at that field,
   **When** provisioned, **Then** the graph for that system stores
   those exact triples and no concept-mapping is applied.

2. **Given** `yaml-ld-path` is set but the configured field is
   absent or null in the descriptor AND `yaml-ld-required` is
   absent or `false`, **When** provisioning is attempted, **Then**
   the adapter falls through to Config A and applies preset-based
   concept mapping without error.

3. **Given** `yaml-ld-path` is set but the configured field is
   absent or null in the descriptor AND `descriptor.yaml-ld-required:
   true` is configured, **When** provisioning is attempted, **Then**
   the adapter returns a structured validation error identifying the
   missing field in the descriptor.

4. **Given** `yaml-ld-path` is **not configured** (null or absent),
   **When** provisioning is triggered, **Then** the adapter falls
   through to Config A and applies the active preset mapping
   (`skos` or `custom`) without error.

5. **Given** a previously provisioned system using `yaml-ld-path`,
   **When** a new version of the descriptor is provisioned with
   updated JSON-LD, **Then** the stored graph is replaced entirely
   with the new payload (no merge with old triples).

6. **Given** a system provisioned via `yaml-ld-path`, **When**
   unprovisioned, **Then** all triples belonging to that system
   are removed.

---

### User Story 4 – Per-System Graph Storage (Priority: P2)

Today all systems share a single JSON-LD blob in the database.
Unprovisioning one system requires scanning the entire graph to
find and remove only that system's triples — error-prone and slow
at scale. With per-system storage, each system owns an isolated
graph partition, making provision and unprovision atomic and
independent.

**Why this priority**: Enables correct isolation for both Config A
and Config B; without it, multi-system correctness cannot be
guaranteed.

**Independent Test**: Provision two descriptors (System A and
System B). Unprovision System A. Verify that System B's triples
remain intact in the database and are returned by the KGM source
endpoint.

**Acceptance Scenarios**:

1. **Given** two provisioned systems, **When** System A is
   unprovisioned, **Then** only System A's graph partition is
   removed; System B's partition is untouched.

2. **Given** a fresh provisioning of System A, **When** stored,
   **Then** the graph partition is identifiable by System A's URN
   without scanning other systems' data.

3. **Given** a re-provision of System A with updated concepts,
   **When** stored, **Then** only System A's partition is replaced;
   other partitions are unaffected.

---

### Edge Cases

- What if two provision requests for different systems arrive simultaneously? → Each request writes atomically to its own system URN row (last-write-wins per partition). The merged-graph cache is invalidated by each write and rebuilt lazily on the next read; no explicit locking is needed because partitions are fully isolated by key.
- What happens when a relation target IRI does not exist in the
  graph at provisioning time? → The triple is stored as-is. No
  lookup is performed; the open-world assumption makes the triple
  valid regardless of whether the target is currently present.
- What if a concept is provisioned with an explicit `iri` that
  collides with another system's concept IRI? → No detection
  occurs; IRI uniqueness is enforced only within the descriptor
  being provisioned. Cross-system IRI collisions are the
  operator's responsibility; under the open-world assumption two
  systems may intentionally reference the same IRI.
- What if a concept's explicit `iri` field contains a Witboost URN
  (starts with `urn:`)? → Log a WARNING and fall back to derived
  IRI. The system URN itself is never used directly as an IRI —
  it is always transformed by the derivation algorithm (FR-005).
- What if the same concept name appears twice in one descriptor's
  `business-concepts-path`? → The second occurrence overwrites the
  first (last-write-wins); a warning is logged.
- What if `model.add(subject, predicate, object)` is called with
  an already-existing triple? → The RDF library (Apache Jena)
  silently ignores duplicate statements; this is expected and safe.
- What if a prefixed shorthand (e.g. `foo:Bar`) appears in the
  descriptor or config but `foo` is not in the prefix map? →
  Validation MUST fail with an error identifying the unknown prefix
  and the field where it was found. Provisioning MUST NOT proceed.
- What if `descriptor.root.is-concept: true` but `root.type` is
  not configured? → Fall back to `concept-mapping.type` for the
  root resource type.
- What if `yaml-ld-path` is configured but the field is absent in
  the descriptor? → If `yaml-ld-required: false` (default): fall
  through to Config A without error. If `yaml-ld-required: true`:
  fail with a structured validation error identifying the missing
  field (FR-010 / OP-004).
- What if `yaml-ld-path` is null or absent in the config?
  → Config A applies; the active `preset` drives concept mapping.
  This is the normal path, not an error condition.

---

## Requirements

### Functional Requirements

- **FR-001**: The adapter MUST support a `graph.preset` configuration
  field accepting at minimum the values `skos` and `custom`.

- **FR-002**: In `custom` preset, the RDF class assigned to concepts
  MUST be driven by `concept-mapping.type`; no SKOS vocabulary URI
  MUST appear unless explicitly configured.

- **FR-003**: In `custom` preset, relation verbs MUST be configurable
  as a fixed IRI via `relations-mapping.verb` or as a per-relation
  dynamic value read from a specified field of each relation entry
  via `relations-mapping.verb-field`.

- **FR-004**: The adapter MUST support a `descriptor.root` config
  object that, when `is-concept: true`, causes the provisioned
  descriptor itself to become an RDF resource. The `root` object
  MUST accept optional overrides for `type`, `type-field`, `verb`,
  `verb-field`, `iri-field`, `name-field`, `properties`, and
  `additionalProperties`, each of which supersedes the corresponding
  top-level `concept-mapping` / `relations-mapping` value for the
  root resource only. When a `root` scalar override is absent, the
  adapter MUST fall back to the matching `concept-mapping` value
  for the following fields:
  - `root.iri-field` absent → use `concept-mapping.iri-field`
  - `root.name-field` absent → use `concept-mapping.name-field`
  - `root.type-field` absent → use `concept-mapping.type-field`
  - `root.type` absent → use `concept-mapping.type`

  `root.verb` and `root.verb-field` have no `concept-mapping`
  counterpart and do not fall back. When `is-concept: false` or
  `root` is absent, the descriptor is not materialised as an RDF
  resource.

- **FR-005**: All concept and relation identifiers MUST be
  interpreted as IRIs. When a concept entry carries an explicit
  value in the configured `iri-field`, that value is used as the
  subject IRI directly after prefix resolution. When no `iri-field`
  value is present, the IRI is derived using the following
  deterministic algorithm:

  **Root IRI derivation** (when `descriptor.root.is-concept: true`):
  1. Take the Witboost system URN (e.g. `urn:dmb:dp:domain:registry-a:0`).
  2. Strip the leading `urn:dmb:dp:` segment.
  3. Slugify the remainder by replacing colons and non-alphanumeric
     characters with hyphens and lowercasing
     (e.g. `domain:registry-a:0` → `domain-registry-a-0`).
  4. Expand using the `wb:` prefix already present in the adapter
     (e.g. `wb:domain-registry-a-0` →
     `https://witboost.com/type#domain-registry-a-0`).

  **Concept IRI derivation**:
  1. Take the root IRI produced above.
  2. Append a hyphen followed by the slugified concept name-field
     value (e.g. root IRI + `-concept-a` →
     `https://witboost.com/type#domain-registry-a-0-concept-a`).

  This algorithm guarantees IRI uniqueness across systems because
  the root IRI encodes the full system identity.

- **FR-006**: When a value explicitly set in the `iri-field` of a
  concept entry matches the Witboost URN pattern (starts with
  `urn:`), the adapter MUST log a WARNING identifying the concept
  name and the offending value, and MUST fall back to the derived
  IRI for that concept's subject. A Witboost URN is not a valid
  HTTP IRI and will produce syntactically invalid RDF if used
  directly as a subject. Note: the system URN itself is NEVER used
  directly as an IRI — it is always transformed by the derivation
  algorithm above.

- **FR-007**: The adapter MUST support two prefix declaration
  strategies that MAY be active simultaneously:
  - `literals`: a static map of prefix labels to namespace IRIs
    defined in the adapter configuration.
  - `field`: a descriptor field name whose value is read at
    provision time as a JSON-LD `@context` object; prefixes from
    this field are merged with `literals`, with field-derived
    prefixes taking priority on conflict.

- **FR-008**: All IRI-typed values in the configuration and in
  concept entries (concept type, relation verb, property predicate
  keys, explicit IRI values) MUST be expanded using the resolved
  prefix map before being written to the graph. A value matching a
  registered prefix (e.g. `skos:Concept`) MUST be expanded to its
  full IRI (e.g. `http://www.w3.org/2004/02/skos/core#Concept`).

- **FR-009**: When `descriptor.yaml-ld-path` is configured and
  non-null, the adapter MUST read the JSON-LD payload from the
  descriptor field at that path and store it as the system's graph
  partition, bypassing all concept-mapping logic regardless of the
  active `preset`. The `descriptor.root` block MUST be ignored in
  this path.

- **FR-010**: When `descriptor.yaml-ld-path` is configured but the
  referenced field is absent or null in the incoming descriptor,
  the adapter MUST apply the following logic:
  - If `descriptor.yaml-ld-required: true` is set, return a
    structured validation error identifying the missing field.
  - If `descriptor.yaml-ld-required` is `false` or absent (the
    default), fall through to Config A and apply preset-based concept
    mapping without error.
  When `descriptor.yaml-ld-path` is itself null or absent in the
  config, the adapter MUST apply Config A preset-based mapping
  without error regardless of `yaml-ld-required`.

- **FR-011**:The JSON-LD graph MUST be stored as one record per
  system URN. Provisioning a system MUST atomically replace only
  that system's record; other systems' records MUST NOT be
  modified.

- **FR-012**: The adapter MUST publish relation triples using the
  IRI provided in the relation entry directly (after prefix
  resolution), without performing any lookup in the global graph.
  Under the open-world assumption, a relation triple is valid
  regardless of whether the target IRI is currently present.

- **FR-013**: The `preset: skos` preset MUST pre-populate the
  following defaults without requiring any explicit configuration:
  the `skos` namespace prefix, `descriptor.root.is-concept: true`,
  `root.type: skos:ConceptScheme`, `root.verb: skos:inScheme`,
  `concept-mapping.type: skos:Concept`,
  `concept-mapping.properties` mapping `skos:prefLabel → name` and
  `skos:definition → description`,
  `relations-mapping.verb: skos:narrower`, and
  `relations-mapping.reverse-verb-map` containing the SKOS
  bidirectional defaults: `skos:narrower → skos:broader`,
  `skos:broader → skos:narrower`, `skos:related → skos:related`.

- **FR-014**: For both `prefixes.literals` and
  `concept-mapping.properties`, user-supplied values in the
  deployment configuration MUST be merged with preset defaults
  rather than replacing them. `additionalProperties` MUST be merged
  onto `properties`, with `additionalProperties` entries winning on
  key conflicts. The merge strategy MUST be documented and MUST be
  implemented regardless of whether the hosting framework supports
  it natively (see OP-005).

- **FR-015**: All preset defaults established by `preset: skos` MUST
  be individually overridable by explicit configuration values.

- **FR-016**: Extra concept properties MUST be configurable as a
  map from RDF property IRIs (or prefixed shorthands) to descriptor
  field names via `concept-mapping.properties`. Additional
  properties beyond the preset defaults are specified in
  `concept-mapping.additionalProperties`.

- **FR-017**: The adapter MUST return a structured validation error
  (via the existing `POST /validate` endpoint) in the following
  cases:
  - Required configuration fields are absent for the selected preset.
  - Any IRI-typed value in the adapter configuration (e.g. a key
    in `concept-mapping.properties`, the value of
    `concept-mapping.type`, `relations-mapping.verb`, any entry in
    `relations-mapping.reverse-verb-map`, or any
    prefixed shorthand in `descriptor.root` overrides) uses a
    prefix label that is not registered in the resolved prefix map.
  - Any IRI-typed value in the incoming descriptor (e.g. a concept's
    explicit `iri-field`, a relation's verb override via
    `verb-field`, or any property value intended as an IRI) uses a
    prefix label that is not registered in the resolved prefix map.
  - Two or more concepts within the **same descriptor** resolve to
    the same subject IRI (intra-descriptor IRI collision). Cross-
    system IRI collisions are not detected; they are the operator's
    responsibility.
  In all cases the error message MUST identify the offending field
  or config key and the specific value or prefix label that caused
  the failure.

- **FR-018**: The KGM source endpoint (`GET /v1/graph/jsonld`) MUST
  continue serving the complete merged graph (all systems'
  partitions combined) so the KGM ingestion job requires no
  changes.

- **FR-019**: The adapter MUST emit structured log entries (SLF4J
  with MDC) for every provision and unprovision operation,
  including at minimum: the system URN, the active preset or
  path strategy, the number of concepts processed, and the
  outcome (success or error type). OTel instrumentation is
  explicitly out of scope for this iteration.

- **FR-020**: The adapter MUST support a `reverse-verb-map` field
  under `relations-mapping` — a `Map<String, String>` from a
  relation verb IRI (or CURIE) to its inverse verb IRI (or CURIE).
  When a relation triple `A → verb → B` is emitted and the
  resolved verb appears as a key in this map, the adapter MUST also
  emit the inverse triple `B → reverseVerb → A` in the same graph
  partition. The map is optional; an absent or empty map means no
  reverse triples are emitted. All keys and values MUST be
  validated against the resolved prefix map at validate/provision
  time (FR-017). The `preset: skos` preset MUST populate this map
  with the three default SKOS bidirectional pairs (FR-013).
  Operators may extend or override individual entries; the merge
  strategy follows FR-014 (preset baseline + user entries, user
  wins on conflict). The `root.verb` link from root to concept is
  **not** subject to reverse-verb-map processing.

- **FR-021**: On a successful provision, the adapter MUST return a
  `ProvisionResult` payload in both `info.publicInfo` and
  `info.privateInfo` of the Witboost provisioner response. The
  payload MUST contain:
  - `systemUrn` (`String`): the URN of the provisioned system.
  - `strategy` (`String`): the active mapping strategy, one of
    `"mapping"` (Config A) or `"passthrough"` (Config B).
  - `provisionedIris` (`List<String>`): for Config A, the full HTTP
    IRIs of every RDF subject created (root IRI if `is-concept:
    true`, then all concept IRIs); for Config B, a list containing
    only the system URN (individual IRIs are embedded in the opaque
    JSON-LD payload and not parsed by the adapter).

### Key Entities

- **Graph Mode**: The selected concept-mapping strategy (`skos` or
  `custom`). Determines which parsing and storage logic is applied
  when `yaml-ld-path` is not configured.

- **Concept**: A named RDF resource derived from one entry in the
  descriptor's concept list. Has a subject IRI (derived or from
  `iri-field`), a type, a set of property mappings, and relations.

- **IRI**: The subject or object identifier of an RDF triple.
  All identifiers in concept entries and configuration are treated
  as IRIs (or prefixed shorthands expanded to IRIs). Witboost URNs
  are not valid IRIs and trigger a warning.

- **Relation**: A directed RDF triple linking two concepts via a
  configured or dynamic verb IRI, published as-is without graph
  lookup.

- **System Graph Partition**: The set of RDF triples contributed by
  one system URN. Stored as an isolated unit replaced or deleted
  atomically.

- **Preset**: A named configuration bundle (`skos`) that supplies
  default values for `concept-mapping`, `relations-mapping`,
  `descriptor`, and `prefixes`. Preset defaults are merged with,
  not replaced by, user-supplied configuration.

- **Prefix Map**: The resolved set of prefix → namespace IRI
  mappings available during a provision operation. Assembled from
  `prefixes.literals` (static config) and `prefixes.field`
  (descriptor-derived context), with field-derived values taking
  priority.

- **YAML-LD Payload**: A JSON-LD document embedded in a descriptor
  field. When `descriptor.yaml-ld-path` is configured and the field
  is present, triggers Config B (pass-through). When not configured,
  Config A (preset-based mapping) applies.

- **ProvisionResult**: The structured payload returned in
  `info.publicInfo` and `info.privateInfo` on a successful provision.
  Contains `systemUrn` (the provisioned system URN), `strategy`
  (`"mapping"` or `"passthrough"`), and `provisionedIris` (the full
  HTTP IRIs of all RDF subjects created, or the system URN for
  Config B pass-through).

---

## Success Criteria

### Measurable Outcomes

- **SC-001**: An OWL-based descriptor provisioned with `preset:
  custom` produces a graph containing zero SKOS-vocabulary triples.

- **SC-002**: An existing SKOS descriptor provisioned against the
  upgraded adapter with `preset: skos` produces a graph semantically
  equivalent to the pre-feature baseline (zero regression).

- **SC-003**: Unprovisioning one system leaves all other systems'
  graph data intact (verified by querying the DB and the KGM source
  endpoint).

- **SC-004**: A `yaml-ld-path` pass-through round-trips correctly:
  the payload returned by `/v1/graph/jsonld` for a system contains
  the triples from the embedded JSON-LD field and no
  adapter-injected triples.

- **SC-005**: Provisioning and unprovisioning complete in under
  3 seconds for descriptors with up to 200 concepts under normal
  database load.

- **SC-007**: The `GET /v1/graph/jsonld` merged-graph response is
  returned in under 5 seconds for a deployment of up to 100
  provisioned systems, each with up to 200 concepts, when served
  from cache.

- **SC-006**: Line coverage for all new and modified service
  classes remains at or above 80 % as required by the constitution.

---

## Decisions

The following architectural questions were raised during spec
review and resolved before planning.

### OP-001 – Witboost Domain Sync: **Removed; Domains via Separate Descriptor**

The adapter will no longer sync Witboost catalog domains in any
preset. Domain sync is removed from `preset: skos` as well.

**Domain topology resolution**: Domain-level RDF resources are
expressed by provisioning a **separate Witboost descriptor** whose
system type is mapped to this adapter. A domain descriptor
provisions its own graph partition (one row per system URN), and
relations from business-object descriptors reference the domain IRI
using the standard relations-mapping mechanism — no special handling
is required in the adapter itself.

This is the assumed starting point. If a future use case requires
automatic domain stub creation from the `domain` field present in
system-level descriptors, it will be addressed in a follow-up
feature rather than in this iteration.

**Rationale**: Keeping domain resources as first-class descriptors
is consistent with the open-world assumption already adopted for
relations (FR-012) and requires no changes to the core mapping
engine. It also gives operators full control over the domain IRI
and properties without hard-coding any domain-derivation logic in
the adapter.

### OP-002 – JSON-LD Storage: **One Row per System URN (Option A)**

Each system's graph is stored as one row in a `graph_state` table
keyed by `system_urn`. The `GET /v1/graph/jsonld` endpoint merges
all rows in memory before returning; this merge result MUST be
cached and invalidated only on provision or unprovision events.

**Rationale**: Provision and unprovision are O(1) (single-row
write or delete) regardless of total graph size. Concurrent
provisions for different systems cannot conflict. The merge
overhead at read time is bounded by the number of systems and is
acceptable given that the KGM ingestion job calls the endpoint on
a schedule, not per user request. Named-graph complexity and
read-modify-write locking (Option B) are not justified at current
scale.

### OP-003 – YAML-LD as Optional Override, Not a Mode

`yaml-ld-path` is an **optional field within `descriptor` config**,
not a separate `preset` value. The adapter checks for it at the start
of every provision operation:

- `yaml-ld-path` is configured **and** the field is present in
  the descriptor → Config B (pass-through). Mode-based mapping is
  bypassed entirely.
- `yaml-ld-path` is configured **but** the field is absent or null
  in the descriptor → see OP-004 (resolved below).
- `yaml-ld-path` is not configured (null/absent in config) →
  Config A applies; the active `preset` drives concept mapping.
  No error.

### OP-004 – Missing YAML-LD Field When `yaml-ld-path` Is Configured: **Permissive with Opt-in Strict Mode**

**Decision**: Default behaviour is **Option B (fall through to Config A)**. An explicit
config flag — `descriptor.yaml-ld-required: true` — switches to
**Option A (fail immediately)**.

- **Default (`yaml-ld-required: false`)**: If `yaml-ld-path` is set
  but the field is absent in the descriptor, apply Config A
  (preset-based concept mapping) without error. Enables a single
  adapter deployment to serve both pass-through and concept-mapped
  descriptors.
- **Strict (`yaml-ld-required: true`)**: If `yaml-ld-path` is set
  but the field is absent, return a validation error identifying
  the missing field. Every descriptor provisioned through this
  instance must carry the JSON-LD payload.

**Rationale**: The permissive default allows mixed-use deployments
without requiring separate adapter instances. Teams that want to
enforce the pass-through contract can opt in to strict mode via a
single config flag, making the commitment explicit.

### OP-005 – Config Merge Strategy for Map Fields: **Custom Merge Bean (Option A)**

**Decision**: A dedicated post-processing component reads the
preset defaults at startup and merges them with the
user-supplied configuration values, giving user values priority
on key conflicts.

**Rationale**: Preset defaults are preset-specific (the SKOS preset
supplies different defaults from a hypothetical future OWL preset).
The merge logic must be preset-aware, which a custom merge bean
implements cleanly without relying on Spring's property source
ordering. This also makes the merge contract explicit and
testable.

---

## Assumptions

- The existing `POST /provision`, `POST /unprovision`, and
  `GET /v1/graph/jsonld` endpoints keep their current signatures;
  no Witboost platform changes are required.
- The KGM ingestion job continues to pull from
  `GET /v1/graph/jsonld` without modification.
- Apache Jena's in-memory model silently drops duplicate triples
  (same subject + predicate + object); this is safe and expected.
- The Witboost catalog domain-sync client is removed from all
  modes; no preset requires a call to the Witboost catalog API.
- Prefix shorthand resolution (e.g. `skos:Concept` → full IRI)
  is handled internally by the adapter using the configured
  `prefixes` map; callers do not need to send full IRIs.
- A Flyway migration will replace the current single-row
  `graph_state` table with a table keyed by `system_urn`. A brief
  maintenance window is acceptable; the migration may drop and
  recreate the table in a single step. Existing data under the
  legacy single-row schema will be lost; teams must re-provision
  their systems after the upgrade.
- The merged graph served by `GET /v1/graph/jsonld` will be
  cached in memory and invalidated on every provision or
  unprovision event; no cache TTL is required.
- Mobile and browser-based clients of the KGM source endpoint are
  out of scope; the endpoint serves machine-to-machine calls only.
- The domain topology (`Domain → System → Concept`) is handled by
  provisioning domain resources via their own separate Witboost
  descriptors. No automatic domain stub creation occurs in this
  iteration; that is deferred to a future feature if needed.
- The expected deployment scale is up to 100 concurrently
  provisioned systems, each with up to 200 concepts. The in-memory
  merge approach for `GET /v1/graph/jsonld` is validated against
  this bound. Growth beyond a few hundred systems would require
  re-evaluation of the merge strategy.
