<!--
  Sync Impact Report
  ──────────────────
  Version change: 1.2.0 → 2.2.0
  Reason for MAJOR bump: All principles redefined from Python/FastAPI/Poetry
    to Java 25/Spring Boot/Maven — backward-incompatible across the board.

  - I.   Code Quality: added readable-variable-names rule (v2.3.0)
    - I.   Code Quality: ruff/mypy → Spotless (Google Java Format)/SpotBugs
    - II.  Testing Standards: pytest/pytest-cov → JUnit 5/JaCoCo (≥ 80%)
    - III. User Experience Consistency: FastAPI/loguru/Pydantic
           → Spring Boot/@RestControllerAdvice/SLF4J+MDC
    - IV.  Performance Requirements: pip-audit/Uvicorn
           → OWASP Dependency-Check/Spring MVC
    - V.   Architecture & Code Organisation: Python packages/modules
           → Java layers/constructor injection
    - VI.  Type Safety & API Contracts: mypy/Any/type:ignore
           → Java generics/@SuppressWarnings/instanceof pattern matching
    - VII. Test Hygiene: Pydantic/pytest → Lombok/JUnit 5/Mockito
    - VIII. Developer Tooling: poetry run → ./mvnw wrapper
  Added sections:
    - IX. Security & Dependency Management (new principle — v2.0.0)
    - X.  User-Facing Documentation (new principle — v2.1.0)
    - IX. SQL injection prevention clause added (v2.2.0)
  Removed sections: none
  Templates requiring updates:
    - .specify/templates/plan-template.md ✅ aligned (generic; no
      language-specific rules — placeholder examples are advisory)
    - .specify/templates/spec-template.md ✅ aligned (success-criteria
      section is language-agnostic)
    - .specify/templates/tasks-template.md ✅ aligned (phase structure
      applies to Java/Maven; file-path examples are illustrative)
  Follow-up TODOs:
    - TODO(JACOCO_THRESHOLD): Add `<minimum>0.80</minimum>` to the JaCoCo
      check goal in pom.xml — currently only the report goal is configured.
    - TODO(SPOTLESS_CI): Re-enable the commented-out `spotless:check` step
      in .gitlab-ci.yml checks stage.
    - TODO(SAST_CI): Re-enable commented-out SAST / Secret Detection jobs
      in .gitlab-ci.yml.
    - TODO(PRECOMMIT): Add .pre-commit-config.yaml to enforce Spotless and
      file-hygiene checks (end-of-file fixer, trailing whitespace) locally.
    - TODO(SQL_INJECTION_FIX): Fix GraphService.saveGraph() and init() —
      remove String.format(..., schemaName); rely on currentSchema in JDBC URL.
      Tracked as T049 in tasks.md.
-->

# Ontology Tech Adapter Constitution

## Core Principles

### I. Code Quality (NON-NEGOTIABLE)

- All Java source MUST be formatted with Spotless using Google
  Java Format at the version pinned in `pom.xml`. Formatting
  violations MUST be fixed before merge; run
  `./mvnw spotless:apply` to auto-correct.
- SpotBugs MUST report zero HIGH or CRITICAL priority bugs before
  merge. New suppressions require a documented justification in
  the PR description.
- Every class or record that crosses a layer boundary MUST be a
  typed, named type: a Lombok-annotated POJO, a Java record, or
  a Jackson-annotated DTO. Raw `Map<String, Object>` or `Object`
  MUST NOT be passed between the controller, service, and domain
  layers.
- Dead code, unused imports, and commented-out code blocks MUST
  be removed before merge.
- **Variable names MUST be readable and self-documenting**: Local
  variables, parameters, and fields MUST express their purpose
  with full, descriptive names. Single-letter or abbreviated
  names are FORBIDDEN except for numerical loop indices (`i`,
  `j`, `k`). Examples of FORBIDDEN names: `v`, `e`, `m`, `cm`,
  `rm`, `def`, `ctx`, `ns`, `out`, `in`, `rel`, `raw`.
  Acceptable alternatives: `prefixMap`, `presetDefinition`,
  `conceptMapping`, `outputStream`, `namespaceUri`.
- Complexity MUST be justified: new abstractions, helper classes,
  or indirection layers require a documented rationale in the PR
  description.

**Rationale**: Consistent formatting (enforced in CI via
`spotless:check`) and static analysis keep the codebase uniform
and navigable. Typed boundaries eliminate silent runtime failures
from unchecked casts or missing fields.

### II. Testing Standards (NON-NEGOTIABLE)

- Line coverage MUST remain at or above 80 % as enforced by
  JaCoCo (`<minimum>0.80</minimum>` in the check goal).
- Every new public method or REST endpoint MUST have at least one
  corresponding test before merge.
