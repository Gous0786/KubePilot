# KubePilot architecture

Companion to [adr/0001-rules-then-llm.md](adr/0001-rules-then-llm.md), which records *why* the pipeline is
split the way it is. This document describes the structure and the rules that keep it intact.

## Pipeline

```text
  kubernetes/              analyzer/              analysis/                ai/
 +------------+         +------------+         +------------+         +--------------+
 |  fabric8   | Snapshot|  one class | Find-   | group by   | Group   | redact ->    |
 |  client    |-------->|  per rule  |-------->| fingerprint|-------->| prompt ->    |
 |  (informer | immutabl|  (7 rules  | ings    | then by    | per     | typed        |
 |   later)   |         |   today)   |         | workload   | workload| Diagnosis    |
 +------------+         +------------+         +------------+         +--------------+
                                                     |                       |
                                                     v                       v
                                                  server/            fingerprint-keyed
                                                REST + JSON            Caffeine cache
```

Everything left of `ai/` runs without a model. That is the point.

## Package map

Packages are organised by concern rather than by layer. Each package's `package-info.java` is
authoritative on what belongs in it.

| Package | Role | May depend on |
| --- | --- | --- |
| `common` | Shared types and ports: `Finding`, `Diagnosis`, `ScanReport`, `DiagnosisEngine`, `Redactor` | JDK, fabric8 **model** |
| `analyzer` | One class per rule, kept flat | `common`, `util`, fabric8 **model** |
| `analysis` | Orchestration: snapshot, fan-out, group, enrich | `common`, `analyzer`, `kubernetes` |
| `ai` | LLM backends, prompt assembly, redaction, the cached call | `common` |
| `kubernetes` | fabric8 **client**, snapshot assembly | `common` |
| `cache` | Reserved — see note below | `common` |
| `server` | REST resources + `dto` | `common`, `analysis` |
| `integration` | External data sources — reserved | `common` |
| `custom` | Out-of-process analyzers — reserved | `common` |
| `util` | Stateless helpers over Kubernetes objects | `common`, fabric8 **model** |
| `cmd` | Picocli subcommands — reserved | `analysis` |

Two things worth knowing about that table:

**`analysis` does not depend on `ai`.** It talks to the `DiagnosisEngine` and `Redactor` interfaces in
`common`, and CDI supplies the implementations. That is what keeps orchestration free of LangChain4j and
makes a rules-only build a legitimate configuration.

**`cache` is currently empty.** The diagnosis cache is a Caffeine cache configured in `application.yml`,
and the cached call lives in `ai/DiagnosisCache` because it wraps the AI service directly. The `cache`
package is reserved for pluggable providers — a shared Redis cache once more than one replica runs.

## Rules worth enforcing

These belong in ArchUnit tests in `src/test/java/io/kubepilot/architecture/`, which **does not exist yet**.
That gap is not theoretical: rules 1 and 2 below were originally written as "no fabric8 anywhere outside
`kubernetes`", the code diverged from that deliberately, and nothing caught the drift because nothing was
enforcing it.

1. **The model/client split.** `common`, `analyzer` and `util` may import `io.fabric8.kubernetes.api.model..`
   — the generated resource types. **Only `kubernetes` may import `io.fabric8.kubernetes.client..`**. This is
   the rule that matters: analyzers can read a `Pod` but cannot obtain a client and make API calls, which is
   what keeps a scan to one batched fetch and every analyzer unit-testable.
2. **Only `ai` imports LangChain4j** (`dev.langchain4j..`, `io.quarkiverse.langchain4j..`). Keeps `analysis`
   runnable with the model switched off.
3. **`analyzer` does not import `kubernetes` or `analysis`.** Snapshot in, findings out.
4. **`server` does not import `analyzer`, `ai` or `kubernetes`.** Resources call `analysis` and nothing else.
5. **`common` imports no framework.** No Quarkus, no CDI, no Jackson annotations, no LangChain4j. Records and
   fabric8 model types only.
