# KubePilot

An SRE agent for Kubernetes.

KubePilot connects to a Kubernetes cluster, detects problems using deterministic analyzers, and uses large
language models to explain root causes in plain language and suggest remediation steps.

Detection and explanation are deliberately separate. Analyzers inspect an immutable snapshot of cluster state
and emit structured findings; the AI layer then explains those findings. A scan is fully useful with the LLM
switched off — that keeps the fast path deterministic and free, gives the resilience layer something real to
fall back to, and means analyzers can be tested without a model or a live cluster.

## Tech stack

| Layer | Technology |
| --- | --- |
| Language | Java 25 (Temurin) |
| Framework | Quarkus 3.33 LTS |
| Build | Maven, via the bundled wrapper (`./mvnw`) |
| Cluster access | Fabric8 Kubernetes Client (`quarkus-kubernetes-client`) |
| AI integration | LangChain4j (`quarkus-langchain4j`) |
| REST API | Quarkus REST with Jackson |
| Configuration | YAML with profile support (`quarkus-config-yaml`) |
| Caching | Quarkus Cache (Caffeine) |
| Resilience | SmallRye Fault Tolerance |
| Health checks | SmallRye Health |
| Templating | Qute, for prompt templates |
| Testing | JUnit 5, Quarkus Test, ArchUnit |
| Packaging | JVM and GraalVM native (Mandrel) |
| Deployment | Container image, Helm chart |

## Requirements

| Tool | Version |
| --- | --- |
| JDK | 25 |
| Maven | provided by `./mvnw` |
| Docker | required for Dev Services and local clusters |
| kubectl, kind | for verification against a real cluster |

## Getting started

```shell
./mvnw quarkus:dev     # dev mode with live reload; Dev UI at /q/dev/
./mvnw verify          # run tests
./mvnw package         # build target/quarkus-app/quarkus-run.jar
```

Dev mode starts without a cluster. To run against a throwaway local cluster:

```shell
kind create cluster --name kubepilot
```

## Project layout

| Package | Responsibility |
| --- | --- |
| `common` | Shared types. Plain Java, no framework dependencies. |
| `analyzer` | Detection rules, one class per analyzer. |
| `analysis` | Scan orchestration and output. |
| `ai` | LLM backends, prompt assembly, redaction. |
| `kubernetes` | Cluster client and snapshot assembly. |
| `cache` | Fingerprint-keyed result cache. |
| `server` | REST layer and DTOs. |
| `integration` | Optional external data sources. |
| `custom` | Out-of-process analyzers. |
| `util` | Stateless helpers. |
| `cmd` | Command-line entry points. |

Every package carries a `package-info.java` describing what belongs in it and what it may depend on. Read
those before adding a class.

## Documentation

- [docs/architecture.md](docs/architecture.md) — pipeline, package boundaries, performance and maintenance notes
- [docs/adr/](docs/adr/) — architecture decision records

## Security

- Cluster access is read-only.
- Evidence is redacted in the `ai` package before anything reaches a model provider — credential values are
  masked while resource names, keys and reasons are preserved. Applied while the prompt is assembled, so
  request logging is covered too. See [docs/redaction.md](docs/redaction.md).
- Secret scanning runs on every push and pull request over the full git history.