- Tests MUST be deterministic: no dependence on network, clock,
  or filesystem ordering. External calls MUST be mocked with
  Mockito (`@Mock`, `@MockBean`).
- Prefer narrow test slices over full-context integration tests:
  use `@WebMvcTest` for controller tests and
  `@ExtendWith(MockitoExtension.class)` for service and domain
  tests. `@SpringBootTest` is reserved for tests that genuinely
  require the full Spring context.
- Integration tests that require a live external service MUST be
  tagged `@Tag("integration")` and MUST be skippable in CI via a
  Maven Surefire exclude rule so the standard pipeline remains
  fast.

**Rationale**: The 80 % coverage gate and deterministic tests
ensure the adapter can be refactored safely and that CI stays
fast. Narrow test slices reduce startup overhead and make test
failures easier to diagnose.

### III. User Experience Consistency

- All provisioning API responses MUST conform to the interface
  specification defined in
  `src/main/resources/tech-adapter-openapi.yaml`.
- Error responses MUST be produced by a dedicated
  `@RestControllerAdvice` handler class; inline
  `catch (Exception e) { return ResponseEntity.status(500)... }`
  in controllers MUST be replaced by typed, re-thrown exceptions
  handled centrally.
- Log messages MUST use SLF4J (`@Slf4j`) with parameterised
  messages and MDC context fields (at minimum: component
  descriptor ID, operation type) so operators can correlate logs
  to specific provisioning requests.
- Spring Boot Actuator and the Micrometer Prometheus endpoint
  (`/actuator/prometheus`) MUST remain enabled; new endpoints
  MUST NOT disable or bypass Actuator health contributors.
- OpenTelemetry instrumentation (OTel Java agent) MUST be
  preserved in the Dockerfile; new long-running operations MUST
  produce a span or metric entry for observability.
- Changes to response schemas MUST be reflected in
  `tech-adapter-openapi.yaml` before the implementing PR is
  merged, so the generated interfaces stay in sync.

**Rationale**: Downstream consumers (the Witboost platform and
its operators) depend on predictable, well-structured responses
and observable telemetry to diagnose provisioning issues in
production.

### IV. Performance Requirements

- The adapter MUST respond to health-check and validation
  endpoints within 500 ms at p95 under normal load.
- Long-running provisioning operations MUST NOT block the shared
  Tomcat thread pool; blocking I/O (external HTTP calls, heavy
  computation) MUST be executed with `@Async` on a bounded
  executor or via `CompletableFuture` so the server stays
  responsive.
- Docker image size SHOULD be kept under 500 MB (uncompressed).
  Build-time toolchains (Maven, the Maven wrapper cache) MUST NOT
  be present in the final runtime image; use a JRE-only base
  (e.g., `eclipse-temurin:25-jre-alpine`).
- Startup time from container start to first successful response
  at `/actuator/health/liveness` MUST remain under 20 seconds.
- OWASP Dependency-Check MUST report zero CVSS ≥ 9
  vulnerabilities before a release tag is created.

**Rationale**: The adapter runs inside Kubernetes alongside other
provisioners; predictable latency, lean images, and fast cold
starts are essential for cluster resource efficiency and reliable
platform orchestration.

## Technology Constraints

- **Runtime**: Java 25 (`eclipse-temurin:25-jre-alpine` in the
  final Docker image).
- **HTTP framework**: Spring Boot 3.x with Spring MVC (Tomcat).
- **Validation**: Jakarta Bean Validation via
  `spring-boot-starter-validation`.
- **Formatting**: Spotless with Google Java Format (version pinned
  in `pom.xml`).
- **Static analysis**: SpotBugs (zero HIGH/MEDIUM bugs required).
- **Testing**: JUnit 5, Mockito, AssertJ, Spring Boot Test,
  JaCoCo (line coverage ≥ 80 %).
- **Logging**: SLF4J + Logback (`@Slf4j`); MDC for request
  context.
- **Observability**: Spring Boot Actuator, Micrometer Prometheus
  registry, OpenTelemetry Java agent.
- **Packaging**: Maven (`./mvnw`); Docker (single-stage, JRE-only
  Alpine image, non-root user, `dumb-init` as PID 1).
- **Security scanning**: OWASP Dependency-Check (CVSS ≥ 9 fail),
  license scanning, GitLab SAST + Secret Detection + Container
  Scanning.
- **API contracts**: OpenAPI Generator Maven plugin (Spring
  generator, `interfaceOnly=true`); generated code lives under
  `target/`; do not edit generated files.
- **Database**: PostgreSQL + Flyway (auto-migration on startup).
- **Utilities**: Lombok, Jackson (JSON + YAML), Apache Jena
  (RDF/JSON-LD).

## Development Workflow

