/**
 * Optional external data sources that enrich a diagnosis: Prometheus metrics, policy reports,
 * cloud provider APIs.
 *
 * <p>Prefer consuming an existing MCP server (Prometheus, Loki, Grafana) over hand-writing one
 * client per backend: far less code, and each new source becomes configuration rather than a
 * new module.
 */
package io.kubepilot.integration;
