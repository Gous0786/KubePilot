/**
 * One class per analyzer, kept flat exactly as k8sgpt's {@code pkg/analyzer} is.
 *
 * <p>Each analyzer inspects an <i>already-fetched</i> snapshot and returns findings.
 * Analyzers must never call the Kubernetes API themselves. That single rule is what keeps a
 * full scan to one batched fetch instead of N-analyzers-times-M-resources round trips, and
 * what makes every analyzer testable from a YAML fixture.
 *
 * <p>Catalog to mirror k8sgpt: pod, deployment, replicaset, statefulset, daemonset, job,
 * cronjob, service, ingress, netpol, pvc, storage, node, hpa, pdb, configmap, security, log,
 * gateway, gatewayclass, httproute, validating/mutating webhook.
 *
 * <p>Depends on: {@code common} only.
 */
package io.kubepilot.analyzer;
