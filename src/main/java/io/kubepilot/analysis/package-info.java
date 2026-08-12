/**
 * Scan orchestration and output formatting. Equivalent to k8sgpt's {@code pkg/analysis}.
 *
 * <p>Owns the run sequence: build snapshot, fan out to analyzers, dedup and group, then
 * optionally enrich through {@code ai}. This is the only package that knows the whole
 * pipeline; analyzers know nothing about each other.
 *
 * <p>Depends on: {@code common}, {@code analyzer}, {@code kubernetes}, {@code cache}, {@code ai}.
 */
package io.kubepilot.analysis;
