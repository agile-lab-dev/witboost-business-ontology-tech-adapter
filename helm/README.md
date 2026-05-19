# Patterns to ignore when building packages.
# ontology-ta

Helm chart for the Witboost Ontology Tech Adapter

## Values

This is only the list of global values used by multiple components.
Each sub-component is documented in its own repo, you can reference it to understand how to configure it properly.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| annotations."reloader.stakater.com/auto" | string | `"true"` |  |
| configOverride | object | `{}` | Override application.yaml via ConfigMap |
| dbName | string | `"ontology_ta"` | PostgreSQL database name |
| dbSchema | string | `"ontology_ta"` | PostgreSQL schema name |
| dockerRegistrySecretName | string | `"regcred"` | Docker Registry Secret name used to access a private registry |
| extraEnvVars | list | `[]` | Extra environment variables |
| image.pullPolicy | string | `"Always"` | Image pull policy |
| image.registry | string | `"registry.gitlab.com/agilefactory/witboost.mesh/witboost.kgm.adapters/witboost.kgm.adapters.businessontologyadapter"` | Image repository |
| image.tag | string | `"latest"` | Image tag |
| labels | object | `{}` | Common labels |
| livenessProbe | object | `{"failureThreshold":5,"httpGet":{"path":"/actuator/health/liveness","port":"http"},"initialDelaySeconds":30,"periodSeconds":15}` | Liveness probe |
| readinessProbe | object | `{"failureThreshold":5,"httpGet":{"path":"/actuator/health/readiness","port":"http"},"initialDelaySeconds":10,"periodSeconds":10}` | Readiness probe |
| replicaCount | int | `1` | Number of replicas |
| resources | object | `{}` | Container resource limits/requests |
| securityContext | object | `{"allowPrivilegeEscalation":false,"runAsNonRoot":true,"runAsUser":1001}` | Security context |
| serviceAccount | object | `{"create":false,"name":""}` | Service Account |