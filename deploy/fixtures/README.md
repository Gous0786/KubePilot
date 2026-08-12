# Fixtures — the acceptance suite

Deliberately broken workloads. Apply them to a throwaway cluster and every one should produce a finding:

```shell
kind create cluster --name kubepilot
kubectl apply -f deploy/fixtures/
```

Keep a **1:1 mapping between a fixture and a rule**. A fixture that produces no finding means a missing
analyzer; a rule with no fixture means an untested rule. That mapping is what makes the catalog trustworthy
as it grows past a handful of rules.

Planned fixtures for M1:

| File | Provokes |
| --- | --- |
| `broken-image.yaml` | `ImagePullBackOff` — nonexistent image tag |
| `badprobe.yaml` | readiness probe that never succeeds |
| `unschedulable.yaml` | resource requests no node can satisfy |
| `pvc-unbound.yaml` | PVC referencing a nonexistent StorageClass |
| `svc-no-endpoints.yaml` | Service whose selector matches no pods |
| `oomkill.yaml` | container exceeding its memory limit |

Distinct from `src/test/resources/fixtures/`, which holds captured YAML for offline golden tests. These run
against a live cluster; those never touch one.