6. **No cycles between packages.**

### Why the model/client distinction rather than a mapping layer

Analyzers read fabric8's generated types directly instead of a hand-written domain model. The Kubernetes API
model *is* the domain model here; re-modelling it would mean a translation layer that grows every time any
rule needs one more field, for no benefit. The cost is that `common` is not framework-free in the strictest
sense — accepted knowingly, and bounded by rule 1.

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

### Measured, on a 1-node kind cluster with 4 broken workloads

| Path | Wall time |
| --- | --- |
| Rules only (`explain=false`) | ~200 ms |
| With diagnosis, cold cache | ~9.2 s (4 sequential model calls) |
| With diagnosis, warm cache | ~180 ms |
| Model unreachable | ~25 s before tuning retries — see below |

Two things those numbers say. The cache is worth roughly 50x on a repeat scan, and the four model calls are
sequential and independent, so parallelising `diagnose()` is the next real win. The last row is a defect, not
a measurement: `@Retry` currently retries permanent failures such as a 400 or a connection refusal, which
costs three attempts and ~6 s per workload for an error that will never succeed.

Record these again after the informer migration. Without a before-number that change is unmeasurable.

## Maintenance levers

Implemented items are marked; the rest are still intentions.

- **Analyzers as plugins.** *(done)* CDI discovery via `@All List<Analyzer>`. Adding `DeploymentAnalyzer`
  required no change to the orchestrator or the resource. Severities are still hardcoded — moving them to
  `resources/analyzers/rules.yaml` is outstanding.
- **Prompts as Qute templates** in `resources/prompts/`. *(not done — prompts are still annotation literals
  on `DiagnosisAiService`.)* Worth moving before they are iterated on much further.
- **Typed LLM output.** *(done)* `DiagnosisAiService` returns a `Diagnosis` record; LangChain4j generates the
  JSON schema and the model conforms. No prose parsing anywhere.
- **Golden analyzer tests.** *(done)* Fixture YAML in, exact findings out — 36 tests, no cluster, no model, no
  network, ~1.3 s.
- **1:1 fixtures to rules.** *(partly)* 7 rules, 5 fixtures. `pod-container-terminated`,
  `deployment-replica-failure` and the init-container paths have no captured fixture.
- **Redaction on the LLM boundary.** One chokepoint in `ai/`, applied while the prompt is built
  rather than on the wire, so the dev request log is covered too. Values are masked, identifiers
  preserved. See [redaction.md](redaction.md).
- **Fault tolerance on the LLM boundary.** *(done, needs tuning)* `@Timeout`, `@Retry`, `@CircuitBreaker` and
  `@Fallback` to `Diagnosis.unavailable`. Verified: with no model reachable the scan still returns 200 with
  every finding. Outstanding: `abortOn` so permanent 4xx failures are not retried.
- **Fingerprint-keyed diagnosis cache.** *(done)* Keyed on model name plus the sorted finding fingerprints of
  a workload — deliberately not on prompt text, which carries `restartCount` and `finishedAt` and would never
  produce a cache hit. `@CacheResult` sits on a separate bean because CDI interceptors do not fire on
  self-invocation, and fault tolerance sits outside the cache so fallbacks are never stored.
- **`@ConfigMapping` interfaces** over scattered `@ConfigProperty`. Profiles, never `if (isDev())`.

## Quarkus notes

Traps worth knowing up front:

- The fabric8 client is blocking — use `@RunOnVirtualThread`, do not block the event loop.
- Analyzers must be CDI beans (`@ApplicationScoped`) or discovery will not find them.
- `application.yml` is only read because `quarkus-config-yaml` is present.
- `final` methods break CDI proxying on normal-scoped beans.
- Prefer virtual threads plus blocking code over reactive Mutiny everywhere; reserve `Multi` for streaming.
- Records work well with Jackson — use them throughout `common`.
