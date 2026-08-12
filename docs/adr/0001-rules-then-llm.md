# ADR 0001 — Deterministic detection, LLM explanation

- **Status:** Accepted
- **Date:** 2026-08-12

## Context

KubePilot must find problems in a Kubernetes cluster and explain them. There are two ways to arrange that.

**Agent-first:** give an LLM cluster-read tools and let it investigate freely. Flexible and impressive in a
demo, but every scan costs tokens and seconds, results vary between identical runs, there is nothing to unit
test, and the product stops working when the provider has an incident.

**Rules-first:** deterministic analyzers detect, and the LLM explains what they found. This is what k8sgpt
does, and it is why k8sgpt is cheap enough to run continuously.

## Decision

Detection is deterministic. The LLM is an enrichment step over findings the rules already produced.

A typed `Finding` sits between the phases. Analyzers read an immutable snapshot and emit findings; the AI
layer consumes findings and emits a `Diagnosis`. The LLM does not decide what is broken.

Concretely:

- Analyzers never call the Kubernetes API — they receive an already-fetched snapshot.
- A scan with the LLM disabled is a supported mode, not a degraded one.
- Every finding carries a fingerprint: a stable hash of rule + resource identity + normalized reason.
- LLM-driven investigation arrives later (M5/M8) as an **additional** mode that never replaces the rules path.

## Consequences

Good:

- The fast path is free, deterministic, and returns in milliseconds.
- Analyzers are unit-testable from YAML fixtures — no cluster, no model, no network. This is what makes a
  30-plus rule catalog maintainable.
- The circuit breaker has somewhere real to fall back to, so an LLM outage degrades quality, not function.
- The fingerprint doubles as cache key and alert-dedup key, so identical failures are explained once.
- Analyzers and prompts evolve independently across a stable contract.

Costs, accepted:

- Novel failure modes nobody wrote a rule for go undetected. Mitigated by M5/M8 exploratory modes.
- The analyzer catalog is ongoing work; k8sgpt carries ~30 analyzers. This is the real cost of the approach.
- `Finding` becomes load-bearing early and is expensive to change later, so its shape deserves care now.

## Alternatives rejected

- **Agent-first investigation** — rejected for v1 on cost, latency, and untestability. Revisited at M5 as an
  addition.
- **LLM assigns severity** — rejected. Severity drives alerting and must be reproducible; it belongs in
  `analyzers/rules.yaml`.
