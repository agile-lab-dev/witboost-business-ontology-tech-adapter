<p align="center">
    <a href="https://www.witboost.com/">
        <img src="docs/img/witboost_logo.svg" alt="witboost" width=600>
    </a>
</p>

Designed by [Agile Lab](https://www.agilelab.it/), Witboost is a versatile platform that addresses a wide range of sophisticated data engineering challenges. It enables businesses to discover, enhance, and productize their data, fostering the creation of automated data platforms that adhere to the highest standards of data governance. Want to know more about Witboost? Check it out [here](https://www.witboost.com) or [contact us!](https://www.witboost.com/contact-us).

This repository is part of our [Starter Kit](https://github.com/agile-lab-dev/witboost-starter-kit) meant to showcase Witboost integration capabilities and provide a "batteries-included" product.

# Ontology Tech Adapter

- [Overview](#overview)
- [Building](#building)
- [Running](#running)
- [Configuring](#configuring)
- [Deploying](#deploying)
- [Architecture](docs/architecture.md)
- [Configuration reference](docs/configuration.md)
- [API specification](src/main/resources/interface-specification.yml)

## Overview

This project implements a dual-role Witboost service that acts as both a **Tech Adapter** and a **KGM Source Adapter** for ontology management.

- As a **Tech Adapter**, it receives Data Product provisioning requests from the Witboost platform and maintains an RDF graph of business concepts derived from the descriptor.
- As a **KGM Source Adapter**, it exposes that graph over HTTP as JSON-LD, making it available for ingestion by the Witboost Knowledge Graph Manager (KGM).

The adapter is **ontology-agnostic**: it supports SKOS, OWL, RDFS, or any custom RDF vocabulary through a preset-driven mapping engine, and optionally accepts embedded YAML-LD payloads directly from the descriptor.

### What's a Tech Adapter?

A Tech Adapter is a microservice which is in charge of deploying components that use a specific technology. When the deployment of a Project is triggered, the platform generates its descriptor and orchestrates the deployment of every component contained in the Data Product. For every such component the platform knows which Tech Adapter is responsible for its deployment, and can thus send a provisioning request with the descriptor to it so that the Tech Adapter can perform whatever operation is required to fulfill this request and report back the outcome to the platform.

You can learn more about how Tech Adapters fit in the broader picture [here](https://docs.witboost.com/docs/p3_tech/p3_customizations/p3_9_provisioning/techAdapter).

### What's a Source Adapter?

A Source Adapter is a microservice in charge of exposing knowledge into a format the Witboost Knowledge Graph Manager (KGM) can understand. It acts as a bridge between Witboost and the sources where this knowledge originates, like external services storing ontologies or other concepts sources.

You can learn more about how Source Adapters fit in the broader picture [here](https://docs.witboost.com/docs/p3_tech/kgm/sources)

### How it works

The adapter supports two provisioning strategies, selected automatically per descriptor:

**Config A — YAML-to-RDF mapping** (default): The descriptor's concept list is parsed using configurable type, property, and relation mappings driven by `app.graph.preset`.

**Config B — YAML-LD pass-through**: When `descriptor.yaml-ld-path` is configured and the referenced field is present in the descriptor, its JSON-LD content is stored verbatim. No concept-mapping logic runs.

Each provisioned system occupies one row in a `graph_state` table present on a PostgreSQL Database. The read endpoint merges all rows into a single JSON-LD document on demand.

**Provision flow (Config A, SKOS preset):**

1. Parse the `business_concepts` list from the descriptor.
2. Map each entry to a `skos:Concept` with an IRI derived from the system URN.
3. Type the descriptor itself as a `skos:ConceptScheme` (root concept).
4. Serialise to JSON-LD and upsert via `system_urn` key.

**Unprovision flow:**

1. Delete the row for this system URN.
2. Other systems are unaffected.

### Software stack

This microservice is written in **Java 25**, using **Spring Boot 4** for the HTTP layer. It uses **PostgreSQL** for persistence via **Flyway** migrations and **Apache Jena** for RDF serialisation.

## Building

**Requirements:**

- Java 25+
- Apache Maven 3.9+

**Build:**

The adapter uses the `openapi-generator` Maven plugin to generate the API endpoints from the interface specification located in `src/main/resources/interface-specification.yml`.

```bash
mvn compile
```

**Format check** (Google Java Format via Spotless):

```bash
mvn spotless:check
```

To apply formatting automatically:

```bash
mvn spotless:apply
```

**Tests** are handled by JUnit:

```bash
mvn test
```

**Artifacts & Docker image:**

```bash
mvn package
docker build -t ontology-tech-adapter .
```

**CI/CD:** the pipeline is based on GitLab CI as that's what we use internally. It's configured by the `.gitlab-ci.yml` file in the root of the repository. You can use that as a starting point for your customizations.

## Running

Requires a running PostgreSQL instance:

```bash
docker run -d \
  --name ontology-ta-postgres \
  -e POSTGRES_DB=ontology_ta \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgrespassword \
  -p 5432:5432 \
  postgres:16
```

Create `src/main/resources/application-local.yaml` (gitignored):

```yaml
app:
  db:
    host: localhost
    port: 5432
    name: ontology_ta
    username: postgres
    password: postgrespassword
  witboost:
    kgm:
      base-url: http://localhost:9006   # omit to skip KGM ingestion trigger
```

Then start the service:

```bash
./mvnw spring-boot:run -Plocal
```

Flyway runs automatically on startup. The service starts on port `8085`.

Swagger UI is available at: `http://localhost:8085/swagger-ui/index.html`

## Configuring

All adapter-specific properties live under `app.graph`. Full details and worked examples are in [`docs/configuration.md`](docs/configuration.md).

### Minimal SKOS config

```yaml
app:
  graph:
    preset: skos   # default — can be omitted
```

### Custom OWL config (example)

> **Spring Boot note**: map keys containing `:` (CURIEs like `rdfs:label`) must be
> wrapped in `[]` so Spring preserves them verbatim — e.g. `"[rdfs:label]": "name"`.

```yaml
app:
  graph:
    preset: custom
    prefixes:
      literals:
        owl:  "http://www.w3.org/2002/07/owl#"
        rdfs: "http://www.w3.org/2000/01/rdf-schema#"
        wb:   "https://witboost.com/type#"
    descriptor:
      business-concepts-path: business_concepts
      root:
        is-concept: true
        type: "owl:Ontology"
        verb: "rdfs:isDefinedBy"
    concept-mapping:
      type: "owl:Class"
      name-field: name
      type-field: type
      properties:
        "[rdfs:label]":   "name"
        "[rdfs:comment]": "description"
    relations-mapping:
      verb: "rdfs:subClassOf"
      verb-field: type
      relations-path: relations
```

### Database

| Property          | Env var (primary)         | Env var (fallback)     | Default            |
| ----------------- | ------------------------- | ---------------------- | ------------------ |
| `app.db.host`     | `ONTOLOGY_TA_DB_HOST`     | `WITBOOST_DB_HOST`     | `localhost`        |
| `app.db.port`     | `ONTOLOGY_TA_DB_PORT`     | `WITBOOST_DB_PORT`     | `5432`             |
| `app.db.name`     | `ONTOLOGY_TA_DB_NAME`     | —                      | `ontology_ta`      |
| `app.db.username` | `ONTOLOGY_TA_DB_USERNAME` | `WITBOOST_DB_USERNAME` | `postgres`         |
| `app.db.password` | `ONTOLOGY_TA_DB_PASSWORD` | `WITBOOST_DB_PASSWORD` | `postgrespassword` |

### KGM integration

After each successful provision or unprovision, the adapter triggers KGM ingestion as a best-effort side-effect (errors are logged but never fail the provision).

| Property                     | Env var         | Default           |
| ---------------------------- | --------------- | ----------------- |
| `app.witboost.kgm.base-url`  | `KGM_BASE_URL`  | `http://kgm:8080` |
| `app.witboost.kgm.source-id` | `KGM_SOURCE_ID` | `ontology-ta`     |

### Environment deployment considerations

The Business Concept Map in Witboost is **not environment-aware**: it portrays a unified view of the platform's ontology, accumulating all provisioned systems into a single merged graph regardless of which Witboost environment triggered the provision request. This means that if the same adapter instance targets two Witboost environments, the latest provision would override the previous one, regardless of the source environment.

**Recommended approach**: Deploy and register this tech adapter **only in the production Witboost environment** (or whichever single environment should be the authoritative source for the Business Concept Map). The Platform Team is responsible for this registration decision.

## Deploying

This microservice is meant to be deployed to a Kubernetes cluster with the included Helm chart and the scripts that can be found in the `helm` subdirectory. You can find more details [here](helm/README.md).

## License

This project is available under the [Apache License, Version 2.0](https://opensource.org/licenses/Apache-2.0); see [LICENSE](LICENSE) for full details.

## About Witboost

[Witboost](https://witboost.com/) is a cutting-edge Data Experience platform, that streamlines complex data projects across various platforms, enabling seamless data production and consumption. This unified approach empowers you to fully utilize your data without platform-specific hurdles, fostering smoother collaboration across teams.

It seamlessly blends business-relevant information, data governance processes, and IT delivery, ensuring technically sound data projects aligned with strategic objectives. Witboost facilitates data-driven decision-making while maintaining data security, ethics, and regulatory compliance.

Moreover, Witboost maximizes data potential through automation, freeing resources for strategic initiatives. Apply your data for growth, innovation and competitive advantage.

[Contact us](https://witboost.com/contact-us) or follow us on:

- [LinkedIn](https://www.linkedin.com/showcase/witboost/)
- [YouTube](https://www.youtube.com/@witboost-platform)
