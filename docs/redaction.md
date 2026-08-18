# Redaction

KubePilot sends cluster evidence to a language model. Redaction is what stops credentials going
with it.

## What it protects against

Three destinations, not one:

- The **model provider**. On a free tier this often means the content may be used to improve their
  products, and that human reviewers may read it.
- The **gateway**. LiteLLM logs and callbacks hold a copy of every prompt.
- **Your own logs**. `%dev` enables `log-requests`, which writes assembled prompts to the
  application log. Redaction that only wrapped the HTTP call would miss this entirely.

Because of the third one, redaction is applied while building the prompt, not on the wire.

## Where it sits

A single chokepoint: `LlmDiagnosisEngine.format()`, the one place findings become prompt text.

```text
Finding.evidence
   -> Redactor.redactEvidence()   pass 1, key-aware
   -> assembled prompt text
   -> Redactor.redactText()       pass 2, whole-prompt sweep
   -> model
```

Analyzers never redact anything. They cannot forget to, and adding an analyzer cannot introduce a
leak path. This is deliberate — see the rationale at the end of this document.

`Redactor` and `RedactionLevel` live in `common` so future outbound paths (the MCP face, webhook
sinks) can reuse the same policy. `EvidenceRedactor` implements it in `ai`.

## The central idea: mask values, keep identifiers

Diagnosing a secret problem almost never needs the secret. It needs the *shape* of the failure.

| Failure | What the model needs | Value needed |
| --- | --- | --- |
| Secret does not exist | secret name, who references it | no |
| Key missing from secret | secret name, key name | no |
| Wrong credential | that authentication failed, and for whom | no |
| Malformed value | length, encoding, scheme | no — a description |
| Expired certificate | notAfter, subject | no — parsed facts |

Kubernetes' own messages make the point:

```text
CreateContainerConfigError: secret "db-creds" not found
CreateContainerConfigError: couldn't find key DB_PASSWORD in Secret default/db-creds
```

Fully diagnosable, and containing no secret value. So names, keys, kinds, namespaces, container
names, image tags, reasons and exit codes are **preserved verbatim**. Only values are masked.

## Masks describe what they hide

Never a bare `<REDACTED>`. Each mask carries every non-secret fact about the value:

```text
password       -> <redacted:32 chars>
apiToken       -> <redacted:jwt,expires=2026-09-01T00:00:00Z>
DATABASE_URL   -> postgres://<redacted>@db.internal:5432/app
tls.crt        -> <redacted:PEM block>
apiKey         -> <empty>
```

`<empty>` matters most: an empty secret is a real and common bug, diagnosable only if reported.
Preserving the host and port of a DSN lets the model reason about connectivity while the password
never leaves the process.

## Pass 1: key-aware

| Class | Keys | Treatment |
| --- | --- | --- |
| Safe | `reason`, `terminationReason`, `exitCode`, `restartCount`, `desiredReplicas`, `availableReplicas`, `initContainer`, `finishedAt`, `image` | verbatim |
| Free text | `message` | scanned by pass 2's detectors |
| Unknown | anything else | described-only |

The unknown row is the important one. A new evidence key added by a future analyzer is masked until
somebody classifies it — the policy **fails closed**.

## Pass 2: detectors, in precision order

Applied to free text and then to the whole assembled prompt.

1. **PEM blocks** — `-----BEGIN … -----END …`
2. **JWTs** — `eyJ….….…`, with the `exp` claim decoded into the mask
3. **Known prefixes** — `AKIA`/`ASIA`, `ghp_`, `sk-`, `AIza`, `xoxb-`
4. **URL userinfo** — `scheme://user:pass@host`, keeping everything but the credentials
5. **Assignments** — `password|secret|token|api_key|credential|authorization` followed by `=` or `:`
6. **High entropy** — a last-resort fallback

### Tuning: the asymmetry

On **free text**, a false positive costs a little context while a false negative leaks. Bias toward
masking. On the **allowlisted structured** path the reverse holds, since that is where diagnostic
value lives. Bias toward preserving.

### Why entropy detection is last, and heavily fenced

Entropy is the only detector that guesses. It runs at length ≥ 24 and Shannon entropy ≥ 4.2, and
skips tokens that are:

- **Kubernetes names** — `[a-z0-9]([-a-z0-9]*[a-z0-9])?`. Without this, a pod named
  `local-path-provisioner-855c7b7774-kpwmn` reads as high-entropy and gets masked, which is exactly
  the failure this design exists to avoid.
- **Digests and IDs** — `sha256:…`, long hex strings, UUIDs
- **Anything containing a path separator** — URLs, image references, file paths
- **Dotted names** — two or more dots, so hostnames survive

Both exclusions are covered by tests.

## Levels

```yaml
kubepilot:
  ai:
    redaction: standard   # strict | standard | off
```

| Level | Behaviour |
| --- | --- |
| `strict` | Allowlisted keys only. Free text and unknown keys are dropped entirely. |
| `standard` | Default. Values masked descriptively, identifiers preserved. |
| `off` | No redaction at all. |

`off` is legitimate and improves diagnosis quality, particularly for credential problems where the
model benefits from seeing the real value. It is appropriate for a model you run yourself with no
egress.

It stays an explicit opt-in because KubePilot **cannot verify** that a base URL is trustworthy —
"local" is an operator assertion, not something the application can check. An unrecognised value
falls back to `standard`.

The active level is reported in every explained scan:

```json
{ "findings": [ ... ], "diagnoses": { ... }, "redaction": "standard" }
```

so nobody discovers redaction was off by reading a values file.

## No round trip

Some designs substitute the real values back into the model's response. KubePilot does not, and the
difference follows from what is masked. Restoring *identifiers* makes sense, because the reader needs
them. Restoring *secrets* would print credentials to the operator's screen and into their logs, which
is the opposite of the goal. The descriptive masks are already readable.

## Determinism

The same value always produces the same mask. Two reasons: scan-to-scan diffs stay meaningful, and
masking can never destabilise a cache key.

A design that generates a fresh random mask on every call, combined with a cache keyed on prompt
text, never registers a hit — the key changes each run. KubePilot is safe regardless, because the
diagnosis cache keys on finding fingerprints rather than prompt text, but determinism is kept as a
property rather than an accident.

## What this does not do

**It is not a substitute for not reading secrets.** `ClusterReader` fetches pods, ReplicaSets and
Deployments — no Secrets, no ConfigMaps. When that changes, strip `.data` at the `ClusterReader`
boundary and keep only key names. Redaction is the second line of defence, not the first.

**It cannot catch novel credential formats.** The prefix list covers common providers; entropy
detection is a fallback with deliberate blind spots. Treat the allowlist, not the scanner, as the
control that actually holds.

**It does not cover tool output.** When agentic tools land — pod logs especially — every tool
return value must pass through the same `Redactor` before entering the conversation. Logs are the
single worst offender for leaked credentials.

## Tests

`EvidenceRedactorTest` covers both directions, and the second is as important as the first:

- **Leak tests** — a known secret planted in an evidence value, inside `message`, and in URL
  userinfo; the literal must never appear in the output.
- **Preservation tests** — names, secret names, key names, reasons, exit codes, pod names and image
  digests must survive untouched. These guard against someone later "hardening" redaction into
  masking identifiers, which would silently destroy diagnosis quality.
- Determinism, unknown-key masking, empty-value reporting, and each level's behaviour.

## Why not per-analyzer anonymization

The obvious alternative is to have each analyzer declare a list of `{unmasked, masked}` pairs for the
values it emits, applied behind an opt-in flag. It was rejected.

| | Per-analyzer anonymization | KubePilot |
| --- | --- | --- |
| What is masked | resource names and namespaces | credential values |
| Where | every analyzer, by hand | one chokepoint |
| Coverage | whatever each author remembered | structural, cannot be skipped |
| Masks | typically random, per call | deterministic, descriptive |
| Default | opt-in | on |
| Credential detection | none | prefix, pattern and entropy detectors |

Two failure modes drove the opposite choices. Masking identifiers destroys the diagnosis —
`secret "YThYazJ…" not found` is useless, and the name was never the sensitive part. And anything
that depends on every analyzer author remembering will be incomplete; in practice a large share of
failure paths end up anonymizing nothing at all, and free-text fields such as events get missed
entirely.