1. **Branch**: Create a feature branch from the main branch.
2. **Implement**: Write or update code following Principles I–IX.
3. **Format**: Run `./mvnw spotless:apply`; commit the result.
4. **Compile**: Run `./mvnw compile`; resolve all SpotBugs
   findings.
5. **Test**: Run `./mvnw test`; verify JaCoCo HTML report shows
   ≥ 80 % line coverage.
6. **Security**: Run `./mvnw dependency-check:check`; resolve or
   add a justified suppression for any CVSS ≥ 9 finding.
7. **Review**: Open an MR; the reviewer MUST verify compliance
   with this constitution before approving.
8. **Merge**: Squash-merge into main after approval.

## Governance

- This constitution supersedes ad-hoc practices. When a conflict
  arises between this document and a PR, this document wins.
- Amendments MUST be documented with a version bump, a rationale,
  and a migration plan if the change is backward-incompatible.
- Version follows semantic versioning:
    - **MAJOR**: Principle removed or redefined in a
      backward-incompatible way.
    - **MINOR**: New principle or section added, or materially
      expanded guidance.
    - **PATCH**: Clarifications, wording, or typo fixes.
- Compliance reviews SHOULD occur at least once per quarter or
  whenever a new principle is proposed.

---

### V. Architecture & Code Organisation (NON-NEGOTIABLE)

- **Models belong in `*.domain.*` or `*.model.*`**: DTO and
  domain classes MUST be defined in `domain` or `model` packages,
  not inlined inside service or controller classes. Service
  classes MUST import from the model subpackage.

- **Constructor injection only**: All Spring-managed dependencies
  MUST be injected via constructor. Field injection (`@Autowired`
  on fields) is FORBIDDEN. This applies equally to
  `@Value`-annotated fields when a `@ConfigurationProperties`
  class can be used instead.

- **No `Optional<T>` for required collaborators**: Constructor
  parameters representing required dependencies MUST NOT be
  wrapped in `Optional<T>`. Required dependencies are injected
  directly; optional behaviour MUST be modelled with a strategy
  or null-object pattern, not by `null`-checking at call sites.

- **No duplicated dispatch logic**: When the same
  workload-resolution or dispatch pattern appears more than once
  in a class it MUST be unified into a single dispatching method
  (e.g., a `switch` expression on a discriminator field).

- **Validation happens in the validation endpoint**: Component
  validation logic MUST be invoked during the HTTP request's
  `/validate` handler. Deferring validation to the asynchronous
  provisioning phase is FORBIDDEN because it removes user-facing
  feedback and may duplicate workspace resolution logic.

**Rationale**: These rules enforce a clean layered architecture
and prevent the tight coupling patterns that make refactoring and
testing difficult.

---

### VI. Type Safety & API Contracts (NON-NEGOTIABLE)

- **No `Object` in public signatures**: Method parameters and
  return types in public APIs MUST use concrete types or bounded
  generics (`<T extends BaseType>`). `Object` is FORBIDDEN in
  public interfaces.

- **No unjustified suppression**: `@SuppressWarnings("unchecked")`
  MUST be accompanied by an inline comment explaining why the
  suppression is safe and why a type-safe alternative is not
  feasible. It is a last resort.

- **Use `instanceof` pattern matching for type narrowing**: When
  working with a type hierarchy (e.g., `Component<?>` subtypes),
  use Java 16+ `if (obj instanceof ConcreteType t)` rather than
  unchecked casts or `getClass()` equality checks. This keeps the
  compiler's flow analysis accurate and makes branch intent
  explicit.

- **Guard external deserialization**: Jackson deserialization of
  data from external or untrusted sources (e.g., YAML descriptor
  `specific` fields) MUST be wrapped in try-catch. Provide a safe
  fallback or a structured error response via the
  `@RestControllerAdvice` handler; raw `JsonProcessingException`
  MUST NOT propagate to callers.

**Rationale**: Java's compile-time type system is a strength;
bypassing it with raw casts or unchecked suppressions introduces
the same silent runtime errors that weaker type systems suffer
from.

---

### VII. Test Hygiene (NON-NEGOTIABLE)

- **Do not test the framework**: Trivial tests that verify Jackson
  serialises a field, or that `@NotNull` rejects null, add no
  value. Only test non-trivial invariants: cross-field rules,
  domain constraints, conditional logic, or custom validators.

- **Use real model instances in tests**: Test helpers MUST return
  real Lombok/Jackson-annotated instances, not Mockito mocks of
  domain objects. Mocking `@Data` POJOs breaks `equals` contracts
  and conceals `instanceof` failures in production code paths.

- **Factory helpers require justification**: Helper methods in
  test utilities exist to remove duplication, not to wrap a
  single constructor call. A helper that produces fewer than ~3
  lines of equivalent inline code MUST NOT be added; inline the
  expression at the call site instead.

