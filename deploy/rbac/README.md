# RBAC

KubePilot is **read-only through M7**. The ClusterRole here should carry `get`, `list` and `watch` only —
never `create`, `update`, `patch` or `delete`.

Write access arrives at M8 for remediation, and only behind three gates: explicit opt-in, a dry-run first,
and human approval. When that happens it belongs in a **separate, separately-bound Role** so the read path
cannot silently inherit write permission.

Scope the verbs to the resources the enabled analyzers actually read. `list` and `watch` on the whole cluster
is already substantial access — every ConfigMap and pod spec becomes visible, and some of that is what
`ai/` has to redact before any of it reaches a model.

Generate a starting point from the extension rather than hand-writing it:

```shell
./mvnw package -Dquarkus.kubernetes.deploy=false
```

with `quarkus-kubernetes` added (M3), then curate the output down. Generated RBAC tends toward permissive.
