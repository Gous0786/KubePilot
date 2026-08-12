/**
 * Optional external data sources that enrich a diagnosis. Equivalent to k8sgpt's
 * {@code pkg/integration}, which ships aws, keda, kyverno and prometheus.
 *
 * <p>Prefer consuming an existing MCP server (Prometheus, Loki, Grafana) over hand-writing one
 * client per backend: far less code, and each new source becomes configuration rather than a
 * new module.
 */
package io.kubepilot.integration;