- **Use meaningful fixture values**: Test data strings MUST be
  representative of real values (e.g.,
  `"urn:dmb:utm:example:0.0.0"`, `"Standard_DS3_v2"`).
  Single-character stubs like `"u"`, `"i"`, `"x"` are FORBIDDEN;
  they hide intent and make assertion messages unintelligible.

**Rationale**: These rules prevent a category of test rot where
the test suite grows but no longer exercises the production code
paths it appears to cover.

---

### VIII. Developer Tooling (NON-NEGOTIABLE)

- **Always use the Maven wrapper**: All Maven invocations MUST
  use `./mvnw` (Linux/macOS) or `.\mvnw.cmd` (Windows). Direct
  `mvn` invocations are FORBIDDEN in documented procedures and CI
  scripts to ensure a consistent Maven version across all
  environments.

- **Pre-commit hooks MUST be installed**: Every contributor MUST
  run `pre-commit install` after cloning once a
  `.pre-commit-config.yaml` is present. At minimum, hooks MUST
  enforce Spotless formatting (`./mvnw spotless:check`) and basic
  file hygiene (end-of-file newline, trailing whitespace). A
  commit that fails these checks MUST NOT be pushed.

**Rationale**: Wrapper and pre-commit enforcement eliminates
"works on my machine" formatting differences and catches
violations before CI, reducing review friction.

---

### IX. Security & Dependency Management (NON-NEGOTIABLE)

- OWASP Dependency-Check MUST be run as part of every release
  pipeline; builds MUST fail on any dependency with CVSS ≥ 9.
- When a dependency triggers a false positive, a suppression
  entry MUST be added to `suppressions/suppression.xml` with a
  comment identifying the CVE, the affected artefact, and the
  reason the vulnerability is not exploitable in this context.
- License scanning (`licensescan:audit`) MUST pass before each
  release; GPL-incompatible licenses are FORBIDDEN.
- GitLab CI MUST enable SAST (semgrep), Secret Detection, and
  Container Scanning on every merge request; builds MUST fail on
  High or Critical findings.
- Secrets MUST NOT be committed to source control. Configuration
  that varies by environment (credentials, URLs, tokens) MUST be
  injected via Kubernetes Secrets or CI/CD variables.
- **SQL injection is FORBIDDEN**: SQL statements MUST NEVER be
  constructed by string concatenation or `String.format` with any
  runtime-sourced value — including operator configuration,
  environment variables, or request data. All data values MUST be
  passed through `JdbcTemplate` parameterised placeholders (`?`).
  SQL identifiers (schema, table, column names) MUST NOT be
  interpolated from external sources; instead, rely on the JDBC
  connection's `currentSchema` / `search_path` setting so that
  schema qualification is never needed in SQL strings. Where
  identifier interpolation is genuinely unavoidable, the value
  MUST be validated against the pattern `^[a-zA-Z][a-zA-Z0-9_]*$`
  at startup, before the first use, and rejected with a fatal
  error if it does not match.

**Rationale**: Witboost provisioners handle infrastructure
credentials and cloud API tokens. A single compromised
dependency, leaked secret, or SQL injection vulnerability can
result in full environment compromise. The current codebase
contains a known SQL injection in `GraphService.saveGraph()` and
`GraphService.init()` where `schemaName` (from `app.db.schema`)
is interpolated via `String.format` into raw SQL. This MUST be
fixed in Phase 1 of every feature that touches `GraphService` or
introduces new data-access code.

---

### X. User-Facing Documentation

- Every feature that introduces or modifies operator-facing
  configuration, deployment behaviour, or API contracts MUST be
  accompanied by a corresponding update to the repository's
  user-facing documentation (e.g. `docs/` directory or README).
- Configuration documentation MUST include:
  - A high-level description of the feature and its purpose.
  - A complete reference of every configuration field with its
    type, default value, required/optional status, and a
    one-sentence description.
  - At least two runnable examples showing distinct use cases
    (e.g. preset-based vs. fully custom configuration).
  - Clear indication of which fields are preset-populated and
    which require explicit operator input.
- Documentation MUST be written for the target operator audience
  (platform engineers deploying the adapter), not for internal
  developers. Avoid referencing internal class names or
  implementation details.
- Documentation changes MUST be included in the same MR as the
  code change they describe; documentation-only follow-up MRs
  are FORBIDDEN for feature work.

**Rationale**: Witboost adapters are deployed by platform teams
who may not have access to or familiarity with the source code.
Accurate, up-to-date configuration documentation is the primary
interface between the adapter and its operators; missing or stale
documentation causes misconfiguration, support burden, and
deployment failures.

---

**Version**: 2.3.0 | **Ratified**: 2026-04-07 | **Last Amended**: 2026-07-08
