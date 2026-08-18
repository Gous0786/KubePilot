/**
 * Pluggable result cache.
 *
 * <p>Keyed on a finding fingerprint so an identical failure is explained once rather than once
 * per affected pod. Start with the in-process Quarkus cache; a shared provider only starts
 * mattering once more than one replica runs.
 */
package io.kubepilot.cache;
