/**
 * LLM backends, prompt assembly and redaction.
 *
 * <p>One adapter per provider, kept flat. A no-op backend is not optional: it is
 * both the rules-only mode and the circuit-breaker fallback when a provider is down.
 *
 * <p><b>Redaction is mandatory here.</b> Nothing reaches a provider until secrets, env vars and
 * annotations are stripped. Pod specs routinely carry live credentials.
 *
 * <p>Prefer a typed return value over {@code String} and let the model bind to a record via
 * JSON schema. Parsing prose is the main long-term maintenance cost in tools like this.
 */
package io.kubepilot.ai;
