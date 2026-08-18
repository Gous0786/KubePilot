# KubePilot architecture

Companion to [adr/0001-rules-then-llm.md](adr/0001-rules-then-llm.md), which records *why* the pipeline is
split the way it is. This document describes the structure and the rules that keep it intact.

## Pipeline

```text
  kubernetes/                analyzer/            analysis/              ai/
 +------------+           +------------+       +------------+       +------------+
 |  fabric8   |  Snapshot |  ~30 rules | Find- |  dedup +   | Group |  redact -> |
 |  client    |---------->|  one class |------>|  group +   |------>|  prompt -> |
 |  (or       |  immutable|  per rule  | ings  |  fingerpr. |       |  typed     |
 |  informer) |           |            |       |            |       |  Diagnosis |
 +------------+           +------------+       +------------+       +------------+
                                                     |                    |
                                                     v                    v
                                                  server/              cache/
                                                REST + DTOs        fingerprint-keyed
```

Everything left of `ai/` runs without a model. That is the point.

## Package map

Packages are organised by concern rather than by layer. Each package's `package-info.java` is
authoritative on what belongs in it.

| Package | Role | May depend on |
| --- | --- | --- |
| `common` | Shared types: findings, failures, refs, severity | JDK only |
| `analyzer` | One class per rule, kept flat | `common` |
| `analysis` | Orchestration: snapshot, fan-out, group, enrich | `common`, `analyzer`, `kubernetes`, `cache`, `ai` |
| `ai` | LLM backends, prompt assembly, **redaction** | `common` |
| `kubernetes` | fabric8 client, API reference, snapshot builder | `common` |
| `cache` | Fingerprint-keyed result cache | `common` |
| `server` | REST resources + `dto` | `common`, `analysis` |
| `integration` | Prometheus, KEDA, Kyverno, AWS | `common` |
| `custom` | Out-of-process analyzers | `common` |
| `util` | Stateless helpers | JDK only |
| `cmd` | Picocli subcommands — reserved | `analysis` |

## Rules worth enforcing

Encode these as ArchUnit tests in `src/test/java/io/kubepilot/architecture/`. They are cheap to write and
they are what actually holds the structure together once deadlines arrive.

1. **`common` imports nothing but the JDK.** No Quarkus, no fabric8, no Jackson. This is what makes analyzers
   testable without a cluster.
2. **Only `kubernetes` and `custom` import `io.fabric8`.** Keeps the client swap (direct calls to informer
   cache) contained to one package.
3. **Only `ai` imports the LLM SDK.** Keeps `analysis` runnable with the model switched off.
4. **`analyzer` does not import `kubernetes`.** Enforces snapshot-in, findings-out — the rule that keeps a
   scan to one batched fetch.
5. **`server` does not import `analyzer`, `ai`, or `kubernetes`.** Resources call `analysis` and nothing else.
6. **No cycles between packages.**

## Latency levers

In rough order of impact. Do not do these speculatively — measure first.

1. **Rules-first** (above). Most requests never touch a model.
2. **Informer-backed reads** (M4). fabric8 `SharedIndexInformer` keeps a watch-fed local cache, so snapshot
   assembly becomes in-memory instead of dozens of sequential round trips. The biggest single win once the
   process is long-lived, and it only stays cheap because `kubernetes/` is a narrow seam.
3. **One snapshot per scan, fanned out** to all analyzers.
4. **Parallel analyzers on virtual threads.** Analyzers are pure functions over the snapshot, so this is safe.
   Use `@RunOnVirtualThread` for the blocking fabric8 client — never block the Quarkus event loop.
5. **Fingerprint-keyed diagnosis cache.** Identical failures are explained once.
6. **Group before prompting.** Fifty pods failing under one ReplicaSet is one LLM call, not fifty.
7. **Model tiering.** Cheap model for classification, strong model only for high-severity root cause.
8. **Stream the response.** Cuts perceived latency more than any real optimization.
9. **Evidence budget.** Cap log lines and events; strip `managedFields` and status noise before serializing.

Record scan wall-time at the end of M1. Without a before-number the M4 migration is unmeasurable.

## Maintenance levers

- **Analyzers as plugins.** CDI discovery, enabled by config, severities and thresholds in
  `resources/analyzers/rules.yaml`. Adding a rule is one class, one fixture, one test — nothing else changes.
- **Prompts as Qute templates** in `resources/prompts/`, versioned and diffable. Prompts as Java string
  literals stop being reviewable within weeks.
- **Typed LLM output.** Return a record and let the schema be generated. Parsing prose is the main long-term
  cost in tools like this.
- **Golden analyzer tests.** Fixture YAML in, exact findings out. No cluster, no model, no network.
- **1:1 fixtures to rules.** A fixture with no finding means a missing analyzer; a finding with no fixture
  means an untested rule. Keeping the mapping honest is what makes a 30-rule catalog trustworthy.
- **Redaction on the LLM boundary.** One chokepoint in `ai/`, applied while the prompt is built
  rather than on the wire, so the dev request log is covered too. Values are masked, identifiers
  preserved. See [redaction.md](redaction.md).
- **Fault tolerance on the LLM boundary.** `@Timeout`, `@Retry`, `@CircuitBreaker`, `@Fallback` to rules-only.
  An SRE tool must stay useful during its provider's incident.
- **`@ConfigMapping` interfaces** over scattered `@ConfigProperty`. Profiles, never `if (isDev())`.

## Quarkus notes

Traps worth knowing up front:

- The fabric8 client is blocking — use `@RunOnVirtualThread`, do not block the event loop.
- Analyzers must be CDI beans (`@ApplicationScoped`) or discovery will not find them.
- `application.yml` is only read because `quarkus-config-yaml` is present.
- `final` methods break CDI proxying on normal-scoped beans.
- Prefer virtual threads plus blocking code over reactive Mutiny everywhere; reserve `Multi` for streaming.
- Records work well with Jackson — use them throughout `common`.
